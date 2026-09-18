package com.tddworks.gemini.api.textGeneration.api

import com.tddworks.openai.api.chat.api.ChatCompletionRequest
import com.tddworks.openai.api.chat.api.ChatMessage
import com.tddworks.openai.api.chat.api.OpenAIModel
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Gemini ↔ OpenAI surface mappers (gateway-core vendored extension file).
 */
class GeminiExtensionsTest {

    private fun response(parts: List<Part>, finish: String? = "STOP", model: String = "gemini-3.6-flash") =
        GenerateContentResponse(
            candidates = listOf(Candidate(content = Content(parts = parts, role = "model"), finishReason = finish)),
            usageMetadata = UsageMetadata(1, 2, 3),
            modelVersion = model,
        )

    @Test
    fun `generate response maps candidates to choices`() {
        val out = response(listOf(Part.TextPart("hi"))).toOpenAIChatCompletion()
        assertEquals("chatcmpl-gemini-123", out.id)
        assertEquals(1, out.choices.size)
        assertEquals("hi", (out.choices[0].message as ChatMessage.AssistantMessage).content)
        assertEquals("STOP", out.choices[0].finishReason?.value)
    }

    @Test
    fun `generate response maps finish reason null safely`() {
        val out = response(listOf(Part.TextPart("x")), finish = null).toOpenAIChatCompletion()
        assertNull(out.choices[0].finishReason)
    }

    @Test
    fun `chunk mapper handles empty candidates`() {
        val out =
            GenerateContentResponse(
                candidates = emptyList(),
                usageMetadata = UsageMetadata(0, 0, 0),
                modelVersion = "m",
            ).toOpenAIChatCompletionChunk()
        assertEquals("chatcmpl-gemini-123", out.id)
        assertEquals(1, out.choices.size)
        assertNull(out.choices[0].delta.content)
    }

    @Test
    fun `chunk mapper uses first text part`() {
        val out = response(listOf(Part.TextPart("chunk"), Part.TextPart("more"))).toOpenAIChatCompletionChunk()
        assertEquals("chunk", out.choices[0].delta.content)
        assertEquals("STOP", out.choices[0].finishReason)
    }

    @Test
    fun `request mapper extracts system message and converts the rest`() {
        val req =
            ChatCompletionRequest(
                messages =
                    listOf(
                        ChatMessage.system("You are helpful"),
                        ChatMessage.user("Hi"),
                        ChatMessage.assistant("Hello"),
                    ),
                model = OpenAIModel("gemini-x"),
            )
        val gen = req.toGeminiGenerateContentRequest()
        assertEquals("You are helpful", gen.systemInstruction?.parts?.first()?.let { (it as Part.TextPart).text })
        assertEquals(2, gen.contents.size)
        assertEquals("user", gen.contents[0].role)
        assertEquals("model", gen.contents[1].role)
        assertEquals("gemini-x", gen.model.value)
    }

    @Test
    fun `request mapper defaults stream to false`() {
        val req = ChatCompletionRequest(messages = listOf(ChatMessage.user("Hi")), model = OpenAIModel("m"))
        val gen = req.toGeminiGenerateContentRequest()
        assertEquals(false, gen.stream)
    }

    @Test
    fun `request mapper throws for unknown message type`() {
        val req =
            ChatCompletionRequest(
                messages = listOf(ChatMessage.VisionMessage(listOf(com.tddworks.openai.api.chat.api.vision.VisionMessageContent.TextContent(content = "image")))),
                model = OpenAIModel("m"),
            )
        val e =
            assertThrows(IllegalArgumentException::class.java) {
                req.toGeminiGenerateContentRequest()
            }
        assertTrue(e.message!!.contains("Unknown message type"))
    }
}