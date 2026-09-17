package com.tddworks.voice.api

import kotlinx.coroutines.flow.Flow

/**
 * A duplex live-voice session. Transport-agnostic: a JVM gateway binds it to a
 * WebSocket, a mobile app binds it to mic/speaker.
 *
 * Both canonical wire shapes map onto this surface:
 * - Gemini Live BidiGenerateContent (setup/realtimeInput/clientContent/toolResponse)
 * - OpenAI Realtime (session.update, input_audio_buffer events, response.create, ...)
 */
interface VoiceSession {

    /** Incoming events from the model. */
    val events: Flow<VoiceEvent>

    /** Send a chunk of audio (raw PCM), encoded base64 in the wire protocol. */
    suspend fun sendAudio(data: ByteArray)

    /** Send a natural-language user turn (also ends the user turn on Gemini Live). */
    suspend fun sendText(text: String)

    /** Deliver the result of a tool call. */
    suspend fun sendToolResponse(name: String, arguments: String, id: String? = null)

    /** Signal the end of the user's turn. */
    suspend fun endTurn()

    /** Close the session gracefully. */
    suspend fun close()
}