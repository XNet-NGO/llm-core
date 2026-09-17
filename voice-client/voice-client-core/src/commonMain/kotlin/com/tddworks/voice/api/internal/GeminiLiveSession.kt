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
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/**
 * Gemini Live (D6, canonical shape two): BidiGenerateContent over WebSocket.
 * Audio travels as base64 `mediaChunks` inside JSON frames; tool calls arrive as
 * `functionCall` parts / `toolCall` messages.
 */
internal class GeminiLiveSession(private val config: VoiceConfig) : VoiceSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val eventsFlow = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 128)
    private val json = Json { ignoreUnknownKeys = true }
    private var outbound: SendChannel<Frame>? = null
    private var callCounter = 0L

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
                eventsFlow.tryEmit(sendSetup())
                for (frame in session.incoming) {
                    if (frame !is Frame.Text) continue
                    val event = parseEvent(frame.readText()) ?: continue
                    eventsFlow.tryEmit(event)
                }
            }
        } catch (e: Throwable) {
            eventsFlow.tryEmit(VoiceEvent.Error(e.message ?: "gemini live session failed"))
        } finally {
            client.close()
            eventsFlow.tryEmit(VoiceEvent.Closed())
        }
    }

    private fun wsUrl(): String {
        val base = config.baseUrl().trimEnd('/')
        val keySuffix =
            if (config.apiKey().isNotEmpty()) "?key=${config.apiKey()}" else ""
        return "$base/v1beta/models/${config.model()}:bidiGenerateContent$keySuffix"
    }

    private suspend fun sendSetup(): VoiceEvent {
        val message =
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
        outbound?.send(Frame.Text(json.encodeToString(message)))
        return VoiceEvent.SessionReady(config.model())
    }

    private fun send(message: JsonObject) {
        scope.launch { outbound?.send(Frame.Text(json.encodeToString(message))) }
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

    private fun nextCallId(): String = (++callCounter).toString()

    override suspend fun close() {
        scope.coroutineContext[Job]?.cancel()
        eventsFlow.tryEmit(VoiceEvent.Closed())
    }

    private fun parseEvent(text: String): VoiceEvent? {
        val root =
            try {
                json.parseToJsonElement(text).jsonObject
            } catch (e: Throwable) {
                return null
            }
        return when {
            root.containsKey("setupComplete") -> VoiceEvent.SessionReady(config.model())
            root.containsKey("serverContent") -> parseServerContent(root["serverContent"]!!.jsonObject)
            root.containsKey("toolCall") -> parseToolCall(root["toolCall"]!!.jsonObject)
            root.containsKey("interrupted") -> VoiceEvent.Interrupted()
            root.containsKey("audioTranscription") ->
                root["audioTranscription"]!!.jsonObject["text"]?.jsonPrimitive?.contentOrNull
                    ?.let { VoiceEvent.InputTranscription(it) }
            root.containsKey("error") ->
                VoiceEvent.Error(
                    message =
                        root["error"]?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull
                            ?: "gemini live error",
                )
            else -> null
        }
    }

    private fun parseServerContent(content: JsonObject): VoiceEvent? {
        var event: VoiceEvent? = null
        content["modelTurn"]?.jsonObject?.get("parts")?.jsonArray?.forEach { partEl ->
            val part = partEl.jsonObject
            part["inlineData"]?.jsonObject?.let { data ->
                data["data"]?.jsonPrimitive?.contentOrNull?.let { event = VoiceEvent.AudioDelta(it) }
            }
            part["text"]?.jsonPrimitive?.contentOrNull?.let {
                event = VoiceEvent.OutputTranscription(it)
            }
            part["functionCall"]?.jsonObject?.let { fc ->
                event =
                    VoiceEvent.ToolCall(
                        name = fc["name"]?.jsonPrimitive?.contentOrNull ?: "",
                        arguments = fc["args"]?.toString() ?: "{}",
                        callId = fc["id"]?.jsonPrimitive?.contentOrNull,
                    )
            }
        }
        if (content["turnComplete"]?.jsonPrimitive?.contentOrNull?.toBooleanStrictOrNull() == true &&
            event == null
        ) {
            event = VoiceEvent.TurnComplete()
        }
        return event
    }

    private fun parseToolCall(call: JsonObject): VoiceEvent? {
        call["functionCalls"]?.jsonArray?.firstOrNull()?.let { fcEl ->
            val fc = fcEl.jsonObject
            return VoiceEvent.ToolCall(
                name = fc["name"]?.jsonPrimitive?.contentOrNull ?: "",
                arguments = fc["args"]?.toString() ?: "{}",
                callId = fc["id"]?.jsonPrimitive?.contentOrNull ?: call["id"]?.jsonPrimitive?.contentOrNull,
            )
        }
        return null
    }
}