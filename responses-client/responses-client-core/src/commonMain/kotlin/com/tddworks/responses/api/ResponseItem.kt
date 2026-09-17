package com.tddworks.responses.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Output items of a [Response].
 */
@Serializable
sealed interface ResponseItem {

    @Serializable
    @SerialName("message")
    data class Message(
        val id: String? = null,
        val role: String = "assistant",
        val status: String? = null,
        val content: List<ResponseContentPart> = emptyList(),
    ) : ResponseItem

    @Serializable
    @SerialName("function_call")
    data class FunctionCall(
        val id: String? = null,
        val callId: String? = null,
        val name: String? = null,
        val arguments: String? = null,
        val status: String? = null,
    ) : ResponseItem

    @Serializable
    @SerialName("function_call_output")
    data class FunctionCallOutput(
        val id: String? = null,
        val callId: String? = null,
        val output: String? = null,
        val status: String? = null,
    ) : ResponseItem

    @Serializable
    @SerialName("reasoning")
    data class Reasoning(
        val id: String? = null,
        val summary: List<JsonElement> = emptyList(),
        val content: List<JsonElement> = emptyList(),
        val effort: String? = null,
    ) : ResponseItem

    /** Unknown output item shape — kept as raw JSON. */
    @Serializable
    @SerialName("__unknown")
    data class Unknown(val payload: JsonElement) : ResponseItem
}