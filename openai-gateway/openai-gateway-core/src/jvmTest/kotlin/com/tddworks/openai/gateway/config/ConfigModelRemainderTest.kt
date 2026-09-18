package com.tddworks.openai.gateway.config

import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Unique config-model coverage not present in kiro-cli's CredentialProvidersTest /
 * ProviderConfigSerializationTest (added concurrently): Capabilities voice
 * serialization + defaults, aliases-map round-trip.
 */
class ConfigModelRemainderTest {

    @Test
    fun `capabilities defaults match chatOnly`() {
        assertEquals(Capabilities(), Capabilities.chatOnly())
    }

    @Test
    fun `capabilities voice serializes as enum name and round-trips`() {
        val json = Json { ignoreUnknownKeys = true }
        val encoded = json.encodeToString(Capabilities.serializer(), Capabilities(voice = VoiceMode.TURN_STREAM))
        assertTrue(encoded.contains("\"voice\":\"TURN_STREAM\""))
        val decoded = json.decodeFromString(Capabilities.serializer(), encoded)
        assertEquals(VoiceMode.TURN_STREAM, decoded.voice)
        assertEquals(null, Capabilities(voice = null).voice)
    }

    @Test
    fun `voice modes are distinct`() {
        assertEquals(3, listOf(VoiceMode.REALTIME, VoiceMode.LIVE, VoiceMode.TURN_STREAM).distinct().size)
    }

    @Test
    fun `fromJson round-trips aliases map and capability voice`() {
        val cfg =
            ProviderConfig.fromJson(
                """{"id":"p","baseUrl":"https://x",
                    "aliases":{"my-model":"upstream-model"},
                    "capabilities":{"voice":"LIVE","embeddings":true}}""",
            )
        assertEquals(mapOf("my-model" to "upstream-model"), cfg.aliases)
        assertEquals(VoiceMode.LIVE, cfg.capabilities.voice)
        assertTrue(cfg.capabilities.embeddings)
        assertTrue(!cfg.capabilities.responses)
    }
}