package com.tddworks.openai.gateway.config

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * A provider described entirely as data. Adding a provider never requires code:
 * the host (gateway admin portal, config file, DB row) writes one of these and the
 * router interprets it live.
 *
 * `id` is the routing namespace: requests use `id/model-slug`.
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
    val aliases: List<String> = emptyList(),
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