package com.tddworks.openai.gateway.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A provider described entirely as data. Adding a provider never requires code:
 * the host (gateway admin portal, config file, DB row) writes one of these and the
 * router interprets it live.
 *
 * `id` is the provider identity: the router resolves a provider by matching the caller's
 * requested provider against this `id` (or `name`). This core does not parse a
 * `provider/model-slug` namespace out of the model string — slug-namespace routing, if
 * needed, is the consuming gateway project's responsibility.
 */
@Serializable
data class ProviderConfig(
    val id: String,
    val name: String = id,
    val enabled: Boolean = true,
    val dialect: Dialect = Dialect.OPENAI_COMPAT,
    val baseUrl: String,
    val auth: ProviderAuth = ProviderAuth(),
    val endpoints: Endpoints = Endpoints(),
    val capabilities: Capabilities = Capabilities(),
    val catalog: Catalog = Catalog(),
    val streaming: StreamFormat = StreamFormat.SSE,
    val timeoutMs: Long = 120_000,

    /** Template-dialect image request body style: `multipart` (flux-2-klein) or `json` (SDXL). */
    val imageInput: String = "multipart",

    /** Template-dialect image response style: `json` (base64 in result.image) or `raw` (binary body). */
    val imageOutput: String = "json",

    /** Whether the model id is appended to the image endpoint path (ai/run/{model} style). */
    val imageModelInPath: Boolean = true,
    /**
     * Gateway-level model remap: incoming model slug → upstream model id. Applied by the
     * provider before dispatching the request. Empty map (default) is a pass-through no-op.
     */
    val aliases: Map<String, String> = emptyMap(),
    /**
     * Per-op template transformations (D7). Keyed by operation name (e.g. `audioSpeech`
     * for TTS on the template engine). See [TemplateTransform].
     */
    val transforms: Map<String, TemplateTransform> = emptyMap(),
) {
    companion object {
        fun fromJson(json: String): ProviderConfig = JsonLenientConfig.decodeFromString(json)

        fun toJson(config: ProviderConfig): String = JsonLenientConfig.encodeToString(config)
    }
}

private val JsonLenientConfig =
    Json {
        isLenient = true
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }