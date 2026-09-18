package com.tddworks.voice.api

import com.tddworks.voice.api.internal.GeminiLiveSession
import com.tddworks.voice.api.internal.OpenAIRealtimeSession
import com.tddworks.voice.api.internal.QwenTtsSession

/**
 * Entry point for live voice sessions. Both canonical engines are first-class
 * citizens; the host binds [VoiceSession.events] and microphones/WebSockets as it
 * sees fit.
 */
object Voice {

    /** Create a session for the configured vendor. */
    fun session(config: VoiceConfig): VoiceSession =
        when (config.vendor) {
            VoiceVendor.GEMINI_LIVE -> GeminiLiveSession(config)
            VoiceVendor.OPENAI_REALTIME -> OpenAIRealtimeSession(config)
            VoiceVendor.QWEN_TTS -> QwenTtsSession(config)
        }

    /** One-liner for [VoiceConfig]. */
    fun config(
        vendor: VoiceVendor,
        apiKey: String,
        baseUrl: String,
        model: String,
        systemInstruction: String? = null,
        voice: String? = null,
        apiVersion: String = "v1beta",
    ) =
        VoiceConfig(
            vendor = vendor,
            apiKey = { apiKey },
            baseUrl = { baseUrl },
            model = { model },
            systemInstruction = systemInstruction,
            voice = voice,
            apiVersion = apiVersion,
        )
}