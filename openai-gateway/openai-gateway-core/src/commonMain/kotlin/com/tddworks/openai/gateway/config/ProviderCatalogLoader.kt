package com.tddworks.openai.gateway.config

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.parameter
import io.ktor.client.statement.bodyAsText
import io.ktor.http.isSuccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Fetches a provider's model catalog from `GET {baseUrl}{path}` with per-provider auth,
 * and resolves [Catalog] per [CatalogMode]. FreeInference-style catalog shapes
 * (context_length, max_output_length, input/output_modalities, supported_features)
 * are the parsing target; unknown shapes degrade silently to empty.
 */
object ProviderCatalogLoader {

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    suspend fun fetchModels(config: ProviderConfig): List<CatalogModel> =
        withContext(Dispatchers.Default) {
            val url = config.baseUrl.trimEnd('/') + (config.endpoints.models ?: config.catalog.path)
            try {
                val client = HttpClient()
                try {
                    val response =
                        client.get(url) {
                            applyAuth(config)
                        }
                    if (response.status.isSuccess()) {
                        parseCatalog(response.bodyAsText())
                    } else {
                        emptyList()
                    }
                } finally {
                    client.close()
                }
            } catch (e: Throwable) {
                emptyList()
            }
        }

    /**
     * Resolve the effective catalog per [Catalog.mode].
     *
     * @param cached optional previously-fetched live entries (host-side TTL cache)
     */
    suspend fun resolveCatalog(
        config: ProviderConfig,
        cached: List<CatalogModel>? = null,
    ): Catalog =
        when (config.catalog.mode) {
            CatalogMode.STATIC -> config.catalog
            CatalogMode.AUTO ->
                config.catalog.copy(models = cached ?: fetchModels(config))
            CatalogMode.MERGED ->
                config.catalog.copy(
                    models =
                        (config.catalog.models + (cached ?: fetchModels(config)))
                            .distinctBy { it.id },
                )
        }

    internal fun parseCatalog(body: String): List<CatalogModel> =
        try {
            val root: JsonElement = json.parseToJsonElement(body)
            val data = root.jsonObject["data"]?.jsonArray ?: return emptyList()
            data.mapNotNull { el ->
                val obj = el.jsonObject
                val id = obj["id"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
                CatalogModel(
                    id = id,
                    contextLength = (obj["context_length"] ?: obj["contextLength"])?.jsonPrimitive?.longOrNull,
                    maxOutputLength = (obj["max_output_length"] ?: obj["maxOutputLength"])?.jsonPrimitive?.longOrNull,
                    inputModalities = stringList(obj["input_modalities"] ?: obj["inputModalities"]),
                    outputModalities = stringList(obj["output_modalities"] ?: obj["outputModalities"]),
                    supportedFeatures = stringList(obj["supported_features"] ?: obj["supportedFeatures"]),
                )
            }
        } catch (e: Throwable) {
            emptyList()
        }

    internal fun io.ktor.client.request.HttpRequestBuilder.applyAuth(config: ProviderConfig) {
        when (config.auth.scheme) {
            AuthScheme.BEARER -> {
                if (config.auth.apiKey.isNotEmpty()) {
                    header("Authorization", "Bearer ${config.auth.apiKey}")
                }
            }
            AuthScheme.X_API_KEY -> {
                if (config.auth.apiKey.isNotEmpty()) {
                    header(config.auth.keyHeader.ifBlank { "X-API-Key" }, config.auth.apiKey)
                }
            }
            AuthScheme.QUERY -> {
                if (config.auth.apiKey.isNotEmpty()) {
                    parameter(config.auth.queryParam.ifBlank { "api_key" }, config.auth.apiKey)
                }
            }
            AuthScheme.NONE -> {}
            AuthScheme.SIGV4, AuthScheme.OAUTH2 -> {
                // Host-provided signer/oauth layer attaches credentials at request time.
            }
        }
        config.auth.extraHeaders.forEach { (k, v) -> header(k, v) }
        config.auth.queryParams.forEach { (k, v) -> parameter(k, v) }
    }

    private fun stringList(el: JsonElement?): List<String> =
        el?.jsonArray?.mapNotNull { it.jsonPrimitive.contentOrNull } ?: emptyList()
}