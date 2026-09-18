package com.tddworks.responses.api.internal

import com.tddworks.common.network.api.ktor.api.HttpRequester
import com.tddworks.common.network.api.ktor.internal.default
import com.tddworks.di.commonModule
import com.tddworks.responses.api.Response
import com.tddworks.responses.api.ResponseCreateRequest
import com.tddworks.responses.api.ResponseInputItem
import com.tddworks.responses.api.ResponseStreamEvent
import com.tddworks.responses.api.Responses
import com.tddworks.responses.api.ResponsesConfig
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.koin.core.context.stopKoin

/**
 * DefaultResponses client behavior: create/retrieve/cancel paths + SSE stream event
 * parsing (typed events, [DONE] tolerance, unknown/unparsable fallbacks, failure
 * emission). Stream parsing needs the DI Json provider (commonModule).
 */
class DefaultResponsesTest {

    @AfterEach
    fun tearDownKoin() { runCatching { org.koin.core.context.stopKoin() } }

    private fun requester(
        handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ): HttpRequester =
        HttpRequester.default(
            HttpClient(MockEngine(handler)) {
                install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
            },
        )
    private val responseJson =
        """{"id":"r-1","object":"response","status":"completed","model":"gpt-5.5",
           "usage":{"input_tokens":1,"output_tokens":2,"total_tokens":3}}""".trimIndent()

    private val ct = headersOf(HttpHeaders.ContentType, ContentType.Application.Json.toString())

    @Test
    fun `create posts to responses path with stream false and decodes`() = runTest {
        var path: String? = null
        var method: HttpMethod? = null
        var body: String? = null
        val responses =
            DefaultResponses(
                requester { request ->
                    path = request.url.encodedPath
                    method = request.method
                    body = (request.body as io.ktor.http.content.TextContent).text
                    respond(responseJson, HttpStatusCode.OK, ct)
                },
            )
        val out = responses.create(ResponseCreateRequest(model = "gpt-5.5"))
        assertEquals("/v1/responses", path)
        assertEquals(HttpMethod.Post, method)
        // stream=false is the default, so it must NOT be serialized as stream:true.
        assertTrue(!body!!.contains("\"stream\":true"))
        assertEquals("r-1", out.id)
        assertEquals(3, out.usage?.totalTokens)
    }

    @Test
    fun `retrieve gets path with id`() = runTest {
        var path: String? = null
        var method: HttpMethod? = null
        val responses = DefaultResponses(requester { request ->
            path = request.url.encodedPath
            method = request.method
            respond(responseJson, HttpStatusCode.OK, ct)
        })
        val out = responses.retrieve("r-9")
        assertEquals("/v1/responses/r-9", path)
        assertEquals(HttpMethod.Get, method)
        assertEquals("r-1", out.id)
    }

    @Test
    fun `cancel posts to cancel path`() = runTest {
        var path: String? = null
        var method: HttpMethod? = null
        val responses = DefaultResponses(requester { request ->
            path = request.url.encodedPath
            method = request.method
            respond(responseJson, HttpStatusCode.OK, ct)
        })
        responses.cancel("r-c")
        assertEquals("/v1/responses/r-c/cancel", path)
        assertEquals(HttpMethod.Post, method)
    }

    @Test
    fun `custom responses path is honored`() = runTest {
        var path: String? = null
        val responses =
            DefaultResponses(
                requester { request ->
                    path = request.url.encodedPath
                    respond(responseJson, HttpStatusCode.OK, ct)
                },
                responsesPath = "/custom/responses",
            )
        responses.retrieve("r")
        assertEquals("/custom/responses/r", path)
    }

    @Test
    fun `stream parses typed events and tolerates keepalives and done`() = runTest {
        runCatching { stopKoin() }
        org.koin.core.context.startKoin { modules(commonModule(false)) }
        val sse =
            """
            data: {"type":"response.created","response":{"id":"r1"}}
            :
            data: {"type":"response.output_text.delta","item_id":"i1","delta":"Hel"}
            data: {"type":"response.output_text.delta","item_id":"i1","delta":"lo"}
            data: {"type":"response.completed"}
            data: [DONE]
            """.trimIndent()
        val responses =
            DefaultResponses(
                requester { request ->
                    assertEquals(HttpMethod.Post, request.method)
                    assertEquals("/v1/responses", request.url.encodedPath)
                    val bodyText = (request.body as io.ktor.http.content.TextContent).text
                    assertTrue(bodyText.contains("\"stream\":true"))
                    respond(
                        sse,
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
                    )
                },
            )
        val events = responses.stream(ResponseCreateRequest(model = "m")).toList()
        assertEquals(4, events.size)
        assertTrue(events[0] is ResponseStreamEvent.Created)
        val deltas = events.filterIsInstance<ResponseStreamEvent.OutputTextDelta>()
        assertEquals(listOf("Hel", "lo"), deltas.map { it.delta })
        assertTrue(events.last() is ResponseStreamEvent.Completed)
    }

    @Test
    fun `stream maps unknown type to Unknown event`() = runTest {
        runCatching { stopKoin() }
        org.koin.core.context.startKoin { modules(commonModule(false)) }
        val responses =
            DefaultResponses(
                requester {
                    respond(
                        """data: {"type":"response.custom_thing","x":1}
data: [DONE]""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
                    )
                },
            )
        val events = responses.stream(ResponseCreateRequest(model = "m")).toList()
        assertEquals(1, events.size)
        val unknown = events[0] as ResponseStreamEvent.Unknown
        assertEquals("response.custom_thing", unknown.type)
    }

    @Test
    fun `stream maps unparsable payload and bad typed payload to Unknown`() = runTest {
        runCatching { stopKoin() }
        org.koin.core.context.startKoin { modules(commonModule(false)) }
        val responses =
            DefaultResponses(
                requester {
                    respond(
                        """data: {this is not json
data: {"type":"response.completed","response":"not-an-object"}
data: [DONE]""",
                        HttpStatusCode.OK,
                        headersOf(HttpHeaders.ContentType, ContentType.Text.EventStream.toString()),
                    )
                },
            )
        val events = responses.stream(ResponseCreateRequest(model = "m")).toList()
        assertEquals(2, events.size)
        assertEquals("unparsable", (events[0] as ResponseStreamEvent.Unknown).type)
        assertEquals("response.completed", (events[1] as ResponseStreamEvent.Unknown).type)
    }

    @Test
    fun `stream failure emits Failed event`() = runTest {
        runCatching { stopKoin() }
        org.koin.core.context.startKoin { modules(commonModule(false)) }
        val responses = DefaultResponses(requester { throw IllegalStateException("boom") })
        val events = responses.stream(ResponseCreateRequest(model = "m")).toList()
        assertEquals(1, events.size)
        assertTrue(events[0] is ResponseStreamEvent.Failed)
    }

    // ---- companion factories + config defaults ----

    @Test
    fun `companion create builds client without network`() {
        val client: Responses = Responses.create("k", "https://127.0.0.1:9")
        assertTrue(client is DefaultResponses)
    }

    @Test
    fun `config defaults match constants`() {
        val cfg = ResponsesConfig()
        assertEquals("CONFIG_API_KEY", cfg.apiKey())
        assertEquals(Responses.BASE_URL, cfg.baseUrl())
    }
}
class ResponsesKoinTest {

    @AfterEach
    fun tearDownKoin() {
        runCatching { org.koin.core.context.stopKoin() }
    }

    @Test
    fun `initResponses boots a working client from config`() {
        runCatching { org.koin.core.context.stopKoin() }
        val client = com.tddworks.responses.di.initResponses(ResponsesConfig(apiKey = { "k" }, baseUrl = { "https://127.0.0.1:9" }))
        assertTrue(client is DefaultResponses)
    }
}
