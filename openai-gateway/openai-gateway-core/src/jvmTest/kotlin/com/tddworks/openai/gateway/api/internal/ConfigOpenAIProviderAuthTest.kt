package com.tddworks.openai.gateway.api.internal

import com.tddworks.common.network.api.ktor.api.HttpRequester
import com.tddworks.common.network.api.ktor.internal.default
import com.tddworks.openai.api.chat.api.ChatCompletionRequest
import com.tddworks.openai.api.chat.api.OpenAIModel
import com.tddworks.openai.gateway.config.AuthScheme
import com.tddworks.openai.gateway.config.CredentialProviders
import com.tddworks.openai.gateway.config.ProviderAuth
import com.tddworks.openai.gateway.config.ProviderConfig
import com.tddworks.openai.gateway.config.RequestSigner
import com.tddworks.openai.gateway.config.SignedCredentials
import com.tddworks.openai.gateway.config.SigningContext
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalSerializationApi::class)
class ConfigOpenAIProviderAuthTest {

    private val chatResponse =
        """{"id":"c","object":"chat.completion","created":1,"model":"m","choices":[],
           "usage":{"prompt_tokens":0,"completion_tokens":0,"total_tokens":0}}"""
            .trimIndent()

    @AfterEach
    fun tearDown() = CredentialProviders.clear()

    private class Captured {
        var authHeader: String? = null
        var customHeader: String? = null
        var queryString: String = ""
    }

    private fun provider(auth: ProviderAuth, captured: Captured): ConfigOpenAIProvider {
        val config =
            ProviderConfig(id = "p", baseUrl = "https://upstream.test", auth = auth)
        val engine =
            MockEngine { request ->
                captured.authHeader = request.headers["Authorization"]
                captured.customHeader = request.headers["X-Amz-Date"]
                captured.queryString = request.url.encodedQuery
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

    private suspend fun ConfigOpenAIProvider.callChat() =
        chatCompletions(ChatCompletionRequest(messages = emptyList(), model = OpenAIModel("m")))

    @Test
    fun `registered SIGV4 signer attaches headers and query params`() = runTest {
        var seen: SigningContext? = null
        CredentialProviders.register(AuthScheme.SIGV4) { auth, ctx ->
            seen = ctx
            SignedCredentials(
                headers =
                    mapOf(
                        "Authorization" to "AWS4-HMAC-SHA256 Credential=${auth.apiKey}/...",
                        "X-Amz-Date" to "20260101T000000Z",
                    ),
                queryParams = mapOf("X-Amz-Signed" to "1"),
            )
        }
        val captured = Captured()
        provider(
                ProviderAuth(
                    scheme = AuthScheme.SIGV4,
                    apiKey = "AKIA",
                    secretKey = "secret",
                    region = "us-east-1",
                    service = "bedrock",
                ),
                captured,
            )
            .callChat()

        assertTrue(captured.authHeader!!.startsWith("AWS4-HMAC-SHA256"))
        assertEquals("20260101T000000Z", captured.customHeader)
        assertTrue(captured.queryString.contains("X-Amz-Signed=1"))
        // Signer received the right context.
        assertEquals("POST", seen!!.method)
        assertEquals("upstream.test", seen!!.host)
        assertEquals("/v1/chat/completions", seen!!.path)
    }

    @Test
    fun `registered OAUTH2 signer attaches bearer token`() = runTest {
        CredentialProviders.register(AuthScheme.OAUTH2) { _, _ ->
            SignedCredentials(headers = mapOf("Authorization" to "Bearer ya29.token"))
        }
        val captured = Captured()
        provider(
                ProviderAuth(
                    scheme = AuthScheme.OAUTH2,
                    tokenUrl = "https://oauth2.test/token",
                    clientId = "cid",
                    clientSecret = "csecret",
                ),
                captured,
            )
            .callChat()

        assertEquals("Bearer ya29.token", captured.authHeader)
    }

    @Test
    fun `SIGV4 with no registered signer degrades to unsigned request`() = runTest {
        val captured = Captured()
        provider(ProviderAuth(scheme = AuthScheme.SIGV4, apiKey = "AKIA"), captured).callChat()
        // No signer registered → nothing attached, request still went through.
        assertNull(captured.authHeader)
        assertNull(captured.customHeader)
    }
}
