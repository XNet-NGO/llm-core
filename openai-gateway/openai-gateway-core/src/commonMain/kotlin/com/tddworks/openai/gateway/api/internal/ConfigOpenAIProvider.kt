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
import com.tddworks.openai.gateway.config.Batch
import com.tddworks.openai.gateway.config.BatchFile
import com.tddworks.openai.gateway.config.BatchRequest
import com.tddworks.openai.gateway.config.CredentialProviders
import com.tddworks.openai.gateway.config.Dialect
import com.tddworks.openai.gateway.config.SignedCredentials
import com.tddworks.openai.gateway.config.SigningContext
import com.tddworks.openai.gateway.config.EmbeddingRequest
import com.tddworks.openai.gateway.config.EmbeddingResponse
import com.tddworks.openai.gateway.config.InteractionRequest
import com.tddworks.openai.gateway.config.InteractionResponse
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
) : OpenAIProvider, EmbeddingsApi, InteractionsApi, BatchApi {

    private val embeddingsApi: EmbeddingsApi =
        ConfigEmbeddingsApi(requester, providerConfig.endpoints.embeddings ?: "/v1beta/openai/embeddings")

    private val batchBase: String =
        providerConfig.endpoints.batches
            ?.removeSuffix("/batches")
            ?: "/v1beta/openai"

    private val batchApi: BatchApi = ConfigBatchApi(requester, batchBase)

    private val interactionsApi: InteractionsApi =
        ConfigInteractionsApi(providerConfig, com.tddworks.di.createJson(), providerConfig.endpoints.interactions ?: "/v1beta/interactions")

    override suspend fun embeddings(request: EmbeddingRequest): EmbeddingResponse =
        embeddingsApi.embeddings(request)

    override suspend fun interact(request: InteractionRequest): InteractionResponse =
        interactionsApi.interact(request)

    override suspend fun retrieveInteraction(id: String): InteractionResponse =
        interactionsApi.retrieveInteraction(id)

    override suspend fun uploadBatchFile(filename: String, content: ByteArray): BatchFile =
        batchApi.uploadBatchFile(filename, content)

    override suspend fun createBatch(request: BatchRequest): Batch =
        batchApi.createBatch(request)

    override suspend fun retrieveBatch(id: String): Batch =
        batchApi.retrieveBatch(id)

    /** The declarative provider configuration this provider was built from. */
    fun providerConfig(): ProviderConfig = providerConfig

    /** Resolve an incoming model slug through the provider's alias map (pass-through if absent). */
    private fun aliasFor(model: String): String = providerConfig.aliases[model] ?: model

    private fun ChatCompletionRequest.remapped(): ChatCompletionRequest {
        val target = aliasFor(model.value)
        return if (target == model.value) this
        else copy(model = com.tddworks.openai.api.chat.api.OpenAIModel(target))
    }

    private fun CompletionRequest.remapped(): CompletionRequest {
        val target = aliasFor(model.value)
        return if (target == model.value) this
        else copy(model = com.tddworks.openai.api.chat.api.OpenAIModel(target))
    }

    private fun ImageCreate.remapped(): ImageCreate {
        val target = aliasFor(model.value)
        return if (target == model.value) this
        else copy(model = com.tddworks.openai.api.chat.api.OpenAIModel(target))
    }

    private fun applyAuthAndHeaders(
        builder: HttpRequestBuilder,
        signed: SignedCredentials = SignedCredentials(),
    ) {
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
                // Credentials are produced by a host-registered RequestSigner (resolved in
                // signedFor()) and passed in via [signed]; nothing static to attach here.
            }
        }
        // Host-signed contributions (SIGV4/OAUTH2) take effect first.
        signed.headers.forEach { (k, v) -> builder.header(k, v) }
        signed.queryParams.forEach { (k, v) -> builder.parameter(k, v) }
        auth.extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        auth.queryParams.forEach { (k, v) -> builder.parameter(k, v) }
    }

    /**
     * Resolve host-provided credentials for schemes that need signing/token exchange
     * (`SIGV4`, `OAUTH2`). Returns empty credentials for all other schemes, or when no signer
     * is registered — the request then proceeds unsigned rather than failing hard, matching
     * the documented graceful-degradation contract in [CredentialProviders].
     */
    private suspend fun signedFor(path: String, body: ByteArray = ByteArray(0)): SignedCredentials {
        val scheme = providerConfig.auth.scheme
        if (scheme != AuthScheme.SIGV4 && scheme != AuthScheme.OAUTH2) return SignedCredentials()
        val signer = CredentialProviders.resolve(scheme) ?: return SignedCredentials()
        val host = providerConfig.baseUrl.substringAfter("://").substringBefore("/")
        return signer.sign(providerConfig.auth, SigningContext(method = "POST", host = host, path = path))
    }

    override suspend fun chatCompletions(request: ChatCompletionRequest): ChatCompletion {
        val path = providerConfig.endpoints.chat ?: "/v1/chat/completions"
        val signed = signedFor(path)
        return requester.performRequest {
            method = HttpMethod.Post
            url(path = path)
            setBody(request.remapped())
            contentType(ContentType.Application.Json)
            applyAuthAndHeaders(this, signed)
        }
    }

    override fun streamChatCompletions(request: ChatCompletionRequest): Flow<ChatCompletionChunk> =
        requester
            .streamRequest<ChatCompletionChunk> {
                method = HttpMethod.Post
                url(path = providerConfig.endpoints.chat ?: "/v1/chat/completions")
                setBody(request.remapped().copy(stream = true))
                contentType(ContentType.Application.Json)
                accept(ContentType.Text.EventStream)
                applyAuthAndHeaders(this)
            }
            .catch { e -> emit(ChatCompletionChunk.error(e)) }

    override suspend fun completions(request: CompletionRequest): Completion {
        val path = providerConfig.endpoints.completions ?: "/v1/completions"
        val signed = signedFor(path)
        return requester.performRequest {
            method = HttpMethod.Post
            url(path = path)
            setBody(request.remapped())
            contentType(ContentType.Application.Json)
            applyAuthAndHeaders(this, signed)
        }
    }

    override suspend fun generate(request: ImageCreate): ListResponse<Image> {
        val path = providerConfig.endpoints.imagesGenerations ?: "/v1/images/generations"
        val signed = signedFor(path)
        return requester.performRequest {
            method = HttpMethod.Post
            url(path = path)
            setBody(request.remapped())
            contentType(ContentType.Application.Json)
            applyAuthAndHeaders(this, signed)
        }
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
 * Build a config-driven Anthropic (D2) provider. Reuses [AnthropicOpenAIProvider]'s
 * request/response adapters and content-block SSE streaming; endpoint, key, and
 * `anthropic-version` come from the [ProviderConfig]. The version is taken from
 * `auth.extraHeaders["anthropic-version"]` when present, else the client default.
 */
internal fun anthropicFrom(config: ProviderConfig): OpenAIProvider {
    val version =
        config.auth.extraHeaders.entries
            .firstOrNull { it.key.equals("anthropic-version", ignoreCase = true) }
            ?.value
            ?: com.tddworks.anthropic.api.Anthropic.ANTHROPIC_VERSION
    val client =
        com.tddworks.anthropic.api.Anthropic.create(
            apiKey = config.auth.apiKey,
            baseUrl = config.baseUrl,
            anthropicVersion = version,
        )
    return AnthropicOpenAIProvider(
        id = config.id,
        name = config.name.ifBlank { config.id },
        config =
            AnthropicOpenAIProviderConfig(
                anthropicVersion = { version },
                apiKey = { config.auth.apiKey },
                baseUrl = { config.baseUrl },
            ),
        client = client,
    )
}

/**
 * Build a config-driven Gemini (D3) native provider. Reuses [GeminiOpenAIProvider]'s
 * contents/parts adapters and `streamGenerateContent` SSE handling; key and base URL
 * come from the [ProviderConfig].
 */
internal fun geminiFrom(config: ProviderConfig): OpenAIProvider {
    val client =
        com.tddworks.gemini.api.textGeneration.api.Gemini.instance(
            com.tddworks.gemini.api.textGeneration.api.GeminiConfig(
                apiKey = { config.auth.apiKey },
                baseUrl = { config.baseUrl },
            ),
        )
    return GeminiOpenAIProvider(
        id = config.id,
        name = config.name.ifBlank { config.id },
        config =
            GeminiOpenAIProviderConfig(
                apiKey = { config.auth.apiKey },
                baseUrl = { config.baseUrl },
            ),
        client = client,
    )
}

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
    Dialect.ANTHROPIC -> anthropicFrom(config)
    Dialect.GEMINI -> geminiFrom(config)
    Dialect.RESPONSES -> ResponsesOpenAIProvider.from(config)
    Dialect.TEMPLATE -> mediaProvider(config)
    // Bedrock is served via its OpenAI-compatible runtime endpoint (spec §D4/§8: "prefer D1
    // when advertised"). Auth is AWS SigV4 — the built-in [AwsSigV4Signer] is auto-registered
    // if the host has not installed its own. The native Converse +
    // application/vnd.amazon.eventstream path is not yet built (needs the binary frame decoder,
    // §3.3) — configure the D1 runtime endpoint to use Bedrock today.
    Dialect.BEDROCK -> {
        if (com.tddworks.openai.gateway.config.CredentialProviders.resolve(AuthScheme.SIGV4) == null) {
            com.tddworks.openai.gateway.config.CredentialProviders.register(
                AuthScheme.SIGV4,
                com.tddworks.openai.gateway.config.AwsSigV4Signer,
            )
        }
        // Native Converse (application/vnd.amazon.eventstream) when the config opts in via
        // streaming=EVENTSTREAM; otherwise the OpenAI-compatible Bedrock runtime (D1 surface).
        if (config.streaming == com.tddworks.openai.gateway.config.StreamFormat.EVENTSTREAM) {
            BedrockConverseProvider(
                id = config.id,
                name = config.name.ifBlank { config.id },
                config = legacyConfig(config),
                providerConfig = config,
                requester = configHttpRequester(config),
            )
        } else {
            ConfigOpenAIProvider(
                id = config.id,
                name = config.name.ifBlank { config.id },
                config = legacyConfig(config),
                providerConfig = config,
                requester = configHttpRequester(config),
            )
        }
    }
    // Voice is duplex/audio, not the chat-oriented OpenAIProvider surface — build it with
    // OpenAIProvider.voiceSession(config) instead (see VoiceProvider.kt).
    Dialect.VOICE_REALTIME ->
        throw IllegalArgumentException(
            "VOICE_REALTIME is a live-audio dialect; build it with OpenAIProvider.voiceSession(config), " +
                "not OpenAIProvider.from(config)",
        )
    else ->
        throw IllegalArgumentException(
            "Dialect ${config.dialect} is not available in this build " +
                "(openai-compat, azure-openai, anthropic, gemini, responses, template, bedrock)",
        )
}