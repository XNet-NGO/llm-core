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
import com.tddworks.openai.gateway.api.OpenAIProviderConfig
import com.tddworks.openai.gateway.config.Dialect
import com.tddworks.openai.gateway.config.ProviderConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Template-dialect (D7) media provider driven entirely by [ProviderConfig]: builds a
 * vendor-native multipart request per the configured endpoint template and parses the
 * vendor response shape (here: Cloudflare Workers AI `ai/run/{model}` returning
 * `{"result":{"image":"<base64>"}}`). Chat/completions are unsupported on this dialect
 * and throw — capability gating is the host's job.
 */
class TemplateMediaProvider(
    override val id: String,
    override val name: String,
    override val config: OpenAIProviderConfig,
    private val providerConfig: ProviderConfig,
) : OpenAIProvider {

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    /** Qwen multimodal-generation body: nested messages with a text part + parameters. */
    private fun qwenBody(request: ImageCreate): JsonObject =
        buildJsonObject {
            put("model", request.model.value.removePrefix("models/"))
            put(
                "input",
                buildJsonObject {
                    put(
                        "messages",
                        buildJsonArray {
                            add(
                                buildJsonObject {
                                    put("role", "user")
                                    put(
                                        "content",
                                        buildJsonArray {
                                            add(buildJsonObject { put("text", request.prompt) })
                                        },
                                    )
                                },
                            )
                        },
                    )
                },
            )
            put(
                "parameters",
                buildJsonObject {
                    put("n", 1)
                    put("size", (request.size?.value ?: "1024x1024").replace("x", "*"))
                },
            )
        }

    override suspend fun chatCompletions(request: ChatCompletionRequest): ChatCompletion =
        throw UnsupportedOperationException("dialect ${providerConfig.dialect} does not support chat")

    override fun streamChatCompletions(request: ChatCompletionRequest): Flow<ChatCompletionChunk> =
        throw UnsupportedOperationException("dialect ${providerConfig.dialect} does not support chat")

    override suspend fun completions(request: CompletionRequest): Completion =
        throw UnsupportedOperationException("dialect ${providerConfig.dialect} does not support completions")

    /**
     * Generate an image through the configured vendor endpoint template.
     *
     * Template for Cloudflare Workers AI: `POST {base}/ai/run/{model}` with multipart
     * `prompt` field; response `{"result":{"image":"<base64>"}}`.
     */
    @OptIn(ExperimentalTime::class)
    override suspend fun generate(request: ImageCreate): ListResponse<Image> {
        val base = providerConfig.baseUrl.trimEnd('/')
        val runPath = providerConfig.endpoints.imagesGenerations ?: "/ai/run"
        val model = request.model.value.removePrefix("models/")
        val url =
            if (providerConfig.imageModelInPath) {
                "$base$runPath/$model"
            } else {
                "$base$runPath"
            }
        val client = HttpClient()
        try {
            val response =
                client.post(url) {
                    timeout { requestTimeoutMillis = providerConfig.timeoutMs }
                    header("Authorization", "Bearer ${providerConfig.auth.apiKey}")
                    providerConfig.auth.extraHeaders.forEach { (k, v) -> header(k, v) }
                    when (providerConfig.imageInput.lowercase()) {
                        "json" ->
                            setBody(
                                TextContent(
                                    json.encodeToString(buildJsonObject { put("prompt", request.prompt) }),
                                    ContentType.Application.Json,
                                ),
                            )
                        "qwen" ->
                            setBody(
                                TextContent(
                                    json.encodeToString(qwenBody(request)),
                                    ContentType.Application.Json,
                                ),
                            )
                        else ->
                            setBody(
                                MultiPartFormDataContent(
                                    formData {
                                        append("prompt", request.prompt)
                                    },
                                ),
                            )
                    }
                }
            if (!response.status.isSuccess()) {
                throw IllegalStateException(
                    "image generation failed: HTTP ${response.status.value} ${response.bodyAsText().take(200)}",
                )
            }
            val imageB64: String?
            val imageUrl: String?
            val urlStyle = providerConfig.imageOutput.lowercase() == "url"
            if (urlStyle) {
                val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
                imageUrl =
                    root["output"]?.jsonObject?.get("choices")?.jsonArray?.firstOrNull()
                        ?.jsonObject?.get("message")?.jsonObject?.get("content")?.jsonArray
                        ?.firstOrNull()?.jsonObject?.get("image")?.jsonPrimitive?.contentOrNull
                imageB64 = null
                if (imageUrl == null) {
                    throw IllegalStateException("image response missing output.choices[0].message.content[0].image")
                }
            } else {
                imageUrl = null
                imageB64 =
                    when (providerConfig.imageOutput.lowercase()) {
                        "raw" -> {
                            @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
                            val bytes = response.readRawBytes()
                            kotlin.io.encoding.Base64.encode(bytes)
                        }
                        else -> {
                        val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
                        root["result"]?.jsonObject?.get("image")?.jsonPrimitive?.contentOrNull
                            ?: throw IllegalStateException("image response missing result.image: ${root.keys}")
                        }
                    }
            }
            return ListResponse(
                created = kotlin.time.Clock.System.now().epochSeconds,
                data = listOf(Image(url = imageUrl, b64JSON = imageB64)),
            )
        } finally {
            client.close()
        }
    }
}

fun mediaProvider(config: ProviderConfig): OpenAIProvider =
    TemplateMediaProvider(
        id = config.id,
        name = config.name.ifBlank { config.id },
        config = legacyConfig(config),
        providerConfig = config,
    )
