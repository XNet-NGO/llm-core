package com.tddworks.responses.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Streaming events for the Responses API. Mirrors the wire event names;
 * unknown event types are skipped by stream consumers.
 */
@Serializable
sealed interface ResponseStreamEvent {

    @Serializable
    @SerialName("response.created")
    data class Created(val response: Response? = null) : ResponseStreamEvent

    @Serializable
    @SerialName("response.in_progress")
    data class InProgress(val response: Response? = null) : ResponseStreamEvent

    @Serializable
    @SerialName("response.output_item.added")
    data class OutputItemAdded(val output_index: Int? = null, val item: ResponseItem? = null) :
        ResponseStreamEvent

    @Serializable
    @SerialName("response.output_item.done")
    data class OutputItemDone(val output_index: Int? = null, val item: ResponseItem? = null) :
        ResponseStreamEvent

    @Serializable
    @SerialName("response.content_part.added")
    data class ContentPartAdded(val item_id: String? = null, val part: JsonElement? = null) :
        ResponseStreamEvent

    @Serializable
    @SerialName("response.output_text.delta")
    data class OutputTextDelta(val item_id: String? = null, val delta: String = "") :
        ResponseStreamEvent

    @Serializable
    @SerialName("response.output_text.done")
    data class OutputTextDone(val item_id: String? = null, val text: String = "") :
        ResponseStreamEvent

    @Serializable
    @SerialName("response.function_call_arguments.delta")
    data class FunctionCallArgumentsDelta(
        val item_id: String? = null,
        val call_id: String? = null,
        val delta: String = "",
    ) : ResponseStreamEvent

    @Serializable
    @SerialName("response.function_call_arguments.done")
    data class FunctionCallArgumentsDone(
        val item_id: String? = null,
        val call_id: String? = null,
        val name: String? = null,
        val arguments: String? = null,
    ) : ResponseStreamEvent

    @Serializable
    @SerialName("response.reasoning_summary_text.delta")
    data class ReasoningSummaryTextDelta(val delta: String = "") : ResponseStreamEvent

    @Serializable
    @SerialName("response.completed")
    data class Completed(val response: Response? = null) : ResponseStreamEvent

    @Serializable
    @SerialName("response.failed")
    data class Failed(val response: Response? = null) : ResponseStreamEvent

    @Serializable
    @SerialName("response.incomplete")
    data class Incomplete(val response: Response? = null) : ResponseStreamEvent

    @Serializable
    @SerialName("error")
    data class ErrorEvent(val code: String? = null, val message: String? = null) : ResponseStreamEvent

    /** Raw unknown event passthrough. */
    @Serializable
    @SerialName("__unknown")
    data class Unknown(val type: String, val payload: JsonElement) : ResponseStreamEvent
}