package com.tddworks.openai.gateway.config

import kotlinx.serialization.Serializable

/**
 * Per-operation endpoint overrides. `null` means the dialect default path is used.
 */
@Serializable
data class Endpoints(
    val chat: String? = null,
    val completions: String? = null,
    val interactions: String? = null,
    val batches: String? = null,
    val files: String? = null,
    val embeddings: String? = null,
    val models: String? = null,
    val responses: String? = null,
    val imagesGenerations: String? = null,
    val imagesEdits: String? = null,
    val audioSpeech: String? = null,
    val audioTranscriptions: String? = null,
    val rerank: String? = null,
    val moderation: String? = null,
    val videos: String? = null,
    val tasks: String? = null,
)