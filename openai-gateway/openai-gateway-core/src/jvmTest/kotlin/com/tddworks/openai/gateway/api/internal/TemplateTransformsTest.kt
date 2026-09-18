package com.tddworks.openai.gateway.api.internal

import com.tddworks.openai.gateway.config.Dialect
import com.tddworks.openai.gateway.config.ProviderAuth
import com.tddworks.openai.gateway.config.ProviderConfig
import com.tddworks.openai.gateway.config.ResponseMapper
import com.tddworks.openai.gateway.config.TemplateTransform
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
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