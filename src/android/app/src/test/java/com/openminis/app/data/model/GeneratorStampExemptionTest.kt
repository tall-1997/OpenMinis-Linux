package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure generators (output = image/video/audio, no text) have no
 * "output token" concept — they run the generation path and never consume
 * maxOutputTokens. Stamping 256k context / 128k output on them was wrong
 * information that misled the UI and budget decisions (真机实锤:
 * `opc-image-v1` landed with 256000/128000).
 *
 * These tests lock the exemption: after modality inference, a model whose
 * output modalities are explicitly non-empty and contain no `text` keeps
 * context/output null. Text-capable models keep the existing stamp.
 */
class GeneratorStampExemptionTest {

    @Test
    fun `inferred video generator keeps context and output null`() {
        val m = applyUnrecognizedModelDefaults(LLMModel("sora-2", "Sora 2", "OpenAI"))
        assertEquals(listOf("video"), m.outputModalities)
        assertNull(m.contextWindow)
        assertNull(m.maxOutputTokens)
        assertTrue(m.isPureVideoGenerator)
    }

    @Test
    fun `inferred image generator keeps context and output null`() {
        val m = applyUnrecognizedModelDefaults(
            LLMModel("doubao-seedream-4-0", "Seedream", "Ark"),
        )
        assertEquals(listOf("image"), m.outputModalities)
        assertNull(m.contextWindow)
        assertNull(m.maxOutputTokens)
        assertTrue(m.isImageOutput)
    }

    @Test
    fun `explicit image-only output keeps context and output null`() {
        // 真机案例：OPC 实例 opc-image-v1，in=['text','image'] out=['image']
        val m = applyUnrecognizedModelDefaults(
            LLMModel(
                "opc-image-v1",
                "OPC Image",
                "OPC",
                inputModalities = listOf("text", "image"),
                outputModalities = listOf("image"),
            ),
        )
        assertEquals(listOf("image"), m.outputModalities)
        assertEquals(listOf("text", "image"), m.inputModalities)
        assertNull(m.contextWindow)
        assertNull(m.maxOutputTokens)
    }

    @Test
    fun `unknown text model still gets the stamp`() {
        val m = applyUnrecognizedModelDefaults(LLMModel("random-chat-xyz", "Chat", "ACME"))
        assertEquals(256_000, m.contextWindow)
        assertEquals(128_000, m.maxOutputTokens)
    }

    @Test
    fun `mixed text and image output still gets the stamp`() {
        val m = applyUnrecognizedModelDefaults(
            LLMModel("hybrid-1", "Hybrid", "ACME", outputModalities = listOf("text", "image")),
        )
        assertEquals(256_000, m.contextWindow)
        assertEquals(128_000, m.maxOutputTokens)
    }

    @Test
    fun `generator with explicit budgets keeps them untouched`() {
        val m = applyUnrecognizedModelDefaults(
            LLMModel(
                "sora-2",
                "Sora 2",
                "OpenAI",
                contextWindow = 32_000,
                maxOutputTokens = 4_096,
            ),
        )
        assertEquals(listOf("video"), m.outputModalities)
        assertEquals(32_000, m.contextWindow)
        assertEquals(4_096, m.maxOutputTokens)
    }
}
