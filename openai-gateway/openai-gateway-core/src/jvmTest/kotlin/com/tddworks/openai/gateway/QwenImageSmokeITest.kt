package com.tddworks.openai.gateway

import com.tddworks.openai.api.chat.api.OpenAIModel
import com.tddworks.openai.api.images.api.ImageCreate
import com.tddworks.openai.api.images.api.Size
import com.tddworks.openai.gateway.api.OpenAIProvider
import com.tddworks.openai.gateway.api.internal.from
import com.tddworks.openai.gateway.config.AuthScheme
import com.tddworks.openai.gateway.config.Capabilities
import com.tddworks.openai.gateway.config.Dialect
import com.tddworks.openai.gateway.config.Endpoints
import com.tddworks.openai.gateway.config.ProviderAuth
import com.tddworks.openai.gateway.config.ProviderConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Qwen image generation via the native multimodal-generation service
 * (qwen-image-2.0-pro), driven by the template dialect with the `qwen` input
 * style and `url` output style. Requires QWEN_KEY.
 *
 * Run: QWEN_KEY=<key> ./gradlew :openai-gateway:openai-gateway-core:jvmTest --tests '*QwenImageSmokeITest'
 */
@EnabledIfEnvironmentVariable(named = "QWEN_KEY", matches = ".+")
class QwenImageSmokeITest {

    private val config =
        ProviderConfig(
            id = "qwen-image",
            name = "Qwen Cloud Image",
            dialect = Dialect.TEMPLATE,
            baseUrl = "https://dashscope-intl.aliyuncs.com",
            endpoints =
                Endpoints(
                    imagesGenerations =
                        "/api/v1/services/aigc/multimodal-generation/generation",
                ),
            auth = ProviderAuth(scheme = AuthScheme.BEARER, apiKey = System.getenv("QWEN_KEY")),
            capabilities = Capabilities(imagesGenerate = true),
            imageInput = "qwen",
            imageOutput = "url",
            imageModelInPath = false,
        )

    @Test
    fun `qwen-image-2 animates via template dialect`() = runBlocking {
        val provider = OpenAIProvider.from(config)
        val result =
            provider.generate(
                ImageCreate(
                    prompt = "a red apple on a white table, minimalist studio lighting",
                    model = OpenAIModel("qwen-image-2.0-pro"),
                    size = Size.size1024x1024,
                ),
            )
        val image = result.data.firstOrNull()
        assertNotNull(image, "expected an image result")
        assertNotNull(image!!.url, "qwen url style should return a signed image URL")
        assertTrue(
            image.url!!.contains("aliyuncs"),
            "expected oss URL, got ${image.url!!.take(80)}",
        )
        println("QWEN IMAGE OK: ${image.url!!.substringBefore('?')}")
    }
}