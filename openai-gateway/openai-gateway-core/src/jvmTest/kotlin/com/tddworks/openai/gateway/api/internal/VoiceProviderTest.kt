package com.tddworks.openai.gateway.api.internal

import com.tddworks.openai.gateway.api.OpenAIProvider
import com.tddworks.openai.gateway.config.Capabilities
import com.tddworks.openai.gateway.config.Dialect
import com.tddworks.openai.gateway.config.ProviderAuth
import com.tddworks.openai.gateway.config.ProviderConfig
import com.tddworks.openai.gateway.config.VoiceMode
import com.tddworks.voice.api.VoiceVendor
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class VoiceProviderTest {

    private fun voiceConfig(mode: VoiceMode?, id: String = "v", aliases: Map<String, String> = emptyMap()) =
        ProviderConfig(
            id = id,
            dialect = Dialect.VOICE_REALTIME,
            baseUrl = "https://voice.test",
            auth = ProviderAuth(apiKey = "k"),
            capabilities = Capabilities(voice = mode),
            aliases = aliases,
        )

    @Test
    fun `maps LIVE to Gemini Live with v1alpha`() {
        val vc = voiceConfig(VoiceMode.LIVE, id = "gemini-live").toVoiceConfig()
        assertEquals(VoiceVendor.GEMINI_LIVE, vc.vendor)
        assertEquals("v1alpha", vc.apiVersion)
        assertEquals("gemini-live", vc.model())
        assertEquals("k", vc.apiKey())
        assertEquals("https://voice.test", vc.baseUrl())
    }

    @Test
    fun `maps REALTIME to OpenAI Realtime with v1beta`() {
        val vc = voiceConfig(VoiceMode.REALTIME).toVoiceConfig()
        assertEquals(VoiceVendor.OPENAI_REALTIME, vc.vendor)
        assertEquals("v1beta", vc.apiVersion)
    }

    @Test
    fun `maps TURN_STREAM to Qwen TTS`() {
        val vc = voiceConfig(VoiceMode.TURN_STREAM).toVoiceConfig()
        assertEquals(VoiceVendor.QWEN_TTS, vc.vendor)
    }

    @Test
    fun `resolves model from first alias when present`() {
        val vc =
            voiceConfig(VoiceMode.REALTIME, aliases = mapOf("fast" to "gpt-4o-realtime")).toVoiceConfig()
        assertEquals("gpt-4o-realtime", vc.model())
    }

    @Test
    fun `throws when voice capability is not set`() {
        assertThrows<IllegalArgumentException> { voiceConfig(mode = null).toVoiceConfig() }
    }

    @Test
    fun `from(config) redirects VOICE_REALTIME to voiceSession`() {
        val ex =
            assertThrows<IllegalArgumentException> {
                OpenAIProvider.from(voiceConfig(VoiceMode.REALTIME))
            }
        assert(ex.message!!.contains("voiceSession")) { "message should redirect: ${ex.message}" }
    }
}
