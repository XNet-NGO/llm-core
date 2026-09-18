package com.tddworks.openai.gateway.api.internal

import com.tddworks.common.network.api.ktor.api.HttpRequester
import com.tddworks.common.network.api.ktor.api.ListResponse
import com.tddworks.common.network.api.ktor.api.performRequest
import com.tddworks.common.network.api.ktor.api.streamRequest
import com.tddworks.common.network.api.ktor.internal.AuthConfig
import com.tddworks.common.network.api.ktor.internal.ClientFeatures
import com.tddworks.common.network.api.ktor.internal.UrlBasedConnectionConfig
import com.tddworks.common.network.api.ktor.internal.createHttpClient
import com.tddworks.common.network.api.ktor.internal.default
import com.tddworks.di.createJson
import com.tddworks.openai.api.chat.api.ChatCompletion
import com.tddworks.openai.api.chat.api.ChatCompletionChunk
import com.tddworks.openai.api.chat.api.ChatCompletionRequest
import com.tddworks.openai.api.images.api.Image
import com.tddworks.openai.api.images.api.ImageCreate
import com.tddworks.openai.api.legacy.completions.api.Completion
import com.tddworks.openai.api.legacy.completions.api.CompletionRequest
import com.tddworks.openai.gateway.api.OpenAIProvider
import com.tddworks.openai.gateway.api.OpenAIProviderConfig
import com.tddworks.openai.gateway.config.AuthScheme
import com.tddworks.openai.gateway.config.Dialect
import com.tddworks.openai.gateway.config.ProviderConfig
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.accept
import io.ktor.http.contentType
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.path
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch

/**
 * An [OpenAIProvider] driven entirely by a [ProviderConfig] — no provider-specific
 * code, no DI wiring. Endpoint overrides and per-provider auth come from the config;
 * any OpenAI-compatible endpoint (freeinference, llama-server, ollama, vLLM, ...)
 * can be added as a config row.
 */
class ConfigOpenAIProvider(
    override val id: String,
    override val name: String,
    override val config: OpenAIProviderConfig,
    private val providerConfig: ProviderConfig,
    private val requester: HttpRequester,
) : OpenAIProvider {

    /** The declarative provider configuration this provider was built from. */
    fun providerConfig(): ProviderConfig = providerConfig

    private fun applyAuthAndHeaders(builder: HttpRequestBuilder) {
        val auth = providerConfig.auth
        when (auth.scheme) {
            AuthScheme.BEARER -> {
                if (auth.apiKey.isNotEmpty()) {
                    builder.header("Authorization", "Bearer ${auth.apiKey}")
                }
            }
            AuthScheme.X_API_KEY -> {
                if (auth.apiKey.isNotEmpty()) {
                    builder.header(auth.keyHeader.ifBlank { "X-API-Key" }, auth.apiKey)
                }
            }
            AuthScheme.QUERY -> {
                if (auth.apiKey.isNotEmpty()) {
                    builder.parameter(auth.queryParam.ifBlank { "api_key" }, auth.apiKey)
                }
            }
            AuthScheme.NONE -> {}
            AuthScheme.SIGV4, AuthScheme.OAUTH2 -> {
                // Host-provided signer/oauth attaches credentials at transport level.
            }
        }
        auth.extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        auth.queryParams.forEach { (k, v) -> builder.parameter(k, v) }
    }

    override suspend fun chatCompletions(request: ChatCompletionRequest): ChatCompletion =
        requester.performRequest {
            method = HttpMethod.Post
            url(path = providerConfig.endpoints.chat ?: "/v1/chat/completions")
            setBody(request)
            contentType(ContentType.Application.Json)
            applyAuthAndHeaders(this)
        }

    override fun streamChatCompletions(request: ChatCompletionRequest): Flow<ChatCompletionChunk> =
        requester
            .streamRequest<ChatCompletionChunk> {
                method = HttpMethod.Post
                url(path = providerConfig.endpoints.chat ?: "/v1/chat/completions")
                setBody(request.copy(stream = true))
                contentType(ContentType.Application.Json)
                accept(ContentType.Text.EventStream)
                applyAuthAndHeaders(this)
            }
            .catch { e -> emit(ChatCompletionChunk.error(e)) }

    override suspend fun completions(request: CompletionRequest): Completion =
        requester.performRequest {
            method = HttpMethod.Post
            url(path = providerConfig.endpoints.completions ?: "/v1/completions")
            setBody(request)
            contentType(ContentType.Application.Json)
            applyAuthAndHeaders(this)
        }

    override suspend fun generate(request: ImageCreate): ListResponse<Image> =
        requester.performRequest {
            method = HttpMethod.Post
            url(path = providerConfig.endpoints.imagesGenerations ?: "/v1/images/generations")
            setBody(request)
            contentType(ContentType.Application.Json)
            applyAuthAndHeaders(this)
        }
}

internal fun configHttpRequester(config: ProviderConfig): HttpRequester =
    HttpRequester.default(
        createHttpClient(
            connectionConfig = UrlBasedConnectionConfig({ config.baseUrl }),
            authConfig =
                AuthConfig(
                    authToken =
                        if (config.auth.scheme == AuthScheme.BEARER && config.auth.apiKey.isNotEmpty()) {
                            { config.auth.apiKey }
                        } else {
                            null
                        },
                ),
            features = ClientFeatures(json = createJson()),
        ),
    )

internal fun legacyConfig(config: ProviderConfig) =
    DefaultOpenAIProviderConfig(apiKey = { config.auth.apiKey }, baseUrl = { config.baseUrl })

/**
 * Build a provider purely from configuration. Unsupported dialects throw
 * [IllegalArgumentException] so configuration errors surface at load time.
 */
fun OpenAIProvider.Companion.from(config: ProviderConfig): OpenAIProvider = when (config.dialect) {
    Dialect.OPENAI_COMPAT, Dialect.AZURE_OPENAI ->
        ConfigOpenAIProvider(
            id = config.id,
            name = config.name.ifBlank { config.id },
            config = legacyConfig(config),
            providerConfig = config,
            requester = configHttpRequester(config),
        )
    Dialect.RESPONSES -> ResponsesOpenAIProvider.from(config)
    Dialect.TEMPLATE -> mediaProvider(config)
    else ->
        throw IllegalArgumentException(
            "Dialect ${config.dialect} is not available in this build (openai-compat, azure-openai, responses)",
        )
}