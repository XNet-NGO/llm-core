package com.tddworks.voice.api

import kotlinx.serialization.Serializable

@Serializable
enum class VoiceVendor {
    GEMINI_LIVE,
    OPENAI_REALTIME,
}

/**
 * Configuration for a live voice session. Both canonical engines (Gemini Live
 * BidiGenerateContent, OpenAI Realtime) are driven by this shape; turning a
 * provider config into this is the host's job.
 */
data class VoiceConfig(
    val vendor: VoiceVendor,
    val apiKey: () -> String = { "CONFIG_API_KEY" },
    val baseUrl: () -> String,
    val model: () -> String,
    val systemInstruction: String? = null,
    val voice: String? = null,
    val audioFormat: String = "pcm16",
    val sampleRate: Int = 24000,
)