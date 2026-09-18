package com.tddworks.openai.gateway

import com.tddworks.openai.api.chat.api.ChatCompletionRequest
import com.tddworks.openai.api.chat.api.ChatMessage
import com.tddworks.openai.api.chat.api.OpenAIModel
import com.tddworks.openai.gateway.api.LLMProvider
import com.tddworks.openai.gateway.api.OpenAIGateway
import com.tddworks.openai.gateway.config.AuthScheme
import com.tddworks.openai.gateway.config.Catalog
import com.tddworks.openai.gateway.config.CatalogMode
import com.tddworks.openai.gateway.config.Endpoints
import com.tddworks.openai.gateway.config.Dialect
import com.tddworks.openai.gateway.config.ProviderAuth
import com.tddworks.openai.gateway.config.ProviderConfig
import com.tddworks.openai.gateway.config.ProviderCatalogLoader
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Config-driven gateway smoke test against the Google AI Studio free tier
 * (OpenAI-compatible surface). Requires AI_STUDIO_KEY in the environment.
 *
 * Run: AI_STUDIO_KEY=<key> ./gradlew :openai-gateway:openai-gateway-core:jvmTest --tests '*AIStudioSmokeITest'
 */
@OptIn(ExperimentalSerializationApi::class)
@EnabledIfEnvironmentVariable(named = "AI_STUDIO_KEY", matches = ".+")
class AIStudioSmokeITest {

    private val config =
        ProviderConfig(
            id = "openai",
            name = "Google AI Studio (free tier)",
            dialect = Dialect.OPENAI_COMPAT,
            baseUrl = "https://generativelanguage.googleapis.com",
            endpoints =
                Endpoints(
                    chat = "/v1beta/openai/chat/completions",
                    models = "/v1beta/openai/models",
                ),
            auth = ProviderAuth(scheme = AuthScheme.BEARER, apiKey = System.getenv("AI_STUDIO_KEY")),
            catalog = Catalog(mode = CatalogMode.AUTO),
        )

    private val model = OpenAIModel("gemini-3.6-flash")

    @Test
    fun `config-driven gateway chat, stream, and catalog against ai studio`() = runBlocking {
        // One gateway instance — one Koin context.
        val gateway = OpenAIGateway.create(listOf(config))
        assertTrue(gateway.getProvider("openai") != null, "config provider should be registered")

        // Non-streaming chat
        val completion =
            gateway.chatCompletions(
                request =
                    ChatCompletionRequest(
                        model = model,
                        messages = listOf(ChatMessage.user("reply with exactly: OK")),
                    ),
                provider = LLMProvider.OPENAI,
            )
        val chatText =
            completion.choices.firstOrNull()?.message?.let {
                (it as? ChatMessage.AssistantMessage)?.content
            }
        assertEquals("OK", chatText?.trim())

        // Streaming chat
        val chunks =
            gateway
                .streamChatCompletions(
                    request =
                        ChatCompletionRequest(
                            model = model,
                            messages = listOf(ChatMessage.user("count from 1 to 3")),
                        ),
                    provider = LLMProvider.OPENAI,
                )
                .toList()
        val streamText = chunks.joinToString("") { it.content() }
        assertTrue(streamText.contains("1") && streamText.contains("3"), "streamed text was: $streamText")

        // Live catalog auto-fetch
        val models = ProviderCatalogLoader.fetchModels(config)
        assertTrue(models.isNotEmpty(), "catalog should contain models")
        assertTrue(models.any { it.id.contains("gemini") }, "expected gemini models, got: ${models.take(3)}")
    }
}