package com.tddworks.openai.gateway

import com.tddworks.openai.api.chat.api.ChatCompletionRequest
import com.tddworks.openai.api.chat.api.ChatMessage
import com.tddworks.openai.api.chat.api.OpenAIModel
import com.tddworks.openai.api.chat.api.vision.ImageUrl
import com.tddworks.openai.api.chat.api.vision.VisionMessageContent
import com.tddworks.openai.gateway.api.LLMProvider
import com.tddworks.openai.gateway.api.OpenAIGateway
import com.tddworks.openai.gateway.api.OpenAIProvider
import com.tddworks.openai.gateway.api.internal.from
import com.tddworks.openai.gateway.config.AuthScheme
import com.tddworks.openai.gateway.config.Dialect
import com.tddworks.openai.gateway.config.EmbeddingRequest
import com.tddworks.openai.gateway.config.Endpoints
import com.tddworks.openai.gateway.config.ProviderAuth
import com.tddworks.openai.gateway.config.ProviderConfig
import io.ktor.client.HttpClient
import io.ktor.http.contentType
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import java.util.Base64

/**
 * Qwen Cloud (DashScope International) smoke test across all modalities with one key:
 *  - text chat + streaming (qwen3.8-flash)
 *  - vision input (qwen3.8-omni-flash, data-URL image)
 *  - audio input + image + text in one streamed omni call (raw SSE)
 *  - embeddings (qwen3.7-text-embedding)
 *
 * Requires QWEN_KEY. Run:
 * QWEN_KEY=<key> ./gradlew :openai-gateway:openai-gateway-core:jvmTest --tests '*QwenCloudSmokeITest'
 */
@EnabledIfEnvironmentVariable(named = "QWEN_KEY", matches = ".+")
class QwenCloudSmokeITest {

    private val config =
        ProviderConfig(
            id = "openai",
            name = "Qwen Cloud",
            dialect = Dialect.OPENAI_COMPAT,
            baseUrl = "https://dashscope-intl.aliyuncs.com",
            endpoints =
                Endpoints(
                    chat = "/compatible-mode/v1/chat/completions",
                    models = "/compatible-mode/v1/models",
                    embeddings = "/compatible-mode/v1/embeddings",
                ),
            auth = ProviderAuth(scheme = AuthScheme.BEARER, apiKey = System.getenv("QWEN_KEY")),
        )

    private val imageDataUrl: String by lazy {
        val bytes = javaClass.getResourceAsStream("/qwen-test-apple.png")?.readBytes()
            ?: runCatching { java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/tmp/kilo/sdxl-apple.png")) }
                .getOrNull()
            ?: throw IllegalStateException("test image missing")
        "data:image/png;base64," + Base64.getEncoder().encodeToString(bytes)
    }

    private val audioDataUrl: String by lazy {
        val bytes = javaClass.getResourceAsStream("/qwen-test-tone.wav")?.readBytes()
            ?: runCatching { java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("/tmp/kilo/tone.wav")) }
                .getOrNull()
            ?: throw IllegalStateException("test audio missing")
        "data:audio/wav;base64," + Base64.getEncoder().encodeToString(bytes)
    }

    @Test
    fun `all modalities against qwen cloud`() = runBlocking {
        // 1) text chat + streaming via the config-driven gateway
        val gateway = OpenAIGateway.create(listOf(config))
        val completion =
            gateway.chatCompletions(
                request =
                    ChatCompletionRequest(
                        model = OpenAIModel("qwen3.8-flash"),
                        messages = listOf(ChatMessage.user("reply with exactly: OK")),
                    ),
                provider = LLMProvider.OPENAI,
            )
        val chatText =
            completion.choices.firstOrNull()?.message?.let {
                (it as? ChatMessage.AssistantMessage)?.content
            }
        assertEquals("OK", chatText?.trim())
        println("QWEN TEXT OK: $chatText")

        val chunks =
            gateway
                .streamChatCompletions(
                    request =
                        ChatCompletionRequest(
                            model = OpenAIModel("qwen3.8-flash"),
                            messages = listOf(ChatMessage.user("count from 1 to 3")),
                        ),
                    provider = LLMProvider.OPENAI,
                )
                .toList()
        val streamText = chunks.joinToString("") { it.content() }
        assertTrue(streamText.contains("1") && streamText.contains("3"), "stream: $streamText")
        println("QWEN STREAM OK: $streamText")

        // 2) vision via the DTO path (data-URL image)
        val vision =
            gateway.chatCompletions(
                request =
                    ChatCompletionRequest(
                        model = OpenAIModel("qwen3.8-omni-flash"),
                        messages =
                            listOf(
                                ChatMessage.vision(
                                    listOf(
                                        VisionMessageContent.TextContent(content = "What object is in the image? One word."),
                                        VisionMessageContent.ImageContent(imageUrl = ImageUrl(imageDataUrl)),
                                    ),
                                ),
                            ),
                    ),
                provider = LLMProvider.OPENAI,
            )
        val visionText =
            (vision.choices.firstOrNull()?.message as? ChatMessage.AssistantMessage)?.content
                ?.lowercase()
        assertTrue(visionText?.contains("apple") == true, "vision reply: $visionText")
        println("QWEN VISION OK: $visionText")

        // 3) embeddings via the config-driven embeddings surface
        val provider = OpenAIProvider.from(config)
        val embedding =
            (provider as com.tddworks.openai.gateway.api.internal.ConfigOpenAIProvider)
                .embeddings(
                    EmbeddingRequest(
                        model = "qwen3.7-text-embedding",
                        input = listOf("hello qwen"),
                    ),
                )
        assertTrue(embedding.data[0].embedding.size >= 512, "dims=${embedding.data[0].embedding.size}")
        println("QWEN EMBEDDINGS OK: dims=${embedding.data[0].embedding.size}")

        // 4) omni: text + image + audio in one STREAMED call (raw SSE, same key/config)
        val omniText = streamOmni()
        assertTrue(omniText.lowercase().contains("apple"), "omni did not perceive the image: $omniText")
        println("QWEN OMNI OK (text+image+audio, streamed): $omniText")
    }

    private suspend fun streamOmni(): String {
        val payload =
            buildJsonObject {
                put("model", "qwen3.8-omni-flash")
                put(
                    "messages",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("role", "user")
                                put(
                                    "content",
                                    buildJsonArray {
                                        add(buildJsonObject { put("type", "text"); put("text", "What object is in the image and what tone do you hear? Reply very briefly.") })
                                        add(buildJsonObject { put("type", "image_url"); put("image_url", buildJsonObject { put("url", imageDataUrl) }) })
                                        add(buildJsonObject { put("type", "input_audio"); put("input_audio", buildJsonObject { put("data", audioDataUrl); put("format", "wav") }) })
                                    },
                                )
                            },
                        )
                    },
                )
                put("stream", true)
            }
        val client = HttpClient()
        try {
            val response =
                client.post("https://dashscope-intl.aliyuncs.com/compatible-mode/v1/chat/completions") {
                    header("Authorization", "Bearer ${System.getenv("QWEN_KEY")}")
                    contentType(ContentType.Application.Json)
                    setBody(payload.toString())
                }
            val text = StringBuilder()
            val channel = response.bodyAsChannel()
            while (!channel.isClosedForRead) {
                val line = channel.readUTF8Line() ?: continue
                val trimmed = line.trim()
                if (!trimmed.startsWith("data:")) continue
                val data = trimmed.removePrefix("data:").trim()
                if (data == "[DONE]") break
                runCatching {
                    val delta =
                        Json.parseToJsonElement(data)
                            .jsonObject["choices"]
                            ?.jsonArray?.firstOrNull()?.jsonObject?.get("delta")
                            ?.jsonObject?.get("content")?.jsonPrimitive?.contentOrNull
                    if (delta != null) text.append(delta)
                }
            }
            return text.toString()
        } finally {
            client.close()
        }
    }
}