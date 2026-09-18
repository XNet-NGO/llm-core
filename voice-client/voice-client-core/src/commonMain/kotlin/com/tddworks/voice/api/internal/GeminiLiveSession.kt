package com.tddworks.voice.api.internal

import com.tddworks.voice.api.VoiceConfig
import com.tddworks.voice.api.VoiceEvent
import com.tddworks.voice.api.VoiceSession
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Gemini Live (D6, canonical shape two): BidiGenerateContent over WebSocket.
 * Audio travels as base64 `mediaChunks` inside JSON frames; tool calls arrive as
 * `functionCall` parts / `toolCall` messages. Session readiness is signaled by the
 * server's `setupComplete` frame.
 */
internal class GeminiLiveSession(private val config: VoiceConfig) : VoiceSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val eventsFlow = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 128)
    private val json = Json { ignoreUnknownKeys = true }
    private var outbound: SendChannel<Frame>? = null

    override val events: Flow<VoiceEvent> = eventsFlow

    init {
        scope.launch { run() }
    }

    private suspend fun run() {
        val client = HttpClient { install(WebSockets) }
        try {
            client.webSocket(urlString = wsUrl(), request = {}) {
                val session = this
                outbound = session.outgoing
                if (System.getenv("LLMCORE_DEBUG") == "1") println("GEMINI-LIVE WS OPENED url=${wsUrl()}")
                session.outgoing.send(Frame.Text(json.encodeToString(setupMessage())))
                if (System.getenv("LLMCORE_DEBUG") == "1") println("GEMINI-LIVE SETUP SENT")
                launch {
                    for (payload in pendingSends) {
                        session.outgoing.send(Frame.Text(payload))
                    }
                }
                for (frame in session.incoming) {
                    val text: String? =
                        when (frame) {
                            is Frame.Text -> frame.readText()
                            is Frame.Binary -> frame.data.decodeToString()
                            else -> null
                        }
                    if (text == null) continue
                    if (System.getenv("LLMCORE_DEBUG") == "1") println("GEMINI-LIVE RAW: $text")
                    parseEvents(text).forEach { eventsFlow.tryEmit(it) }
                }
            }
        } catch (e: Throwable) {
            eventsFlow.tryEmit(VoiceEvent.Error(e.message ?: "gemini live session failed"))
        } finally {
            client.close()
            eventsFlow.tryEmit(VoiceEvent.Closed())
        }
    }

    /**
     * Gemini Live binds at the GenerativeService WebSocket connector:
     * `wss://<host>/ws/google.ai.generativelanguage.v1beta/GenerativeService.BidiGenerateContent`.
     * `VoiceConfig.baseUrl` is the ws connector prefix; the model travels in the
     * setup message.
     */
    private fun wsUrl(): String {
        val base = config.baseUrl().trimEnd('/')
        val keySuffix =
            if (config.apiKey().isNotEmpty()) "?key=${config.apiKey()}" else ""
        val connector =
            "/ws/google.ai.generativelanguage.${config.apiVersion}.GenerativeService.BidiGenerateContent"
        return "$base$connector$keySuffix"
    }

    private fun setupMessage(): JsonObject =
        buildJsonObject {
                put(
                    "setup",
                    buildJsonObject {
                        put("model", "models/${config.model()}")
                        put(
                            "generationConfig",
                            buildJsonObject {
                                put("responseModalities", JsonArray(listOf(JsonPrimitive("AUDIO"))))
                                put(
                                    "speechConfig",
                                    buildJsonObject {
                                        put(
                                            "voiceConfig",
                                            buildJsonObject {
                                                put(
                                                    "prebuiltVoiceConfig",
                                                    buildJsonObject {
                                                        put("voiceName", config.voice ?: "Puck")
                                                    },
                                                )
                                            },
                                        )
                                    },
                                )
                            },
                        )
                        config.systemInstruction?.let { instruction ->
                            put(
                                "systemInstruction",
                                buildJsonObject {
                                    put(
                                        "parts",
                                        buildJsonArray { add(buildJsonObject { put("text", instruction) }) },
                                    )
                                },
                            )
                        }
                    },
            )
        }
        

    private val pendingSends = Channel<String>(Channel.UNLIMITED)

    private fun send(message: JsonObject) {
        val payload = json.encodeToString(message)
        if (System.getenv("LLMCORE_DEBUG") == "1") println("GEMINI-LIVE OUT: $payload")
        pendingSends.trySend(payload)
    }

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun sendAudio(data: ByteArray) {
        val b64 = Base64.encode(data)
        send(
            buildJsonObject {
                put(
                    "realtimeInput",
                    buildJsonObject {
                        put(
                            "mediaChunks",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("mimeType", "audio/pcm;rate=${config.sampleRate}")
                                        put("data", b64)
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
    }

    override suspend fun sendText(text: String) {
        send(
            buildJsonObject {
                put(
                    "clientContent",
                    buildJsonObject {
                        put(
                            "turns",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put(
                                            "parts",
                                            buildJsonArray { add(buildJsonObject { put("text", text) }) },
                                        )
                                        put("role", "user")
                                    },
                                )
                            },
                        )
                        put("turnComplete", true)
                    },
                )
            },
        )
    }

    override suspend fun sendToolResponse(name: String, arguments: String, id: String?) {
        val responsePayload =
            try {
                json.parseToJsonElement(arguments)
            } catch (e: Throwable) {
                buildJsonObject { put("result", arguments) }
            }
        send(
            buildJsonObject {
                put(
                    "toolResponse",
                    buildJsonObject {
                        put(
                            "functionResponses",
                            buildJsonArray {
                                add(
                                    buildJsonObject {
                                        put("id", id ?: "fc_${nextCallId()}")
                                        put("name", name)
                                        put("response", responsePayload)
                                    },
                                )
                            },
                        )
                    },
                )
            },
        )
    }

    override suspend fun endTurn() {
        // Gemini Live: the turn is ended by the complete flag on clientContent;
        // an explicit end-of-turn marker for audio turns is sent with turns.
        send(
            buildJsonObject {
                put(
                    "clientContent",
                    buildJsonObject {
                        put("turns", buildJsonArray {})
                        put("turnComplete", true)
                    },
                )
            },
        )
    }

    override suspend fun close() {
        scope.coroutineContext[Job]?.cancel()
        eventsFlow.tryEmit(VoiceEvent.Closed())
    }

    private var callCounter = 0L

    private fun nextCallId(): String = (++callCounter).toString()

    private fun parseEvents(text: String): List<VoiceEvent> {
        val root =
            try {
                json.parseToJsonElement(text).jsonObject
            } catch (e: Throwable) {
                return emptyList()
            }
        return when {
            root.containsKey("setupComplete") ->
                listOf(VoiceEvent.SessionReady(config.model()))
            root.containsKey("serverContent") -> parseServerContent(root["serverContent"]!!.jsonObject)
            root.containsKey("toolCall") -> parseToolCall(root["toolCall"]!!.jsonObject)
            root.containsKey("interrupted") -> listOf(VoiceEvent.Interrupted())
            root.containsKey("audioTranscription") ->
                root["audioTranscription"]!!.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                    ?.let { listOf(VoiceEvent.InputTranscription(it)) }
                    ?: emptyList()
            root.containsKey("error") ->
                listOf(
                    VoiceEvent.Error(
                        message =
                            root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                                ?: "gemini live error",
                    ),
                )
            else -> emptyList()
        }
    }

    private fun parseServerContent(content: JsonObject): List<VoiceEvent> {
        val events = mutableListOf<VoiceEvent>()
        content["modelTurn"]?.jsonObject?.get("parts")?.jsonArray?.forEach { partEl ->
            val part = partEl.jsonObject
            part["inlineData"]?.jsonObject?.let { data ->
                data["data"]?.jsonPrimitive?.contentOrNull?.let { events.add(VoiceEvent.AudioDelta(it)) }
            }
            part["text"]?.jsonPrimitive?.contentOrNull?.let {
                events.add(VoiceEvent.OutputTranscription(it))
            }
            part["functionCall"]?.jsonObject?.let { fc ->
                events.add(
                    VoiceEvent.ToolCall(
                        name = fc["name"]?.jsonPrimitive?.contentOrNull ?: "",
                        arguments = fc["args"]?.toString() ?: "{}",
                        callId = fc["id"]?.jsonPrimitive?.contentOrNull,
                    ),
                )
            }
        }
        val complete =
            ((content["turnComplete"] ?: content["generationComplete"])
                ?.jsonPrimitive?.contentOrNull
                ?: "false").toBooleanStrictOrNull() == true
        if (complete) events.add(VoiceEvent.TurnComplete())
        return events
    }

    private fun parseToolCall(call: JsonObject): List<VoiceEvent> {
        val fc = call["functionCalls"]?.jsonArray?.firstOrNull()?.jsonObject ?: return emptyList()
        return listOf(
            VoiceEvent.ToolCall(
                name = fc["name"]?.jsonPrimitive?.contentOrNull ?: "",
                arguments = fc["args"]?.toString() ?: "{}",
                callId =
                    fc["id"]?.jsonPrimitive?.contentOrNull
                        ?: call["id"]?.jsonPrimitive?.contentOrNull,
            ),
        )
    }
}