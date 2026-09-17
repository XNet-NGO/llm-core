package com.tddworks.responses.api

/**
 * Configuration for the OpenAI Responses API client.
 */
data class ResponsesConfig(
    val apiKey: () -> String = { "CONFIG_API_KEY" },
    val baseUrl: () -> String = { Responses.BASE_URL },
)