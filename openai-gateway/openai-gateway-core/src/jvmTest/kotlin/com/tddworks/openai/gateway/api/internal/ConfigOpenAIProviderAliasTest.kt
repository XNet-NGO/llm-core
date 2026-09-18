package com.tddworks.openai.gateway.api.internal

import com.tddworks.common.network.api.ktor.api.HttpRequester
import com.tddworks.common.network.api.ktor.internal.default
import com.tddworks.openai.api.chat.api.ChatCompletionRequest
import com.tddworks.openai.api.chat.api.OpenAIModel
import com.tddworks.openai.gateway.config.ProviderAuth
import com.tddworks.openai.gateway.config.ProviderConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import io.ktor.utils.io.readRemaining
import kotlinx.coroutines.test.runTest
import kotlinx.io.readByteArray
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

@OptIn(ExperimentalSerializationApi::class)
class ConfigOpenAIProviderAliasTest {

    private val chatResponse =
        """
        {"id":"c","object":"chat.completion","created":1,"model":"m",
         "choices":[],"usage":{"prompt_tokens":0,"completion_tokens":0,"total_tokens":0}}
        """
            .trimIndent()

    /**
     * Builds a provider whose requester captures the outgoing request body so the test can
     * assert what `model` value was actually sent upstream.
     */
    private fun providerCapturing(
        aliases: Map<String, String>,
        capture: (String) -> Unit,
    ): ConfigOpenAIProvider {
        val config =
            ProviderConfig(
                id = "p",
                baseUrl = "https://upstream.test",
                auth = ProviderAuth(apiKey = "k"),
                aliases = aliases,
            )
        val engine =
            MockEngine { request ->
                val bodyBytes = request.body.toByteArrayForTest()
                capture(bodyBytes.decodeToString())
                respond(
                    content = chatResponse,
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            }
        val client =
            HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
        return ConfigOpenAIProvider(
            id = config.id,
            name = config.id,
            config = legacyConfig(config),
            providerConfig = config,
            requester = HttpRequester.default(client),
        )
    }

    private fun modelInBody(body: String): String =
        Json.parseToJsonElement(body).jsonObject["model"]!!.jsonPrimitive.content

    @Test
    fun `remaps model slug to upstream id when alias present`() = runTest {
        var sent = ""
        val provider =
            providerCapturing(aliases = mapOf("fast" to "gpt-4o-mini")) { sent = it }

        provider.chatCompletions(
            ChatCompletionRequest(messages = emptyList(), model = OpenAIModel("fast")),
        )

        assertEquals("gpt-4o-mini", modelInBody(sent))
    }

    @Test
    fun `passes model through unchanged when no alias matches`() = runTest {
        var sent = ""
        val provider =
            providerCapturing(aliases = mapOf("fast" to "gpt-4o-mini")) { sent = it }

        provider.chatCompletions(
            ChatCompletionRequest(messages = emptyList(), model = OpenAIModel("gpt-4o")),
        )

        assertEquals("gpt-4o", modelInBody(sent))
    }

    @Test
    fun `empty alias map is a no-op`() = runTest {
        var sent = ""
        val provider = providerCapturing(aliases = emptyMap()) { sent = it }

        provider.chatCompletions(
            ChatCompletionRequest(messages = emptyList(), model = OpenAIModel("llama3")),
        )

        assertEquals("llama3", modelInBody(sent))
    }
}

private suspend fun io.ktor.http.content.OutgoingContent.toByteArrayForTest(): ByteArray =
    when (this) {
        is io.ktor.http.content.OutgoingContent.ByteArrayContent -> bytes()
        is io.ktor.http.content.OutgoingContent.ReadChannelContent ->
            readFrom().readRemaining().readByteArray()
        else -> ByteArray(0)
    }
