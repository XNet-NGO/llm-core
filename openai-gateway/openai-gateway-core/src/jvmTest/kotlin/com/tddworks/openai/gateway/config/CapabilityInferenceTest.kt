package com.tddworks.openai.gateway.config

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class CapabilityInferenceTest {

    @Test
    fun `infers embeddings from output modality and feature`() {
        val byOutput = CapabilityInference.infer(listOf(CatalogModel("e", outputModalities = listOf("embeddings"))))
        assertTrue(byOutput.embeddings)
        val byFeature = CapabilityInference.infer(listOf(CatalogModel("e", supportedFeatures = listOf("embed"))))
        assertTrue(byFeature.embeddings)
    }

    @Test
    fun `infers tts and stt from audio modalities`() {
        val tts = CapabilityInference.infer(listOf(CatalogModel("a", outputModalities = listOf("audio"))))
        assertTrue(tts.tts)
        assertFalse(tts.stt)
        val stt = CapabilityInference.infer(listOf(CatalogModel("a", inputModalities = listOf("audio"))))
        assertTrue(stt.stt)
    }

    @Test
    fun `infers imagesGenerate from image output`() {
        val c = CapabilityInference.infer(listOf(CatalogModel("i", outputModalities = listOf("image"))))
        assertTrue(c.imagesGenerate)
    }

    @Test
    fun `infers rerank and moderation from features`() {
        val c =
            CapabilityInference.infer(
                listOf(CatalogModel("x", supportedFeatures = listOf("rerank", "moderation"))),
            )
        assertTrue(c.rerank)
        assertTrue(c.moderation)
    }

    @Test
    fun `chat defaults true and unknown modalities do not set flags`() {
        val c = CapabilityInference.infer(listOf(CatalogModel("t", inputModalities = listOf("text"))))
        assertTrue(c.chat)
        assertFalse(c.embeddings)
        assertFalse(c.tts)
        assertFalse(c.imagesGenerate)
    }

    @Test
    fun `explicit config wins over inference`() {
        // Config forces embeddings on; catalog has no evidence — must stay on.
        val merged =
            CapabilityInference.merge(
                config = Capabilities(embeddings = true, responses = true),
                inferred = Capabilities(embeddings = false),
            )
        assertTrue(merged.embeddings)
        assertTrue(merged.responses) // config-only flag preserved
    }

    @Test
    fun `inference adds capabilities left at default`() {
        val merged =
            CapabilityInference.merge(
                config = Capabilities(), // all defaults
                inferred = Capabilities(tts = true, imagesGenerate = true),
            )
        assertTrue(merged.tts)
        assertTrue(merged.imagesGenerate)
    }

    @Test
    fun `resolve merges catalog inference under config`() {
        val config =
            ProviderConfig(
                id = "p",
                baseUrl = "https://x",
                capabilities = Capabilities(responses = true),
            )
        val catalog = Catalog(models = listOf(CatalogModel("m", outputModalities = listOf("audio"))))
        val caps = CapabilityInference.resolve(config, catalog)
        assertTrue(caps.tts) // inferred
        assertTrue(caps.responses) // from config
    }
}
