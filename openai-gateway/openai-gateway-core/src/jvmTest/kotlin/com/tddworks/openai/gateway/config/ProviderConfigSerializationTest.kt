package com.tddworks.openai.gateway.config

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ProviderConfigSerializationTest {

    @Test
    fun `round-trips a full ProviderConfig via fromJson toJson`() {
        val original =
            ProviderConfig(
                id = "p",
                name = "Provider",
                dialect = Dialect.OPENAI_COMPAT,
                baseUrl = "https://api.test/v1",
                auth = ProviderAuth(scheme = AuthScheme.BEARER, apiKey = "sk"),
                aliases = mapOf("fast" to "gpt-4o-mini"),
            )
        val json = ProviderConfig.toJson(original)
        val back = ProviderConfig.fromJson(json)
        assertEquals(original.id, back.id)
        assertEquals(original.dialect, back.dialect)
        assertEquals(original.baseUrl, back.baseUrl)
        assertEquals("gpt-4o-mini", back.aliases["fast"])
        assertEquals(AuthScheme.BEARER, back.auth.scheme)
    }

    @Test
    fun `applies defaults for a minimal config`() {
        val c = ProviderConfig.fromJson("""{"id":"m","baseUrl":"https://x"}""")
        assertEquals("m", c.name) // name defaults to id
        assertTrue(c.enabled)
        assertEquals(Dialect.OPENAI_COMPAT, c.dialect)
        assertEquals(AuthScheme.BEARER, c.auth.scheme)
        assertEquals(StreamFormat.SSE, c.streaming)
        assertEquals(120_000, c.timeoutMs)
        assertTrue(c.aliases.isEmpty())
    }

    @Test
    fun `ignores unknown keys leniently`() {
        val c =
            ProviderConfig.fromJson(
                """{"id":"m","baseUrl":"https://x","futureField":123,"nested":{"a":1}}""",
            )
        assertEquals("m", c.id)
    }

    @Test
    fun `parses extended SIGV4 and OAUTH2 auth fields`() {
        val c =
            ProviderConfig.fromJson(
                """{"id":"b","baseUrl":"https://bedrock","auth":{"scheme":"SIGV4",
                   "apiKey":"AKIA","secretKey":"s","region":"us-east-1","service":"bedrock"}}""",
            )
        assertEquals(AuthScheme.SIGV4, c.auth.scheme)
        assertEquals("us-east-1", c.auth.region)
        assertEquals("bedrock", c.auth.service)
        assertEquals("s", c.auth.secretKey)
    }

    @Test
    fun `Capabilities all and chatOnly factories`() {
        val all = Capabilities.all()
        assertTrue(all.chat && all.embeddings && all.tts && all.stt && all.imagesGenerate)
        val chat = Capabilities.chatOnly()
        assertTrue(chat.chat)
        assertFalse(chat.embeddings)
        assertFalse(chat.tts)
    }

    @Test
    fun `Catalog defaults`() {
        val cat = Catalog()
        assertEquals(CatalogMode.AUTO, cat.mode)
        assertEquals(3600, cat.ttlSeconds)
        assertEquals("/models", cat.path)
        assertTrue(cat.models.isEmpty())
    }

    @Test
    fun `CatalogModel round-trips modality fields`() {
        val m =
            Json.decodeFromString(
                CatalogModel.serializer(),
                """{"id":"x","contextLength":8192,"inputModalities":["text","image"],
                   "supportedFeatures":["reasoning"]}""",
            )
        assertEquals("x", m.id)
        assertEquals(8192, m.contextLength)
        assertEquals(listOf("text", "image"), m.inputModalities)
        assertEquals(listOf("reasoning"), m.supportedFeatures)
    }
}
