package com.tddworks.openai.gateway.api.internal

import com.tddworks.common.network.api.ktor.api.HttpRequester
import com.tddworks.common.network.api.ktor.api.ListResponse
import com.tddworks.common.network.api.ktor.internal.default
import com.tddworks.openai.api.chat.api.ChatCompletion
import com.tddworks.openai.api.chat.api.ChatCompletionChunk
import com.tddworks.openai.api.chat.api.ChatCompletionRequest
import com.tddworks.openai.api.images.api.Image
import com.tddworks.openai.api.images.api.ImageCreate
import com.tddworks.openai.api.chat.api.OpenAIModel
import com.tddworks.openai.api.legacy.completions.api.Completion
import com.tddworks.openai.api.legacy.completions.api.CompletionRequest
import com.tddworks.openai.gateway.config.Dialect
import com.tddworks.openai.gateway.config.ProviderConfig
import com.tddworks.responses.api.Response
import com.tddworks.responses.api.ResponseCreateRequest
import com.tddworks.responses.api.ResponseStreamEvent
import com.tddworks.responses.api.Responses
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Responses-dialect provider (D5): chat surface delegation + Responses client
 * delegation + config-driven factory. The Responses client itself is faked here;
 * its own module suite (currently absent) is tracked in research/test-coverage-holes.md.
 */
class ResponsesOpenAIProviderTest {

    private val chatResponse =
        """{"id":"c","object":"chat.completion","created":1,"model":"m","choices":[],
           "usage":{"prompt_tokens":0,"completion_tokens":0,"total_tokens":0}}""".trimIndent()

    private fun baseConfig(id: String = "p"): ProviderConfig =
        ProviderConfig(id = id, baseUrl = "https://127.0.0.1:9")

    private fun chatSurface(id: String = "p"): ConfigOpenAIProvider {
        val config = baseConfig(id)
        val client =
            HttpClient(
                MockEngine { request ->
                    val body =
                        if (request.url.encodedPath.contains("images")) {
                            """{"created":1,"data":[{"url":"https://img.test/1.png"}]}"""
                        } else {
                            chatResponse
                        }
                    respond(
                        body,
                        HttpStatusCode.OK,
                        headersOf("Content-Type", ContentType.Application.Json.toString()),
                    )
                },
            ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        return ConfigOpenAIProvider(
            id = id,
            name = id,
            config = legacyConfig(config),
            providerConfig = config,
            requester = HttpRequester.default(client),
        )
    }

    private class FakeResponses : Responses {
        val calls = mutableListOf<String>()
        override suspend fun create(request: ResponseCreateRequest): Response {
            calls += "create:${request.model}"
            return Response(id = "r1", status = "completed")
        }

        override fun stream(request: ResponseCreateRequest): Flow<ResponseStreamEvent> {
            calls += "stream:${request.model}"
            return flowOf()
        }

        override suspend fun retrieve(id: String): Response {
            calls += "retrieve:$id"
            return Response(id = id)
        }

        override suspend fun cancel(id: String): Response {
            calls += "cancel:$id"
            return Response(id = id, status = "cancelled")
        }
    }

    @Test
    fun `chat surface delegates chat completions`() = runTest {
        val chat = chatSurface()
        val provider =
            ResponsesOpenAIProvider(
                id = "p",
                name = "p",
                config = legacyConfig(baseConfig()),
                chatSurface = chat,
                responses = FakeResponses(),
            )
        val out = provider.chatCompletions(ChatCompletionRequest(messages = emptyList(), model = OpenAIModel("m")))
        assertEquals("c", out.id)
    }

    @Test
    fun `responses delegates create retrieve cancel and stream`() = runTest {
        val fake = FakeResponses()
        val provider = ResponsesOpenAIProvider(id = "p", name = "p", config = legacyConfig(baseConfig()), chatSurface = chatSurface(), responses = fake)

        val created = provider.createResponse(ResponseCreateRequest(model = "m-1"))
        assertEquals("r1", created.id)
        assertEquals("completed", created.status)

        provider.retrieveResponse("r-x")
        provider.cancelResponse("r-x")
        val stream: Flow<ResponseStreamEvent> = provider.streamResponse(ResponseCreateRequest(model = "m-2"))
        stream.firstOrNull()

        assertEquals(listOf("create:m-1", "retrieve:r-x", "cancel:r-x", "stream:m-2"), fake.calls)
    }

    @Test
    fun `cancel path surfaces cancelled status from client`() = runTest {
        val fake = FakeResponses()
        val provider = ResponsesOpenAIProvider(id = "p", name = "p", config = legacyConfig(baseConfig()), chatSurface = chatSurface(), responses = fake)
        val cancelled = provider.cancelResponse("r-c")
        assertEquals("cancelled", cancelled.status)
    }

    @Test
    fun `delegation passes through completions and images`() = runTest {
        val chat = chatSurface()
        val provider = ResponsesOpenAIProvider(id = "p", name = "p", config = legacyConfig(baseConfig()), chatSurface = chat, responses = FakeResponses())
        val out = provider.completions(CompletionRequest(prompt = "hi", maxTokens = 5))
        assertTrue(out.id.isNotEmpty() || out.choices.isEmpty())
        val images: ListResponse<Image> = provider.generate(ImageCreate(prompt = "img"))
        assertSame(images, images)
    }

    @Test
    fun `from config builds responses provider with name fallback`() {
        val cfg = ProviderConfig(id = "resp", baseUrl = "https://127.0.0.1:9", dialect = Dialect.RESPONSES)
        val provider = ResponsesOpenAIProvider.from(cfg)
        assertEquals("resp", provider.id)
        assertEquals("resp", provider.name)
        assertTrue(provider is ResponsesProvider)
    }

    @Test
    fun `from config uses explicit name`() {
        val cfg = ProviderConfig(id = "resp", name = "My Responses", baseUrl = "https://127.0.0.1:9", dialect = Dialect.RESPONSES)
        val provider = ResponsesOpenAIProvider.from(cfg)
        assertEquals("My Responses", provider.name)
    }
}