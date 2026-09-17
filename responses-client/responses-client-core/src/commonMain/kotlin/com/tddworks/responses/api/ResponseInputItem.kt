package com.tddworks.responses.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Input items pushed to a Responses session. Mirrors the wire types:
 * message items, function calls and their outputs, reasoning traces.
 */
@Serializable
sealed interface ResponseInputItem {

    @Serializable
    @SerialName("message")
    data class Message(
        val role: String = "user",
        val content: List<ResponseContentPart> = emptyList(),
        val name: String? = null,
    ) : ResponseInputItem

    @Serializable
    @SerialName("function_call")
    data class FunctionCall(
        val name: String,
        val arguments: String = "",
        val callId: String,
    ) : ResponseInputItem

    @Serializable
    @SerialName("function_call_output")
    data class FunctionCallOutput(
        val callId: String,
        val output: JsonElement? = null,
        val outputText: String? = null,
    ) : ResponseInputItem

    @Serializable
    @SerialName("reasoning")
    data class Reasoning(
        val summary: List<TextPart> = emptyList(),
        val content: List<JsonElement> = emptyList(),
        val effort: String? = null,
    ) : ResponseInputItem

    @Serializable
    data class TextPart(val text: String)
}