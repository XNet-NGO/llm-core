package com.tddworks.openai.gateway.api.internal

import com.tddworks.openai.gateway.api.OpenAIProvider
import com.tddworks.openai.gateway.config.CredentialProviders
import com.tddworks.openai.gateway.config.Dialect
import com.tddworks.openai.gateway.config.ProviderAuth
import com.tddworks.openai.gateway.config.ProviderConfig
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.koin.core.context.GlobalContext

@OptIn(ExperimentalSerializationApi::class)
class ConfigProviderFromDialectTest {

    // BEDROCK auto-registers the default SigV4 signer; clear it so it does not leak between tests.
    @AfterEach fun tearDownCreds() = CredentialProviders.clear()

    private fun config(dialect: Dialect, id: String = "p") =
        ProviderConfig(
            id = id,
            dialect = dialect,
            baseUrl = "https://upstream.test",
            auth = ProviderAuth(apiKey = "k"),
        )

    @Test
    fun `builds a config-driven Anthropic provider for ANTHROPIC dialect`() {
        val provider = OpenAIProvider.from(config(Dialect.ANTHROPIC, id = "claude"))
        assertInstanceOf(AnthropicOpenAIProvider::class.java, provider)
        assertEquals("claude", provider.id)
    }

    @Test
    fun `builds a config-driven Gemini provider for GEMINI dialect`() {
        val provider = OpenAIProvider.from(config(Dialect.GEMINI, id = "gemini-native"))
        assertInstanceOf(GeminiOpenAIProvider::class.java, provider)
        assertEquals("gemini-native", provider.id)
    }

    @Test
    fun `carries anthropic-version from extraHeaders when provided`() {
        val cfg =
            config(Dialect.ANTHROPIC).copy(
                auth =
                    ProviderAuth(
                        apiKey = "k",
                        extraHeaders = mapOf("anthropic-version" to "2024-10-22"),
                    ),
            )
        val provider = OpenAIProvider.from(cfg) as AnthropicOpenAIProvider
        assertEquals("2024-10-22", provider.config.anthropicVersion())
    }

    @Test
    fun `builds a D1-surface provider for BEDROCK dialect (OpenAI-compatible runtime)`() {
        val provider = OpenAIProvider.from(config(Dialect.BEDROCK, id = "bedrock"))
        assertInstanceOf(ConfigOpenAIProvider::class.java, provider)
        assertEquals("bedrock", provider.id)
    }

    @Test
    fun `still throws for dialects not available in this build`() {
        assertThrows<IllegalArgumentException> {
            OpenAIProvider.from(config(Dialect.VOICE_REALTIME))
        }
    }

    @Test
    fun `multiple config-driven Gemini providers coexist without global Koin state`() {
        // Regression guard: Gemini.instance() must not start a global Koin context.
        // Building two providers previously threw KoinApplicationAlreadyStartedException.
        val a = OpenAIProvider.from(config(Dialect.GEMINI, id = "g1"))
        val b = OpenAIProvider.from(config(Dialect.GEMINI, id = "g2"))
        assertInstanceOf(GeminiOpenAIProvider::class.java, a)
        assertInstanceOf(GeminiOpenAIProvider::class.java, b)
        assertEquals("g1", a.id)
        assertEquals("g2", b.id)
        assertNull(GlobalContext.getOrNull(), "from(GEMINI) must not start a global Koin context")
    }
}
