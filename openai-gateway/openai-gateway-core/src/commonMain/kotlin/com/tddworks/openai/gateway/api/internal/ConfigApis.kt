package com.tddworks.openai.gateway.api.internal

import com.tddworks.common.network.api.ktor.api.HttpRequester
import com.tddworks.common.network.api.ktor.api.performRequest
import com.tddworks.openai.gateway.config.Batch
import com.tddworks.openai.gateway.config.BatchFile
import com.tddworks.openai.gateway.config.BatchRequest
import com.tddworks.openai.gateway.config.EmbeddingRequest
import com.tddworks.openai.gateway.config.EmbeddingResponse
import com.tddworks.openai.gateway.config.InteractionRequest
import com.tddworks.openai.gateway.config.InteractionResponse
import com.tddworks.openai.gateway.config.ProviderConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsText
import io.ktor.client.request.get
import io.ktor.http.ContentDisposition
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.http.headersOf
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json

/** Embeddings over the OpenAI-compatible surface (Bearer auth). */
interface EmbeddingsApi {
    suspend fun embeddings(request: EmbeddingRequest): EmbeddingResponse
}

/** Gemini Interactions API (stateful sessions; x-goog-api-key auth, no Bearer). */
interface InteractionsApi {
    suspend fun interact(request: InteractionRequest): InteractionResponse
    suspend fun retrieveInteraction(id: String): InteractionResponse
}

/** OpenAI-compatible Batch API: file upload, create/retrieve/poll batches. */
interface BatchApi {
    suspend fun uploadBatchFile(filename: String, content: ByteArray): BatchFile
    suspend fun createBatch(request: BatchRequest): Batch
    suspend fun retrieveBatch(id: String): Batch
}

/**
 * Config-driven implementations of the extended API surfaces. Auth per operation:
 * embeddings/batch ride the provider's Bearer requester; interactions uses the
 * provider's API key as `x-goog-api-key` (Gemini convention) with NO bearer token.
 */
internal class ConfigEmbeddingsApi(
    private val requester: HttpRequester,
    private val path: String = "/v1/openai/embeddings",
) : EmbeddingsApi {
    override suspend fun embeddings(request: EmbeddingRequest): EmbeddingResponse =
        requester.performRequest {
            method = io.ktor.http.HttpMethod.Post
            url(path = path)
            setBody(request)
            contentType(io.ktor.http.ContentType.Application.Json)
        }
}

internal class ConfigInteractionsApi(
    private val providerConfig: ProviderConfig,
    private val json: Json,
    private val path: String = "/v1beta/interactions",
    private val client: HttpClient? = null,
) : InteractionsApi {
    private fun http(): HttpClient = client ?: HttpClient()

    private suspend fun io.ktor.client.statement.HttpResponse.bodyOrThrow(): String {
        val text = bodyAsText()
        if (!status.isSuccess()) {
            throw IllegalStateException("interactions failed: HTTP ${status.value} ${text.take(200)}")
        }
        return text
    }

    override suspend fun interact(request: InteractionRequest): InteractionResponse {
        val client = http()
        try {
            val response =
                client.post(providerConfig.baseUrl.trimEnd('/') + path) {
                    timeout { requestTimeoutMillis = providerConfig.timeoutMs }
                    header("x-goog-api-key", providerConfig.auth.apiKey)
                    providerConfig.auth.extraHeaders.filterKeys { it.lowercase() != "authorization" }
                        .forEach { (k, v) -> header(k, v) }
                    setBody(io.ktor.http.content.TextContent(json.encodeToString(request), io.ktor.http.ContentType.Application.Json))
                }
            return json.decodeFromString(response.bodyOrThrow())
        } finally {
            if (client == null) client.close()
        }
    }

    override suspend fun retrieveInteraction(id: String): InteractionResponse {
        val client = http()
        try {
            val response =
                client.get(providerConfig.baseUrl.trimEnd('/') + path + "/$id") {
                    timeout { requestTimeoutMillis = providerConfig.timeoutMs }
                    header("x-goog-api-key", providerConfig.auth.apiKey)
                }
            return json.decodeFromString(response.bodyOrThrow())
        } finally {
            if (client == null) client.close()
        }
    }
}

internal class ConfigBatchApi(
    private val requester: HttpRequester,
    private val basePath: String = "/v1beta/openai",
) : BatchApi {

    override suspend fun uploadBatchFile(filename: String, content: ByteArray): BatchFile =
        requester.performRequest {
            method = io.ktor.http.HttpMethod.Post
            url(path = "$basePath/files")
            setBody(
                MultiPartFormDataContent(
                    formData {
                        append(
                            "file",
                            content,
                            headersOf(HttpHeaders.ContentDisposition, "filename=\"$filename\""),
                        )
                        append("purpose", "batch")
                    },
                ),
            )
        }

    override suspend fun createBatch(request: BatchRequest): Batch =
        requester.performRequest {
            method = io.ktor.http.HttpMethod.Post
            url(path = "$basePath/batches")
            setBody(request)
            contentType(io.ktor.http.ContentType.Application.Json)
        }

    override suspend fun retrieveBatch(id: String): Batch =
        requester.performRequest {
            method = io.ktor.http.HttpMethod.Get
            url(path = "$basePath/batches/$id")
        }
}