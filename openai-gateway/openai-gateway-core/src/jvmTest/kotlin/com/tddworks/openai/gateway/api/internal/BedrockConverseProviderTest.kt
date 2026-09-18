package com.tddworks.openai.gateway.api.internal

import com.tddworks.common.network.api.ktor.api.HttpRequester
import com.tddworks.common.network.api.ktor.internal.default
import com.tddworks.openai.api.chat.api.ChatCompletionRequest
import com.tddworks.openai.api.chat.api.ChatMessage
import com.tddworks.openai.api.chat.api.OpenAIModel
import com.tddworks.openai.gateway.api.OpenAIProvider
import com.tddworks.openai.gateway.config.AuthScheme
import com.tddworks.openai.gateway.config.CredentialProviders
import com.tddworks.openai.gateway.config.Dialect
import com.tddworks.openai.gateway.config.ProviderAuth
import com.tddworks.openai.gateway.config.ProviderConfig
import com.tddworks.openai.gateway.config.StreamFormat
import app.cash.turbine.test
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalSerializationApi::class)
class BedrockConverseProviderTest {

    @AfterEach fun tearDown() = CredentialProviders.clear()

    private fun provider(engine: MockEngine): BedrockConverseProvider {
        val config =
            ProviderConfig(
                id = "bedrock",
                dialect = Dialect.BEDROCK,
                baseUrl = "https://bedrock-runtime.us-east-1.amazonaws.com",
                auth = ProviderAuth(scheme = AuthScheme.SIGV4, apiKey = "AKIA", secretKey = "s", region = "us-east-1", service = "bedrock"),
                streaming = StreamFormat.EVENTSTREAM,
            )
        val client = HttpClient(engine)
        return BedrockConverseProvider(
            id = config.id,
            name = config.id,
            config = legacyConfig(config),
            providerConfig = config,
            requester = HttpRequester.default(client),
        )
    }

    private fun bareProvider(): BedrockConverseProvider =
        provider(MockEngine { respond("", HttpStatusCode.OK) })

    @Test
    fun `maps chat request to Converse body (system split, text blocks, inferenceConfig)`() {
        val body =
            bareProvider().toConverseBody(
                ChatCompletionRequest(
                    messages =
                        listOf(
                            ChatMessage.system("be brief"),
                            ChatMessage.user("hi"),
                            ChatMessage.assistant("hello"),
                        ),
                    model = OpenAIModel("m"),
                    maxTokens = 128,
                ),
            )
        val obj = Json.parseToJsonElement(body).jsonObject
        // system separated out
        assertEquals("be brief", obj["system"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        // two non-system turns, first is user
        val messages = obj["messages"]!!.jsonArray
        assertEquals(2, messages.size)
        assertEquals("user", messages[0].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals("hi", messages[0].jsonObject["content"]!!.jsonArray[0].jsonObject["text"]!!.jsonPrimitive.content)
        assertEquals("assistant", messages[1].jsonObject["role"]!!.jsonPrimitive.content)
        assertEquals(128, obj["inferenceConfig"]!!.jsonObject["maxTokens"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun `parses Converse response into ChatCompletion`() {
        val json =
            """{"output":{"message":{"role":"assistant","content":[{"text":"Hello "},{"text":"world"}]}},
               "stopReason":"end_turn"}"""
        val completion = bareProvider().parseConverse(json, "anthropic.claude")
        assertEquals("Hello world", (completion.choices[0].message as ChatMessage.AssistantMessage).content)
        assertEquals("stop", completion.choices[0].finishReason!!.value)
        assertEquals("anthropic.claude", completion.model)
    }

    @Test
    fun `chatCompletions posts to converse path and returns parsed completion`() = runTest {
        var calledPath = ""
        val engine =
            MockEngine { request ->
                calledPath = request.url.encodedPath
                respond(
                    content = """{"output":{"message":{"content":[{"text":"ok"}]}},"stopReason":"end_turn"}""",
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", ContentType.Application.Json.toString()),
                )
            }
        val completion =
            provider(engine).chatCompletions(
                ChatCompletionRequest(messages = listOf(ChatMessage.user("hi")), model = OpenAIModel("m")),
            )
        assertEquals("/model/m/converse", calledPath)
        assertEquals("ok", (completion.choices[0].message as ChatMessage.AssistantMessage).content)
    }

    @Test
    fun `streaming decodes eventstream contentBlockDelta into chunks`() = runTest {
        val frames = eventStreamFrames(listOf("""{"delta":{"text":"He"}}""", """{"delta":{"text":"llo"}}"""))
        val engine =
            MockEngine {
                respond(
                    content = frames,
                    status = HttpStatusCode.OK,
                    headers = headersOf("Content-Type", "application/vnd.amazon.eventstream"),
                )
            }
        val chunks = mutableListOf<String>()
        provider(engine)
            .streamChatCompletions(
                ChatCompletionRequest(messages = listOf(ChatMessage.user("hi")), model = OpenAIModel("m")),
            )
            .test {
                chunks.add(awaitItem().content())
                chunks.add(awaitItem().content())
                awaitComplete()
            }
        assertEquals(listOf("He", "llo"), chunks)
    }

    @Test
    fun `from(config) builds native Converse provider when streaming is EVENTSTREAM`() {
        val config =
            ProviderConfig(
                id = "b",
                dialect = Dialect.BEDROCK,
                baseUrl = "https://bedrock-runtime.us-east-1.amazonaws.com",
                auth = ProviderAuth(scheme = AuthScheme.SIGV4, apiKey = "k", secretKey = "s"),
                streaming = StreamFormat.EVENTSTREAM,
            )
        assertTrue(OpenAIProvider.from(config) is BedrockConverseProvider)
    }

    @Test
    fun `from(config) builds D1 surface for BEDROCK when streaming is SSE`() {
        val config =
            ProviderConfig(
                id = "b",
                dialect = Dialect.BEDROCK,
                baseUrl = "https://bedrock-runtime.us-east-1.amazonaws.com",
                auth = ProviderAuth(scheme = AuthScheme.SIGV4, apiKey = "k", secretKey = "s"),
                streaming = StreamFormat.SSE,
            )
        assertTrue(OpenAIProvider.from(config) is ConfigOpenAIProvider)
    }

    /** Build a concatenation of vnd.amazon.eventstream frames wrapping each JSON payload. */
    private fun eventStreamFrames(payloads: List<String>): ByteArray {
        var out = ByteArray(0)
        for (p in payloads) out += frame(p.encodeToByteArray())
        return out
    }

    private fun frame(payload: ByteArray): ByteArray {
        // Minimal frame: headers=":event-type"=contentBlockDelta (string) then payload.
        val name = ":event-type".encodeToByteArray()
        val value = "contentBlockDelta".encodeToByteArray()
        val headers = ByteArray(0) +
            byteArrayOf(name.size.toByte()) + name + byteArrayOf(7) +
            be16(value.size) + value
        val headersLen = headers.size
        val total = 8 + 4 + headersLen + payload.size + 4
        val prelude = be32(total) + be32(headersLen)
        val preludeCrc = be32(0) // decoder is CRC-lenient
        val msgCrc = be32(0)
        return prelude + preludeCrc + headers + payload + msgCrc
    }

    private fun be32(v: Int) = byteArrayOf((v ushr 24).toByte(), (v ushr 16).toByte(), (v ushr 8).toByte(), v.toByte())
    private fun be16(v: Int) = byteArrayOf((v ushr 8).toByte(), v.toByte())
}
