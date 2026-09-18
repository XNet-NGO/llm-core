package com.tddworks.openai.gateway.api.internal

import com.tddworks.common.network.api.ktor.api.HttpRequester
import com.tddworks.common.network.api.ktor.internal.default
import com.tddworks.openai.gateway.config.AuthScheme
import com.tddworks.openai.gateway.config.Batch
import com.tddworks.openai.gateway.config.BatchFile
import com.tddworks.openai.gateway.config.BatchRequest
import com.tddworks.openai.gateway.config.EmbeddingRequest
import com.tddworks.openai.gateway.config.EmbeddingResponse
import com.tddworks.openai.gateway.config.InteractionRequest
import com.tddworks.openai.gateway.config.InteractionResponse
import com.tddworks.openai.gateway.config.ProviderAuth
import com.tddworks.openai.gateway.config.ProviderConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ConfigApisTest {

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    private fun requester(
        handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ): HttpRequester {
        val engine = MockEngine(handler)
        val client =
            HttpClient(engine) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            }
        return HttpRequester.default(client)
    }

    private val embeddingJson =
        """{"object":"list","data":[{"object":"embedding","index":0,"embedding":[0.1,0.2]}],
           "usage":{"prompt_tokens":3,"total_tokens":3}}""".trimIndent()

    // ---- EmbeddingsApi ----

    @Test
    fun `embeddings posts to path and decodes response`() = runTest {
        var seenPath: String? = null
        var seenMethod: HttpMethod? = null
        val api =
            ConfigEmbeddingsApi(
                requester { req ->
                    seenPath = req.url.encodedPath
                    seenMethod = req.method
                    respond(embeddingJson, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                },
                path = "/v1/compatible-mode/embeddings",
            )
        val out = api.embeddings(EmbeddingRequest(model = "m", input = listOf("hi")))
        assertEquals("/v1/compatible-mode/embeddings", seenPath)
        assertEquals(HttpMethod.Post, seenMethod)
        assertEquals(1, out.data.size)
        assertEquals(listOf(0.1, 0.2), out.data[0].embedding)
        assertEquals(3, out.usage?.totalTokens)
    }

    // ---- BatchApi ----

    @Test
    fun `uploadBatchFile posts multipart to files path`() = runTest {
        var seenPath: String? = null
        var seenMethod: HttpMethod? = null
        val api =
            ConfigBatchApi(
                requester { req ->
                    seenPath = req.url.encodedPath
                    seenMethod = req.method
                    respond("""{"id":"file-1","object":"file","bytes":4,"filename":"a.jsonl","purpose":"batch"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                },
            )
        val file = api.uploadBatchFile("a.jsonl", "{}".encodeToByteArray())
        assertEquals("/v1beta/openai/files", seenPath)
        assertEquals(HttpMethod.Post, seenMethod)
        assertEquals("file-1", file.id)
        assertEquals("a.jsonl", file.filename)
        assertEquals("batch", file.purpose)
    }

    @Test
    fun `createBatch posts to batches path`() = runTest {
        var seenPath: String? = null
        val api =
            ConfigBatchApi(
                requester { req ->
                    seenPath = req.url.encodedPath
                    respond("""{"id":"b-1","object":"batch","endpoint":"/v1/chat/completions","status":"validating"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                },
            )
        val batch = api.createBatch(BatchRequest(inputFileId = "file-1"))
        assertEquals("/v1beta/openai/batches", seenPath)
        assertEquals("b-1", batch.id)
        assertEquals("validating", batch.status)
    }

    @Test
    fun `retrieveBatch gets single batch`() = runTest {
        var seenPath: String? = null
        val api =
            ConfigBatchApi(
                requester { req ->
                    seenPath = req.url.encodedPath
                    respond("""{"id":"b-1","object":"batch","status":"completed"}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                },
            )
        val batch = api.retrieveBatch("b-1")
        assertEquals("/v1beta/openai/batches/b-1", seenPath)
        assertEquals("completed", batch.status)
    }

    @Test
    fun `batch decodes unknown keys and optional fields leniently`() = runTest {
        val api =
            ConfigBatchApi(
                requester { req ->
                    respond("""{"id":"b","object":"batch","status":"failed","weird_extra":1}""", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString()))
                },
            )
        val batch = api.retrieveBatch("b")
        assertEquals("failed", batch.status)
    }

    // ---- InteractionsApi (Gemini stateful, x-goog-api-key auth) ----

    private val interactionJson =
        """{"id":"i-1","object":"interaction","status":"COMPLETED","model":"gemini-3.6-flash",
            "usage":{"total_tokens":5}}""".trimIndent()

    private fun interactions(
        handler: suspend io.ktor.client.engine.mock.MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ): ConfigInteractionsApi =
        ConfigInteractionsApi(
            providerConfig =
                ProviderConfig(
                    id = "g",
                    baseUrl = "https://gen.test",
                    auth = ProviderAuth(scheme = AuthScheme.X_API_KEY, apiKey = "gkey", keyHeader = "x-goog-api-key"),
                ),
            json = json,
            client = HttpClient(MockEngine(handler)),
        )

    @Test
    fun `interact sends x-goog-api-key without bearer and returns decoded response`() = runTest {
        var keyHeader: String? = null
        var authHeader: String? = null
        var body: String? = null
        val api =
            interactions { req ->
                keyHeader = req.headers["x-goog-api-key"]
                authHeader = req.headers["Authorization"]
                body = (req.body as io.ktor.http.content.TextContent).text
                respond(interactionJson)
            }
        val out = api.interact(InteractionRequest(model = "gemini-3.6-flash", input = JsonPrimitive("hi")))
        assertEquals("gkey", keyHeader)
        assertNull(authHeader)
        assertTrue(body!!.contains("\"model\":\"gemini-3.6-flash\""))
        assertEquals("i-1", out.id)
        assertEquals("COMPLETED", out.status)
        assertEquals(5, out.usage?.totalTokens)
    }

    @Test
    fun `interact strips authorization from extraHeaders`() = runTest {
        var authHeader: String? = null
        var extra: String? = null
        val api =
            ConfigInteractionsApi(
                providerConfig =
                    ProviderConfig(
                        id = "g",
                        baseUrl = "https://gen.test",
                        auth =
                            ProviderAuth(
                                scheme = AuthScheme.X_API_KEY,
                                apiKey = "gkey",
                                keyHeader = "x-goog-api-key",
                                extraHeaders = mapOf("Authorization" to "Bearer nope", "X-Extra" to "1"),
                            ),
                    ),
                json = json,
                client =
                    HttpClient(
                        MockEngine { req ->
                            authHeader = req.headers["Authorization"]
                            extra = req.headers["X-Extra"]
                            respond(interactionJson)
                        },
                    ),
            )
        api.interact(InteractionRequest(model = "m", input = JsonPrimitive("x")))
        assertNull(authHeader)
        assertEquals("1", extra)
    }

    @Test
    fun `interact error path throws with http status`() = runTest {
        val api =
            interactions { req ->
                respond("""{"error":{"message":"nope"}}""", HttpStatusCode.BadRequest)
            }
        val e =
            runCatching {
                api.interact(InteractionRequest(model = "m", input = JsonPrimitive("x")))
            }.exceptionOrNull()
        assertTrue(e is IllegalStateException && e.message!!.contains("400"))
    }

    @Test
    fun `retrieveInteraction gets path with id`() = runTest {
        var seenPath: String? = null
        val api =
            interactions { req ->
                seenPath = req.url.encodedPath
                respond(interactionJson)
            }
        val out = api.retrieveInteraction("i-42")
        assertEquals("/v1beta/interactions/i-42", seenPath)
        assertEquals(out.id, out.id)
    }

    @Test
    fun `serialized interaction request keeps steps and tools`() {
        val req =
            InteractionRequest(
                model = "m",
                input = JsonPrimitive("x"),
                steps = buildJsonArray { add(buildJsonObject { put("a", 1) }) },
                tools = listOf(buildJsonObject { put("type", "web_search") }),
            )
        val encoded = json.encodeToString(InteractionRequest.serializer(), req)
        val decoded = json.decodeFromString(InteractionRequest.serializer(), encoded)
        assertEquals(1, decoded.steps?.size)
        assertEquals("web_search", decoded.tools?.first()?.jsonObject?.get("type")?.jsonPrimitive?.content)
    }

    @Test
    fun `batch dtos round-trip with snake_case names`() {
        val original = Batch(id = "b", status = "completed", inputFileId = "f", completionWindow = "24h", createdAt = 1)
        val encoded = json.encodeToString(Batch.serializer(), original)
        assertTrue(encoded.contains("\"input_file_id\""))
        assertTrue(encoded.contains("\"completion_window\""))
        val decoded = json.decodeFromString(Batch.serializer(), encoded)
        assertEquals("f", decoded.inputFileId)
        assertEquals(1, decoded.createdAt)
    }
}