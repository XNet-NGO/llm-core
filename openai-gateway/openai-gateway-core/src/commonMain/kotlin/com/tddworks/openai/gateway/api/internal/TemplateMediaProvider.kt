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
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlin.time.ExperimentalTime
import kotlinx.serialization.json.contentOrNull
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
        val client = HttpClient()
        try {
            val response =
                client.post("$base$runPath/$model") {
                    header("Authorization", "Bearer ${providerConfig.auth.apiKey}")
                    providerConfig.auth.extraHeaders.forEach { (k, v) -> header(k, v) }
                    setBody(
                        MultiPartFormDataContent(
                            formData {
                                append("prompt", request.prompt)
                            },
                        ),
                    )
                }
            if (!response.status.isSuccess()) {
                throw IllegalStateException(
                    "image generation failed: HTTP ${response.status.value} ${response.bodyAsText().take(200)}",
                )
            }
            val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
            val imageB64 =
                root["result"]?.jsonObject?.get("image")?.jsonPrimitive?.contentOrNull
                    ?: throw IllegalStateException("image response missing result.image: ${root.keys}")
            return ListResponse(
                created = kotlin.time.Clock.System.now().epochSeconds,
                data = listOf(Image(url = null, b64JSON = imageB64)),
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
