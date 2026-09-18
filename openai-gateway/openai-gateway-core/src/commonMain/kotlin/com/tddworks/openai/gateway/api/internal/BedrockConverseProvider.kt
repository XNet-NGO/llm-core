package com.tddworks.openai.gateway.api.internal

import com.tddworks.common.network.api.ktor.api.EventStreamDecoder
import com.tddworks.common.network.api.ktor.api.HttpRequester
import com.tddworks.common.network.api.ktor.api.ListResponse
import com.tddworks.common.network.api.ktor.api.performRequest
import com.tddworks.common.network.api.ktor.api.streamRequest
import com.tddworks.openai.api.chat.api.ChatChoice
import com.tddworks.openai.api.chat.api.ChatChunk
import com.tddworks.openai.api.chat.api.ChatCompletion
import com.tddworks.openai.api.chat.api.ChatCompletionChunk
import com.tddworks.openai.api.chat.api.ChatCompletionRequest
import com.tddworks.openai.api.chat.api.ChatDelta
import com.tddworks.openai.api.chat.api.ChatMessage
import com.tddworks.openai.api.chat.api.FinishReason
import com.tddworks.openai.api.chat.api.Role
import com.tddworks.openai.api.images.api.Image
import com.tddworks.openai.api.images.api.ImageCreate
import com.tddworks.openai.api.legacy.completions.api.Completion
import com.tddworks.openai.api.legacy.completions.api.CompletionRequest
import com.tddworks.openai.gateway.api.OpenAIProvider
import com.tddworks.openai.gateway.api.OpenAIProviderConfig
import com.tddworks.openai.gateway.config.AuthScheme
import com.tddworks.openai.gateway.config.CredentialProviders
import com.tddworks.openai.gateway.config.ProviderConfig
import com.tddworks.openai.gateway.config.SignedCredentials
import com.tddworks.openai.gateway.config.SigningContext
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readAvailable
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Native AWS Bedrock provider (D4) over the **Converse** API: `POST /model/{modelId}/converse`
 * and `converse-stream` (`application/vnd.amazon.eventstream`). Maps the OpenAI chat surface to
 * Converse's unified JSON and back, so it works for any Converse-capable model (Claude, Llama,
 * Nova, Mistral, …) without per-model body templates.
 *
 * Auth is AWS SigV4 via the registered [AuthScheme.SIGV4] signer. Prefer the OpenAI-compatible
 * Bedrock runtime (D1) when a deployment advertises it; this native path is for the Converse API.
 */
internal class BedrockConverseProvider(
    override val id: String,
    override val name: String,
    override val config: OpenAIProviderConfig,
    private val providerConfig: ProviderConfig,
    private val requester: HttpRequester,
) : OpenAIProvider {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    override suspend fun chatCompletions(request: ChatCompletionRequest): ChatCompletion {
        val model = aliasFor(request.model.value)
        val path = "/model/$model/converse"
        val body = toConverseBody(request)
        val signed = sign(path, body.encodeToByteArray())
        val response =
            requester.performRequest<String> {
                method = HttpMethod.Post
                url(path = path)
                setBody(body)
                contentType(ContentType.Application.Json)
                applySigned(this, signed)
            }
        return parseConverse(response, model)
    }

    override fun streamChatCompletions(request: ChatCompletionRequest): Flow<ChatCompletionChunk> =
        flow {
            val model = aliasFor(request.model.value)
            val path = "/model/$model/converse-stream"
            val body = toConverseBody(request)
            val signed = sign(path, body.encodeToByteArray())
            requester.streamRequest<Unit>(
                builder = {
                    method = HttpMethod.Post
                    url(path = path)
                    setBody(body)
                    contentType(ContentType.Application.Json)
                    applySigned(this, signed)
                },
                block = { response -> emitConverseStream(response, model) },
            )
        }

    override suspend fun completions(request: CompletionRequest): Completion =
        throw UnsupportedOperationException("Bedrock Converse: use chatCompletions")

    override suspend fun generate(request: ImageCreate): ListResponse<Image> =
        throw UnsupportedOperationException("Bedrock Converse: images not supported on this surface")

    // --- mapping -----------------------------------------------------------------------------

    private fun aliasFor(model: String): String = providerConfig.aliases[model] ?: model

    /** OpenAI chat request → Converse request JSON (system split out; text content blocks). */
    internal fun toConverseBody(request: ChatCompletionRequest): String {
        val systemText = request.messages.filter { it.role == Role.System }.joinToString("\n") {
            (it.content as? String).orEmpty()
        }
        val turns = request.messages.filter { it.role != Role.System }
        val obj = buildJsonObject {
            putJsonArray("messages") {
                turns.forEach { m ->
                    add(
                        buildJsonObject {
                            put("role", if (m.role == Role.Assistant) "assistant" else "user")
                            putJsonArray("content") {
                                add(buildJsonObject { put("text", (m.content as? String).orEmpty()) })
                            }
                        },
                    )
                }
            }
            if (systemText.isNotEmpty()) {
                putJsonArray("system") { add(buildJsonObject { put("text", systemText) }) }
            }
            request.maxTokens?.let { mt ->
                putJsonObject("inferenceConfig") { put("maxTokens", mt) }
            }
        }
        return json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), obj)
    }

    /** Converse response JSON → OpenAI ChatCompletion. */
    internal fun parseConverse(bodyText: String, model: String): ChatCompletion {
        val root = json.parseToJsonElement(bodyText).jsonObject
        val text =
            root["output"]?.jsonObject
                ?.get("message")?.jsonObject
                ?.get("content")?.jsonArray
                ?.mapNotNull { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull }
                ?.joinToString("")
                ?: ""
        val stop = root["stopReason"]?.jsonPrimitive?.contentOrNull
        return ChatCompletion(
            id = "bedrock-converse",
            created = 0,
            model = model,
            choices =
                listOf(
                    ChatChoice(
                        index = 0,
                        message = ChatMessage.AssistantMessage(text),
                        finishReason = stop?.let { FinishReason(mapStopReason(it)) },
                    ),
                ),
        )
    }

    private fun mapStopReason(bedrock: String): String =
        when (bedrock) {
            "end_turn", "stop_sequence" -> "stop"
            "max_tokens" -> "length"
            "tool_use" -> "tool_calls"
            else -> bedrock
        }

    /** Read the eventstream body and emit a ChatCompletionChunk per contentBlockDelta text. */
    private suspend fun kotlinx.coroutines.flow.FlowCollector<ChatCompletionChunk>.emitConverseStream(
        response: HttpResponse,
        model: String,
    ) {
        val channel: ByteReadChannel = response.bodyAsChannel()
        var carry = ByteArray(0)
        val buf = ByteArray(8192)
        while (!channel.isClosedForRead) {
            val n = channel.readAvailable(buf, 0, buf.size)
            if (n <= 0) continue
            carry += buf.copyOfRange(0, n)
            val (messages, consumed) = EventStreamDecoder.decode(carry)
            if (consumed > 0) carry = carry.copyOfRange(consumed, carry.size)
            for (m in messages) {
                val delta = extractDeltaText(m.payloadText()) ?: continue
                if (delta.isEmpty()) continue
                emit(chunk(model, delta))
            }
        }
        // Flush any final complete frames left in carry.
        val (rest, _) = EventStreamDecoder.decode(carry)
        for (m in rest) extractDeltaText(m.payloadText())?.let { if (it.isNotEmpty()) emit(chunk(model, it)) }
    }

    private fun extractDeltaText(payload: String): String? =
        try {
            json.parseToJsonElement(payload).jsonObject["delta"]?.jsonObject
                ?.get("text")?.jsonPrimitive?.contentOrNull
        } catch (e: Throwable) {
            null
        }

    private fun chunk(model: String, text: String) =
        ChatCompletionChunk(
            id = "bedrock-converse",
            `object` = "chat.completion.chunk",
            created = 0,
            model = model,
            choices = listOf(ChatChunk(delta = ChatDelta(content = text, role = Role.Assistant), index = 0, finishReason = null)),
        )

    // --- signing -----------------------------------------------------------------------------

    private suspend fun sign(path: String, body: ByteArray): SignedCredentials {
        val signer = CredentialProviders.resolve(AuthScheme.SIGV4) ?: return SignedCredentials()
        val host = providerConfig.baseUrl.substringAfter("://").substringBefore("/")
        return signer.sign(providerConfig.auth, SigningContext("POST", host, path, body))
    }

    private fun applySigned(builder: HttpRequestBuilder, signed: SignedCredentials) {
        signed.headers.forEach { (k, v) -> builder.header(k, v) }
    }
}
