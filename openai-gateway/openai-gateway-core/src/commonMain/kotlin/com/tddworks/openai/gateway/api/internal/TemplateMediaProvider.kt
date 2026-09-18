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
import com.tddworks.openai.gateway.config.ResponseMapper
import com.tddworks.openai.gateway.config.TemplateTransform
import com.tddworks.openai.gateway.config.applyAuth
import com.tddworks.openai.gateway.config.VideoRequest
import com.tddworks.openai.gateway.config.VideoTask
import io.ktor.client.HttpClient
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.timeout
import io.ktor.client.request.forms.MultiPartFormDataContent
import io.ktor.client.request.forms.formData
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpMethod
import io.ktor.http.HttpHeaders
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
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
/** Async video generation on the template dialect (submit + poll). */
interface VideoGenerationApi {
    suspend fun submitVideo(request: VideoRequest): VideoTask
    suspend fun retrieveVideoTask(taskId: String): VideoTask
}

/** Text-to-speech on the template dialect, served by the `audioSpeech` transform. */
data class TtsRequest(
    val text: String,
    val model: String? = null,
    val voice: String? = null,
    val format: String? = null,
)

interface TtsApi {
    suspend fun synthesize(request: TtsRequest): ByteArray
}

/** Speech-to-text on the template dialect, served by the `stt` transform (json/raw/multipart, sync or async with `stt.poll`). */
data class SttRequest(
    val audio: ByteArray,
    val mime: String = "audio/wav",
    val model: String? = null,
    val language: String? = null,
)

interface SttApi {
    suspend fun transcribe(request: SttRequest): String
}

class TemplateMediaProvider(
    override val id: String,
    override val name: String,
    override val config: OpenAIProviderConfig,
    private val providerConfig: ProviderConfig,
    private val client: HttpClient? = null,
) : OpenAIProvider, VideoGenerationApi, TtsApi, SttApi {

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    private fun http(): HttpClient = client ?: HttpClient()

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
        if (providerConfig.transforms.containsKey("imagesGenerations.poll")) {
            return generateAsync(request)
        }
        val base = providerConfig.baseUrl.trimEnd('/')
        val runPath = providerConfig.endpoints.imagesGenerations ?: "/ai/run"
        val model = request.model.value.removePrefix("models/")
        val url =
            if (providerConfig.imageModelInPath) {
                "$base$runPath/$model"
            } else {
                "$base$runPath"
            }
        val http = http()
        try {
            val response =
                http.post(url) {
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
            if (client == null) http.close()
        }
    }

    override suspend fun submitVideo(request: VideoRequest): VideoTask {
        val transformed = providerConfig.transforms["videoSubmit"]
        if (transformed != null) {
            return submitVideoTransformed(request, transformed)
        }
        val base = providerConfig.baseUrl.trimEnd('/')
        val path = providerConfig.endpoints.videos
            ?: "/api/v1/services/aigc/video-generation/video-synthesis"
        val http = http()
        try {
            val response =
                http.post(base + path) {
                    timeout { requestTimeoutMillis = providerConfig.timeoutMs }
                    header("Authorization", "Bearer ${providerConfig.auth.apiKey}")
                    header("X-DashScope-Async", "enable")
                    setBody(
                        TextContent(
                            json.encodeToString(
                                buildJsonObject {
                                    put("model", request.model)
                                    put("input", buildJsonObject { put("prompt", request.prompt) })
                                    put(
                                        "parameters",
                                        buildJsonObject {
                                            request.resolution?.let { put("resolution", it) }
                                            request.ratio?.let { put("ratio", it) }
                                            request.duration?.let { put("duration", it) }
                                        },
                                    )
                                },
                            ),
                            ContentType.Application.Json,
                        ),
                    )
                }
            if (!response.status.isSuccess()) {
                throw IllegalStateException("video submit failed: HTTP ${response.status.value} ${response.bodyAsText().take(200)}")
            }
            val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
            return json.decodeFromJsonElement(root["output"] ?: root)
        } finally {
            if (client == null) http.close()
        }
    }

    private fun videoParams(request: VideoRequest): Map<String, String> =
        mapOf(
            "model" to (request.model.ifEmpty { providerConfig.aliases.values.firstOrNull() ?: "" }),
            "prompt" to (request.prompt ?: ""),
            "ratio" to (request.ratio ?: ""),
            "resolution" to (request.resolution ?: ""),
            "duration" to (request.duration?.toString() ?: ""),
        )

    private val pendingVideoParams = mutableMapOf<String, Map<String, String>>()

    private suspend fun submitVideoTransformed(request: VideoRequest, submit: TemplateTransform): VideoTask {
        val params = HashMap(videoParams(request))
        val submitResponse = sendTransform(submit, params)
        val root = json.parseToJsonElement(submitResponse.bodyAsText())
        val mapper = submit.responseMapper
        val id =
            resolveJsonPath(root, mapper?.jobId ?: "$.task_id")?.jsonPrimitive?.contentOrNull
                ?: resolveJsonPath(root, "$.id")?.jsonPrimitive?.contentOrNull
                ?: throw IllegalStateException("videoSubmit: task id not found")
        val status =
            resolveJsonPath(root, "$.task_status")?.jsonPrimitive?.contentOrNull
                ?: resolveJsonPath(root, "$.status")?.jsonPrimitive?.contentOrNull
                ?: "PENDING"
        pendingVideoParams[id] = params
        return VideoTask(taskId = id, status = status.uppercase())
    }

    override suspend fun retrieveVideoTask(taskId: String): VideoTask {
        val pollTransform = providerConfig.transforms["videoPoll"]
        if (pollTransform != null) {
            val params = HashMap(pendingVideoParams[taskId] ?: emptyMap()).apply { put("job_id", taskId) }
            val body = sendTransform(pollTransform, params).bodyAsText()
            if (body.contains("failed") || body.contains("\"error\"")) {
                throw IllegalStateException("video task $taskId failed: ${body.take(200)}")
            }
            val root = runCatching { json.parseToJsonElement(body) }.getOrNull()?.jsonObject
            val url =
                root?.let { r ->
                    resolveJsonPath(r, pollTransform.responseMapper?.from ?: "$.video_url")?.jsonPrimitive?.contentOrNull
                }
            val status =
                root?.let { r ->
                    resolveJsonPath(r, "$.task_status")?.jsonPrimitive?.contentOrNull
                        ?: resolveJsonPath(r, "$.status")?.jsonPrimitive?.contentOrNull
                        ?: resolveJsonPath(r, "$.state")?.jsonPrimitive?.contentOrNull
                } ?: "PENDING"
            if (url == null && status == "PENDING") {
                throw IllegalStateException("video task $taskId still pending")
            }
            return VideoTask(taskId = taskId, status = status.uppercase(), videoUrl = url)
        }
        val base = providerConfig.baseUrl.trimEnd('/')
        val path =
            (providerConfig.endpoints.tasks ?: "/api/v1/tasks") + "/$taskId"
        val http = http()
        try {
            val response =
                http.get(base + path) {
                    timeout { requestTimeoutMillis = providerConfig.timeoutMs }
                    header("Authorization", "Bearer ${providerConfig.auth.apiKey}")
                }
            if (!response.status.isSuccess()) {
                throw IllegalStateException("video poll failed: HTTP ${response.status.value}")
            }
            val root = json.parseToJsonElement(response.bodyAsText()).jsonObject
            return json.decodeFromJsonElement(root["output"] ?: root)
        } finally {
            if (client == null) http.close()
        }
    }

    @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class, kotlin.time.ExperimentalTime::class)
    private suspend fun generateAsync(request: ImageCreate): ListResponse<Image> {
        val submit = providerConfig.transforms["imagesGenerations"]
            ?: throw IllegalStateException("async image generation needs an imagesGenerations transform")
        val params =
            mutableMapOf(
                "prompt" to request.prompt,
                "model" to (request.model.value.ifEmpty { providerConfig.aliases.values.firstOrNull() ?: "" }),
            )
        request.size?.value?.let { params["size"] = it }
        val image =
            runTransform("imagesGenerations", submit, params) { bytes, url ->
                if (url != null) {
                    Image(url = url)
                } else {
                    Image(b64JSON = kotlin.io.encoding.Base64.encode(bytes))
                }
            }
        return ListResponse(created = kotlin.time.Clock.System.now().epochSeconds, data = listOf(image))
    }

    override suspend fun synthesize(request: TtsRequest): ByteArray {
        val transform =
            providerConfig.transforms["audioSpeech"]
                ?: throw UnsupportedOperationException(
                    "dialect ${providerConfig.dialect} has no audioSpeech transform configured",
                )
        val params = mutableMapOf(
            "text" to request.text,
            "model" to (request.model ?: providerConfig.aliases.values.firstOrNull() ?: ""),
            "voice_id" to (request.voice ?: ""),
            "voice" to (request.voice ?: ""),
            "format" to (request.format ?: ""),
        )
        return runTransform("audioSpeech", transform, params) { bytes, url ->
            url?.let { throw IllegalStateException("decode=url is not supported for tts") }
            bytes
        }
    }

    override suspend fun transcribe(request: SttRequest): String {
        val transform =
            providerConfig.transforms["stt"]
                ?: throw UnsupportedOperationException(
                    "dialect ${providerConfig.dialect} has no stt transform configured",
                )
        @OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
        fun b64(data: ByteArray): String = kotlin.io.encoding.Base64.encode(data)
        val params = mutableMapOf(
            "data" to b64(request.audio),
            "mime" to request.mime,
            "model" to (request.model ?: providerConfig.aliases.values.firstOrNull() ?: ""),
            "language" to (request.language ?: ""),
            "format" to "audio",
        )
        return runTransform("stt", transform, params, audioBody = request.audio, audioMime = request.mime) { bytes, url ->
            url?.let { throw IllegalStateException("decode=url is not supported for stt") }
            bytes.decodeToString()
        }
    }

    /**
     * Runs a transform, optionally as a submit/poll job when
     * transforms["<key>.poll"] exists and the submit mapper declares a jobId path.
     */
    private suspend fun <T> runTransform(
        key: String,
        transform: TemplateTransform,
        params: MutableMap<String, String>,
        audioBody: ByteArray? = null,
        audioMime: String = "audio/wav",
        result: suspend (ByteArray, String?) -> T,
    ): T {
        val poll = providerConfig.transforms["$key.poll"]
        val submitResponse = sendTransform(transform, params, audioBody, audioMime)
        val mapper = transform.responseMapper
        if (poll == null || mapper?.jobId == null) {
            val syncResult = decodeMapped(mapper, submitResponse.readRawBytes())
                ?: throw IllegalStateException("sync $key: response mapper found nothing at ${mapper?.from ?: "$"}")
            return result(syncResult, null)
        }
        val root = json.parseToJsonElement(submitResponse.readRawBytes().decodeToString())
        val id =
            resolveJsonPath(root, mapper.jobId)?.jsonPrimitive?.contentOrNull
                ?: throw IllegalStateException("async $key: job id not found at ${mapper.jobId}")
        val pollParams = HashMap(params).apply { put("job_id", id) }
        val deadline = System.currentTimeMillis() + providerConfig.timeoutMs
        while (true) {
            val pollBytes = sendTransform(poll, pollParams, audioBody, audioMime).readRawBytes()
            val body = pollBytes.decodeToString()
            if (body.contains("\"failed\"") || body.contains("\"error\"")) {
                throw IllegalStateException("async $key job $id failed: ${body.take(200)}")
            }
            val decoded: ByteArray? = decodeMapped(poll.responseMapper ?: mapper, pollBytes)
            if (decoded != null) {
                val pollMapper = poll.responseMapper ?: mapper
                val decodedUrl = if (pollMapper.decode == "url") bodyUrlOrNull(body, pollMapper.from) else null
                return result(decoded, decodedUrl)
            }
            if (System.currentTimeMillis() > deadline) {
                throw IllegalStateException("async $key job $id timed out after ${providerConfig.timeoutMs}ms")
            }
            kotlinx.coroutines.delay(2_000)
        }
    }

    private fun bodyUrlOrNull(body: String, from: String): String? =
        runCatching {
            val el = resolveJsonPath(json.parseToJsonElement(body), from)
            el?.jsonPrimitive?.contentOrNull
        }.getOrNull()

    /** Sends a transform request honoring auth scheme, headers, and body format. */
    private suspend fun sendTransform(
        transform: TemplateTransform,
        params: MutableMap<String, String>,
        audioBody: ByteArray? = null,
        audioMime: String = "audio/wav",
    ): io.ktor.client.statement.HttpResponse {
        val http = http()
        return try {
            val response =
                http.request(providerConfig.baseUrl.trimEnd('/') + renderTemplate(transform.path ?: "/", params)) {
                    method = HttpMethod.parse(transform.method)
                    timeout { requestTimeoutMillis = providerConfig.timeoutMs }
                    applyAuth(providerConfig)
                    transform.headers.forEach { (k, v) -> header(k, renderTemplate(v, params)) }
                    when (transform.bodyFormat.lowercase()) {
                        "raw" -> {
                            val raw = renderTemplate(transform.rawTemplate ?: "", params)
                            if (raw.isNotEmpty()) {
                                setBody(TextContent(raw, ContentType.parse(transform.contentType)))
                            } else if (audioBody != null) {
                                setBody(audioBody)
                                header(HttpHeaders.ContentType, audioMime.ifEmpty { "application/octet-stream" })
                            }
                        }
                        "multipart" -> {
                            setBody(
                                MultiPartFormDataContent(
                                    formData {
                                        append("model_id", params["model"] ?: "")
                                        if (params["language"].isNullOrEmpty().not()) {
                                            append("language_code", params["language"]!!)
                                        }
                                        append(
                                            transform.multipartField,
                                            audioBody ?: ByteArray(0),
                                            io.ktor.http.Headers.build {
                                                append(HttpHeaders.ContentType, audioMime)
                                                append(HttpHeaders.ContentDisposition, "filename=\"audio\"")
                                            },
                                        )
                                    },
                                ),
                            )
                        }
                        else -> {
                            transform.requestTemplate?.let { template ->
                                val rendered = renderTemplateJson(template, params)
                                setBody(TextContent(json.encodeToString(rendered), ContentType.Application.Json))
                            }
                        }
                    }
                }
            if (!response.status.isSuccess()) {
                throw IllegalStateException(
                    "transform ${transform.path} failed: HTTP ${response.status.value} ${response.bodyAsText().take(200)}",
                )
            }
            response
        } finally {
            if (client == null) http.close()
        }
    }

    /** Decodes raw response bytes via the mapper; null when the mapped path is absent (poll retry). */
    private fun decodeMapped(mapper: ResponseMapper?, bytes: ByteArray): ByteArray? {
        val decode = mapper?.decode?.lowercase() ?: "raw"
        if (decode == "raw") return bytes
        val body = bytes.decodeToString()
        val root = runCatching { json.parseToJsonElement(body) }.getOrNull() ?: return null
        val resolved = resolveJsonPath(root, mapper?.from ?: "$") ?: return null
        val value = (resolved as? kotlinx.serialization.json.JsonPrimitive)?.content ?: return null
        return when (decode) {
            "base64" -> decodeBase64(value)
            "hex" -> hexToBytes(value)
            "text" -> value.encodeToByteArray()
            "url" -> value.encodeToByteArray()
            else -> throw IllegalStateException("unsupported response mapper decode: $decode")
        }
    }


    private fun ttsParams(request: TtsRequest): Map<String, String> =
        mapOf(
            "text" to request.text,
            "model" to (request.model ?: providerConfig.aliases.values.firstOrNull() ?: ""),
            "voice_id" to (request.voice ?: ""),
            "voice" to (request.voice ?: ""),
            "format" to (request.format ?: ""),
        )

    private suspend fun decodeResponseMapper(
        mapper: ResponseMapper?,
        response: io.ktor.client.statement.HttpResponse,
    ): ByteArray {
        val decode = mapper?.decode?.lowercase() ?: "raw"
        if (decode == "raw") return response.readRawBytes()
        val root = json.parseToJsonElement(response.bodyAsText())
        val resolved = resolveJsonPath(root, mapper?.from ?: "$")
            ?: throw IllegalStateException("response mapper path not found: ${mapper?.from}")
        val value = (resolved as? kotlinx.serialization.json.JsonPrimitive)?.content
            ?: throw IllegalStateException("response mapper path is not a string: ${mapper?.from}")
        return when (decode) {
            "base64" -> decodeBase64(value)
            "hex" -> hexToBytes(value)
            "text" -> value.encodeToByteArray()
            else -> throw IllegalStateException("unsupported response mapper decode: $decode")
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


// ---- template rendering + response mapping (top-level, unit-tested) ----

internal fun renderTemplate(template: String, params: Map<String, String>): String {
    var out = template
    params.forEach { (k, v) -> out = out.replace("{{$k}}", v) }
    return out
}

internal fun renderTemplateJson(template: kotlinx.serialization.json.JsonElement, params: Map<String, String>): kotlinx.serialization.json.JsonElement =
    when (template) {
        is kotlinx.serialization.json.JsonPrimitive -> {
            val raw = template.content
            if (raw.contains("{{")) {
                kotlinx.serialization.json.JsonPrimitive(renderTemplate(raw, params))
            } else {
                template
            }
        }
        is kotlinx.serialization.json.JsonObject ->
            kotlinx.serialization.json.buildJsonObject {
                template.forEach { (k, v) -> put(k, renderTemplateJson(v, params)) }
            }
        is kotlinx.serialization.json.JsonArray ->
            kotlinx.serialization.json.buildJsonArray {
                template.forEach { add(renderTemplateJson(it, params)) }
            }
        else -> template
    }

internal fun resolveJsonPath(root: kotlinx.serialization.json.JsonElement, path: String): kotlinx.serialization.json.JsonElement? {
    if (path == "$") return root
    var current: kotlinx.serialization.json.JsonElement? = root
    path.removePrefix("$").trimStart('.').split('.').forEach { segment ->
        if (current == null) return null
        val key = segment.substringBefore("[").removeSuffix("]")
        val index = Regex("""\[(\d+)]""").find(segment)?.groupValues?.get(1)?.toIntOrNull()
        current =
            when (val el = current) {
                is kotlinx.serialization.json.JsonObject -> el[key]
                is kotlinx.serialization.json.JsonArray -> key.toIntOrNull()?.let { el.getOrNull(it) }
                else -> null
            }
        if (current != null && index != null) {
            current = (current as? kotlinx.serialization.json.JsonArray)?.getOrNull(index)
        }
    }
    return current
}

@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
internal fun decodeBase64(value: String): ByteArray = kotlin.io.encoding.Base64.decode(value)

internal fun hexToBytes(value: String): ByteArray {
    val clean = value.trim()
    require(clean.length % 2 == 0) { "hex string must have even length" }
    return ByteArray(clean.length / 2) { i ->
        clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
    }
}
