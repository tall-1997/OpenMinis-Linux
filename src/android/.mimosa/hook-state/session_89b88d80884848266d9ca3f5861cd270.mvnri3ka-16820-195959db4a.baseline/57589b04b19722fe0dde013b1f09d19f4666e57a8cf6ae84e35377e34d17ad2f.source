package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TokenUsageCalibrationTest {

    @Test
    fun startsAtOne() {
        assertEquals(1.0, TokenUsageCalibration.scale("test"), 0.0001)
    }

    @Test
    fun estimateUnchangedBeforeObservation() {
        val raw = 1000
        assertEquals(raw, TokenUsageCalibration.estimate("test2", raw))
    }

    @Test
    fun singleObservationMovesScale() {
        TokenUsageCalibration.observe("test3", 1000, 4000) // ratio=4, EMA → 1.0*0.7+4*0.3=1.9
        assertEquals(1.9, TokenUsageCalibration.scale("test3"), 0.01)
    }

    @Test
    fun estimateAppliesScale() {
        TokenUsageCalibration.observe("test4", 500, 1500) // ratio=3, EMA→1.0*0.7+3*0.3=1.6
        val estimated = TokenUsageCalibration.estimate("test4", 100)
        // 100 * 1.6 = 160
        assertEquals(160, estimated)
    }

    @Test
    fun ratioClampedToRange() {
        // ratio = 10000/10 = 1000 → clamped to 8
        TokenUsageCalibration.observe("test5", 10, 10000)
        assertTrue(TokenUsageCalibration.scale("test5") <= 8.0)
        
        // ratio = 1/1000 = 0.001 → clamped to 1
        TokenUsageCalibration.observe("test6", 1000, 1)
        assertTrue(TokenUsageCalibration.scale("test6") >= 1.0)
    }

    @Test
    fun blankModelKeyIsNoop() {
        val before = TokenUsageCalibration.scale("")
        TokenUsageCalibration.observe("", 100, 500)
        assertEquals(before, TokenUsageCalibration.scale(""), 0.0001)
    }

    @Test
    fun invalidValuesAreNoop() {
        val key = "test7"
        TokenUsageCalibration.observe(key, 0, 500)
        TokenUsageCalibration.observe(key, 500, 0)
        assertEquals(1.0, TokenUsageCalibration.scale(key), 0.0001)
    }
}