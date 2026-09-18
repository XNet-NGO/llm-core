package com.tddworks.openai.gateway.api.internal

import com.tddworks.common.network.api.ktor.api.HttpRequester
import com.tddworks.common.network.api.ktor.internal.default
import com.tddworks.openai.api.chat.api.ChatCompletionRequest
import com.tddworks.openai.api.chat.api.OpenAIModel
import com.tddworks.openai.api.images.api.ImageCreate
import com.tddworks.openai.api.legacy.completions.api.CompletionRequest
import com.tddworks.openai.gateway.api.OpenAIProvider
import com.tddworks.openai.gateway.config.AuthScheme
import com.tddworks.openai.gateway.config.AwsSigV4Signer
import com.tddworks.openai.gateway.config.CredentialProviders
import com.tddworks.openai.gateway.config.Dialect
import com.tddworks.openai.gateway.config.EmbeddingRequest
import com.tddworks.openai.gateway.config.ProviderAuth
import com.tddworks.openai.gateway.config.ProviderConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Remaining ConfigOpenAIProvider branches: auth schemes at provider level (query,
 * x-api-key, extra headers, blank key), completions/image alias remap, provider-level
 * embeddings/batch delegation, stream error emission, Bedrock auto-signer, VOICE_REALTIME
 * redirect message. MockEngine via HttpRequester.default per the house pattern.
 */
class ConfigOpenAIProviderRemainderTest {

    private val chatResponse =
        """{"id":"c","object":"chat.completion","created":1,"model":"m","choices":[],
           "usage":{"prompt_tokens":0,"completion_tokens":0,"total_tokens":0}}""".trimIndent()

    private val embeddingJson =
        """{"object":"list","data":[{"object":"embedding","index":0,"embedding":[0.1]}],
           "usage":{"prompt_tokens":1,"total_tokens":1}}""".trimIndent()

    private val imageJson = """{"created":1,"data":[{"url":"https://img.test/1.png"}]}"""

    @AfterEach
    fun tearDown() = CredentialProviders.clear()

    private fun provider(
        auth: ProviderAuth = ProviderAuth(apiKey = "k"),
        aliases: Map<String, String> = emptyMap(),
        baseUrl: String = "https://up.test",
        handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ): ConfigOpenAIProvider {
        val config = ProviderConfig(id = "p", baseUrl = baseUrl, auth = auth, aliases = aliases)
        val client =
            HttpClient(
                MockEngine(handler),
            ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        return ConfigOpenAIProvider(
            id = config.id,
            name = config.id,
            config = legacyConfig(config),
            providerConfig = config,
            requester = HttpRequester.default(client),
        )
    }

    // ---- auth schemes at provider level ----

    @Test
    fun `query scheme appends api key as query param`() = runTest {
        var query: String? = null
        val p =
            provider(
                auth = ProviderAuth(scheme = AuthScheme.QUERY, apiKey = "qk"),
            ) { request ->
                query = request.url.encodedQuery
                respond(chatResponse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }
        p.chatCompletions(ChatCompletionRequest(messages = emptyList(), model = OpenAIModel("m")))
        assertTrue(query!!.contains("api_key=qk"))
    }

    @Test
    fun `x-api-key scheme uses custom key header`() = runTest {
        var keyHeader: String? = null
        val p =
            provider(auth = ProviderAuth(scheme = AuthScheme.X_API_KEY, apiKey = "xk", keyHeader = "X-Custom-Key")) { request ->
                keyHeader = request.headers["X-Custom-Key"]
                respond(chatResponse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }
        p.chatCompletions(ChatCompletionRequest(messages = emptyList(), model = OpenAIModel("m")))
        assertEquals("xk", keyHeader)
    }

    @Test
    fun `blank api key sends no auth header`() = runTest {
        var auth: String? = null
        val p = provider(auth = ProviderAuth(scheme = AuthScheme.BEARER, apiKey = "")) { request ->
            auth = request.headers["Authorization"]
            respond(chatResponse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        p.chatCompletions(ChatCompletionRequest(messages = emptyList(), model = OpenAIModel("m")))
        assertNull(auth)
    }

    @Test
    fun `extra headers and static query params attach per request`() = runTest {
        var extra: String? = null
        var qv: String? = null
        val p =
            provider(
                auth =
                    ProviderAuth(
                        apiKey = "k",
                        extraHeaders = mapOf("X-Extra" to "1"),
                        queryParams = mapOf("api-version" to "v2"),
                    ),
            ) { request ->
                extra = request.headers["X-Extra"]
                qv = request.url.parameters["api-version"]
                respond(chatResponse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }
        p.chatCompletions(ChatCompletionRequest(messages = emptyList(), model = OpenAIModel("m")))
        assertEquals("1", extra)
        assertEquals("v2", qv)
    }

    // ---- alias remap beyond chat ----

    @Test
    fun `completions remaps model via aliases`() = runTest {
        var body: String? = null
        val p = provider(aliases = mapOf("slug" to "upstream-cc")) { request ->
            body = (request.body as io.ktor.http.content.TextContent).text
            respond("""{"id":"x","object":"text_completion","created":1,"model":"m","choices":[]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        p.completions(CompletionRequest(model = OpenAIModel("slug"), prompt = "hi", maxTokens = 3))
        assertTrue(body!!.contains("\"model\":\"upstream-cc\""))
    }

    @Test
    fun `image generation remaps model via aliases`() = runTest {
        var body: String? = null
        val p = provider(aliases = mapOf("img-slug" to "real-img")) { request ->
            body = (request.body as io.ktor.http.content.TextContent).text
            respond(imageJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val out = p.generate(ImageCreate(prompt = "x", model = OpenAIModel("img-slug")))
        assertTrue(body!!.contains("\"model\":\"real-img\""))
        assertEquals("https://img.test/1.png", out.data[0].url)
    }

    @Test
    fun `alias miss passes original model through`() = runTest {
        var body: String? = null
        val p = provider(aliases = mapOf("a" to "b")) { request ->
            body = (request.body as io.ktor.http.content.TextContent).text
            respond("""{"id":"x","object":"text_completion","created":1,"model":"m","choices":[]}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        p.completions(CompletionRequest(model = OpenAIModel("other"), prompt = "hi"))
        assertTrue(body!!.contains("\"model\":\"other\""))
    }

    // ---- provider-level extended API delegation ----

    @Test
    fun `provider embeddings delegate to embeddings api`() = runTest {
        var path: String? = null
        val p = provider { request ->
            path = request.url.encodedPath
            respond(embeddingJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
        }
        val out = p.embeddings(EmbeddingRequest(model = "m", input = listOf("x")))
        assertEquals("/v1beta/openai/embeddings", path)
        assertEquals(1, out.data.size)
    }

    @Test
    fun `provider batch upload and create delegate to batch api`() = runTest {
        val paths = mutableListOf<String>()
        val p = provider { request ->
            paths += request.url.encodedPath
            if (request.url.encodedPath.endsWith("files")) {
                respond("""{"id":"f-1","object":"file"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            } else {
                respond("""{"id":"b-1","object":"batch","status":"validating","input_file_id":"f-1"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
            }
        }
        val file = p.uploadBatchFile("a.jsonl", byteArrayOf(1))
        assertEquals("/v1beta/openai/files", paths.first())
        assertEquals("f-1", file.id)
        val created = p.createBatch(com.tddworks.openai.gateway.config.BatchRequest(inputFileId = "f-1"))
        assertEquals("f-1", created.inputFileId)
    }

    @Test
    fun `provider endpoint overrides win for chat and embeddings`() = runTest {
        var chatPath: String? = null
        val config =
            ProviderConfig(
                id = "p",
                baseUrl = "https://up.test",
                endpoints =
                    com.tddworks.openai.gateway.config.Endpoints(
                        chat = "/custom/chat",
                        embeddings = "/custom/embed",
                    ),
            )
        val paths = mutableListOf<String>()
        val client =
            HttpClient(
                MockEngine { request ->
                    paths += request.url.encodedPath
                    when (request.url.encodedPath) {
                        "/custom/chat" -> respond(chatResponse, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                        else -> respond(embeddingJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                    }
                },
            ) { install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) } }
        val p =
            ConfigOpenAIProvider(
                id = "p",
                name = "p",
                config = legacyConfig(config),
                providerConfig = config,
                requester = HttpRequester.default(client),
            )
        p.chatCompletions(ChatCompletionRequest(messages = emptyList(), model = OpenAIModel("m")))
        p.embeddings(EmbeddingRequest(model = "m", input = listOf("x")))
        assertEquals(listOf("/custom/chat", "/custom/embed"), paths)
    }

    // ---- stream error emission ----

    @Test
    fun `stream errors emit error chunk instead of failing flow`() = runTest {
        val p = provider {
            throw IllegalStateException("boom")
        }
        val chunks = p.streamChatCompletions(ChatCompletionRequest(messages = emptyList(), model = OpenAIModel("m"))).toList()
        assertEquals(1, chunks.size)
        assertEquals("error", chunks[0].`object`)
    }

    // ---- from() dialect routing edge cases ----

    @Test
    fun `from auto-registers bedrock sigv4 signer when none installed`() {
        assertNull(CredentialProviders.resolve(AuthScheme.SIGV4))
        val cfg = ProviderConfig(id = "b", baseUrl = "https://bedrock.test", dialect = Dialect.BEDROCK)
        val provider = OpenAIProvider.from(cfg)
        assertSame(AwsSigV4Signer, CredentialProviders.resolve(AuthScheme.SIGV4))
        assertEquals("b", provider.id)
    }

    @Test
    fun `from keeps host-installed signer for bedrock`() {
        val custom = com.tddworks.openai.gateway.config.RequestSigner { _, _ -> com.tddworks.openai.gateway.config.SignedCredentials() }
        CredentialProviders.register(AuthScheme.SIGV4, custom)
        val cfg = ProviderConfig(id = "b", baseUrl = "https://bedrock.test", dialect = Dialect.BEDROCK)
        OpenAIProvider.from(cfg)
        assertSame(custom, CredentialProviders.resolve(AuthScheme.SIGV4))
    }

    @Test
    fun `from redirects voice_realtime with clear message`() {
        val cfg = ProviderConfig(id = "v", baseUrl = "https://v.test", dialect = Dialect.VOICE_REALTIME)
        val e =
            assertThrows(IllegalArgumentException::class.java) {
                OpenAIProvider.from(cfg)
            }
        assertTrue(e.message!!.contains("voiceSession"))
    }

    @Test
    fun `from builds gemini native provider`() {
        val cfg = ProviderConfig(id = "g", baseUrl = "https://gen.test", dialect = Dialect.GEMINI)
        val provider = OpenAIProvider.from(cfg)
        assertTrue(provider is GeminiOpenAIProvider)
        assertEquals("g", provider.id)
    }
}