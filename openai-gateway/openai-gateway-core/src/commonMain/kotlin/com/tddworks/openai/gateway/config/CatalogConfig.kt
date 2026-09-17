package com.tddworks.openai.gateway.config

import kotlinx.serialization.Serializable

@Serializable
enum class CatalogMode {
    /** Fetch the model catalog live from `GET {base}/models` and cache it. */
    AUTO,

    /** Use the configured model list only; never call the catalog endpoint. */
    STATIC,

    /** Static list as the base, overlaid with live catalog data when reachable. */
    MERGED,
}

@Serializable
enum class StreamFormat {
    SSE,
    EVENTSTREAM,
    WS,
    NDJSON,
    CHUNKED,
}

@Serializable
data class CatalogModel(
    val id: String,
    val contextLength: Long? = null,
    val maxOutputLength: Long? = null,
    val inputModalities: List<String> = emptyList(),
    val outputModalities: List<String> = emptyList(),
    val supportedFeatures: List<String> = emptyList(),
)

@Serializable
data class Catalog(
    val mode: CatalogMode = CatalogMode.AUTO,
    val ttlSeconds: Long = 3600,
    val path: String = "/models",
    val models: List<CatalogModel> = emptyList(),
)