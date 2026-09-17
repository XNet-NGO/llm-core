package com.tddworks.responses.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class ResponseError(
    val code: String? = null,
    val message: String? = null,
    val param: String? = null,
    val type: String? = null,
)

@Serializable
data class ResponseUsage(
    val inputTokens: Long? = null,
    val inputTokensDetails: JsonElement? = null,
    val outputTokens: Long? = null,
    val outputTokensDetails: JsonElement? = null,
    val totalTokens: Long? = null,
)

@Serializable
data class Response(
    val id: String = "",
    @SerialName("object") val type: String = "response",
    val created_at: Long = 0,
    val status: String = "in_progress",
    val model: String = "",
    val output: List<ResponseItem> = emptyList(),
    val output_text: String = "",
    val output_audio: JsonElement? = null,
    val error: ResponseError? = null,
    val incomplete_details: JsonElement? = null,
    val instructions: String? = null,
    val max_output_tokens: Long? = null,
    val metadata: Map<String, String> = emptyMap(),
    val parallel_tool_calls: Boolean? = null,
    val previous_response_id: String? = null,
    val reasoning: JsonElement? = null,
    val store: Boolean? = null,
    val temperature: Double? = null,
    val text: JsonElement? = null,
    val tool_choice: JsonElement? = null,
    val tools: List<ResponseTool> = emptyList(),
    val top_p: Double? = null,
    val truncation: String? = null,
    val usage: ResponseUsage? = null,
    val user: String? = null,
)

/** Convenience view over the output items. */
fun Response.textBlocks(): List<String> =
    output.mapNotNull { item ->
        (item as? ResponseItem.Message)?.content?.mapNotNull { part ->
            (part as? ResponseContentPart.OutputText)?.text
        }?.joinToString("")
    }