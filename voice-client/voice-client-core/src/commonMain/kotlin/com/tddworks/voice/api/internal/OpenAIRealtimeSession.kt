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


// ---- pure wire contract (unit-tested) ----

internal fun realtimeWsUrl(base: String, apiKey: String, model: String): String {
    val trimmed = base.trimEnd('/')
    val keySuffix = if (apiKey.isNotEmpty()) "&api-key=$apiKey" else ""
    return "$trimmed/v1/realtime?model=$model$keySuffix"
}

internal fun realtimeAudioAppend(dataB64: String): JsonObject =
    buildJsonObject {
        put("type", "input_audio_buffer.append")
        put("audio", dataB64)
    }

internal fun realtimeSessionUpdate(systemInstruction: String?, voice: String?): JsonObject =
    buildJsonObject {
        put("type", "session.update")
        put(
            "session",
            buildJsonObject {
                put("modalities", JsonArray(listOf(JsonPrimitive("audio"), JsonPrimitive("text"))))
                put("instructions", systemInstruction ?: "")
                put("voice", voice ?: "alloy")
            },
        )
    }

internal fun realtimeToolOutput(callId: String, arguments: String): JsonObject =
    buildJsonObject {
        put("type", "conversation.item.create")
        put(
            "item",
            buildJsonObject {
                put("type", "function_call_output")
                put("call_id", callId)
                put("output", arguments)
            },
        )
    }

internal fun realtimeResponseCreate(): JsonObject = buildJsonObject { put("type", "response.create") }

internal fun realtimeCommit(): JsonObject = buildJsonObject { put("type", "input_audio_buffer.commit") }

internal fun realtimeArguments(raw: kotlinx.serialization.json.JsonElement?): String =
    when (raw) {
        null -> "{}"
        is kotlinx.serialization.json.JsonPrimitive -> raw.content
        else -> raw.toString()
    }

internal fun parseRealtimeEvent(json: Json, text: String, model: String): VoiceEvent? {
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
                        ?: model,
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
                arguments = realtimeArguments(root["arguments"]),
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

// ---- session wrapper ----
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
                launch {
                    for (payload in pendingSends) {
                        sessionScope.outgoing.send(Frame.Text(payload))
                    }
                }
                eventsFlow.tryEmit(VoiceEvent.SessionReady(config.model()))
                for (frame in sessionScope.incoming) {
                    if (frame !is Frame.Text) continue
                    val event = parseRealtimeEvent(json, frame.readText(), config.model()) ?: continue
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

    private fun wsUrl(): String = realtimeWsUrl(config.baseUrl(), config.apiKey(), config.model())

    private val pendingSends = Channel<String>(Channel.UNLIMITED)

    private fun send(message: JsonObject) {
        pendingSends.trySend(json.encodeToString(message))
    }

    @OptIn(ExperimentalEncodingApi::class)
    override suspend fun sendAudio(data: ByteArray) {
        send(realtimeAudioAppend(Base64.encode(data)))
    }

    override suspend fun sendText(text: String) {
        send(realtimeSessionUpdate(config.systemInstruction, config.voice))
        endTurn()
    }

    override suspend fun sendToolResponse(name: String, arguments: String, id: String?) {
        send(realtimeToolOutput(id ?: "call_${nextCallId()}", arguments))
        send(realtimeResponseCreate())
    }

    override suspend fun endTurn() {
        send(realtimeCommit())
        send(realtimeResponseCreate())
    }

    private fun nextCallId(): String = (++callCounter).toString()

    override suspend fun close() {
        scope.coroutineContext[Job]?.cancel()
        eventsFlow.tryEmit(VoiceEvent.Closed())
    }
}
