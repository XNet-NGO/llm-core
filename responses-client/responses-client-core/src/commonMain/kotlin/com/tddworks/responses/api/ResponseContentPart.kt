package com.tddworks.responses.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Content parts inside a message item. Common surface across providers;
 * unknown part types are ignored by the lenient decoder.
 */
@Serializable
sealed interface ResponseContentPart {

    @Serializable
    @SerialName("input_text")
    data class InputText(val text: String) : ResponseContentPart

    @Serializable
    @SerialName("output_text")
    data class OutputText(
        val text: String,
        val annotations: List<JsonElement> = emptyList(),
    ) : ResponseContentPart

    @Serializable
    @SerialName("refusal")
    data class Refusal(val refusal: String) : ResponseContentPart

    @Serializable
    @SerialName("input_audio")
    data class InputAudio(
        val data: String = "",
        val format: String = "pcm16",
        val transcript: String? = null,
    ) : ResponseContentPart

    @Serializable
    @SerialName("input_image")
    data class InputImage(
        val imageUrl: String? = null,
        val imageData: String? = null,
        val detail: String? = null,
    ) : ResponseContentPart
}