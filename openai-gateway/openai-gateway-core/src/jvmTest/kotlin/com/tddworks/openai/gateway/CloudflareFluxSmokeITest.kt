package com.tddworks.openai.gateway

import com.tddworks.openai.api.images.api.ImageCreate
import com.tddworks.openai.api.chat.api.OpenAIModel
import com.tddworks.openai.api.images.api.Size
import com.tddworks.openai.gateway.api.OpenAIGateway
import com.tddworks.openai.gateway.config.AuthScheme
import com.tddworks.openai.gateway.config.Capabilities
import com.tddworks.openai.gateway.config.Dialect
import com.tddworks.openai.gateway.config.ProviderAuth
import com.tddworks.openai.gateway.config.ProviderConfig
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable

/**
 * Template-dialect media generation vs Cloudflare Workers AI (`flux-2-klein-9b`),
 * fully config-driven: no Cloudflare-specific code beyond the generic template.
 *
 * Requires CF_TOKEN (Workers AI: Edit) and CF_ACCOUNT (account id).
 *
 * Run: CF_TOKEN=<token> CF_ACCOUNT=<id> ./gradlew :openai-gateway:openai-gateway-core:jvmTest --tests '*CloudflareFluxSmokeITest'
 */
@EnabledIfEnvironmentVariable(named = "CF_TOKEN", matches = ".+")
@EnabledIfEnvironmentVariable(named = "CF_ACCOUNT", matches = ".+")
class CloudflareFluxSmokeITest {

    private val config =
        ProviderConfig(
            id = "cloudflare",
            name = "Cloudflare Workers AI",
            dialect = Dialect.TEMPLATE,
            baseUrl = "https://api.cloudflare.com/client/v4/accounts/${System.getenv("CF_ACCOUNT")}",
            auth = ProviderAuth(scheme = AuthScheme.BEARER, apiKey = System.getenv("CF_TOKEN")),
            capabilities = Capabilities(imagesGenerate = true),
        )

    @Test
    fun `config-driven image generation via flux-2-klein`() = runBlocking {
        val gateway = OpenAIGateway.create(listOf(config))

        val provider = gateway.getProvider("cloudflare")
        assertNotNull(provider, "template provider should be registered")

        val result =
            provider!!.generate(
                ImageCreate(
                    prompt = "a red apple on a white table, minimalist studio lighting",
                    model = OpenAIModel("@cf/black-forest-labs/flux-2-klein-9b"),
                    size = Size.size512x512,
                ),
            )

        val image = result.data.firstOrNull()
        assertNotNull(image, "expected an image result")
        assertNotNull(image!!.b64JSON, "expected base64 image data")

        val bytes = java.util.Base64.getDecoder().decode(image.b64JSON)
        assertTrue(bytes.isNotEmpty(), "decoded image should not be empty")
        // flux-2-klein returns JPEG (FF D8)
        assertTrue(
            bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte(),
            "expected JPEG magic, got ${bytes.take(4).joinToString { it.toString(16) }}",
        )
        println("FLUX IMAGE OK: ${bytes.size} bytes JPEG via config-driven template dialect")
    }
}