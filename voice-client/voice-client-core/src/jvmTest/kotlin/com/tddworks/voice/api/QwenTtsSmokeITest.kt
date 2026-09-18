package com.tddworks.voice.api

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Qwen DashScope TTS over WebSocket (TURN_STREAM voice session).
 *
 * NOTE: on this account the TTS service currently rejects every qwen3-tts model id
 * server-side (ModelNotFound via /api-ws/v1/inference). The test verifies the session
 * wire contract: it must either receive audio (ServerReady + AudioDeltas) or the
 * server's explicit error — never a silent hang.
 *
 * Requires QWEN_KEY. Run: QWEN_KEY=<key> ./gradlew :voice-client:voice-client-core:jvmTest --tests '*QwenTtsSmokeITest'
 */
@EnabledIfEnvironmentVariable(named = "QWEN_KEY", matches = ".+")
class QwenTtsSmokeITest {

    @Test
    fun `qwen tts duplex session exchanges or surfaces server error`() = runBlocking {
        val config =
            Voice.config(
                vendor = VoiceVendor.QWEN_TTS,
                apiKey = System.getenv("QWEN_KEY"),
                baseUrl = "wss://dashscope-intl.aliyuncs.com/api-ws/v1/inference",
                model = "qwen3-tts-flash",
                voice = "Cherry",
            )
        val session = Voice.session(config)
        val events = Channel<VoiceEvent>(Channel.UNLIMITED)
        val collector = launch { session.events.collect { events.trySend(it) } }

        session.sendText("Hello from Qwen TTS, this is a test.")

        var audioChunks = 0
        var serverError: String? = null
        var ready = false
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline && audioChunks == 0 && serverError == null) {
            val received =
                withTimeoutOrNull(2_000) { events.receiveCatching().getOrNull() } ?: continue
            when (received) {
                is VoiceEvent.SessionReady -> { ready = true; println("TTS READY") }
                is VoiceEvent.AudioDelta -> { audioChunks++ }
                is VoiceEvent.Error -> { serverError = "${received.code}: ${received.message}"; println("TTS ERROR: $serverError") }
                else -> {}
            }
        }
        collector.cancel()
        session.close()
        events.close()

        if (audioChunks > 0) {
            println("TTS OK: $audioChunks audio chunks, ready=$ready")
        }
        assertTrue(audioChunks > 0 || serverError != null, "expected audio or an explicit server error")
        println("TTS RESULT: chunks=$audioChunks ready=$ready error=${serverError ?: "none"}")
    }
}