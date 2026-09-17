package com.tddworks.openai.gateway.config

import kotlinx.serialization.Serializable

@Serializable
enum class AuthScheme {
    BEARER,
    X_API_KEY,
    QUERY,
    SIGV4,
    OAUTH2,
    NONE,
}

/**
 * Authentication description for a provider. Orthogonal to the wire dialect.
 *
 * @param scheme auth mechanism
 * @param apiKey secret/token (prefer referencing a secret store or env at the host layer)
 * @param keyHeader header name for [AuthScheme.X_API_KEY] (e.g. `x-api-key`, `x-goog-api-key`)
 * @param queryParam query-parameter name for [AuthScheme.QUERY] (e.g. `api_key`, `key`)
 * @param extraHeaders additional static headers, e.g. `anthropic-version`
 * @param queryParams additional static query parameters, e.g. Azure `api-version`
 */
@Serializable
data class ProviderAuth(
    val scheme: AuthScheme = AuthScheme.BEARER,
    val apiKey: String = "",
    val keyHeader: String = "X-API-Key",
    val queryParam: String = "api_key",
    val extraHeaders: Map<String, String> = emptyMap(),
    val queryParams: Map<String, String> = emptyMap(),
)