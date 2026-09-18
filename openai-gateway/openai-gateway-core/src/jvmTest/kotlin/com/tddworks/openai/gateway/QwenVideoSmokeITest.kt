package com.tddworks.openai.gateway

import com.tddworks.openai.gateway.api.OpenAIProvider
import com.tddworks.openai.gateway.api.internal.TemplateMediaProvider
import com.tddworks.openai.gateway.api.internal.VideoGenerationApi
import com.tddworks.openai.gateway.api.internal.from
import com.tddworks.openai.gateway.config.AuthScheme
import com.tddworks.openai.gateway.config.Capabilities
import com.tddworks.openai.gateway.config.Dialect
import com.tddworks.openai.gateway.config.Endpoints
import com.tddworks.openai.gateway.config.ProviderAuth
import com.tddworks.openai.gateway.config.ProviderConfig
import com.tddworks.openai.gateway.config.VideoRequest
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Async video generation (wan3.0-video) on the Qwen native surface, driven by the
 * config-driven template dialect: submit (X-DashScope-Async: enable) then poll the
 * task until SUCCEEDED. Requires QWEN_KEY.
 *
 * Run: QWEN_KEY=<key> ./gradlew :openai-gateway:openai-gateway-core:jvmTest --tests '*QwenVideoSmokeITest'
 */
@EnabledIfEnvironmentVariable(named = "QWEN_KEY", matches = ".+")
class QwenVideoSmokeITest {

    private val config =
        ProviderConfig(
            id = "qwen-video",
            name = "Qwen Cloud Video",
            dialect = Dialect.TEMPLATE,
            baseUrl = "https://dashscope-intl.aliyuncs.com",
            endpoints =
                Endpoints(
                    videos = "/api/v1/services/aigc/video-generation/video-synthesis",
                    tasks = "/api/v1/tasks",
                ),
            auth = ProviderAuth(scheme = AuthScheme.BEARER, apiKey = System.getenv("QWEN_KEY")),
            capabilities = Capabilities(imagesGenerate = false),
        )

    @Test
    fun `wan3 video synthesis async flow`() = runBlocking {
        val provider = OpenAIProvider.from(config) as TemplateMediaProvider

        val submitted =
            provider.submitVideo(
                VideoRequest(
                    model = "wan3.0-video",
                    prompt =
                        "A kitten running across a rooftop under the moonlight, " +
                            "city neon lights flickering in the distance, cinematic quality.",
                    resolution = "480P",
                    ratio = "adaptive",
                    duration = 5,
                ),
            )
        assertTrue(submitted.taskId.isNotBlank(), "expected a task id")
        println("VIDEO SUBMITTED: ${submitted.taskId} status=${submitted.status}")

        var task = submitted
        var polls = 0
        while (task.status == "PENDING" || task.status == "RUNNING") {
            if (++polls > 24) break // ~6 minutes cap
            delay(15_000)
            task = provider.retrieveVideoTask(task.taskId)
            println("VIDEO POLL[$polls]: ${task.status}")
        }
        assertEquals("SUCCEEDED", task.status, "video task should succeed, url=${task.videoUrl}")
        assertTrue(task.videoUrl?.contains("aliyuncs") == true, "expected oss video url")
        println("VIDEO OK: ${task.videoUrl!!.substringBefore('?')}")
    }
}