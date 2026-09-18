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
 * @param region AWS region for [AuthScheme.SIGV4] (e.g. `us-east-1`)
 * @param service AWS service name for [AuthScheme.SIGV4] (e.g. `bedrock`)
 * @param secretKey AWS secret access key for [AuthScheme.SIGV4] (`apiKey` holds the access key id)
 * @param sessionToken optional AWS session token for [AuthScheme.SIGV4] temporary credentials
 * @param tokenUrl OAuth2 token endpoint for [AuthScheme.OAUTH2]
 * @param clientId OAuth2 client id for [AuthScheme.OAUTH2]
 * @param clientSecret OAuth2 client secret for [AuthScheme.OAUTH2]
 * @param scopes OAuth2 scopes for [AuthScheme.OAUTH2]
 */
@Serializable
data class ProviderAuth(
    val scheme: AuthScheme = AuthScheme.BEARER,
    val apiKey: String = "",
    val keyHeader: String = "X-API-Key",
    val queryParam: String = "api_key",
    val extraHeaders: Map<String, String> = emptyMap(),
    val queryParams: Map<String, String> = emptyMap(),
    // SIGV4
    val region: String = "",
    val service: String = "",
    val secretKey: String = "",
    val sessionToken: String = "",
    // OAUTH2
    val tokenUrl: String = "",
    val clientId: String = "",
    val clientSecret: String = "",
    val scopes: List<String> = emptyList(),
)