package com.tddworks.common.network.api.ktor.api

import app.cash.turbine.test
import com.tddworks.common.network.api.ktor.StreamResponse
import com.tddworks.common.network.api.ktor.internal.JsonLenient
import io.ktor.client.*
import io.ktor.client.engine.mock.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.utils.io.*
import kotlin.test.assertEquals
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.extension.RegisterExtension
import org.koin.dsl.module
import org.koin.test.junit5.AutoCloseKoinTest
import org.koin.test.junit5.KoinTestExtension

class StreamTest : AutoCloseKoinTest() {

    @JvmField
    @RegisterExtension
    val koinTestExtension =
        KoinTestExtension.create { modules(module { single<Json> { JsonLenient } }) }

    @Test
    fun `test streamEventsFrom with stream response`(): Unit = runBlocking {
        val channel = ByteChannel(autoFlush = true)
        val mockEngine = MockEngine { request ->
            when (request.url.toString()) {
                "http://example.com/stream" ->
                    respond(content = channel, status = HttpStatusCode.OK)

                else -> respond("", HttpStatusCode.NotFound)
            }
        }

        val client = HttpClient(mockEngine)

        val content =
            flow<StreamResponse> {
                client.preparePost("http://example.com/stream").execute { streamEventsFrom(it) }
            }

        channel.writeStringUtf8("Hello world!\n")
        channel.writeStringUtf8("data: {\"content\": \"some-content-1\"}\n")
        channel.writeStringUtf8("data: {\"content\": \"some-content-2\"}\n")
        channel.writeStringUtf8("data: [DONE]\n")
        content.test {
            assertEquals(StreamResponse("some-content-1"), awaitItem())
            assertEquals(StreamResponse("some-content-2"), awaitItem())
            awaitComplete()
            channel.close()
        }
    }

    @Test
    fun `tolerates CRLF, missing spaces, comments, keepalives and blank lines`(): Unit =
        runBlocking {
            val channel = ByteChannel(autoFlush = true)
            val mockEngine = MockEngine { request ->
                when (request.url.toString()) {
                    "http://example.com/stream" ->
                        respond(content = channel, status = HttpStatusCode.OK)

                    else -> respond("", HttpStatusCode.NotFound)
                }
            }
            val client = HttpClient(mockEngine)
            val content =
                flow<StreamResponse> {
                    client.preparePost("http://example.com/stream").execute { streamEventsFrom(it) }
                }

            // CRLF line endings + a leading SSE comment/keepalive line
            channel.writeStringUtf8(": ping\r\n")
            channel.writeStringUtf8("\r\n") // blank separator
            // no space after data:
            channel.writeStringUtf8("data:{\"content\": \"c1\"}\r\n")
            // extra spaces after data:
            channel.writeStringUtf8("data:   {\"content\": \"c2\"}\r\n")
            // another keepalive mid-stream
            channel.writeStringUtf8(": keep-alive\r\n")
            // end sentinel with no space after data:
            channel.writeStringUtf8("data:[DONE]\r\n")

            content.test {
                assertEquals(StreamResponse("c1"), awaitItem())
                assertEquals(StreamResponse("c2"), awaitItem())
                awaitComplete()
                channel.close()
            }
        }

    @Test
    fun `parses NDJSON bare-object records without data prefix`(): Unit = runBlocking {
        val channel = ByteChannel(autoFlush = true)
        val mockEngine = MockEngine { request ->
            when (request.url.toString()) {
                "http://example.com/stream" ->
                    respond(content = channel, status = HttpStatusCode.OK)

                else -> respond("", HttpStatusCode.NotFound)
            }
        }
        val client = HttpClient(mockEngine)
        val content =
            flow<StreamResponse> {
                client.preparePost("http://example.com/stream").execute { streamEventsFrom(it) }
            }

        // NDJSON (ollama-style): one JSON object per line, no `data:` prefix, CRLF-mixed.
        channel.writeStringUtf8("{\"content\": \"n1\"}\n")
        channel.writeStringUtf8("{\"content\": \"n2\"}\r\n")
        content.test {
            assertEquals(StreamResponse("n1"), awaitItem())
            assertEquals(StreamResponse("n2"), awaitItem())
            channel.close()
            awaitComplete()
        }
    }
}
