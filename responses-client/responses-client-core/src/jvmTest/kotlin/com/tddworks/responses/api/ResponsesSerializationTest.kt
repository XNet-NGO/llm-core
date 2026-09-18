package com.tddworks.responses.api

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Serialization contracts for the Responses API DTOs (sealed variants + full shapes). */
class ResponsesSerializationTest {

    private val json = Json { isLenient = true; ignoreUnknownKeys = true }

    // ---- ResponseItem sealed variants ----

    @Test
    fun `response message item round-trips`() {
        val item: ResponseItem =
            ResponseItem.Message(
                id = "m1",
                role = "assistant",
                status = "completed",
                content = listOf(ResponseContentPart.OutputText(text = "hi")),
            )
        val str = json.encodeToString(ResponseItem.serializer(), item)
        assertTrue(str.contains("\"message\""))
        assertEquals(item, json.decodeFromString(ResponseItem.serializer(), str))
    }

    @Test
    fun `response function call item round-trips`() {
        val item: ResponseItem =
            ResponseItem.FunctionCall(id = "fc", callId = "c1", name = "tool", arguments = "{}", status = "completed")
        val decoded = json.decodeFromString(ResponseItem.serializer(), json.encodeToString(ResponseItem.serializer(), item))
        assertEquals("tool", (decoded as ResponseItem.FunctionCall).name)
        assertEquals("completed", decoded.status)
    }

    @Test
    fun `response function call output item round-trips`() {
        val item: ResponseItem = ResponseItem.FunctionCallOutput(callId = "c1", output = "42")
        val decoded = json.decodeFromString(ResponseItem.serializer(), json.encodeToString(ResponseItem.serializer(), item))
        assertEquals("42", (decoded as ResponseItem.FunctionCallOutput).output)
    }

    @Test
    fun `response reasoning item round-trips`() {
        val item: ResponseItem = ResponseItem.Reasoning(id = "r", summary = listOf(JsonPrimitive("s")), effort = "high")
        val decoded = json.decodeFromString(ResponseItem.serializer(), json.encodeToString(ResponseItem.serializer(), item))
        assertTrue(decoded is ResponseItem.Reasoning)
        assertEquals(1, (decoded as ResponseItem.Reasoning).summary.size)
        assertEquals("high", decoded.effort)
    }

    @Test
    fun `unknown response item keeps raw payload`() {
        val raw = buildJsonObject { put("type", "weird"); put("data", 1) }
        val item: ResponseItem = ResponseItem.Unknown(payload = raw)
        val str = json.encodeToString(ResponseItem.serializer(), item)
        val decoded = json.decodeFromString(ResponseItem.serializer(), str)
        assertTrue(decoded is ResponseItem.Unknown)
        assertEquals("weird", (decoded as ResponseItem.Unknown).payload.jsonObject["type"]?.jsonPrimitive?.content)
    }

    // ---- ResponseInputItem variants ----

    @Test
    fun `input items round-trip all variants`() {
        val items: List<ResponseInputItem> =
            listOf(
                ResponseInputItem.Message(role = "user", content = listOf(ResponseContentPart.InputText("q"))),
                ResponseInputItem.FunctionCall(name = "f", arguments = "{}", callId = "c"),
                ResponseInputItem.FunctionCallOutput(callId = "c", outputText = "out"),
                ResponseInputItem.Reasoning(summary = listOf(ResponseInputItem.TextPart("why"))),
            )
        val str = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ResponseInputItem.serializer()), items)
        val decoded = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ResponseInputItem.serializer()), str)
        assertEquals(4, decoded.size)
        assertTrue(decoded[3] is ResponseInputItem.Reasoning)
        assertTrue(str.contains("\"function_call_output\""))
    }

    // ---- ResponseContentPart variants ----

    @Test
    fun `content parts round-trip all variants`() {
        val parts: List<ResponseContentPart> =
            listOf(
                ResponseContentPart.InputText("q"),
                ResponseContentPart.OutputText("a", annotations = listOf(JsonPrimitive("u"))),
                ResponseContentPart.Refusal("no"),
                ResponseContentPart.InputAudio(data = "aGk=", format = "wav", transcript = "hi"),
                ResponseContentPart.InputImage(imageUrl = "https://i/1.png", detail = "low"),
            )
        val str = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(ResponseContentPart.serializer()), parts)
        val decoded = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(ResponseContentPart.serializer()), str)
        assertEquals(5, decoded.size)
        assertEquals("wav", (decoded[3] as ResponseContentPart.InputAudio).format)
        assertEquals("https://i/1.png", (decoded[4] as ResponseContentPart.InputImage).imageUrl)
        assertTrue(str.contains("\"input_image\""))
    }

    // ---- ResponseCreateRequest full shape ----

    @Test
    fun `create request round-trips full shape`() {
        val req =
            ResponseCreateRequest(
                model = "gpt-5.5",
                input = listOf(ResponseInputItem.Message(content = listOf(ResponseContentPart.InputText("hello")))),
                instructions = "be terse",
                maxOutputTokens = 256,
                previousResponseId = "pr-1",
                store = true,
                stream = false,
                include = listOf("reasoning.summary"),
                reasoning = ReasoningConfig(effort = "high", summary = "concise", mode = "enabled"),
                text = ResponseTextConfig(format = buildJsonObject { put("type", "json_object") }),
                tools = listOf(ResponseTool(type = "function", name = "f", description = "d")),
                toolChoice = JsonPrimitive("auto"),
                parallelToolCalls = true,
                temperature = 0.7,
                topP = 0.9,
                metadata = mapOf("k" to "v"),
                outputAudio = OutputAudioConfig(voice = "alloy", format = "pcm16"),
                user = "u-1",
            )
        val str = json.encodeToString(ResponseCreateRequest.serializer(), req)
        val decoded = json.decodeFromString(ResponseCreateRequest.serializer(), str)
        assertEquals(req, decoded)
        assertTrue(str.contains("gpt-5.5"))
        assertTrue(str.contains("\"max_output_tokens\":256"))
        assertTrue(str.contains("\"tool_choice\""))
        assertTrue(str.contains("\"output_audio\""))
        assertTrue(str.contains("\"top_p\":0.9"))
    }

    // ---- Response full shape + helpers ----

    @Test
    fun `response round-trips full shape`() {
        val resp =
            Response(
                id = "r-1",
                type = "response",
                created_at = 1,
                status = "completed",
                model = "gpt-5.5",
                output =
                    listOf(
                        ResponseItem.Message(content = listOf(ResponseContentPart.OutputText("alpha"))),
                        ResponseItem.FunctionCall(name = "f"),
                    ),
                output_text = "alpha",
                error = ResponseError(code = "500", message = "x", type = "server_error"),
                metadata = mapOf("m" to "1"),
                tools = listOf(ResponseTool(name = "t")),
                usage = ResponseUsage(inputTokens = 1, outputTokens = 2, totalTokens = 3),
                top_p = 0.5,
                temperature = 0.2,
            )
        val str = json.encodeToString(Response.serializer(), resp)
        val decoded = json.decodeFromString(Response.serializer(), str)
        assertEquals(resp, decoded)
        assertEquals("server_error", decoded.error!!.type)
        assertEquals(3, decoded.usage!!.totalTokens)
    }

    @Test
    fun `textBlocks extracts output text from message parts`() {
        val resp =
            Response(
                id = "r",
                output =
                    listOf(
                        ResponseItem.Message(content = listOf(ResponseContentPart.OutputText("a"), ResponseContentPart.OutputText("b"))),
                        ResponseItem.FunctionCall(name = "f"),
                        ResponseItem.Message(content = listOf(ResponseContentPart.Refusal("no"))),
                    ),
            )
        // "ab" for the text message; the refusal-only message contributes an empty block.
        assertEquals(listOf("ab", ""), resp.textBlocks())
    }

    @Test
    fun `textBlocks returns empty for no messages`() {
        assertEquals(emptyList<String>(), Response(id = "r").textBlocks())
    }

    @Test
    fun `response tolerates unknown keys`() {
        val decoded =
            json.decodeFromString(
                Response.serializer(),
                """{"id":"r","status":"completed","some_future_field":{"x":1}}""",
            )
        assertEquals("r", decoded.id)
        assertEquals("completed", decoded.status)
    }

    @Test
    fun `response usage details stay as json elements`() {
        val resp = Response(usage = ResponseUsage(inputTokensDetails = JsonObject(emptyMap())))
        val encoded = json.encodeToString(Response.serializer(), resp)
        val decoded = json.decodeFromString(Response.serializer(), encoded)
        assertNull(decoded.usage!!.outputTokensDetails)
    }
}