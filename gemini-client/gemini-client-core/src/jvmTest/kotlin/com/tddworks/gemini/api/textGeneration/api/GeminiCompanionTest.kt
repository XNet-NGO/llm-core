package com.tddworks.gemini.api.textGeneration.api

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Gemini client factory overloads + model catalog + Part polymorphic serialization. */
class GeminiCompanionTest {

    // ---- factories (no network at construction) ----

    private fun stopKoinIfAny() {
        runCatching { org.koin.core.context.stopKoin() }
    }

    @Test
    fun `create with static key builds client`() {
        stopKoinIfAny()
        try {
            val client = Gemini.create("k")
            assertTrue(client is Gemini)
        } finally {
            stopKoinIfAny()
        }
    }

    @Test
    fun `create with lambda config builds client`() {
        stopKoinIfAny()
        try {
            val client = Gemini.create(apiKey = { "k" }, baseUrl = { "https://127.0.0.1:9" })
            assertTrue(client is Gemini)
        } finally {
            stopKoinIfAny()
        }
    }

    @Test
    fun `instance builds client without koin`() {
        val client = Gemini.instance(GeminiConfig(apiKey = { "k" }, baseUrl = { "https://127.0.0.1:9" }))
        assertTrue(client is Gemini)
    }

    @Test
    fun `default returns koin-managed client`() {
        stopKoinIfAny()
        try {
            // default() reads the global Koin graph; bootstrap it via initGemini first.
            com.tddworks.gemini.di.initGemini(GeminiConfig(apiKey = { "k" }))
            val client = Gemini.default()
            assertTrue(client is Gemini)
        } finally {
            stopKoinIfAny()
        }
    }

    @Test
    fun `create config starts koin and resolves`() {
        runCatching { org.koin.core.context.stopKoin() }
        try {
            val client = Gemini.create(GeminiConfig(apiKey = { "k" }))
            assertTrue(client is Gemini)
        } finally {
            runCatching { org.koin.core.context.stopKoin() }
        }
    }

    // ---- model catalog ----

    @Test
    fun `model catalog lists five available models`() {
        assertEquals(
            listOf(
                GeminiModel.GEMINI_1_5_PRO,
                GeminiModel.GEMINI_1_5_FLASH_8b,
                GeminiModel.GEMINI_1_5_FLASH,
                GeminiModel.GEMINI_2_0_FLASH,
                GeminiModel.GEMINI_2_0_FLASH_LITE,
            ),
            GeminiModel.availableModels,
        )
        assertEquals("gemini-2.0-flash", GeminiModel.GEMINI_2_0_FLASH.value)
    }

    @Test
    fun `model round-trips through json`() {
        val json = Json
        val encoded = json.encodeToString(GeminiModel.serializer(), GeminiModel("gemini-3.6-flash"))
        assertEquals("\"gemini-3.6-flash\"", encoded)
        assertEquals(GeminiModel("gemini-3.6-flash"), json.decodeFromString(GeminiModel.serializer(), encoded))
    }

    // ---- Part polymorphic serialization ----

    @Test
    fun `text part serializes and deserializes`() {
        val json = Json
        val encoded = json.encodeToString(Part.serializer(), Part.TextPart("hi"))
        val decoded = json.decodeFromString(Part.serializer(), encoded)
        assertEquals(Part.TextPart("hi"), decoded)
    }

    @Test
    fun `inline data part round-trips with snake keys`() {
        val part: Part = Part.InlineDataPart(Part.InlineDataPart.InlineData(mimeType = "image/png", data = "AEs="))
        val json = Json
        val encoded = json.encodeToString(Part.serializer(), part)
        assertTrue(encoded.contains("\"inline_data\""))
        assertTrue(encoded.contains("\"mime_type\""))
        assertEquals(part, json.decodeFromString(Part.serializer(), encoded))
    }

    @Test
    fun `unknown part shape throws serialization exception`() {
        assertThrows(SerializationException::class.java) {
            Json.decodeFromString(Part.serializer(), """{"bogus":1}""")
        }
    }

    @Test
    fun `inline data selects by presence of inline_data key`() {
        val decoded = Json.decodeFromString(Part.serializer(), """{"inline_data":{"mime_type":"audio/pcm","data":"AA=="}}""")
        assertTrue(decoded is Part.InlineDataPart)
        assertEquals("audio/pcm", (decoded as Part.InlineDataPart).inlineData.mimeType)
    }
}