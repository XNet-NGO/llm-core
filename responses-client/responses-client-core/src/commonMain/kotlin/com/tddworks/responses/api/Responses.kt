package com.tddworks.responses.api

import com.tddworks.responses.api.internal.default
import kotlinx.coroutines.flow.Flow

/**
 * OpenAI Responses API (D5) — stateful sessions with items-based input, streaming and
 * lifecycle endpoints. Transport-shape identical across providers that mirror Responses
 * (OpenAI, OpenRouter passthrough, MiniMax responses-compat).
 */
interface Responses {
    companion object {
        const val BASE_URL = "https://api.openai.com"
        const val RESPONSES_PATH = "/v1/responses"

        /** Create a client with a static API key. */
        fun create(apiKey: String, baseUrl: String = BASE_URL): Responses =
            default(ResponsesConfig(apiKey = { apiKey }, baseUrl = { baseUrl }))

        /** Create a client with dynamic API key/base URL. */
        fun create(apiKey: () -> String, baseUrl: () -> String = { BASE_URL }): Responses =
            default(ResponsesConfig(apiKey, baseUrl))
    }

    /** Create a response (non-streaming). */
    suspend fun create(request: ResponseCreateRequest): Response

    /** Create a response and stream its events. */
    fun stream(request: ResponseCreateRequest): Flow<ResponseStreamEvent>

    /** Retrieve an existing response by id. */
    suspend fun retrieve(id: String): Response

    /** Cancel an in-flight response. */
    suspend fun cancel(id: String): Response
}