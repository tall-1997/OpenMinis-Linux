package com.openminis.app.ui.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class StepperClampTest {

    @Test
    fun emptyOrGarbageIsNull() {
        assertNull(parseClampedStepperValue("", 1, 8))
        assertNull(parseClampedStepperValue("  ", 1, 8))
        assertNull(parseClampedStepperValue("ab", 1, 8))
    }

    @Test
    fun inRangeUnchanged() {
        assertEquals(3, parseClampedStepperValue("3", 1, 8))
        assertEquals(600, parseClampedStepperValue("600", 30, 1800))
    }

    @Test
    fun overshootClampsToMax() {
        assertEquals(8, parseClampedStepperValue("99", 1, 8))
        assertEquals(80_000, parseClampedStepperValue("999999", 2_000, 80_000))
        assertEquals(200, parseClampedStepperValue("9000", 10, 200))
    }

    @Test
    fun undershootClampsToMin() {
        assertEquals(1, parseClampedStepperValue("0", 1, 8))
        assertEquals(30, parseClampedStepperValue("1", 30, 1800))
        assertEquals(10, parseClampedStepperValue("2", 10, 200))
    }
}
