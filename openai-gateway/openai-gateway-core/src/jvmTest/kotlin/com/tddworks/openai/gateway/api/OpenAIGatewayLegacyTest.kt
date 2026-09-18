package com.tddworks.openai.gateway.api

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Legacy create() overloads (static-key, lambda-key, defaults) — the Koin DI boot path.
 * Creation does not hit the network; assertions are provider-set level.
 */
class OpenAIGatewayLegacyTest {

    @AfterEach
    fun stopKoin() {
        runCatching { org.koin.core.context.stopKoin() }
    }

    @Test
    fun `create with static keys boots all four providers`() {
        val gateway =
            OpenAIGateway.create(
                openAIKey = "k1",
                anthropicKey = "k2",
                geminiKey = "k3",
            )
        val ids = gateway.getProviders().map { it.id }.toSet()
        assertTrue(ids.contains("openai"))
        assertTrue(ids.contains("anthropic"))
        assertTrue(ids.contains("ollama"))
        assertTrue(ids.contains("gemini"))
        assertNotNull(gateway.getProvider("openai"))
    }

    @Test
    fun `create with dynamic config lambdas boots providers`() {
        val gateway =
            OpenAIGateway.create(
                openAIKey = { "k1" },
                openAIBaseUrl = { "https://openai.test" },
                geminiKey = { "k3" },
                geminiBaseUrl = { "https://gemini.test" },
            )
        assertEquals(4, gateway.getProviders().size)
    }

    @Test
    fun `create with default args boots gateway`() {
        val gateway = OpenAIGateway.create(openAIKey = "k1")
        assertNotNull(gateway)
        assertTrue(gateway.getProviders().isNotEmpty())
    }

    @Test
    fun `config-driven create coexists after legacy boot`() {
        // Legacy boot then config boot in the same JVM must not collide (Koin restarted).
        OpenAIGateway.create(openAIKey = "k1")
        stopKoin()
        val gateway =
            OpenAIGateway.create(
                listOf(
                    com.tddworks.openai.gateway.config.ProviderConfig(
                        id = "cfg-a",
                        baseUrl = "https://127.0.0.1:9",
                    ),
                ),
            )
        assertEquals(listOf("cfg-a"), gateway.getProviders().map { it.id })
    }
}