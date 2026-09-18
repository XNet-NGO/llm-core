package com.tddworks.voice.api.internal

import com.tddworks.voice.api.Voice
import com.tddworks.voice.api.VoiceConfig
import com.tddworks.voice.api.VoiceEvent
import com.tddworks.voice.api.VoiceSession
import com.tddworks.voice.api.VoiceVendor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Voice wire-contract unit tests: payload builders + event parsers for all three
 * sessions (Gemini Live, OpenAI Realtime, Qwen TTS) and the Voice facade. No
 * WebSocket involved — the extracted pure functions are deterministic.
 */
class VoiceSessionsUnitTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun config(
        vendor: VoiceVendor = VoiceVendor.GEMINI_LIVE,
        model: String = "gemini-3.8-live",
        systemInstruction: String? = null,
        voice: String? = null,
        apiVersion: String = "v1alpha",
        audioFormat: String = "wav",
        sampleRate: Int = 24000,
    ): VoiceConfig =
        VoiceConfig(
            vendor = vendor,
            apiKey = { "secret" },
            baseUrl = { "wss://generativelanguage.googleapis.com" },
            model = { model },
            systemInstruction = systemInstruction,
            voice = voice,
            audioFormat = audioFormat,
            sampleRate = sampleRate,
            apiVersion = apiVersion,
        )

    // ================= Gemini Live =================

    @Test
    fun `gemini ws url appends key and api version connector`() {
        assertEquals(
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent?key=secret",
            geminiWsUrl("wss://generativelanguage.googleapis.com/", "secret", "v1alpha"),
        )
    }

    @Test
    fun `gemini ws url omits key when absent`() {
        assertTrue(!geminiWsUrl("wss://h", "", "v1beta").contains("key="))
        assertTrue(geminiWsUrl("wss://h", "", "v1beta").contains("v1beta"))
    }

    @Test
    fun `gemini setup carries model modalities voice and optional system instruction`() {
        val setup = geminiSetup(config(systemInstruction = "be brief", voice = "Kore"))
        val inner = setup["setup"]!!.jsonObject
        assertEquals("models/gemini-3.8-live", inner["model"]?.jsonPrimitive?.content)
        val modalities = inner["generationConfig"]!!.jsonObject["responseModalities"]!!.jsonArray
        assertEquals("AUDIO", modalities[0].jsonPrimitive.content)
        assertEquals(
            "Kore",
            inner["generationConfig"]!!.jsonObject["speechConfig"]!!.jsonObject["voiceConfig"]!!
                .jsonObject["prebuiltVoiceConfig"]!!.jsonObject["voiceName"]?.jsonPrimitive?.content,
        )
        assertEquals(
            "be brief",
            inner["systemInstruction"]!!.jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `gemini setup defaults voice to Puck and omits system instruction`() {
        val inner = geminiSetup(config())["setup"]!!.jsonObject
        assertEquals(
            "Puck",
            inner["generationConfig"]!!.jsonObject["speechConfig"]!!.jsonObject["voiceConfig"]!!
                .jsonObject["prebuiltVoiceConfig"]!!.jsonObject["voiceName"]?.jsonPrimitive?.content,
        )
        assertNull(inner["systemInstruction"])
    }

    @Test
    fun `gemini user turn is role user with turnComplete`() {
        val turn = geminiUserTurn("hello").jsonObject
        val content = turn["clientContent"]!!.jsonObject
        assertEquals(true, content["turnComplete"]?.jsonPrimitive?.content?.toBoolean())
        val turns = content["turns"]!!.jsonArray
        assertEquals("user", turns[0].jsonObject["role"]?.jsonPrimitive?.content)
        assertEquals("hello", turns[0].jsonObject["parts"]!!.jsonArray[0].jsonObject["text"]?.jsonPrimitive?.content)
    }

    @Test
    fun `gemini audio chunk carries mime and base64`() {
        val chunk = geminiAudioChunk("AEs=", 24000)["realtimeInput"]!!.jsonObject["mediaChunks"]!!.jsonArray[0].jsonObject
        assertEquals("audio/pcm;rate=24000", chunk["mimeType"]?.jsonPrimitive?.content)
        assertEquals("AEs=", chunk["data"]?.jsonPrimitive?.content)
    }

    @Test
    fun `gemini tool response embeds parsed args or falls back to result object`() {
        val parsed = geminiToolResponse("search", """{"q":"x"}""", "fc_1", json)["toolResponse"]!!.jsonObject
        val fn = parsed["functionResponses"]!!.jsonArray[0].jsonObject
        assertEquals("fc_1", fn["id"]?.jsonPrimitive?.content)
        assertEquals("search", fn["name"]?.jsonPrimitive?.content)
        assertEquals("x", fn["response"]!!.jsonObject["q"]?.jsonPrimitive?.content)

        val fallback = geminiToolResponse("f", "not json", "fc_2", json)["toolResponse"]!!.jsonObject
        assertEquals(
            "not json",
            fallback["functionResponses"]!!.jsonArray[0].jsonObject["response"]!!.jsonObject["result"]?.jsonPrimitive?.content,
        )
    }

    @Test
    fun `gemini end turn sends empty turns with complete`() {
        val end = geminiEndTurnMessage()["clientContent"]!!.jsonObject
        assertEquals(0, end["turns"]!!.jsonArray.size)
        assertEquals(true, end["turnComplete"]?.jsonPrimitive?.content?.toBoolean())
    }

    @Test
    fun `gemini parse handles every frame type`() {
        assertEquals(listOf(VoiceEvent.SessionReady("m")), parseGeminiEvents(json, """{"setupComplete":{}}""", "m"))

        val content =
            """{"serverContent":{"modelTurn":{"parts":[
                 {"inlineData":{"data":"AAA="}},
                 {"text":"hello"},
                 {"functionCall":{"name":"t","args":{"a":1},"id":"fc"}}
               ]},"turnComplete":true}}"""
        val events = parseGeminiEvents(json, content, "m")
        assertEquals(4, events.size)
        assertEquals(VoiceEvent.AudioDelta("AAA="), events[0])
        assertEquals(VoiceEvent.OutputTranscription("hello"), events[1])
        assertEquals(VoiceEvent.ToolCall("t", """{"a":1}""", "fc"), events[2])
        assertEquals(VoiceEvent.TurnComplete(), events[3])

        assertEquals(1, parseGeminiEvents(json, """{"toolCall":{"functionCalls":[{"name":"f","args":"{}"}]}}""", "m").size)
        assertEquals(listOf(VoiceEvent.Interrupted()), parseGeminiEvents(json, """{"interrupted":{}}""", "m"))
        assertEquals(
            listOf(VoiceEvent.InputTranscription("user said")),
            parseGeminiEvents(json, """{"audioTranscription":{"text":"user said"}}""", "m"),
        )
        assertEquals(
            listOf(VoiceEvent.Error("boom")),
            parseGeminiEvents(json, """{"error":{"message":"boom"}}""", "m"),
        )
        assertTrue(parseGeminiEvents(json, "{not json", "m").isEmpty())
        assertTrue(parseGeminiEvents(json, """{"unknown":1}""", "m").isEmpty())
    }

    @Test
    fun `gemini generationComplete marks turn complete`() {
        val events = parseGeminiEvents(json, """{"serverContent":{"generationComplete":true}}""", "m")
        assertEquals(listOf(VoiceEvent.TurnComplete()), events)
    }

    // ================= OpenAI Realtime =================

    @Test
    fun `realtime ws url carries model and key suffix`() {
        assertEquals(
            "wss://api.test/v1/realtime?model=gpt-4o-realtime&api-key=sk",
            realtimeWsUrl("wss://api.test", "sk", "gpt-4o-realtime"),
        )
        assertTrue(!realtimeWsUrl("wss://api.test", "", "m").contains("api-key"))
    }

    @Test
    fun `realtime update carries modalities instructions and voice`() {
        val update = realtimeSessionUpdate("be terse", "coral")
        assertEquals("session.update", update["type"]?.jsonPrimitive?.content)
        val session = update["session"]!!.jsonObject
        assertEquals("be terse", session["instructions"]?.jsonPrimitive?.content)
        assertEquals("coral", session["voice"]?.jsonPrimitive?.content)
        assertEquals(2, session["modalities"]!!.jsonArray.size)
    }

    @Test
    fun `realtime audio append and tool output and commit shapes`() {
        assertEquals("input_audio_buffer.append", realtimeAudioAppend("AA==")["type"]?.jsonPrimitive?.content)
        val item = realtimeToolOutput("call_1", """{"ok":true}""")["item"]!!.jsonObject
        assertEquals("function_call_output", item["type"]?.jsonPrimitive?.content)
        assertEquals("call_1", item["call_id"]?.jsonPrimitive?.content)
        assertEquals("response.create", realtimeResponseCreate()["type"]?.jsonPrimitive?.content)
        assertEquals("input_audio_buffer.commit", realtimeCommit()["type"]?.jsonPrimitive?.content)
    }

    @Test
    fun `realtime parse covers event matrix`() {
        assertEquals(
            VoiceEvent.SessionReady("gpt-4o"),
            parseRealtimeEvent(json, """{"type":"session.created","session":{"model":"gpt-4o"}}""", "fallback"),
        )
        assertEquals(
            VoiceEvent.SessionReady("fallback"),
            parseRealtimeEvent(json, """{"type":"session.created"}""", "fallback"),
        )
        assertEquals(
            VoiceEvent.AudioDelta("ZA=="),
            parseRealtimeEvent(json, """{"type":"response.audio.delta","delta":"ZA=="}""", "m"),
        )
        assertEquals(
            VoiceEvent.OutputTranscription("par", partial = true),
            parseRealtimeEvent(json, """{"type":"response.audio_transcript.delta","delta":"par"}""", "m"),
        )
        assertEquals(
            VoiceEvent.OutputTranscription("full"),
            parseRealtimeEvent(json, """{"type":"response.audio_transcript.done","transcript":"full"}""", "m"),
        )
        assertEquals(
            VoiceEvent.TextDelta("t"),
            parseRealtimeEvent(json, """{"type":"response.output_text.delta","delta":"t"}""", "m"),
        )
        assertEquals(
            VoiceEvent.TextDelta("t"),
            parseRealtimeEvent(json, """{"type":"response.text.delta","delta":"t"}""", "m"),
        )
        assertEquals(
            VoiceEvent.ToolCall("f", """{"a":1}""", "c1"),
            parseRealtimeEvent(json, """{"type":"response.function_call_arguments.done","name":"f","arguments":{"a":1},"call_id":"c1"}""", "m"),
        )
        assertEquals(
            VoiceEvent.TurnComplete(),
            parseRealtimeEvent(json, """{"type":"response.done"}""", "m"),
        )
        assertEquals(
            VoiceEvent.Interrupted("speech started"),
            parseRealtimeEvent(json, """{"type":"input_audio_buffer.speech_started"}""", "m"),
        )
        assertEquals(
            VoiceEvent.Error("nope", "invalid_request_error"),
            parseRealtimeEvent(json, """{"type":"error","error":{"message":"nope","type":"invalid_request_error"}}""", "m"),
        )
        assertNull(parseRealtimeEvent(json, """{"type":"response.created"}""", "m"))
        assertNull(parseRealtimeEvent(json, "{bad", "m"))
    }

    // ================= Qwen TTS =================

    @Test
    fun `qwen ws url appends inference path unless present`() {
        assertEquals("wss://h/api-ws/v1/inference", qwenWsUrl("wss://h"))
        assertEquals("wss://h/api-ws/v1/inference", qwenWsUrl("wss://h/"))
        assertEquals("wss://h/custom/api-ws", qwenWsUrl("wss://h/custom/api-ws"))
    }

    @Test
    fun `qwen start frame is run-task with audio parameters`() {
        val start = json.parseToJsonElement(qwenStartFrame("t1", "qwen3-tts-flash", "Cherry", "wav", 24000)).jsonObject
        val header = start["header"]!!.jsonObject
        assertEquals("run-task", header["action"]?.jsonPrimitive?.content)
        assertEquals("t1", header["task_id"]?.jsonPrimitive?.content)
        assertEquals("duplex", header["streaming"]?.jsonPrimitive?.content)
        val payload = start["payload"]!!.jsonObject
        assertEquals("qwen3-tts-flash", payload["model"]?.jsonPrimitive?.content)
        assertEquals("audio", payload["task_group"]?.jsonPrimitive?.content)
        assertEquals("tts", payload["task"]?.jsonPrimitive?.content)
        assertEquals("SpeechSynthesizer", payload["function"]?.jsonPrimitive?.content)
        val params = payload["parameters"]!!.jsonObject
        assertEquals("Cherry", params["voice"]?.jsonPrimitive?.content)
        assertEquals("wav", params["format"]?.jsonPrimitive?.content)
        assertEquals(24000, params["sample_rate"]?.jsonPrimitive?.content?.toInt())
        assertEquals("PlainText", params["text_type"]?.jsonPrimitive?.content)
        assertEquals(50, params["volume"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `qwen start frame defaults voice and format`() {
        val params = json.parseToJsonElement(qwenStartFrame("t", "m", null, "", 16000)).jsonObject["payload"]!!
            .jsonObject["parameters"]!!.jsonObject
        assertEquals("Cherry", params["voice"]?.jsonPrimitive?.content)
        assertEquals("wav", params["format"]?.jsonPrimitive?.content)
        assertEquals(16000, params["sample_rate"]?.jsonPrimitive?.content?.toInt())
    }

    @Test
    fun `qwen continue and finish frames carry action and text`() {
        val cont = json.parseToJsonElement(qwenContinuePayload("t2", "m", "say hi")).jsonObject
        assertEquals("continue-task", cont["header"]!!.jsonObject["action"]?.jsonPrimitive?.content)
        assertEquals("say hi", cont["payload"]!!.jsonObject["input"]!!.jsonObject["text"]?.jsonPrimitive?.content)
        assertEquals("m", cont["payload"]!!.jsonObject["model"]?.jsonPrimitive?.content)

        val finish = json.parseToJsonElement(qwenFinishPayload("t2")).jsonObject
        assertEquals("finish-task", finish["header"]!!.jsonObject["action"]?.jsonPrimitive?.content)
        assertEquals("t2", finish["header"]!!.jsonObject["task_id"]?.jsonPrimitive?.content)
    }

    @Test
    fun `qwen parse maps succeeded failed and ignores others`() {
        assertEquals(
            VoiceEvent.SessionReady("m"),
            parseQwenEvent(json, """{"header":{"event":"task-succeeded"}}""", "m"),
        )
        assertEquals(
            VoiceEvent.Error("Model not found", "ModelNotFound"),
            parseQwenEvent(json, """{"header":{"event":"task-failed","error_code":"ModelNotFound","error_message":"Model not found"}}""", "m"),
        )
        assertEquals(
            VoiceEvent.Error("tts task failed"),
            parseQwenEvent(json, """{"header":{"event":"task-failed"}}""", "m"),
        )
        assertNull(parseQwenEvent(json, """{"header":{"event":"task-started"}}""", "m"))
        assertNull(parseQwenEvent(json, "nope", "m"))
    }

    // ================= Voice facade =================

    @Test
    fun `facade maps vendors to session types`() {
        val base = config()
        assertTrue(Voice.session(base.copy(vendor = VoiceVendor.GEMINI_LIVE)) is GeminiLiveSession)
        assertTrue(Voice.session(base.copy(vendor = VoiceVendor.OPENAI_REALTIME)) is OpenAIRealtimeSession)
        assertTrue(Voice.session(base.copy(vendor = VoiceVendor.QWEN_TTS)) is QwenTtsSession)
    }

    @Test
    fun `facade config one-liner defaults`() {
        val cfg = Voice.config(vendor = VoiceVendor.QWEN_TTS, apiKey = "k", baseUrl = "wss://q", model = "m")
        assertEquals(VoiceVendor.QWEN_TTS, cfg.vendor)
        assertEquals("k", cfg.apiKey())
        assertEquals("wss://q", cfg.baseUrl())
        assertEquals("m", cfg.model())
        assertEquals("v1beta", cfg.apiVersion)
        assertEquals(24000, cfg.sampleRate)
        assertEquals("pcm16", cfg.audioFormat)
        assertNull(cfg.systemInstruction)
        assertEquals("alloy", cfg.voice ?: "alloy")
    }

    @Test
    fun `facade config passes explicit overrides`() {
        val cfg =
            Voice.config(
                vendor = VoiceVendor.GEMINI_LIVE,
                apiKey = "k",
                baseUrl = "wss://g",
                model = "x",
                systemInstruction = "si",
                voice = "Kore",
                apiVersion = "v1alpha",
            )
        assertEquals("si", cfg.systemInstruction)
        assertEquals("Kore", cfg.voice)
        assertEquals("v1alpha", cfg.apiVersion)
    }
}