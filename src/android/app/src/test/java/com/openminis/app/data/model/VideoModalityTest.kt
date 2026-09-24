package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoModalityTest {
    @Test
    fun `sora id infers pure video generator`() {
        val m = LLMModel("sora-2", "Sora 2", "OpenAI").withInferredVideoModality()
        assertTrue(m.isVideoOutput)
        assertTrue(m.isPureVideoGenerator)
        assertFalse(m.isTextOutput)
    }

    @Test
    fun `veo id infers video output`() {
        val m = LLMModel("veo-3.0-generate-preview", "Veo 3", "Google").withInferredVideoModality()
        assertTrue(m.isPureVideoGenerator)
    }

    @Test
    fun `gpt-4o is not a video generator`() {
        val m = LLMModel.gpt4oMini.withInferredVideoModality()
        assertFalse(m.isVideoOutput)
        assertFalse(m.isPureVideoGenerator)
        assertTrue(m.isTextOutput)
    }

    @Test
    fun `seedance and doubao-video infer video output`() {
        assertTrue(
            LLMModel("doubao-seedance-1-0-pro", "Seedance", "Ark")
                .withInferredVideoModality().isPureVideoGenerator,
        )
        assertTrue(
            LLMModel("doubao-video-gen-01", "豆包文生视频", "Ark")
                .withInferredVideoModality().isPureVideoGenerator,
        )
        assertTrue(
            LLMModel("hunyuan-video", "混元视频", "Hunyuan")
                .withInferredVideoModality().isVideoOutput,
        )
    }

    @Test
    fun `explicit catalog video is not overwritten`() {
        val m = LLMModel(
            "custom",
            "Custom",
            "relay",
            outputModalities = listOf("text", "video"),
        ).withInferredVideoModality()
        assertTrue(m.isVideoOutput)
        assertFalse(m.isPureVideoGenerator)
        assertTrue(m.isTextOutput)
    }

    @Test
    fun `empty catalog sora is not stamped text-only`() {
        val m = applyUnrecognizedModelDefaults(LLMModel("sora-2", "Sora 2", "OpenAI"))
        assertTrue(m.isPureVideoGenerator)
        assertFalse(m.outputModalities.orEmpty().contains("text"))
    }

    @Test
    fun `persisted empty-catalog text stamp is repaired`() {
        val stamped = applyUnrecognizedModelDefaults(LLMModel("sora-2", "Sora 2", "OpenAI"))
            .copy(outputModalities = listOf("text"), inputModalities = listOf("text"))
        val healed = applyUnrecognizedModelDefaults(stripDefaultedGeneratorModality(stamped))
        assertTrue(healed.isPureVideoGenerator)
    }

    @Test
    fun `catalog text with a real context window is not inferred`() {
        val m = applyUnrecognizedModelDefaults(
            LLMModel(
                "sora-2",
                "Sora 2",
                "OpenAI",
                contextWindow = 32_000,
                outputModalities = listOf("text"),
            ),
        )
        assertEquals(listOf("text"), m.outputModalities)
        assertFalse(m.isPureVideoGenerator)
    }

    @Test
    fun `empty catalog seedream infers image not text`() {
        val m = applyUnrecognizedModelDefaults(LLMModel("doubao-seedream-4-0", "Seedream", "Ark"))
        assertTrue(m.isImageOutput)
        assertFalse(m.outputModalities.orEmpty().contains("text"))
    }
}
