package com.tddworks.openai.gateway.api.internal

import com.tddworks.common.network.api.ktor.api.ListResponse
import com.tddworks.openai.api.chat.api.ChatCompletion
import com.tddworks.openai.api.chat.api.ChatCompletionChunk
import com.tddworks.openai.api.chat.api.ChatCompletionRequest
import com.tddworks.openai.api.images.api.Image
import com.tddworks.openai.api.images.api.ImageCreate
import com.tddworks.openai.api.legacy.completions.api.Completion
import com.tddworks.openai.api.legacy.completions.api.CompletionRequest
import com.tddworks.openai.gateway.api.OpenAIProvider
import com.tddworks.openai.gateway.config.ProviderConfig
import com.tddworks.responses.api.Response
import com.tddworks.responses.api.ResponseCreateRequest
import com.tddworks.responses.api.ResponseStreamEvent
import com.tddworks.responses.api.Responses
import com.tddworks.responses.api.ResponsesConfig
import com.tddworks.responses.api.internal.default
import kotlinx.coroutines.flow.Flow

/** A provider that additionally speaks the Responses API (D5). */
interface ResponsesProvider : OpenAIProvider {
    suspend fun createResponse(request: ResponseCreateRequest): Response
    fun streamResponse(request: ResponseCreateRequest): Flow<ResponseStreamEvent>
    suspend fun retrieveResponse(id: String): Response
    suspend fun cancelResponse(id: String): Response
}

/**
 * Responses-dialect provider: chat surface via the config-driven [ConfigOpenAIProvider],
 * Responses semantics via the Responses client.
 */
class ResponsesOpenAIProvider(
    override val id: String,
    override val name: String,
    override val config: com.tddworks.openai.gateway.api.OpenAIProviderConfig,
    private val chatSurface: ConfigOpenAIProvider,
    private val responses: Responses,
) : ResponsesProvider {

    override suspend fun chatCompletions(request: ChatCompletionRequest): ChatCompletion =
        chatSurface.chatCompletions(request)

    override fun streamChatCompletions(request: ChatCompletionRequest): Flow<ChatCompletionChunk> =
        chatSurface.streamChatCompletions(request)

    override suspend fun completions(request: CompletionRequest): Completion =
        chatSurface.completions(request)

    override suspend fun generate(request: ImageCreate): ListResponse<Image> =
        chatSurface.generate(request)

    override suspend fun createResponse(request: ResponseCreateRequest): Response =
        responses.create(request)

    override fun streamResponse(request: ResponseCreateRequest): Flow<ResponseStreamEvent> =
        responses.stream(request)

    override suspend fun retrieveResponse(id: String): Response = responses.retrieve(id)

    override suspend fun cancelResponse(id: String): Response = responses.cancel(id)

    companion object {
        fun from(config: ProviderConfig): ResponsesOpenAIProvider =
            ResponsesOpenAIProvider(
                id = config.id,
                name = config.name.ifBlank { config.id },
                config = legacyConfig(config),
                chatSurface =
                    ConfigOpenAIProvider(
                        id = config.id,
                        name = config.name.ifBlank { config.id },
                        config = legacyConfig(config),
                        providerConfig = config,
                        requester = configHttpRequester(config),
                    ),
                responses =
                    Responses.default(
                        ResponsesConfig(
                            apiKey = { config.auth.apiKey },
                            baseUrl = { config.baseUrl },
                        ),
                    ),
            )
    }
}