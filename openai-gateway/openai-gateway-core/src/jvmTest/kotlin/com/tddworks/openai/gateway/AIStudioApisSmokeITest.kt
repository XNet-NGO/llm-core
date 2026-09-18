package com.tddworks.openai.gateway

import com.tddworks.openai.gateway.api.OpenAIProvider
import com.tddworks.openai.gateway.api.internal.ConfigOpenAIProvider
import com.tddworks.openai.gateway.api.internal.from
import com.tddworks.openai.gateway.config.AuthScheme
import com.tddworks.openai.gateway.config.BatchRequest
import com.tddworks.openai.gateway.config.Capabilities
import com.tddworks.openai.gateway.config.Dialect
import com.tddworks.openai.gateway.config.EmbeddingRequest
import com.tddworks.openai.gateway.config.Endpoints
import com.tddworks.openai.gateway.config.ProviderAuth
import com.tddworks.openai.gateway.config.ProviderConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Embeddings + Batch API + Interactions API smoke test against Google AI Studio.
 * Requires AI_STUDIO_KEY (AQ.). The provider is built config-driven via the factory
 * (no gateway/Koin), exercising: embeddings (gemini-embedding-2),
 * interactions (gemini-3.6-flash stateful), and the OpenAI-compatible batch flow.
 *
 * Run: AI_STUDIO_KEY=<key> ./gradlew :openai-gateway:openai-gateway-core:jvmTest --tests '*AIStudioApisSmokeITest'
 */
@EnabledIfEnvironmentVariable(named = "AI_STUDIO_KEY", matches = ".+")
class AIStudioApisSmokeITest {

    private val config =
        ProviderConfig(
            id = "ai-studio",
            name = "Google AI Studio (free tier)",
            dialect = Dialect.OPENAI_COMPAT,
            baseUrl = "https://generativelanguage.googleapis.com",
            endpoints =
                Endpoints(
                    chat = "/v1beta/openai/chat/completions",
                    models = "/v1beta/openai/models",
                    embeddings = "/v1beta/openai/embeddings",
                    interactions = "/v1beta/interactions",
                    batches = "/v1beta/openai/batches",
                    files = "/v1beta/openai/files",
                ),
            auth = ProviderAuth(scheme = AuthScheme.BEARER, apiKey = System.getenv("AI_STUDIO_KEY")),
            capabilities =
                Capabilities(
                    chat = true,
                    embeddings = true,
                    responses = true,
                    imagesGenerate = false,
                ),
        )

    private val provider: ConfigOpenAIProvider
        get() = OpenAIProvider.from(config) as ConfigOpenAIProvider

    @Test
    fun `embeddings via config-driven provider`() = runBlocking {
        val response =
            provider.embeddings(
                EmbeddingRequest(
                    model = "gemini-embedding-2",
                    input = listOf("hello world", "llm-core"),
                ),
            )
        assertEquals(2, response.data.size)
        val dims = response.data[0].embedding.size
        assertTrue(dims >= 1024, "expected >=1024 dims, got $dims")
        println("EMBEDDINGS OK: ${response.data.size} vectors, $dims dims")
    }

    @Test
    fun `batch api upload create and poll`() = runBlocking {
        val jsonl =
            listOf(
                """{"custom_id":"r1","method":"POST","url":"/v1/chat/completions","body":{"model":"gemini-3.6-flash","messages":[{"role":"user","content":"Say OK"}]}}""",
                """{"custom_id":"r2","method":"POST","url":"/v1/chat/completions","body":{"model":"gemini-3.6-flash","messages":[{"role":"user","content":"Say OK too"}]}}""",
            ).joinToString("\n").toByteArray()

        val file =
            try {
                provider.uploadBatchFile("batch.jsonl", jsonl)
            } catch (e: io.ktor.client.plugins.ClientRequestException) {
                // AI Studio's OpenAI-compat surface exposes /batches but not /files
                // upload on this key/tier — probe it as a documented limitation.
                if (e.response.status == io.ktor.http.HttpStatusCode.NotFound) {
                    println("BATCH UPLOAD UNAVAILABLE (404): OpenAI-compat /files not served for this key")
                    throw org.opentest4j.TestAbortedException("file upload endpoint unavailable (HTTP 404)")
                }
                throw e
            }
        assertNotNull(file.id, "file upload should return an id")
        println("BATCH FILE OK: ${file.id} purpose=${file.purpose}")

        val batch = provider.createBatch(BatchRequest(inputFileId = file.id, endpoint = "/v1/chat/completions"))
        assertNotNull(batch.id, "batch create should return an id")
        println("BATCH CREATED: ${batch.id} status=${batch.status}")

        var finalBatch = batch
        repeat(6) {
            if (finalBatch.status == "completed" || finalBatch.status == "failed" || finalBatch.status == "cancelled") {
                return@repeat
            }
            delay(10_000)
            finalBatch = provider.retrieveBatch(batch.id)
            println("BATCH POLL: ${finalBatch.status}")
        }
        assertTrue(
            finalBatch.status == "completed" || finalBatch.status == "processing" || finalBatch.status == "in_progress",
            "batch should reach a terminal-ish state, got ${finalBatch.status}",
        )
        if (finalBatch.status == "completed") {
            assertNotNull(finalBatch.outputFileId, "completed batch must have an output file")
        }
        println("BATCH FINAL: ${finalBatch.status} output=${finalBatch.outputFileId}")
    }

    @Test
    fun `interactions api stateful session`() = runBlocking {
        val response =
            provider.interact(
                com.tddworks.openai.gateway.config.InteractionRequest(
                    model = "gemini-3.6-flash",
                    input = JsonPrimitive("Say OK"),
                ),
            )
        assertEquals("completed", response.status)
        assertTrue(response.id.startsWith("v1_") || response.id.isNotBlank(), "expected interaction id")
        assertTrue(
            (response.usage?.totalOutputTokens ?: 0L) >= 1L,
            "expected output tokens, got ${response.usage}",
        )
        println(
            "INTERACTIONS OK: id=${response.id} status=${response.status} outputTokens=${response.usage?.totalOutputTokens}",
        )

        val retrieved = provider.retrieveInteraction(response.id)
        assertEquals("completed", retrieved.status)
        println("INTERACTIONS RETRIEVED: ${retrieved.id} status=${retrieved.status}")
    }
}
