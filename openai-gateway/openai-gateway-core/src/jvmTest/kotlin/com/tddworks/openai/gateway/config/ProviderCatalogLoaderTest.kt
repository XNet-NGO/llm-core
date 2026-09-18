package com.tddworks.openai.gateway.config

import io.ktor.client.request.HttpRequestBuilder
import com.tddworks.openai.gateway.config.applyAuth
import io.ktor.client.request.url
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unit tests for the config-driven catalog path. Network-dependent branches use a
 * localhost port that refuses connections so the loader degrades to empty lists
 * without touching the network (the graceful-failure path is a real behavior).
 */
class ProviderCatalogLoaderTest {

    private val freeInferenceBody =
        """
        {"data":[
          {"id":"gemini-3.6-flash","context_length":1048576,"max_output_length":8192,
           "input_modalities":["text","image"],"output_modalities":["text"],
           "supported_features":["reasoning","tools"]},
          {"id":"qwen3-flash","context_length":131072,"max_output_length":32768,
           "input_modalities":["text","image","audio"],"output_modalities":["text"],
           "supported_features":[]}
        ]}
        """.trimIndent()

    private val camelCaseBody =
        """
        {"data":[
          {"id":"m","contextLength":100,"maxOutputLength":50,
           "inputModalities":["text"],"outputModalities":["text"],
           "supportedFeatures":["x"]}
        ]}
        """.trimIndent()

    private fun config(
        mode: CatalogMode = CatalogMode.AUTO,
        path: String = "/models",
        models: List<CatalogModel> = emptyList(),
        auth: ProviderAuth = ProviderAuth(scheme = AuthScheme.BEARER, apiKey = "k"),
    ) =
        ProviderConfig(
            id = "p",
            baseUrl = "http://127.0.0.1:9",
            auth = auth,
            endpoints = Endpoints(models = null),
            catalog = Catalog(mode = mode, path = path, models = models),
        )

    // ---- parseCatalog ----

    @Test
    fun `parseCatalog reads freeinference shape`() {
        val models = ProviderCatalogLoader.parseCatalog(freeInferenceBody)
        assertEquals(2, models.size)
        val g = models.first { it.id == "gemini-3.6-flash" }
        assertEquals(1_048_576, g.contextLength)
        assertEquals(8192, g.maxOutputLength)
        assertEquals(listOf("text", "image"), g.inputModalities)
        assertEquals(listOf("reasoning", "tools"), g.supportedFeatures)
    }

    @Test
    fun `parseCatalog reads camelCase shape`() {
        val models = ProviderCatalogLoader.parseCatalog(camelCaseBody)
        assertEquals(1, models.size)
        assertEquals(100, models[0].contextLength)
        assertEquals(50, models[0].maxOutputLength)
    }

    @Test
    fun `parseCatalog tolerates missing data and malformed json`() {
        assertEquals(emptyList<CatalogModel>(), ProviderCatalogLoader.parseCatalog("{}"))
        assertEquals(emptyList<CatalogModel>(), ProviderCatalogLoader.parseCatalog("{"))
        assertEquals(emptyList<CatalogModel>(), ProviderCatalogLoader.parseCatalog("[]"))
        assertEquals(emptyList<CatalogModel>(), ProviderCatalogLoader.parseCatalog(""))
    }

    @Test
    fun `parseCatalog skips entries without id`() {
        val body = """{"data":[{"context_length":1},{"id":"ok","context_length":2}]}"""
        val models = ProviderCatalogLoader.parseCatalog(body)
        assertEquals(1, models.size)
        assertEquals("ok", models[0].id)
    }

    // ---- resolveCatalog ----

    @Test
    fun `static mode never fetches`() = runTest {
        val cfg = config(mode = CatalogMode.STATIC, models = listOf(CatalogModel(id = "s")))
        val resolved = ProviderCatalogLoader.resolveCatalog(cfg)
        assertEquals(cfg.catalog, resolved)
    }

    @Test
    fun `auto mode uses cached entries without fetching`() = runTest {
        val cfg = config(mode = CatalogMode.AUTO)
        val cached = listOf(CatalogModel(id = "cached"))
        val resolved = ProviderCatalogLoader.resolveCatalog(cfg, cached)
        assertEquals(listOf(CatalogModel(id = "cached")), resolved.models)
    }

    @Test
    fun `auto mode degrades to empty on fetch failure`() = runTest {
        val cfg = config(mode = CatalogMode.AUTO)
        val resolved = ProviderCatalogLoader.resolveCatalog(cfg)
        assertTrue(resolved.models.isEmpty())
    }

    @Test
    fun `merged mode combines static plus cached distinct by id`() = runTest {
        val cfg =
            config(
                mode = CatalogMode.MERGED,
                models = listOf(CatalogModel(id = "a"), CatalogModel(id = "dup")),
            )
        val resolved =
            ProviderCatalogLoader.resolveCatalog(
                cfg,
                listOf(CatalogModel(id = "dup"), CatalogModel(id = "b")),
            )
        assertEquals(listOf("a", "dup", "b"), resolved.models.map { it.id })
    }

    @Test
    fun `merged mode falls back to static when fetch fails`() = runTest {
        val cfg = config(mode = CatalogMode.MERGED, models = listOf(CatalogModel(id = "a")))
        val resolved = ProviderCatalogLoader.resolveCatalog(cfg)
        assertEquals(listOf("a"), resolved.models.map { it.id })
    }

    // ---- applyAuth (request builder inspection, no network) ----

    private fun builder(): HttpRequestBuilder = HttpRequestBuilder().apply { url("https://up.test/x") }

    @Test
    fun `applyAuth bearer attaches Authorization only when key present`() {
        val b = builder()
        b.applyAuth(config(auth = ProviderAuth(scheme = AuthScheme.BEARER, apiKey = "sk-1")))
        assertEquals("Bearer sk-1", b.headers["Authorization"])

        val empty = builder()
        empty.applyAuth(config(auth = ProviderAuth(scheme = AuthScheme.BEARER, apiKey = "")))
        assertNull(empty.headers["Authorization"])
    }

    @Test
    fun `applyAuth x-api-key uses keyHeader and falls back to default`() {
        val b = builder()
        b.applyAuth(
            config(auth = ProviderAuth(scheme = AuthScheme.X_API_KEY, apiKey = "k", keyHeader = "x-custom")),
        )
        assertEquals("k", b.headers["x-custom"])

        val def = builder()
        def.applyAuth(config(auth = ProviderAuth(scheme = AuthScheme.X_API_KEY, apiKey = "k")))
        assertEquals("k", def.headers["X-API-Key"])
    }

    @Test
    fun `applyAuth query scheme appends query param`() {
        val b = builder()
        b.applyAuth(config(auth = ProviderAuth(scheme = AuthScheme.QUERY, apiKey = "qk", queryParam = "key")))
        assertEquals("qk", b.url.parameters["key"])
    }

    @Test
    fun `applyAuth none attaches nothing`() {
        val b = builder()
        b.applyAuth(config(auth = ProviderAuth(scheme = AuthScheme.NONE, apiKey = "ignored")))
        assertTrue(b.headers.isEmpty())
        assertTrue(b.url.parameters.isEmpty())
    }

    @Test
    fun `applyAuth extraHeaders and queryParams always applied`() {
        val b = builder()
        b.applyAuth(
            config(
                auth =
                    ProviderAuth(
                        scheme = AuthScheme.BEARER,
                        apiKey = "k",
                        extraHeaders = mapOf("anthropic-version" to "2023-06-01"),
                        queryParams = mapOf("api-version" to "2024-10-21"),
                    ),
            ),
        )
        assertEquals("2023-06-01", b.headers["anthropic-version"])
        assertEquals("2024-10-21", b.url.parameters["api-version"])
    }

    // ---- signer application on catalog GET ----

    @Test
    fun `catalog fetch invokes registered SIGV4 signer once`() = runTest {
        var calls = 0
        CredentialProviders.register(AuthScheme.SIGV4) { auth, ctx ->
            calls++
            assertEquals("GET", ctx.method)
            assertEquals("127.0.0.1", ctx.host)
            assertEquals("/models", ctx.path)
            SignedCredentials(headers = mapOf("Authorization" to "AWS4-HMAC-SHA256 x"))
        }
        try {
            val cfg =
                config(
                    auth =
                        ProviderAuth(
                            scheme = AuthScheme.SIGV4,
                            apiKey = "AKIA",
                            secretKey = "sec",
                            region = "us-east-1",
                            service = "bedrock",
                        ),
                )
            val models = ProviderCatalogLoader.fetchModels(cfg)
            assertEquals(1, calls)
            assertTrue(models.isEmpty())
        } finally {
            CredentialProviders.clear()
        }
    }

    @Test
    fun `catalog fetch does not consult signer for bearer scheme`() = runTest {
        var calls = 0
        CredentialProviders.register(AuthScheme.SIGV4) { _, _ -> calls++; SignedCredentials() }
        try {
            ProviderCatalogLoader.fetchModels(config())
            assertEquals(0, calls)
        } finally {
            CredentialProviders.clear()
        }
    }
}