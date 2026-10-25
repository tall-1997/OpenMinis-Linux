package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.ModelOverrides
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SamplingPolicyTest {
    @Test
    fun blank_and_invalid_inputs_are_omitted() {
        assertNull(SamplingPolicy.parseUserInput(""))
        assertNull(SamplingPolicy.parseUserInput("  "))
        assertNull(SamplingPolicy.parseUserInput("hot"))
        assertNull(SamplingPolicy.canonical(Double.NaN))
        assertNull(SamplingPolicy.canonical(Double.POSITIVE_INFINITY))
        assertNull(SamplingPolicy.canonical(-0.1))
        assertNull(SamplingPolicy.canonical(2.1))
        assertEquals(0.0, SamplingPolicy.canonical(0.0)!!, 0.0)
        assertEquals(2.0, SamplingPolicy.canonical(2.0)!!, 0.0)
    }

    @Test
    fun known_reasoning_families_and_thinking_omit_the_field() {
        assertNull(SamplingPolicy.wire("inst", "gpt-5.4", 0.2, false))
        assertNull(SamplingPolicy.wire("inst", "o3-mini", 0.2, false))
        assertNull(SamplingPolicy.wire("inst", "claude-sonnet-4-6", 0.2, false))
        assertNull(SamplingPolicy.wire("inst", "deepseek-r1", 0.2, false))
        assertNull(SamplingPolicy.wire("inst", "gpt-4.1", 0.7, true))
        assertEquals(0.7, SamplingPolicy.wire("inst", "gpt-4.1", 0.7, false)!!, 0.0)
        assertEquals(0.4, SamplingPolicy.wire("inst", "my-gateway-model", 0.4, false)!!, 0.0)
    }

    @Test
    fun rejection_is_remembered_by_instance_and_model_and_is_not_rewritten_to_one() {
        SamplingPolicy.clearDeniedForTests()
        val error = LLMError.ProviderError("[400] temperature is deprecated for this model")
        assertTrue(SamplingPolicy.isTemperatureRejection(error))
        assertFalse(SamplingPolicy.isTemperatureRejection(LLMError.TransientError("timeout")))
        SamplingPolicy.deny("alpha", "custom-a")
        assertNull(SamplingPolicy.wire("alpha", "custom-a", 0.3, false))
        assertEquals(0.3, SamplingPolicy.wire("beta", "custom-a", 0.3, false)!!, 0.0)
        assertEquals(0.3, SamplingPolicy.wire("alpha", "custom-b", 0.3, false)!!, 0.0)
        SamplingPolicy.clearDeniedForTests()
    }

    @Test
    fun empty_override_includes_temperature() {
        assertTrue(ModelOverrides().isEmpty)
        assertFalse(ModelOverrides(temperature = 0.0).isEmpty)
        assertFalse(ModelOverrides(temperature = 1.0).isEmpty)
    }
}
