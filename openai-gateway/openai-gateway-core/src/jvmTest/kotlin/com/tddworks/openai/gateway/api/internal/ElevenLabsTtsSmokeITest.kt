package com.tddworks.openai.gateway.api.internal

import com.tddworks.openai.gateway.config.Dialect
import com.tddworks.openai.gateway.config.ProviderAuth
import com.tddworks.openai.gateway.config.ProviderConfig
import com.tddworks.openai.gateway.config.ResponseMapper
import com.tddworks.openai.gateway.config.TemplateTransform
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * LIVE ElevenLabs TTS over the D7 transforms engine — the field's first consumer.
 * Pins: xi-api-key auth, voice_id path template, mp3 raw-body mapper.
 *
 * Free API tier rejects library voices (402 paid_plan_required) on restricted
 * combinations; the test walks a candidate set until the account accepts one.
 *
 * Requires ELEVENLABS_KEY. Run:
 *   ELEVENLABS_KEY=<key> ./gradlew :openai-gateway:openai-gateway-core:jvmTest --tests '*ElevenLabsTtsSmokeITest'
 */
@EnabledIfEnvironmentVariable(named = "ELEVENLABS_KEY", matches = ".+")
class ElevenLabsTtsSmokeITest {

    @Test
    fun `elevenlabs tts synthesizes mp3 audio via transforms engine`() = runTest {
        val key: String = System.getenv("ELEVENLABS_KEY")
        val forcedModel: String? = System.getenv("ELEVENLABS_MODEL")
        val forcedVoice: String? = System.getenv("ELEVENLABS_VOICE")
        val voiceCandidates =
            forcedVoice?.let { listOf(it) } ?: listOf(
                "EXAVITQu4vr4xnSDxMaL", // Sarah
                "21m00Tcm4TlvDq8ikWAM", // Rachel
                "CwhRBWXzGAHq8TQ4Fs17", // Roger
                "pNInz6obpgDQGcFmaJgB", // Chris
            )
        val modelCandidates =
            forcedModel?.let { listOf(it) } ?: listOf("eleven_v3", "eleven_multilingual_v2")
        val provider =
            com.tddworks.openai.gateway.api.internal.mediaProvider(
                ProviderConfig(
                    id = "elevenlabs",
                    name = "ElevenLabs",
                    dialect = Dialect.TEMPLATE,
                    baseUrl = "https://api.elevenlabs.io",
                    auth = ProviderAuth(scheme = com.tddworks.openai.gateway.config.AuthScheme.X_API_KEY, apiKey = key, keyHeader = "xi-api-key"),
                    aliases = mapOf("tts" to modelCandidates.first()),
                    transforms =
                        mapOf(
                            "audioSpeech" to
                                TemplateTransform(
                                    path = "/v1/text-to-speech/{{voice_id}}",
                                    headers = mapOf("Accept" to "audio/mpeg"),
                                    // NOTE: stability tuning is paid-only on v3 models (402).
                                    requestTemplate =
                                        buildJsonObject {
                                            put("text", JsonPrimitive("{{text}}"))
                                            put("model_id", JsonPrimitive("{{model}}"))
                                        },
                                    responseMapper = ResponseMapper(from = "$", decode = "raw"),
                                ),
                        ),
                ),
            ) as TtsApi
        var audio: ByteArray? = null
        var lastError: Throwable? = null
        outer@ for (model in modelCandidates) {
            for (voice in voiceCandidates) {
                val attempt =
                    runCatching {
                        provider.synthesize(
                            TtsRequest(
                                text = "Hello from llm-core, this audio was synthesized through the template transforms engine. One two three four five.",
                                voice = voice,
                            ),
                        )
                    }
                if (attempt.isSuccess) {
                    audio = attempt.getOrNull()
                    print("ELEVENLABS TTS OK: model=$model voice=$voice ${audio!!.size} bytes")
                    break@outer
                }
                lastError = attempt.exceptionOrNull()
                print("ELEVENLABS TTS attempt failed: model=$model voice=$voice ${lastError!!.message}")
            }
        }
        assertTrue(audio != null, "all voice/model attempts failed; last: ${lastError?.message}")
        assertTrue(audio!!.size > 1000, "expected a real mp3, got ${audio!!.size} bytes")
        val head = audio!!.take(3).toByteArray()
        val isMp3 =
            head[0] == 0x49.toByte() || // 'I' (ID3)
                (head[0] == 0xFF.toByte() && (head[1].toInt() and 0xE0) == 0xE0)
        assertTrue(isMp3, "expected mp3 magic bytes, got ${head.joinToString { "%02x".format(it.toInt() and 0xFF) }}")
    }
}