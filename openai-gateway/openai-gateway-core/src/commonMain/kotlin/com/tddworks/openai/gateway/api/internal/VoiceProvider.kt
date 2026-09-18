package com.tddworks.openai.gateway.api.internal

import com.tddworks.openai.gateway.api.OpenAIProvider
import com.tddworks.openai.gateway.config.ProviderConfig
import com.tddworks.openai.gateway.config.VoiceMode
import com.tddworks.voice.api.Voice
import com.tddworks.voice.api.VoiceConfig
import com.tddworks.voice.api.VoiceSession
import com.tddworks.voice.api.VoiceVendor

/**
 * D6 voice binding: turn a declarative [ProviderConfig] (dialect VOICE_REALTIME) into a live
 * [VoiceSession] from `voice-client-core`. Voice does not fit the chat-oriented [OpenAIProvider]
 * surface, so it is exposed as its own factory rather than through `OpenAIProvider.from(config)`.
 *
 * Vendor is derived from `capabilities.voice` ([VoiceMode]):
 * - `LIVE`        → Gemini Live (bidi BidiGenerateContent)
 * - `REALTIME`    → OpenAI Realtime
 * - `TURN_STREAM` → Qwen TTS (one-way streamed)
 *
 * Model resolves from the alias map (first entry) if present, else the provider id. Base URL,
 * API key, and (Gemini) API version come from the config.
 */
fun OpenAIProvider.Companion.voiceSession(config: ProviderConfig): VoiceSession =
    Voice.session(config.toVoiceConfig())

internal fun ProviderConfig.toVoiceConfig(): VoiceConfig {
    val vendor =
        when (capabilities.voice) {
            VoiceMode.LIVE -> VoiceVendor.GEMINI_LIVE
            VoiceMode.REALTIME -> VoiceVendor.OPENAI_REALTIME
            VoiceMode.TURN_STREAM -> VoiceVendor.QWEN_TTS
            null ->
                throw IllegalArgumentException(
                    "VOICE_REALTIME provider '$id' must set capabilities.voice (LIVE|REALTIME|TURN_STREAM)",
                )
        }
    // Prefer an explicit alias target as the upstream model; fall back to the provider id.
    val modelId = aliases.values.firstOrNull() ?: id
    // Gemini Live uses v1alpha; others default to v1beta.
    val version = if (vendor == VoiceVendor.GEMINI_LIVE) "v1alpha" else "v1beta"
    return VoiceConfig(
        vendor = vendor,
        apiKey = { auth.apiKey },
        baseUrl = { baseUrl },
        model = { modelId },
        apiVersion = version,
    )
}
