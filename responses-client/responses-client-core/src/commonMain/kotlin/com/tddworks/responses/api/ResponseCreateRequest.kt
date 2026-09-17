package com.tddworks.responses.api

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ResponseTool(
    val type: String = "function",
    val name: String = "",
    val description: String = "",
    val parameters: JsonElement? = null,
    val strict: Boolean? = null,
)

@Serializable
data class ReasoningConfig(
    val effort: String? = null,
    val summary: String? = null,
    val mode: String? = null,
)

@Serializable
data class ResponseTextConfig(
    val format: JsonElement? = null,
)

@Serializable
data class OutputAudioConfig(
    val voice: String = "alloy",
    val format: String = "pcm16",
)

@Serializable
data class ResponseCreateRequest(
    val model: String,
    val input: List<ResponseInputItem> = emptyList(),
    val instructions: String? = null,
    val maxOutputTokens: Long? = null,
    val previousResponseId: String? = null,
    val store: Boolean? = null,
    val stream: Boolean = false,
    val include: List<String> = emptyList(),
    val reasoning: ReasoningConfig? = null,
    val text: ResponseTextConfig? = null,
    val tools: List<ResponseTool> = emptyList(),
    val toolChoice: JsonElement? = null,
    val parallelToolCalls: Boolean? = null,
    val temperature: Double? = null,
    val topP: Double? = null,
    val metadata: Map<String, String> = emptyMap(),
    val outputAudio: OutputAudioConfig? = null,
    val user: String? = null,
)