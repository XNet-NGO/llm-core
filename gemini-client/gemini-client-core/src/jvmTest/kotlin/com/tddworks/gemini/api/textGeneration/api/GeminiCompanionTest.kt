package com.tddworks.gemini.api.textGeneration.api

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin

class GeminiCompanionTest {

    @AfterEach
    fun tearDown() {
        if (GlobalContext.getOrNull() != null) stopKoin()
    }

    @Test
    fun `instance builds a client without starting global Koin`() {
        val g = Gemini.instance(GeminiConfig(apiKey = { "k" }, baseUrl = { "https://g.test" }))
        assertNotNull(g)
        // instance() must not touch the global Koin context.
        assert(GlobalContext.getOrNull() == null) { "instance() should not start global Koin" }
    }

    @Test
    fun `create with string args builds a client`() {
        val g = Gemini.create(apiKey = "k", baseUrl = "https://g.test")
        assertNotNull(g)
    }

    @Test
    fun `create with lambda args builds a client`() {
        val g = Gemini.create(apiKey = { "k" }, baseUrl = { "https://g.test" })
        assertNotNull(g)
    }

    @Test
    fun `create with config builds a client`() {
        val g = Gemini.create(GeminiConfig(apiKey = { "k" }))
        assertNotNull(g)
    }

    @Test
    fun `base url constant is the generative language endpoint`() {
        assert(Gemini.BASE_URL.contains("generativelanguage.googleapis.com"))
    }
}
