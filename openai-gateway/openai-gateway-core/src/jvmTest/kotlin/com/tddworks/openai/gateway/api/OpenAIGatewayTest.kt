package com.tddworks.openai.gateway.api

import com.tddworks.openai.gateway.api.internal.from
import com.tddworks.openai.gateway.config.Dialect
import com.tddworks.openai.gateway.config.ProviderConfig
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Config-driven facade coverage: create() interprets config rows (enabled filter,
 * provider resolution by id/name, missing provider null).
 */
class OpenAIGatewayTest {

    @AfterEach
    fun stopKoin() {
        runCatching { org.koin.core.context.stopKoin() }
    }

    private fun config(id: String, enabled: Boolean = true, dialect: Dialect = Dialect.OPENAI_COMPAT) =
        ProviderConfig(
            id = id,
            name = id,
            enabled = enabled,
            dialect = dialect,
            baseUrl = "https://127.0.0.1:9",
            auth = com.tddworks.openai.gateway.config.ProviderAuth(apiKey = "k"),
        )

    @Test
    fun `create builds gateway from config rows`() {
        val gateway = OpenAIGateway.create(listOf(config("a"), config("b")))
        assertEquals(setOf("a", "b"), gateway.getProviders().map { it.id }.toSet())
        assertNotNull(gateway.getProvider("a"))
        assertNotNull(gateway.getProvider("b"))
    }

    @Test
    fun `create filters disabled configs`() {
        val gateway = OpenAIGateway.create(listOf(config("a"), config("off", enabled = false)))
        assertEquals(listOf("a"), gateway.getProviders().map { it.id })
    }

    @Test
    fun `create with empty list yields no providers`() {
        val gateway = OpenAIGateway.create(emptyList())
        assertTrue(gateway.getProviders().isEmpty())
        assertNull(gateway.getProvider("nope"))
    }

    @Test
    fun `getProvider resolves by id and returns null for unknown`() {
        val gateway = OpenAIGateway.create(listOf(config("a")))
        assertNotNull(gateway.getProvider("a"))
        assertNull(gateway.getProvider("missing"))
    }

    @Test
    fun `config-driven gateway exposes provider metadata`() {
        val gateway = OpenAIGateway.create(listOf(config("a")))
        val p = gateway.getProvider("a")!!
        assertEquals("a", p.id)
        assertEquals("a", p.name)
    }
}