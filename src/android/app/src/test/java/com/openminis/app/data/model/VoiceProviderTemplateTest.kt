package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [GH#520] Template lookup must agree with the request router about what is a
 * Bailian (DashScope / Model Studio) endpoint.
 *
 * The old template lookup was a plain `baseURL.contains(marker)` test while
 * [BailianEndpoint.matches] parses the HOST. A relay URL with
 * `/maas.aliyuncs.com` in its PATH therefore seeded Alibaba ASR/TTS voice
 * models at add-time, but [com.openminis.app.provider.voice.VoiceProviderFactory]
 * routed the instance to the generic OpenAI-compatible adapter — every seeded
 * voice call then 404'd. These cases pin the shared predicate.
 */
class VoiceProviderTemplateTest {

    @Test
    fun `legacy dashscope base seeds the alibaba template`() {
        assertEquals(
            "alibaba",
            VoiceProviderTemplate.template("https://dashscope.aliyuncs.com/compatible-mode")?.id,
        )
    }

    @Test
    fun `maas workspace host seeds the alibaba template`() {
        assertEquals(
            "alibaba",
            VoiceProviderTemplate.template("https://ws-1234.cn-beijing.maas.aliyuncs.com/compatible-mode")?.id,
        )
    }

    @Test
    fun `pseudo maas hosts are not bailian`() {
        // [GH#520] Both used to match on the substring and seed Alibaba models.
        assertNull(VoiceProviderTemplate.template("https://relay.example/maas.aliyuncs.com"))
        assertNull(VoiceProviderTemplate.template("https://x.maas.aliyuncs.com.evil.example/v1"))
    }

    @Test
    fun `other vendors keep their marker matching`() {
        assertEquals("elevenlabs", VoiceProviderTemplate.template("https://api.elevenlabs.io")?.id)
        assertEquals("minimax", VoiceProviderTemplate.template("https://api.minimax.io")?.id)
        assertEquals("deepgram", VoiceProviderTemplate.template("https://api.deepgram.com")?.id)
    }
}
