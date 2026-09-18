package com.tddworks.anthropic.api.messages.api

import com.tddworks.anthropic.api.Anthropic
import com.tddworks.anthropic.api.AnthropicConfig
import com.tddworks.anthropic.di.iniAnthropic
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Anthropic content-block serializer (text/image blocks, error branches) +
 * companion factory overloads + DI boot.
 */
class AnthropicContentSerializerTest {

    private val json = Json { ignoreUnknownKeys = true }

    // ---- ContentSerializer deserialize ----

    @Test
    fun `primitive json deserializes to text content`() {
        val content = json.decodeFromString(ContentSerializer, "\"plain text\"")
        assertEquals(Content.TextContent("plain text"), content)
    }

    @Test
    fun `array with text block deserializes`() {
        val content = json.decodeFromString(ContentSerializer, """[{"type":"text","text":"hello"}]""")
        assertEquals(listOf(BlockMessageContent.TextContent("hello")), (content as Content.BlockContent).blocks)
    }

    @Test
    fun `array with image block deserializes source fields`() {
        val content =
            json.decodeFromString(
                ContentSerializer,
                """[{"type":"image","source":{"media_type":"image/png","data":"AEs=","type":"base64"}}]""",
            )
        val image = ((content as Content.BlockContent).blocks[0] as BlockMessageContent.ImageContent)
        assertEquals("image/png", image.source.mediaType)
        assertEquals("AEs=", image.source.data)
        assertEquals("base64", image.source.type)
    }

    @Test
    fun `missing text throws`() {
        assertThrows(IllegalArgumentException::class.java) {
            json.decodeFromString(ContentSerializer, """[{"type":"text"}]""")
        }
    }

    @Test
    fun `missing image source fields throw`() {
        assertThrows(IllegalArgumentException::class.java) {
            json.decodeFromString(ContentSerializer, """[{"type":"image","source":{}}]""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            json.decodeFromString(ContentSerializer, """[{"type":"image","source":{"media_type":"image/png","data":"x"}}]""")
        }
    }

    @Test
    fun `unsupported block type throws`() {
        val e =
            assertThrows(IllegalArgumentException::class.java) {
                json.decodeFromString(ContentSerializer, """[{"type":"tool_use"}]""")
            }
        assertTrue(e.message!!.contains("Unsupported content block type"))
    }

    @Test
    fun `unsupported content shape throws`() {
        val e =
            assertThrows(IllegalArgumentException::class.java) {
                json.decodeFromString(ContentSerializer, """{"type":"text","text":"x"}""")
            }
        assertTrue(e.message!!.contains("Unsupported content format"))
    }

    // ---- ContentSerializer serialize ----

    @Test
    fun `text content serializes as plain string`() {
        val out = json.encodeToString(ContentSerializer, Content.TextContent("hi"))
        assertEquals("\"hi\"", out)
    }

    @Test
    fun `block content serializes to array with type discriminators`() {
        val content: Content =
            Content.BlockContent(
                blocks =
                    listOf(
                        BlockMessageContent.TextContent("a"),
                        BlockMessageContent.ImageContent(
                            source = BlockMessageContent.ImageContent.Source("image/png", "AEs=", "base64"),
                        ),
                    ),
            )
        val out = json.encodeToString(ContentSerializer, content)
        assertTrue(out.contains("\"type\":\"text\""))
        assertTrue(out.contains("image/png"))
        val back = json.decodeFromString(ContentSerializer, out)
        assertEquals(content, back)
    }

    // ---- companion factories + DI ----

    @AfterEach
    fun tearDownKoin() {
        runCatching { org.koin.core.context.stopKoin() }
    }

    @Test
    fun `create with static key builds client`() {
        val client = Anthropic.create("k")
        assertTrue(client is Anthropic)
    }

    @Test
    fun `create with lambda config builds client`() {
        val client = Anthropic.create(apiKey = { "k" }, baseUrl = { "https://127.0.0.1:9" })
        assertTrue(client is Anthropic)
    }

    @Test
    fun `create with config and custom version builds client`() {
        val client =
            Anthropic.create(
                AnthropicConfig(
                    apiKey = { "k" },
                    baseUrl = { "https://127.0.0.1:9" },
                    anthropicVersion = { "2024-10-22" },
                ),
            )
        assertTrue(client is Anthropic)
    }

    @Test
    fun `constants are pinned`() {
        assertEquals("https://api.anthropic.com", Anthropic.BASE_URL)
        assertEquals("2023-06-01", Anthropic.ANTHROPIC_VERSION)
    }

    @Test
    fun `iniAnthropic boots a client from config`() {
        runCatching { org.koin.core.context.stopKoin() }
        val client = iniAnthropic(AnthropicConfig(apiKey = { "k" }, baseUrl = { "https://127.0.0.1:9" }))
        assertTrue(client is Anthropic)
    }
}