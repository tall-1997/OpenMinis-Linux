package com.openminis.app.data.model

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageModalityTest {
    @Test
    fun `seedream infers image output`() {
        val m = LLMModel("doubao-seedream-4-0", "Seedream", "Ark").withInferredImageModality()
        assertTrue(m.isImageOutput)
        assertTrue(m.looksLikeImageGenerator)
        assertFalse(m.isTextOutput)
    }

    @Test
    fun `cogview and hunyuan-image infer image output`() {
        assertTrue(LLMModel("cogview-4", "CogView 4", "Zhipu").withInferredImageModality().isImageOutput)
        assertTrue(LLMModel("hunyuan-image-v2.1", "混元生图", "Hunyuan").withInferredImageModality().isImageOutput)
        assertTrue(LLMModel("glm-image", "GLM Image", "Zhipu").withInferredImageModality().isImageOutput)
        assertTrue(LLMModel("qwen-image-plus", "Qwen Image", "DashScope").withInferredImageModality().isImageOutput)
    }

    @Test
    fun `vision chat models are not image generators`() {
        val m = LLMModel.gpt4oMini.withInferredImageModality()
        assertFalse(m.looksLikeImageGenerator)
        assertFalse(m.isImageOutput)
        assertTrue(m.isTextOutput)
    }

    @Test
    fun `explicit catalog image is not overwritten`() {
        val m = LLMModel(
            "custom",
            "Custom",
            "relay",
            outputModalities = listOf("text", "image"),
        ).withInferredImageModality()
        assertTrue(m.isImageOutput)
        assertTrue(m.isTextOutput)
    }
}
