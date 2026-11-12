package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [OpenMinis#305] Dedicated ASR/TTS inference for providers whose `/v1/models`
 * response declares no audio modality — the reported "Bailian / SiliconFlow ASR
 * model list is empty".
 *
 * SiliconFlow serves `FunAudioLLM/SenseVoiceSmall` (ASR) and
 * `FunAudioLLM/CosyVoice2-0.5B` (TTS); Bailian serves `paraformer-*` (ASR) and
 * `cosyvoice-*` / `sambert-*` (TTS). None of those names carried the old
 * `-asr` / `tts` tokens, and the old inference gate also refused to touch a
 * model whose modality lists were present-but-text-only — which is exactly what
 * those endpoints return. Both halves are pinned here.
 */
class VoiceModalityInferenceTest {

    private fun textOnly(id: String) = LLMModel(
        id = id,
        displayName = id,
        provider = "TestProvider",
        inputModalities = listOf("text"),
        outputModalities = listOf("text"),
    )

    @Test
    fun `sensevoice is inferred as ASR even when the API says text-only`() {
        val m = textOnly("FunAudioLLM/SenseVoiceSmall").withInferredVoiceModality()
        assertTrue("SenseVoice is a dedicated ASR model: $m", m.hasAudioInput)
        assertFalse(m.hasAudioOutput)
    }

    @Test
    fun `cosyvoice is inferred as TTS even when the API says text-only`() {
        val m = textOnly("FunAudioLLM/CosyVoice2-0.5B").withInferredVoiceModality()
        assertTrue("CosyVoice is a dedicated TTS model: $m", m.hasAudioOutput)
        assertFalse(m.hasAudioInput)
    }

    @Test
    fun `bailian paraformer and sambert are classified`() {
        assertTrue(textOnly("paraformer-realtime-v2").withInferredVoiceModality().hasAudioInput)
        assertTrue(textOnly("sambert-zhichu-v1").withInferredVoiceModality().hasAudioOutput)
    }

    @Test
    fun `qwen3 asr keeps working`() {
        assertTrue(textOnly("qwen3-asr-flash").withInferredVoiceModality().hasAudioInput)
    }

    @Test
    fun `richer modalities are never relabelled by an id match`() {
        val vision = LLMModel(
            id = "cosyvoice-vision-experiment",
            displayName = "cosyvoice-vision-experiment",
            provider = "TestProvider",
            inputModalities = listOf("text", "image"),
            outputModalities = listOf("text"),
        ).withInferredVoiceModality()
        assertEquals(listOf("text", "image"), vision.inputModalities)
        assertFalse("a vision model must not be turned into a TTS model by name", vision.hasAudioOutput)
    }
}
