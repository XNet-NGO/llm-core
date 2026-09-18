package com.tddworks.voice.api

import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Live bidirectional (WS BidiGenerateContent) smoke test against the Google AI Studio
 * Live API with the free tier key (AQ. format). Requires AI_STUDIO_KEY.
 *
 * Probes live-capable model ids until the stream connector accepts one, then sends a
 * text turn and asserts the model's audio output flows back. Turn completion arrives
 * as the final frame; the assertion here covers connect + bidi exchange, which is the
 * deterministic contract.
 *
 * Run: AI_STUDIO_KEY=<key> ./gradlew :voice-client:voice-client-core:jvmTest --tests '*GeminiLiveBidiSmokeITest'
 */
@EnabledIfEnvironmentVariable(named = "AI_STUDIO_KEY", matches = ".+")
class GeminiLiveBidiSmokeITest {

    private val candidates =
        listOf(
            "gemini-3.8-live",
            "gemini-3.8-flash-live",
            "gemini-3-flash-live",
            "gemini-2.5-flash-live",
            "gemini-2.5-flash-native-audio-dialog",
        )

    private data class TurnOutcome(val model: String, val ready: Boolean, val output: Boolean)

    @Test
    fun `bidirectional live session exchanges a turn`() = runBlocking {
        var outcome: TurnOutcome? = null

        for (model in candidates) {
            val config =
                Voice.config(
                    vendor = VoiceVendor.GEMINI_LIVE,
                    apiKey = System.getenv("AI_STUDIO_KEY"),
                    baseUrl = "wss://generativelanguage.googleapis.com",
                    model = model,
                    systemInstruction = "You are terse. Answer in one short sentence.",
                    voice = "Puck",
                    apiVersion = "v1alpha",
                )
            val session = Voice.session(config)
            val events = Channel<VoiceEvent>(Channel.UNLIMITED)
            var collector: Job? = null

            var ready = false
            var output = false
            var failedWith: String? = null

            collector = launch { session.events.collect { events.trySend(it) } }
            session.sendText("Say OK.")
            session.endTurn()

            val deadline = System.currentTimeMillis() + 40_000
            while (System.currentTimeMillis() < deadline && !(ready && output)) {
                val event = events.receiveCatching().getOrNull() ?: continue
                when (event) {
                    is VoiceEvent.SessionReady -> ready = true
                    is VoiceEvent.AudioDelta -> output = true
                    is VoiceEvent.OutputTranscription -> output = true
                    is VoiceEvent.TextDelta -> output = true
                    is VoiceEvent.Error -> {
                        failedWith = event.message
                        break
                    }
                    else -> {}
                }
            }

            collector.cancel()
            session.close()
            events.close()

            if (failedWith == null && ready && output) {
                outcome = TurnOutcome(model, ready, output)
                println("LIVE BIDI: model=$model ready=$ready output=$output")
                break
            }
            if (failedWith != null) {
                println("MODEL $model ERROR: $failedWith")
            }
        }

        assertNotNull(outcome, "no live model accepted the session; tried: $candidates")
        assertTrue(outcome!!.ready, "session was never ready")
        assertTrue(outcome!!.output, "expected model audio or text output")
        println("LIVE BIDI OK with model=${outcome!!.model}")
    }
}