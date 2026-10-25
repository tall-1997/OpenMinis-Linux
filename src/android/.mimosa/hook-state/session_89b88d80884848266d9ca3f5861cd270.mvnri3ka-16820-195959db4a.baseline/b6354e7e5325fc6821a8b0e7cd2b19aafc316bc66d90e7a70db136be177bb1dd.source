package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Test

class HostEventBridgeTest {

    @Test
    fun oscillationBetween14and19FiresLowOnce() {
        val lows = mutableListOf<String>()
        var inLow = false
        for (pct in listOf(16, 14, 16, 14)) {
            val (next, ev) = HostEventBridge.batteryStep(inLow, pct)
            inLow = next
            if (ev != null) lows += ev
        }
        assertEquals(listOf("battery_low"), lows)
    }

    @Test
    fun sequenceWithRecoveryThenDrop() {
        val events = mutableListOf<String>()
        var inLow = false
        for (pct in listOf(16, 14, 16, 14, 20, 14)) {
            val (next, ev) = HostEventBridge.batteryStep(inLow, pct)
            inLow = next
            if (ev != null) events += ev
        }
        // 14–19 oscillation: one low. 20 recovers. 14 after recovery is a new low.
        assertEquals(listOf("battery_low", "battery_ok", "battery_low"), events)
        assertEquals(2, events.count { it == "battery_low" })
    }
}
