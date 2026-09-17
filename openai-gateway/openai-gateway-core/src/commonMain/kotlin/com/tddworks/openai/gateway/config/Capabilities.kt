package com.tddworks.openai.gateway.config

import kotlinx.serialization.Serializable

@Serializable
enum class VoiceMode {
    /** Bidirectional duplex voice (OpenAI Realtime, Gemini Live). */
    REALTIME,

    /** Gemini Live style bidi streaming. */
    LIVE,

    /** One-way streamed TTS/STT (ElevenLabs, Deepgram, Cartesia). */
    TURN_STREAM,
}

/**
 * Capability flags for a provider. Explicit config always wins over catalog inference.
 */
@Serializable
data class Capabilities(
    val chat: Boolean = true,
    val completions: Boolean = false,
    val embeddings: Boolean = false,
    val responses: Boolean = false,
    val rerank: Boolean = false,
    val moderation: Boolean = false,
    val tts: Boolean = false,
    val stt: Boolean = false,
    val imagesGenerate: Boolean = false,
    val imagesEdit: Boolean = false,
    val voice: VoiceMode? = null,
) {
    companion object {
        fun chatOnly() = Capabilities()
        fun all() =
            Capabilities(
                chat = true,
                completions = true,
                embeddings = true,
                responses = true,
                rerank = true,
                moderation = true,
                tts = true,
                stt = true,
                imagesGenerate = true,
                imagesEdit = true,
            )
    }
}