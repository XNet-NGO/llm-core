package com.tddworks.openai.gateway.api.internal

import com.tddworks.openai.gateway.config.Dialect
import com.tddworks.openai.gateway.config.ProviderAuth
import com.tddworks.openai.gateway.config.ProviderConfig
import com.tddworks.openai.gateway.config.ResponseMapper
import com.tddworks.openai.gateway.config.TemplateTransform
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import com.tddworks.openai.api.images.api.ImageCreate
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import com.tddworks.openai.gateway.config.VideoRequest
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Base64

/**
 * TEMPLATE-dialect transforms: placeholder rendering (path/headers/body), per-scheme
 * auth via applyAuth, response mappers (raw/base64/hex/text), error paths. The live
 * path is covered separately by TemplateTtsSmokeITest (env-gated).
 */
class TemplateTransformsTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ---- pure renderers ----

    @Test
    fun `renderTemplate substitutes all params`() {
        assertEquals(
            "/v1/tts/rachel",
            renderTemplate("/v1/tts/{{voice_id}}", mapOf("voice_id" to "rachel", "text" to "hi")),
        )
        assertEquals("no placeholders", renderTemplate("no placeholders", mapOf("x" to "y")))
        assertEquals("missing {{nope}}", renderTemplate("missing {{nope}}", mapOf()))
    }

    @Test
    fun `renderTemplateJson substitutes nested string values only`() {
        val template =
            buildJsonObject {
                put("text", JsonPrimitive("{{text}}"))
                put("n", JsonPrimitive(42))
                put("nested", buildJsonObject { put("voice", JsonPrimitive("{{voice}}")) })
                put("model_id", JsonPrimitive("eleven_v3"))
            }
        val out = renderTemplateJson(template, mapOf("text" to "hello", "voice" to "r"))
        assertEquals("hello", out.jsonObject["text"]?.jsonPrimitive?.content)
        assertEquals("r", out.jsonObject["nested"]!!.jsonObject["voice"]?.jsonPrimitive?.content)
        assertEquals("eleven_v3", out.jsonObject["model_id"]?.jsonPrimitive?.content)
        assertEquals(42, out.jsonObject["n"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `resolveJsonPath walks objects and arrays`() {
        val root =
            Json.parseToJsonElement(
                """{"data":{"audio":"ABC","list":[{"x":1},{"x":2}]}}""",
            )
        assertEquals(root, resolveJsonPath(root, "$"))
        assertEquals("ABC", (resolveJsonPath(root, "$.data.audio") as JsonPrimitive).content)
        assertEquals(2, resolveJsonPath(root, "$.data.list[1].x")?.jsonPrimitive?.content?.toInt() ?: -1)
        assertNull(resolveJsonPath(root, "$.data.missing"))
        assertNull(resolveJsonPath(root, "$.data.audio.more"))
    }

    @Test
    fun `hexToBytes decodes hex strings`() {
        assertTrue(hexToBytes("68656c6c6f").contentEquals("hello".encodeToByteArray()))
        assertTrue(hexToBytes("").isEmpty())
        assertThrows(IllegalArgumentException::class.java) { hexToBytes("abc") }
    }

    @Test
    fun `decodeBase64 decodes standard base64`() {
        assertTrue(decodeBase64("aGVsbG8=").contentEquals("hello".encodeToByteArray()))
    }

    // ---- synthesize end-to-end with MockEngine ----

    private fun ttsProvider(
        transform: TemplateTransform,
        auth: ProviderAuth = ProviderAuth(apiKey = "sk"),
        handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ): TemplateMediaProvider {
        val config =
            ProviderConfig(
                id = "eleven",
                baseUrl = "https://voice.test",
                dialect = Dialect.TEMPLATE,
                auth = auth,
                transforms = mapOf("audioSpeech" to transform),
            )
        return TemplateMediaProvider(
            id = config.id,
            name = config.id,
            config = legacyConfig(config),
            providerConfig = config,
            client = HttpClient(MockEngine(handler)),
        )
    }

    private fun ttsHandler(
        path: MutableList<String> = mutableListOf(),
        auth: MutableList<String?> = mutableListOf(),
        body: MutableList<String?> = mutableListOf(),
    ): suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData =
        { request ->
            path += request.url.encodedPath
            auth += request.headers["Authorization"]
            val text = request.body as? io.ktor.http.content.TextContent
            body += text?.text
            val mp3 = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte())
            respond(mp3, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "audio/mpeg"))
        }

    @Test
    fun `synthesize renders path and template with per-scheme auth`() = runTest {
        val paths = mutableListOf<String>()
        val auths = mutableListOf<String?>()
        val bodies = mutableListOf<String?>()
        val provider =
            ttsProvider(
                transform =
                    TemplateTransform(
                        path = "/v1/text-to-speech/{{voice_id}}",
                        headers = mapOf("Accept" to "audio/mpeg"),
                        requestTemplate =
                            buildJsonObject {
                                put("text", JsonPrimitive("{{text}}"))
                                put("model_id", JsonPrimitive("{{model}}"))
                            },
                        responseMapper = ResponseMapper(from = "$", decode = "raw"),
                    ),
                auth = ProviderAuth(scheme = com.tddworks.openai.gateway.config.AuthScheme.X_API_KEY, apiKey = "sk-1", keyHeader = "xi-api-key"),
                handler = ttsHandler(paths, auths, bodies),
            )
        val audio =
            provider.synthesize(
                TtsRequest(text = "hello", model = "eleven_v3", voice = "21m00Tcm4TlvDq8ikWAM"),
            )
        assertTrue(audio.contentEquals(byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte())))
        assertEquals("/v1/text-to-speech/21m00Tcm4TlvDq8ikWAM", paths.single())
        // X_API_KEY scheme -> xi-api-key header, no bearer
        assertNull(auths.single())
        assertTrue(bodies.single()!!.contains("\"text\":\"hello\""))
        assertTrue(bodies.single()!!.contains("\"model_id\":\"eleven_v3\""))
    }

    @Test
    fun `synthesize uses bearer scheme and headers when configured`() = runTest {
        val paths = mutableListOf<String>()
        val auths = mutableListOf<String?>()
        val provider =
            ttsProvider(
                transform =
                    TemplateTransform(
                        path = "/tts",
                        headers = mapOf("Accept" to "audio/mpeg"),
                        requestTemplate = buildJsonObject { put("text", JsonPrimitive("{{text}}")) },
                        responseMapper = ResponseMapper(from = "$", decode = "raw"),
                    ),
                auth = ProviderAuth(apiKey = "sk-bearer"),
                handler = ttsHandler(paths, auths),
            )
        provider.synthesize(TtsRequest(text = "x"))
        assertEquals("Bearer sk-bearer", auths.single())
    }

    @Test
    fun `synthesize base64 mapper decodes nested value`() = runTest {
        val b64 = Base64.getEncoder().encodeToString("wav-data".encodeToByteArray())
        val provider =
            ttsProvider(
                transform =
                    TemplateTransform(
                        path = "/tts",
                        responseMapper = ResponseMapper(from = "$.data.audio", decode = "base64"),
                    ),
                handler = { request ->
                    respond("""{"data":{"audio":"$b64"}}""")
                },
            )
        val audio = provider.synthesize(TtsRequest(text = "x"))
        assertTrue(audio.contentEquals("wav-data".encodeToByteArray()))
    }

    @Test
    fun `synthesize hex mapper decodes dashscope style output`() = runTest {
        val provider =
            ttsProvider(
                transform =
                    TemplateTransform(
                        path = "/tts",
                        responseMapper = ResponseMapper(from = "$.data.audio", decode = "hex"),
                    ),
                handler = { respond("""{"data":{"audio":"68656c6c6f"}}""") },
            )
        val audio = provider.synthesize(TtsRequest(text = "x"))
        assertTrue(audio.contentEquals("hello".encodeToByteArray()))
    }

    @Test
    fun `synthesize text mapper returns utf8 bytes`() = runTest {
        val provider =
            ttsProvider(
                transform =
                    TemplateTransform(
                        path = "/tts",
                        responseMapper = ResponseMapper(from = "$.transcript", decode = "text"),
                    ),
                handler = { respond("""{"transcript":"hi there"}""") },
            )
        val audio = provider.synthesize(TtsRequest(text = "x"))
        assertTrue(audio.contentEquals("hi there".encodeToByteArray()))
    }

    @Test
    fun `synthesize without transform throws`() = runTest {
        val config =
            ProviderConfig(
                id = "plain",
                baseUrl = "https://voice.test",
                dialect = Dialect.TEMPLATE,
                transforms = emptyMap(),
            )
        val provider =
            TemplateMediaProvider(
                id = "plain",
                name = "plain",
                config = legacyConfig(config),
                providerConfig = config,
                client = HttpClient(MockEngine { respond(byteArrayOf(1)) }),
            )
        val e = runCatching { provider.synthesize(TtsRequest(text = "x")) }.exceptionOrNull()
        assertTrue(e is UnsupportedOperationException)
        assertTrue(e!!.message!!.contains("audioSpeech"))
    }

    @Test
    fun `synthesize missing mapper path throws`() = runTest {
        val provider =
            ttsProvider(
                transform = TemplateTransform(path = "/tts", responseMapper = ResponseMapper(from = "$.nope", decode = "base64")),
                handler = { respond("""{"other":1}""") },
            )
        val e = runCatching { provider.synthesize(TtsRequest(text = "x")) }.exceptionOrNull()
        assertTrue(e is IllegalStateException && e.message!!.contains("$.nope"))
    }

    @Test
    fun `synthesize non-2xx throws with http code`() = runTest {
        val provider =
            ttsProvider(
                transform = TemplateTransform(path = "/tts", responseMapper = ResponseMapper(from = "$", decode = "raw")),
                handler = { respond("""{"detail":"bad voice"}""", HttpStatusCode.NotFound) },
            )
        val e = runCatching { provider.synthesize(TtsRequest(text = "x")) }.exceptionOrNull()
        assertTrue(e is IllegalStateException && e.message!!.contains("HTTP 404"))
    }

    @Test
    fun `model falls back to first alias when not provided`() = runTest {
        var body: String? = null
        val provider =
            ttsProvider(
                transform =
                    TemplateTransform(
                        path = "/tts",
                        requestTemplate = buildJsonObject { put("model", JsonPrimitive("{{model}}")) },
                    ),
                handler = { request ->
                    body = (request.body as? io.ktor.http.content.TextContent)?.text
                    respond("""{"ok":1}""")
                },
            )
        // aliases map lives on the provider config - rebuild with alias
        val config =
            ProviderConfig(
                id = "eleven",
                baseUrl = "https://voice.test",
                dialect = Dialect.TEMPLATE,
                aliases = mapOf("tts" to "eleven_v3"),
                transforms = mapOf("audioSpeech" to TemplateTransform(
                    path = "/tts",
                    requestTemplate = buildJsonObject { put("model", JsonPrimitive("{{model}}")) },
                )),
            )
        val p =
            TemplateMediaProvider(
                id = config.id, name = config.id, config = legacyConfig(config),
                providerConfig = config,
                client = HttpClient(MockEngine { request ->
                    body = (request.body as? io.ktor.http.content.TextContent)?.text
                    respond("""{"ok":1}""")
                }),
            )
        p.synthesize(TtsRequest(text = "x", model = null))
        assertTrue(body!!.contains("\"model\":\"eleven_v3\""))
    }
}
/**
 * New engine capabilities: async submit/poll jobs (AssemblyAI/BFL/Runway style),
 * raw SSML bodies (Azure Speech), raw-audio STT (Deepgram), multipart STT
 * (ElevenLabs), transform-driven video + async images.
 */
class TemplateTransformsAsyncTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun provider(
        transforms: Map<String, com.tddworks.openai.gateway.config.TemplateTransform>,
        timeoutMs: Long = 5_000,
        handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ): TemplateMediaProvider {
        val config =
            com.tddworks.openai.gateway.config.ProviderConfig(
                id = "p",
                baseUrl = "https://svc.test",
                dialect = com.tddworks.openai.gateway.config.Dialect.TEMPLATE,
                timeoutMs = timeoutMs,
                transforms = transforms,
            )
        return TemplateMediaProvider(
            id = config.id, name = config.id, config = legacyConfig(config),
            providerConfig = config,
            client = HttpClient(MockEngine(handler)),
        )
    }

    // ---- async submit/poll ----

    @Test
    fun `async stt submits then polls to transcript`() = runTest {
        var calls = 0
        val p =
            provider(
                transforms =
                    mapOf(
                        "stt" to
                            com.tddworks.openai.gateway.config.TemplateTransform(
                                path = "/v2/transcript",
                                requestTemplate = buildJsonObject { put("audio_url", JsonPrimitive("https://a.test/x.wav")) },
                                responseMapper =
                                    com.tddworks.openai.gateway.config.ResponseMapper(
                                        from = "$.id",
                                        decode = "text",
                                        jobId = "$.id",
                                    ),
                            ),
                        "stt.poll" to
                            com.tddworks.openai.gateway.config.TemplateTransform(
                                path = "/v2/transcript/{{job_id}}",
                                responseMapper = com.tddworks.openai.gateway.config.ResponseMapper(from = "$.text", decode = "text"),
                            ),
                    ),
                timeoutMs = 20_000,
            ) { request ->
                calls++
                when {
                    request.url.encodedPath.contains("/poll") && calls > 1 -> respond("""{"id":"j1","status":"completed","text":"hello transcription"}""")
                    request.url.encodedPath.contains("transcript/j1") -> respond("""{"id":"j1","status":"completed","text":"hello transcription"}""")
                    else -> respond("""{"id":"j1","status":"queued"}""")
                }
            }
        val text = p.transcribe(SttRequest(audio = "fake".encodeToByteArray()))
        assertEquals("hello transcription", text)
    }

    @Test
    fun `async job failure surfaces server error`() = runTest {
        val p =
            provider(
                transforms =
                    mapOf(
                        "stt" to
                            com.tddworks.openai.gateway.config.TemplateTransform(
                                path = "/submit",
                                responseMapper = com.tddworks.openai.gateway.config.ResponseMapper(from = "$.id", decode = "text", jobId = "$.id"),
                            ),
                        "stt.poll" to
                            com.tddworks.openai.gateway.config.TemplateTransform(
                                path = "/poll/{{job_id}}",
                                responseMapper = com.tddworks.openai.gateway.config.ResponseMapper(from = "$.text", decode = "text"),
                            ),
                    ),
                timeoutMs = 5_000,
            ) { request ->
                if (request.url.encodedPath.contains("submit")) respond("""{"id":"j1"}""")
                else respond("""{"status":"failed","error":"audio too short"}""")
            }
        val e = runCatching { p.transcribe(SttRequest(audio = "noise".encodeToByteArray())) }.exceptionOrNull()
        assertTrue(e is IllegalStateException && e.message!!.contains("failed"))
    }

    @Test
    fun `async job times out while pending`() = runTest {
        val p =
            provider(
                transforms =
                    mapOf(
                        "stt" to
                            com.tddworks.openai.gateway.config.TemplateTransform(
                                path = "/submit",
                                responseMapper = com.tddworks.openai.gateway.config.ResponseMapper(from = "$.id", decode = "text", jobId = "$.id"),
                            ),
                        "stt.poll" to
                            com.tddworks.openai.gateway.config.TemplateTransform(
                                path = "/poll/{{job_id}}",
                                responseMapper = com.tddworks.openai.gateway.config.ResponseMapper(from = "$.text", decode = "text"),
                            ),
                    ),
                timeoutMs = 2_500,
            ) { request ->
                if (request.url.encodedPath.contains("submit")) respond("""{"id":"j1"}""")
                else respond("""{"status":"processing"}""")
            }
        val started = System.currentTimeMillis()
        val e = runCatching { p.transcribe(SttRequest(audio = "a".encodeToByteArray())) }.exceptionOrNull()
        val elapsed = System.currentTimeMillis() - started
        assertTrue(e is IllegalStateException && e.message!!.contains("timed out"))
        assertTrue(elapsed >= 2_000, "expected at least one 2s poll delay, took ${elapsed}ms")
    }

    // ---- body formats ----

    @Test
    fun `raw ssml body renders template with content type`() = runTest {
        val p =
            provider(
                transforms =
                    mapOf(
                        "audioSpeech" to
                            com.tddworks.openai.gateway.config.TemplateTransform(
                                path = "/cognitiveservices/v1",
                                rawTemplate = "<speak><voice name=\"{{voice_id}}\">{{text}}</voice></speak>",
                                bodyFormat = "raw",
                                contentType = "application/ssml+xml",
                                responseMapper = com.tddworks.openai.gateway.config.ResponseMapper(decode = "raw"),
                            ),
                    ),
            ) { request ->
                val body = (request.body as? io.ktor.http.content.TextContent)?.text ?: ""
                if (!body.contains("<speak>") || !body.contains("hello there")) {
                    respond("bad body", HttpStatusCode.BadRequest)
                } else {
                    respond(byteArrayOf(1, 2, 3), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "audio/mpeg"))
                }
            }
        val audio = p.synthesize(TtsRequest(text = "hello there", voice = "en-US-Ava"))
        assertTrue(audio.contentEquals(byteArrayOf(1, 2, 3)))
    }

    @Test
    fun `raw audio stt sends bytes body with mime`() = runTest {
        val p =
            provider(
                transforms =
                    mapOf(
                        "stt" to
                            com.tddworks.openai.gateway.config.TemplateTransform(
                                path = "/v1/listen?model=nova-3",
                                bodyFormat = "raw",
                                responseMapper =
                                    com.tddworks.openai.gateway.config.ResponseMapper(
                                        from = "$.results.channels[0].alternatives[0].transcript",
                                        decode = "text",
                                    ),
                            ),
                    ),
            ) { request ->
                val ct = request.headers["Content-Type"]
                if (ct == null || !ct.contains("audio/wav")) respond("wrong ct", HttpStatusCode.BadRequest)
                respond("""{"results":{"channels":[{"alternatives":[{"transcript":"hi transcript"}]}]}}""")
            }
        val text = p.transcribe(SttRequest(audio = byteArrayOf(1, 2, 3), mime = "audio/wav"))
        assertEquals("hi transcript", text)
    }

    @Test
    fun `multipart stt sends file part with mime`() = runTest {
        val p =
            provider(
                transforms =
                    mapOf(
                        "stt" to
                            com.tddworks.openai.gateway.config.TemplateTransform(
                                path = "/v1/speech-to-text",
                                requestTemplate = buildJsonObject { put("model_id", JsonPrimitive("scribev1")) },
                                bodyFormat = "multipart",
                                multipartField = "file",
                                responseMapper = com.tddworks.openai.gateway.config.ResponseMapper(from = "$.text", decode = "text"),
                            ),
                    ),
            ) { request ->
                if (request.body !is io.ktor.client.request.forms.MultiPartFormDataContent) {
                    respond("expected multipart body", HttpStatusCode.BadRequest)
                } else {
                    respond("""{"text":"multipart transcript"}""")
                }
            }
        val text = p.transcribe(SttRequest(audio = byteArrayOf(9, 9), mime = "audio/mpeg"))
        assertEquals("multipart transcript", text)
    }

    @Test
    fun `json stt embeds base64 data and language`() = runTest {
        var body: String? = null
        val p =
            provider(
                transforms =
                    mapOf(
                        "stt" to
                            com.tddworks.openai.gateway.config.TemplateTransform(
                                path = "/v1/speech-to-text",
                                requestTemplate =
                                    buildJsonObject {
                                        put("file", JsonPrimitive("{{data}}"))
                                        put("language_code", JsonPrimitive("{{language}}"))
                                    },
                                responseMapper = com.tddworks.openai.gateway.config.ResponseMapper(from = "$.text", decode = "text"),
                            ),
                    ),
            ) { request ->
                body = (request.body as? io.ktor.http.content.TextContent)?.text
                respond("""{"text":"json transcript"}""")
            }
        val text = p.transcribe(SttRequest(audio = "abc".encodeToByteArray(), language = "en"))
        assertEquals("json transcript", text)
        assertTrue(body!!.contains("\"file\":\"YWJj\""))
        assertTrue(body!!.contains("\"language_code\":\"en\""))
    }

    // ---- async images (BFL style) ----

    @Test
    fun `async image gen submits then polls for url`() = runTest {
        val p =
            provider(
                transforms =
                    mapOf(
                        "imagesGenerations" to
                            com.tddworks.openai.gateway.config.TemplateTransform(
                                path = "/{{model}}",
                                requestTemplate = buildJsonObject { put("prompt", JsonPrimitive("{{prompt}}")) },
                                responseMapper =
                                    com.tddworks.openai.gateway.config.ResponseMapper(
                                        from = "$.id",
                                        decode = "text",
                                        jobId = "$.id",
                                    ),
                            ),
                        "imagesGenerations.poll" to
                            com.tddworks.openai.gateway.config.TemplateTransform(
                                path = "/get_result",
                                responseMapper =
                                    com.tddworks.openai.gateway.config.ResponseMapper(
                                        from = "$.result.sample",
                                        decode = "url",
                                    ),
                            ),
                    ),
                timeoutMs = 15_000,
            ) { request ->
                when {
                    request.url.encodedPath.contains("get_result") ->
                        respond("""{"id":"g1","status":"Ready","result":{"sample":"https://img.test/1.png"}}""")
                    else -> respond("""{"id":"g1","status":"Pending"}""")
                }
            }
        val out = p.generate(ImageCreate(prompt = "a cat", model = com.tddworks.openai.api.chat.api.OpenAIModel("flux-pro-1.1")))
        assertEquals("https://img.test/1.png", out.data[0].url)
    }

    // ---- video transforms (Runway style) ----

    @Test
    fun `video submit and poll via transforms`() = runTest {
        val p =
            provider(
                transforms =
                    mapOf(
                        "videoSubmit" to
                            com.tddworks.openai.gateway.config.TemplateTransform(
                                path = "/text_to_video",
                                requestTemplate =
                                    buildJsonObject {
                                        put("promptText", JsonPrimitive("{{prompt}}"))
                                        put("ratio", JsonPrimitive("{{ratio}}"))
                                    },
                                responseMapper =
                                    com.tddworks.openai.gateway.config.ResponseMapper(
                                        from = "$.id",
                                        decode = "text",
                                        jobId = "$.id",
                                    ),
                            ),
                        "videoPoll" to
                            com.tddworks.openai.gateway.config.TemplateTransform(
                                path = "/tasks/{{job_id}}",
                                responseMapper = com.tddworks.openai.gateway.config.ResponseMapper(from = "$.output[0]", decode = "text"),
                            ),
                    ),
            ) { request ->
                when {
                    request.url.encodedPath.contains("tasks/") ->
                        respond("""{"id":"t1","status":"SUCCEEDED","output":["https://v.test/1.mp4"]}""")
                    else -> respond("""{"id":"t1","status":"PENDING"}""")
                }
            }
        val submitted = p.submitVideo(VideoRequest(model = "gen4.5", prompt = "waves", ratio = "16:9"))
        assertEquals("PENDING", submitted.status)
        val done = p.retrieveVideoTask("t1")
        assertEquals("SUCCEEDED", done.status)
        assertEquals("https://v.test/1.mp4", done.videoUrl)
    }

    @Test
    fun `video poll failure throws with server message`() = runTest {
        val p =
            provider(
                transforms =
                    mapOf(
                        "videoSubmit" to
                            com.tddworks.openai.gateway.config.TemplateTransform(
                                path = "/text_to_video",
                                responseMapper = com.tddworks.openai.gateway.config.ResponseMapper(from = "$.id", decode = "text", jobId = "$.id"),
                            ),
                        "videoPoll" to
                            com.tddworks.openai.gateway.config.TemplateTransform(
                                path = "/tasks/{{job_id}}",
                                responseMapper = com.tddworks.openai.gateway.config.ResponseMapper(from = "$.output[0]", decode = "text"),
                            ),
                    ),
            ) { request ->
                if (request.url.encodedPath.contains("tasks/")) respond("""{"status":"failed","error":"content moderation"}""")
                else respond("""{"id":"t9"}""")
            }
        p.submitVideo(VideoRequest(model = "m", prompt = "p"))
        val e = runCatching { p.retrieveVideoTask("t9") }.exceptionOrNull()
        assertTrue(e is IllegalStateException && e.message!!.contains("failed"))
    }
}
