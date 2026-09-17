package com.tddworks.voice.api

/**
 * Events emitted by a [VoiceSession]. Canonical union of the Gemini Live and
 * OpenAI Realtime message sets.
 */
sealed interface VoiceEvent {

    /** Session established with the model. */
    data class SessionReady(val model: String, val sessionId: String? = null) : VoiceEvent

    /** Model audio output chunk, base64-encoded (PCM format per config). */
    data class AudioDelta(val base64: String) : VoiceEvent

    /** Incremental text output from the model. */
    data class TextDelta(val text: String) : VoiceEvent

    /** Live transcription of the user's speech. */
    data class InputTranscription(val transcript: String, val partial: Boolean = false) : VoiceEvent

    /** Transcription of the model's spoken output. */
    data class OutputTranscription(val transcript: String, val partial: Boolean = false) :
        VoiceEvent

    /** A tool call the model wants executed. */
    data class ToolCall(
        val name: String,
        val arguments: String,
        val callId: String? = null,
    ) : VoiceEvent

    /** The model finished its turn. */
    data class TurnComplete(val turnId: String? = null) : VoiceEvent

    /** The model turn was interrupted (speech detected / cancel). */
    data class Interrupted(val reason: String? = null) : VoiceEvent

    data class Error(val message: String, val code: String? = null) : VoiceEvent

    /** Session has ended. */
    data class Closed(val reason: String? = null) : VoiceEvent
}