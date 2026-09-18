package com.tddworks.openai.gateway.api.internal

import com.tddworks.openai.api.images.api.ImageCreate
import com.tddworks.openai.api.chat.api.OpenAIModel
import com.tddworks.openai.gateway.config.Dialect
import com.tddworks.openai.gateway.config.ProviderConfig
import com.tddworks.openai.gateway.config.VideoRequest
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Template-media provider (D7): URL assembly (model-in-path), auth headers,
 * json/qwen/multipart input styles, url/json/raw output styles, error paths,
 * async video submit + poll. All network via MockEngine.
 */
class TemplateMediaProviderTest {

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    private fun provider(
        imageConfig: ProviderConfig = ProviderConfig(id = "m", baseUrl = "https://media.test", dialect = Dialect.TEMPLATE),
        client: HttpClient,
    ): TemplateMediaProvider =
        TemplateMediaProvider(
            id = imageConfig.id,
            name = imageConfig.name.ifBlank { imageConfig.id },
            config = legacyConfig(imageConfig),
            providerConfig = imageConfig,
            client = client,
        )

    private fun capturingClient(
        handler: suspend MockRequestHandleScope.(io.ktor.client.request.HttpRequestData) -> io.ktor.client.request.HttpResponseData,
    ): HttpClient = HttpClient(MockEngine(handler))

    private val b64 = "aGVsbG8=" // "hello"

    // ---- URL & auth ----

    @Test
    fun `generate appends model to path when imageModelInPath`() = runTest {
        var path: String? = null
        var method: HttpMethod? = null
        var auth: String? = null
        val cfg =
            ProviderConfig(
                id = "m",
                baseUrl = "https://media.test",
                dialect = Dialect.TEMPLATE,
                auth = com.tddworks.openai.gateway.config.ProviderAuth(apiKey = "sk-1"),
                endpoints = com.tddworks.openai.gateway.config.Endpoints(imagesGenerations = "/ai/run"),
                imageModelInPath = true,
            )
        val p =
            provider(imageConfig = cfg, client = capturingClient { req ->
                path = req.url.encodedPath
                method = req.method
                auth = req.headers["Authorization"]
                respond("""{"result":{"image":"$b64"}}""")
            })
        p.generate(ImageCreate(prompt = "x", model = OpenAIModel("flux-2-klein")))
        assertEquals("/ai/run/flux-2-klein", path)
        assertEquals(HttpMethod.Post, method)
        assertEquals("Bearer sk-1", auth)
    }

    @Test
    fun `generate omits model from path when imageModelInPath false`() = runTest {
        var path: String? = null
        val cfg =
            ProviderConfig(
                id = "m",
                baseUrl = "https://media.test",
                dialect = Dialect.TEMPLATE,
                endpoints = com.tddworks.openai.gateway.config.Endpoints(imagesGenerations = "/generate"),
                imageModelInPath = false,
            )
        val p = provider(imageConfig = cfg, client = capturingClient { req ->
            path = req.url.encodedPath
            respond("""{"result":{"image":"$b64"}}""")
        })
        p.generate(ImageCreate(prompt = "x", model = OpenAIModel("m1")))
        assertEquals("/generate", path)
    }

    @Test
    fun `generate sends extraHeaders`() = runTest {
        var extra: String? = null
        val cfg =
            ProviderConfig(
                id = "m",
                baseUrl = "https://media.test",
                dialect = Dialect.TEMPLATE,
                auth = com.tddworks.openai.gateway.config.ProviderAuth(apiKey = "k", extraHeaders = mapOf("X-Num" to "7")),
            )
        val p = provider(imageConfig = cfg, client = capturingClient { req ->
            extra = req.headers["X-Num"]
            respond("""{"result":{"image":"$b64"}}""")
        })
        p.generate(ImageCreate(prompt = "x"))
        assertEquals("7", extra)
    }

    // ---- input styles ----

    @Test
    fun `json input sends prompt body`() = runTest {
        var body: String? = null
        var contentType: String? = null
        val cfg =
            ProviderConfig(
                id = "m",
                baseUrl = "https://media.test",
                dialect = Dialect.TEMPLATE,
                imageInput = "json",
            )
        val p = provider(imageConfig = cfg, client = capturingClient { req ->
            body = (req.body as io.ktor.http.content.TextContent).text
            contentType = req.body.contentType.toString()
            respond("""{"result":{"image":"$b64"}}""")
        })
        p.generate(ImageCreate(prompt = "hello"))
        assertTrue(body!!.contains("\"prompt\":\"hello\""))
        assertTrue(contentType!!.contains("application/json"))
    }

    @Test
    fun `qwen input nests messages and converts size`() = runTest {
        var body: String? = null
        val cfg =
            ProviderConfig(
                id = "m",
                baseUrl = "https://media.test",
                dialect = Dialect.TEMPLATE,
                imageInput = "qwen",
            )
        val p = provider(imageConfig = cfg, client = capturingClient { req ->
            body = (req.body as io.ktor.http.content.TextContent).text
            respond("""{"result":{"image":"$b64"}}""")
        })
        p.generate(ImageCreate(prompt = "a cat", model = OpenAIModel("models/qwen-image-2.0-pro")))
        val root = json.parseToJsonElement(body!!).jsonObject
        assertEquals("qwen-image-2.0-pro", root["model"]?.jsonPrimitive?.content)
        assertEquals("1024*1024", root["parameters"]?.jsonObject?.get("size")?.jsonPrimitive?.content)
        val content = root["input"]?.jsonObject?.get("messages")?.jsonArray?.first()?.jsonObject
        assertEquals("user", content?.get("role")?.jsonPrimitive?.content)
    }

    @Test
    fun `multipart input is default`() = runTest {
        var contentType: String? = null
        val p = provider(client = capturingClient { req ->
            contentType = req.body.contentType.toString()
            respond("""{"result":{"image":"$b64"}}""")
        })
        p.generate(ImageCreate(prompt = "x"))
        assertTrue(contentType!!.startsWith("multipart/form-data"))
    }

    // ---- output styles ----

    @Test
    fun `json output reads result image`() = runTest {
        val p = provider(client = capturingClient { respond("""{"result":{"image":"$b64"}}""") })
        val out = p.generate(ImageCreate(prompt = "x"))
        assertEquals(b64, out.data[0].b64JSON)
        assertNull(out.data[0].url)
    }

    @Test
    fun `json output throws when result image missing`() = runTest {
        val p = provider(client = capturingClient { respond("""{"result":{"no":"image"}}""") })
        val e = runCatching { p.generate(ImageCreate(prompt = "x")) }.exceptionOrNull()
        assertTrue(e is IllegalStateException && e.message!!.contains("result.image"))
    }

    @Test
    fun `url output reads output choices image url`() = runTest {
        val cfg =
            ProviderConfig(
                id = "m",
                baseUrl = "https://media.test",
                dialect = Dialect.TEMPLATE,
                imageOutput = "url",
            )
        val p = provider(imageConfig = cfg, client = capturingClient { req ->
            respond("""{"output":{"choices":[{"message":{"content":[{"image":"https://img.test/1.png"}]}}]}}""")
        })
        val out = p.generate(ImageCreate(prompt = "x"))
        assertEquals("https://img.test/1.png", out.data[0].url)
        assertNull(out.data[0].b64JSON)
    }

    @Test
    fun `url output throws when image url missing`() = runTest {
        val cfg =
            ProviderConfig(
                id = "m",
                baseUrl = "https://media.test",
                dialect = Dialect.TEMPLATE,
                imageOutput = "url",
            )
        val p = provider(imageConfig = cfg, client = capturingClient { req ->
            respond("""{"output":{"choices":[{"message":{"content":[{"text":"no image"}]}}]}}""")
        })
        val e = runCatching { p.generate(ImageCreate(prompt = "x")) }.exceptionOrNull()
        assertTrue(e is IllegalStateException && e.message!!.contains("output.choices[0].message.content[0].image"))
    }

    @Test
    fun `raw output base64-encodes binary body`() = runTest {
        val bytes = "hello".encodeToByteArray()
        val cfg =
            ProviderConfig(
                id = "m",
                baseUrl = "https://media.test",
                dialect = Dialect.TEMPLATE,
                imageOutput = "raw",
            )
        val p = provider(imageConfig = cfg, client = capturingClient { req ->
            respond(bytes, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "image/png"))
        })
        val out = p.generate(ImageCreate(prompt = "x"))
        assertEquals(b64, out.data[0].b64JSON)
    }

    @Test
    fun `non-2xx image response throws with code`() = runTest {
        val p = provider(client = capturingClient { req ->
            respond("oops", HttpStatusCode.BadGateway)
        })
        val e = runCatching { p.generate(ImageCreate(prompt = "x")) }.exceptionOrNull()
        assertTrue(e is IllegalStateException && e.message!!.contains("HTTP 502"))
    }

    // ---- video ----

    @Test
    fun `submitVideo posts qwen body with async header and decodes output`() = runTest {
        var path: String? = null
        var asyncHeader: String? = null
        var body: String? = null
        var auth: String? = null
        val cfg =
            ProviderConfig(
                id = "m",
                baseUrl = "https://media.test",
                dialect = Dialect.TEMPLATE,
                auth = com.tddworks.openai.gateway.config.ProviderAuth(apiKey = "sk-2"),
            )
        val p = provider(imageConfig = cfg, client = capturingClient { req ->
            path = req.url.encodedPath
            asyncHeader = req.headers["X-DashScope-Async"]
            auth = req.headers["Authorization"]
            body = (req.body as io.ktor.http.content.TextContent).text
            respond("""{"output":{"task_id":"t-1","task_status":"PENDING"}}""")
        })
        val task = p.submitVideo(VideoRequest(model = "wan3.0-video", prompt = "waves", resolution = "480p", ratio = "16:9", duration = 5))
        assertEquals("/api/v1/services/aigc/video-generation/video-synthesis", path)
        assertEquals("enable", asyncHeader)
        assertEquals("Bearer sk-2", auth)
        assertTrue(body!!.contains("\"model\":\"wan3.0-video\""))
        assertTrue(body!!.contains("\"resolution\":\"480p\""))
        assertTrue(body!!.contains("\"duration\":5"))
        assertEquals("t-1", task.taskId)
        assertEquals("PENDING", task.status)
    }

    @Test
    fun `submitVideo omits null parameters`() = runTest {
        var body: String? = null
        val p = provider(client = capturingClient { req ->
            body = (req.body as io.ktor.http.content.TextContent).text
            respond("""{"output":{"task_id":"t","task_status":"PENDING"}}""")
        })
        p.submitVideo(VideoRequest(model = "m", prompt = "p"))
        assertTrue(!body!!.contains("resolution"))
        assertTrue(!body!!.contains("ratio"))
    }

    @Test
    fun `submitVideo non-2xx throws`() = runTest {
        val p = provider(client = capturingClient { respond("denied", HttpStatusCode.Unauthorized) })
        val e = runCatching { p.submitVideo(VideoRequest(model = "m", prompt = "p")) }.exceptionOrNull()
        assertTrue(e is IllegalStateException && e.message!!.contains("HTTP 401"))
    }

    @Test
    fun `retrieveVideoTask polls tasks path with id and decodes output`() = runTest {
        var path: String? = null
        var auth: String? = null
        val cfg =
            ProviderConfig(
                id = "m",
                baseUrl = "https://media.test",
                dialect = Dialect.TEMPLATE,
                endpoints = com.tddworks.openai.gateway.config.Endpoints(tasks = "/api/v1/tasks"),
                auth = com.tddworks.openai.gateway.config.ProviderAuth(apiKey = "sk-3"),
            )
        val p = provider(imageConfig = cfg, client = capturingClient { req ->
            path = req.url.encodedPath
            auth = req.headers["Authorization"]
            respond("""{"output":{"task_id":"t-9","task_status":"SUCCEEDED","video_url":"https://v.test/9.mp4"}}""")
        })
        val task = p.retrieveVideoTask("t-9")
        assertEquals("/api/v1/tasks/t-9", path)
        assertEquals("Bearer sk-3", auth)
        assertEquals("SUCCEEDED", task.status)
        assertEquals("https://v.test/9.mp4", task.videoUrl)
    }

    @Test
    fun `retrieveVideoTask decodes root when no output key`() = runTest {
        val p = provider(client = capturingClient { req ->
            respond("""{"task_id":"t-1","task_status":"PENDING"}""")
        })
        val task = p.retrieveVideoTask("t-1")
        assertEquals("t-1", task.taskId)
    }

    @Test
    fun `retrieveVideoTask non-2xx throws`() = runTest {
        val p = provider(client = capturingClient { respond("boom", HttpStatusCode.InternalServerError) })
        val e = runCatching { p.retrieveVideoTask("t") }.exceptionOrNull()
        assertTrue(e is IllegalStateException && e.message!!.contains("HTTP 500"))
    }

    // ---- capability gating ----

    @Test
    fun `chat and completions throw on template dialect`() = runTest {
        val p = provider(client = capturingClient { respond("{}") })
        val err = runCatching { p.chatCompletions(com.tddworks.openai.api.chat.api.ChatCompletionRequest(messages = emptyList(), model = OpenAIModel("m"))) }.exceptionOrNull()
            assertTrue(err is UnsupportedOperationException)
        val err2 = runCatching { p.completions(com.tddworks.openai.api.legacy.completions.api.CompletionRequest(prompt = "x")) }.exceptionOrNull()
            assertTrue(err2 is UnsupportedOperationException)
    }
}