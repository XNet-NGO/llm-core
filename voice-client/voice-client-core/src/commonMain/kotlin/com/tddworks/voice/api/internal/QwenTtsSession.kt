package com.tddworks.voice.api.internal

import com.tddworks.voice.api.VoiceConfig
import com.tddworks.voice.api.VoiceEvent
import com.tddworks.voice.api.VoiceSession
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Qwen DashScope TTS over WebSocket (TURN_STREAM). The wire contract (verified against
 * the live intl endpoint) is the same duplex sequence the official `dashscope` SDK sends:
 *
 * 1. `wss://{host}/api-ws/v1/inference` upgraded with `Authorization: Bearer <key>`
 *    (DashScope accepts the raw API key as a bearer token).
 * 2. A `run-task` frame to establish the task:
 *    `header{action:"run-task", task_id, streaming:"duplex"}` + `payload{model,
 *    task_group:"audio", task:"tts", function:"SpeechSynthesizer", input:{},
 *    parameters{voice, format, sample_rate, text_type, rate, pitch, seed, type}}`.
 * 3. One or more `continue-task` frames carrying the utterance:
 *    `payload{model, task_group, task, function, input:{text:"<sentence>"}}`.
 * 4. A `finish-task` frame to close the task.
 *
 * Audio returns as BINARY frames (each a PCM chunk in the selected format); protocol
 * acknowledgements and errors come back as TEXT frames (`header.event: task-succeeded /
 * task-failed`).
 */
internal class QwenTtsSession(private val config: VoiceConfig) : VoiceSession {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val eventsFlow = MutableSharedFlow<VoiceEvent>(extraBufferCapacity = 128)
    private val json = Json { ignoreUnknownKeys = true }
    private val pendingSends = Channel<String>(Channel.UNLIMITED)
    private var outbound: SendChannel<Frame>? = null
    private var taskEstablished = false
    private val taskId = "tts_ss_${io.ktor.util.date.GMTDate().timestamp}"
    private val startQueued = CompletableDeferred<Unit>()

    override val events: Flow<VoiceEvent> = eventsFlow

    init {
        scope.launch { run() }
    }

    private fun wsUrl(): String {
        val base = config.baseUrl().trimEnd('/')
        // DashScope inference WS: /api-ws/v1/inference
        return if (base.contains("api-ws")) base else "$base/api-ws/v1/inference"
    }

    private suspend fun run() {
        val client = HttpClient { install(WebSockets) }
        try {
            client.webSocket(
                urlString = wsUrl(),
                request = {
                    if (config.apiKey().isNotEmpty()) {
                        header("Authorization", "Bearer ${config.apiKey()}")
                    }
                },
            ) {
                outbound = outgoing
                // Start frame must go FIRST, before any continue-task/finish-task frames.
                pendingSends.trySend(startFrame())
                startQueued.complete(Unit)
                launch {
                    for (payload in pendingSends) {
                        outgoing.send(Frame.Text(payload))
                    }
                }
                for (frame in incoming) {
                    when (frame) {
                        is Frame.Binary -> {
                            // audio data chunk
                            val bytes = frame.data
                            emitBinary(bytes)
                        }
                        is Frame.Text -> {
                            val text = frame.readText()
                            runCatching { emitJsonEvent(text) }
                        }
                        else -> {}
                    }
                }
            }
        } catch (e: Throwable) {
            eventsFlow.tryEmit(VoiceEvent.Error(e.message ?: "qwen tts failed"))
        } finally {
            client.close()
            eventsFlow.tryEmit(VoiceEvent.Closed())
        }
    }

    private fun startFrame(): String =
        json.encodeToString(
            buildJsonObject {
                put(
                    "header",
                    buildJsonObject {
                        put("action", "run-task")
                        put("task_id", taskId)
                        put("streaming", "duplex")
                    },
                )
                put(
                    "payload",
                    buildJsonObject {
                        put("model", config.model())
                        put("task_group", "audio")
                        put("task", "tts")
                        put("function", "SpeechSynthesizer")
                        put("input", buildJsonObject {})
                        put(
                            "parameters",
                            buildJsonObject {
                                put("voice", config.voice ?: "Cherry")
                                put("format", config.audioFormat.ifBlank { "wav" })
                                put("sample_rate", config.sampleRate)
                                put("text_type", "PlainText")
                                put("volume", 50)
                                put("rate", 1.0)
                                put("pitch", 1.0)
                                put("seed", 0)
                                put("type", 0)
                            },
                        )
                    },
                )
            },
        )

    private fun emitBinary(bytes: ByteArray) {
        val b64 = klaus(bytes)
        eventsFlow.tryEmit(VoiceEvent.AudioDelta(b64))
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
    private fun klaus(bytes: ByteArray): String = kotlin.io.encoding.Base64.encode(bytes)

    private fun emitJsonEvent(text: String) {
        val root = json.parseToJsonElement(text).jsonObject
        val header = root["header"]?.jsonObject
        val event = header?.get("event")?.jsonPrimitive?.contentOrNull
        when (event) {
            "task-succeeded" -> {
                if (!taskEstablished) {
                    taskEstablished = true
                    eventsFlow.tryEmit(VoiceEvent.SessionReady(config.model()))
                }
            }
            "task-failed" -> {
                val message = header?.get("error_message")?.jsonPrimitive?.contentOrNull ?: "tts task failed"
                val code = header?.get("error_code")?.jsonPrimitive?.contentOrNull
                eventsFlow.tryEmit(VoiceEvent.Error(code = code, message = message))
            }
        }
    }

    override suspend fun sendAudio(data: ByteArray) {
        // TTS sessions are text-in; audio input is not part of the contract.
    }

    override suspend fun sendText(text: String) {
        startQueued.await()
        val payload =
            buildJsonObject {
                put(
                    "header",
                    buildJsonObject {
                        put("action", "continue-task")
                        put("task_id", taskId)
                        put("streaming", "duplex")
                    },
                )
                put(
                    "payload",
                    buildJsonObject {
                        put("model", config.model())
                        put("task_group", "audio")
                        put("task", "tts")
                        put("function", "SpeechSynthesizer")
                        put(
                            "input",
                            buildJsonObject {
                                put("text", text)
                            },
                        )
                    },
                )
            }
        pendingSends.trySend(json.encodeToString(payload))
    }

    override suspend fun endTurn() {
        startQueued.await()
        // TTS synthesizes per utterance, so an explicit finish closes the task.
        val finish =
            buildJsonObject {
                put(
                    "header",
                    buildJsonObject {
                        put("action", "finish-task")
                        put("task_id", taskId)
                        put("streaming", "duplex")
                    },
                )
                put("payload", buildJsonObject {})
            }
        pendingSends.trySend(json.encodeToString(finish))
    }

    override suspend fun sendToolResponse(name: String, arguments: String, id: String?) {
        // not part of the TTS contract
    }

    override suspend fun close() {
        scope.coroutineContext[Job]?.cancel()
        eventsFlow.tryEmit(VoiceEvent.Closed())
    }
}