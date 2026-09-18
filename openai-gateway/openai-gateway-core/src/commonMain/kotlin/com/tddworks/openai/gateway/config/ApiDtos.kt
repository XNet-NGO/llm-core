package com.tddworks.openai.gateway.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

// ---- Embeddings (OpenAI-compatible) ----

@Serializable
data class EmbeddingRequest(
    val model: String,
    val input: List<String> = emptyList(),
    @SerialName("encoding_format") val encodingFormat: String = "float",
)

@Serializable
data class EmbeddingData(
    val `object`: String = "embedding",
    val index: Int = 0,
    val embedding: List<Double> = emptyList(),
)

@Serializable
data class EmbeddingResponse(
    val `object`: String = "list",
    val data: List<EmbeddingData> = emptyList(),
    val usage: EmbeddingUsage? = null,
)

@Serializable
data class EmbeddingUsage(
    @SerialName("prompt_tokens") val promptTokens: Long? = null,
    @SerialName("total_tokens") val totalTokens: Long? = null,
)

// ---- Interactions API (Gemini stateful) ----

@Serializable
data class InteractionRequest(
    val model: String,
    val input: JsonElement,
    val steps: JsonArray? = null,
    val tools: List<JsonElement>? = null,
)

@Serializable
data class InteractionUsage(
    @SerialName("total_tokens") val totalTokens: Long? = null,
    @SerialName("total_input_tokens") val totalInputTokens: Long? = null,
    @SerialName("total_output_tokens") val totalOutputTokens: Long? = null,
)

@Serializable
data class InteractionResponse(
    val id: String = "",
    val `object`: String = "interaction",
    val status: String = "pending",
    val model: String = "",
    val created: String? = null,
    val updated: String? = null,
    val usage: InteractionUsage? = null,
    val steps: JsonArray? = null,
)

// ---- Batch API (OpenAI-compatible) ----

@Serializable
data class BatchFile(
    val id: String = "",
    val `object`: String = "file",
    val bytes: Long? = null,
    val filename: String? = null,
    val purpose: String? = null,
    val status: String? = null,
)

@Serializable
data class BatchRequest(
    @SerialName("input_file_id") val inputFileId: String,
    val endpoint: String = "/v1/chat/completions",
    @SerialName("completion_window") val completionWindow: String = "24h",
    val metadata: Map<String, String> = emptyMap(),
)

@Serializable
data class Batch(
    val id: String = "",
    val `object`: String = "batch",
    val endpoint: String? = null,
    val status: String = "validating",
    @SerialName("input_file_id") val inputFileId: String? = null,
    @SerialName("output_file_id") val outputFileId: String? = null,
    @SerialName("error_file_id") val errorFileId: String? = null,
    @SerialName("completion_window") val completionWindow: String? = null,
    @SerialName("created_at") val createdAt: Long? = null,
    @SerialName("completed_at") val completedAt: Long? = null,
    @SerialName("expires_at") val expiresAt: Long? = null,
)

// ---- Video generation (vendor-native, async submit + poll) ----

@Serializable
data class VideoRequest(
    val model: String,
    val prompt: String,
    val resolution: String? = null,
    val ratio: String? = null,
    val duration: Long? = null,
)

@Serializable
data class VideoTask(
    @SerialName("task_id") val taskId: String = "",
    @SerialName("task_status") val status: String = "PENDING",
    @SerialName("video_url") val videoUrl: String? = null,
    @SerialName("submit_time") val submitTime: String? = null,
    @SerialName("end_time") val endTime: String? = null,
)
