package com.openminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [P2-convergence] The Zen structured-refusal body is now parsed once
 * ([ZenErrorBody.parse]) and read by both the free-lane classifier
 * ([FreeLaneBodies.errorType]) and the OpenAI chat error mapper
 * (`zenStructuredProviderRefusal`). These tests pin the shared parse and the
 * parity between the two consumers.
 */
class ZenErrorBodyTest {

    @Test
    fun bareEnvelopeIsParsedWithTypeAndMessage() {
        val zen = ZenErrorBody.parse(
            """{"type":"error","error":{"type":"FreeTierError","message":"free tier refused"}}""",
        )!!
        assertEquals("error", zen.envelopeType)
        assertEquals("FreeTierError", zen.errorType)
        assertEquals("free tier refused", zen.errorMessage)
    }

    @Test
    fun sseFramedErrorIsFoundWithUnknownEnvelope() {
        val zen = ZenErrorBody.parse(
            "event: error\ndata: {\"error\":{\"type\":\"ModelError\",\"message\":\"refused\"}}\n\n",
        )!!
        // SSE frames carry no outer type field — the chat mapper's gate must see "".
        assertEquals("", zen.envelopeType)
        assertEquals("ModelError", zen.errorType)
        assertEquals("ModelError", FreeLaneBodies.errorType("event: x\ndata: {\"error\":{\"type\":\"ModelError\"}}\n\n"))
    }

    @Test
    fun bodiesWithoutStructureReturnNull() {
        assertNull(ZenErrorBody.parse("Model is unavailable."))
        assertNull(ZenErrorBody.parse("""{"error":"string shape has no structure"}"""))
        assertNull(ZenErrorBody.parse(""))
        assertNull(FreeLaneBodies.errorType("""{"error":"string shape has no structure"}"""))
    }

    @Test
    fun chatMapperAndLaneClassifierAgreeOnTheSameBodies() {
        val freeTier = """{"type":"error","error":{"type":"FreeTierError","message":"m"}}"""
        val region = """{"type":"error","error":{"type":"RegionError","message":"m"}}"""
        val model = """{"type":"error","error":{"type":"ModelError","message":"m"}}"""
        val other = """{"type":"error","error":{"type":"BrandNewError","message":"m"}}"""
        val plain = """{"error":{"message":"no channel"}}"""

        // Envelope gate: only the bare "type":"error" spelling maps.
        val refusals = listOf(
            com.openminis.app.provider.openai.zenStructuredProviderRefusal(freeTier)!!,
            com.openminis.app.provider.openai.zenStructuredProviderRefusal(region)!!,
            com.openminis.app.provider.openai.zenStructuredProviderRefusal(model)!!,
        ).map { it as com.openminis.app.data.model.LLMError.ProviderError }
        assertTrue(refusals[0].detail.startsWith("FreeTierError:"))
        assertTrue(refusals[1].detail.startsWith("RegionError:"))
        assertTrue(refusals[2].detail.startsWith("ModelError:"))
        // Unmapped dialect word and non-error envelope stay null (generic path).
        assertNull(com.openminis.app.provider.openai.zenStructuredProviderRefusal(other))
        assertNull(com.openminis.app.provider.openai.zenStructuredProviderRefusal(plain))
        // The lane classifier reads the same bodies through the same parse.
        assertEquals("BrandNewError", FreeLaneBodies.errorType(other))
        assertEquals("FreeTierError", FreeLaneBodies.errorType(freeTier))
        assertNull(FreeLaneBodies.errorType(plain))
    }
}
