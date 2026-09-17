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
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.put

/**
 * OpenAI Realtime (D6, canonical shape one): WebSocket JSON events, base64 PCM audio
 * via `input_audio_buffer.append`, output via `response.audio.delta`.
 */
internal class OpenAIRealtimeSession(private val config: VoiceConfig) : VoiceSession {

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
                val sessionScope = this
                outbound = sessionScope.outgoing
                eventsFlow.tryEmit(VoiceEvent.SessionReady(config.model()))
                for (frame in sessionScope.incoming) {
                    if (frame !is Frame.Text) continue
                    val event = parseEvent(frame.readText()) ?: continue
                    eventsFlow.tryEmit(event)
                }
            }
        } catch (e: Throwable) {
            eventsFlow.tryEmit(VoiceEvent.Error(e.message ?: "realtime session failed"))
        } finally {
            client.close()
            eventsFlow.tryEmit(VoiceEvent.Closed())
        }
    }

    private fun wsUrl(): String {
        val base = config.baseUrl().trimEnd('/')
        val keySuffix =
            if (config.apiKey().isNotEmpty()) "&api-key=${config.apiKey()}" else ""
        return "$base/v1/realtime?model=${config.model()}$keySuffix"
    }

    private fun send(message: JsonObject) {
        scope.launch { outbound?.send(Frame.Text(json.encodeToString(message))) }
    }

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun sendAudio(data: ByteArray) {
        val b64 = Base64.encode(data)
        send(
            buildJsonObject {
                put("type", "input_audio_buffer.append")
                put("audio", b64)
            },
        )
    }

    override suspend fun sendText(text: String) {
        send(
            buildJsonObject {
                put("type", "session.update")
                put(
                    "session",
                    buildJsonObject {
                        put("modalities", JsonArray(listOf(JsonPrimitive("audio"), JsonPrimitive("text"))))
                        put("instructions", config.systemInstruction ?: "")
                        put("voice", config.voice ?: "alloy")
                    },
                )
            },
        )
        endTurn()
    }

    override suspend fun sendToolResponse(name: String, arguments: String, id: String?) {
        send(
            buildJsonObject {
                put("type", "conversation.item.create")
                put(
                    "item",
                    buildJsonObject {
                        put("type", "function_call_output")
                        put("call_id", id ?: "call_${nextCallId()}")
                        put("output", arguments)
                    },
                )
            },
        )
        send(buildJsonObject { put("type", "response.create") })
    }

    override suspend fun endTurn() {
        send(buildJsonObject { put("type", "input_audio_buffer.commit") })
        send(buildJsonObject { put("type", "response.create") })
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
        val type = root["type"]?.jsonPrimitive?.contentOrNull ?: return null
        return when (type) {
            "session.created" ->
                VoiceEvent.SessionReady(
                    model =
                        root["session"]?.jsonObject?.get("model")?.jsonPrimitive?.contentOrNull
                            ?: config.model(),
                )
            "response.audio.delta" ->
                root["delta"]?.jsonPrimitive?.contentOrNull?.let { VoiceEvent.AudioDelta(it) }
            "response.audio_transcript.delta" ->
                root["delta"]?.jsonPrimitive?.contentOrNull?.let {
                    VoiceEvent.OutputTranscription(it, partial = true)
                }
            "response.audio_transcript.done" ->
                root["transcript"]?.jsonPrimitive?.contentOrNull?.let {
                    VoiceEvent.OutputTranscription(it)
                }
            "response.text.delta", "response.output_text.delta" ->
                root["delta"]?.jsonPrimitive?.contentOrNull?.let { VoiceEvent.TextDelta(it) }
            "response.function_call_arguments.done" ->
                VoiceEvent.ToolCall(
                    name = root["name"]?.jsonPrimitive?.contentOrNull ?: "",
                    arguments = root["arguments"]?.jsonPrimitive?.contentOrNull ?: "{}",
                    callId = root["call_id"]?.jsonPrimitive?.contentOrNull,
                )
            "response.done" -> VoiceEvent.TurnComplete()
            "input_audio_buffer.speech_started" -> VoiceEvent.Interrupted("speech started")
            "error" ->
                root["error"]?.jsonObject?.let {
                    VoiceEvent.Error(
                        message = it["message"]?.jsonPrimitive?.contentOrNull ?: "unknown error",
                        code = it["type"]?.jsonPrimitive?.contentOrNull,
                    )
                }
            else -> null
        }
    }
}