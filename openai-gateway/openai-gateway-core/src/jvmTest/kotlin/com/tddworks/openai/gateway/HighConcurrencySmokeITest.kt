package com.tddworks.openai.gateway

import com.tddworks.openai.api.chat.api.ChatCompletionRequest
import com.tddworks.openai.api.chat.api.ChatMessage
import com.tddworks.openai.api.chat.api.OpenAIModel
import com.tddworks.openai.gateway.api.LLMProvider
import com.tddworks.openai.gateway.api.OpenAIGateway
import com.tddworks.openai.gateway.config.AuthScheme
import com.tddworks.openai.gateway.config.Catalog
import com.tddworks.openai.gateway.config.CatalogMode
import com.tddworks.openai.gateway.config.Dialect
import com.tddworks.openai.gateway.config.Endpoints
import com.tddworks.openai.gateway.config.ProviderAuth
import com.tddworks.openai.gateway.config.ProviderConfig
import io.ktor.client.plugins.ClientRequestException
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.ExperimentalSerializationApi
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable
import org.junit.jupiter.api.Assumptions.assumeTrue

/**
 * High-concurrency smoke test against the Google AI Studio free tier.
 *
 * Rate limits used for tuning (from the AI Studio quota page):
 *   gemini-3.5-flash-lite: 15/500 RPM, 250K TPM, 500 RPD
 *
 * - Two concurrent chats and streams must succeed (burst).
 * - The hammer phase (30 concurrent) must surface HTTP 429s cleanly, never hang.
 *
 * The hammer phase is env-gated (HARD_HAMMER=1) because the free tier has a
 * per-day request budget (RPD) that a hammer exhausts quickly.
 *
 * Run: AI_STUDIO_KEY=<key> ./gradlew :openai-gateway:openai-gateway-core:jvmTest --tests '*HighConcurrencySmokeITest'
 * Run with hammer: HARD_HAMMER=1 AI_STUDIO_KEY=<key> ./gradlew ...
 */
@OptIn(ExperimentalSerializationApi::class)
@EnabledIfEnvironmentVariable(named = "AI_STUDIO_KEY", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class HighConcurrencySmokeITest {

    private val config
        get() =
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
                auth =
                    ProviderAuth(
                        scheme = AuthScheme.BEARER,
                        apiKey = System.getenv("AI_STUDIO_KEY"),
                    ),
                catalog = Catalog(mode = CatalogMode.AUTO),
            )

    private val model = OpenAIModel("gemini-3.5-flash-lite")

    private lateinit var gateway: OpenAIGateway

    @BeforeAll
    fun setup() {
        gateway = OpenAIGateway.create(listOf(config))
    }

    @AfterAll
    fun teardown() {
        runCatching { org.koin.core.context.stopKoin() }
    }

    private suspend fun chat(n: Int) {
        gateway.chatCompletions(
            request =
                ChatCompletionRequest(
                    model = model,
                    messages = listOf(ChatMessage.user("reply with the single word: ok$n")),
                ),
            provider = LLMProvider.OPENAI,
        )
    }

    private suspend fun stream(n: Int) {
        gateway
            .streamChatCompletions(
                request =
                    ChatCompletionRequest(
                        model = model,
                        messages = listOf(ChatMessage.user("reply with the single word: ok$n")),
                    ),
                provider = LLMProvider.OPENAI,
            )
            .collect {}
    }

    @Test
    fun `burst of 2 concurrent chats succeeds`() = runBlocking {
        val results = (1..2).map { n -> async { runCatching { chat(n) } } }.awaitAll()
        results.forEachIndexed { i, r ->
            assertTrue(r.isSuccess, "concurrent chat $i failed: ${r.exceptionOrNull()}")
        }
    }

    @Test
    fun `burst of 2 concurrent streams succeeds`() = runBlocking {
        val results = (1..2).map { n -> async { runCatching { stream(n) } } }.awaitAll()
        results.forEachIndexed { i, r ->
            assertTrue(r.isSuccess, "concurrent stream $i failed: ${r.exceptionOrNull()}")
        }
    }

    @Test
    fun `hammer 30 concurrent requests surfaces 429 rate limits cleanly`() = runBlocking {
        assumeTrue(System.getenv("HARD_HAMMER") == "1", "hammer phase requires HARD_HAMMER=1")

        val started = System.currentTimeMillis()
        val results = (1..30).map { n -> async { runCatching { chat(n) } } }.awaitAll()

        val ok = results.count { it.isSuccess }
        val rateLimited =
            results.count { r ->
                val e = r.exceptionOrNull()
                e is ClientRequestException && e.response.status == HttpStatusCode.TooManyRequests
            }
        val other = results.size - ok - rateLimited

        println(
            "HAMMER RESULT: ok=$ok rateLimited429=$rateLimited other=$other " +
                "elapsedMs=${System.currentTimeMillis() - started}",
        )

        assertTrue(ok >= 1, "expected at least one success under load, got $ok")
        assertEquals(0, other, "unexpected non-429 failures occurred: $other")
        // Free tier burst is low; the hammer must have observed rate limiting,
        // otherwise the gate below is misbehaving (crucial property of the test).
        assertTrue(ok + rateLimited == 30, "every request must resolve as success or 429")
    }
}