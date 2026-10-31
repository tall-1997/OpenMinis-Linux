package com.openminis.app.ui.theme

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-decor-consumer-gate] Unit tests for the DecorClock active-consumer
 * registry — the counter that starts and stops the shared phase ticker.
 *
 * The registry is snapshot-state backed so the 0↔1 transition can drive
 * [DecorClockProvider]'s frame loop; reads/writes outside composition land on
 * the global snapshot, which is exactly what these tests exercise. The gate
 * contract they pin down:
 *
 *  1. a consumer (decorPhaseFor / decor* entry in composition) registers once;
 *  2. leaving composition unregisters exactly once (DisposableEffect pairing);
 *  3. an unbalanced unregister must never push the count negative — a negative
 *     count would keep [DecorClockConsumers.hasConsumers] true forever and the
 *     ticker would never stop again.
 */
class DecorClockConsumersTest {

    @Before
    fun reset() {
        DecorClockConsumers.activeCount.intValue = 0
    }

    @After
    fun cleanup() {
        DecorClockConsumers.activeCount.intValue = 0
    }

    /** First consumer flips the gate on: count 0→1, hasConsumers true. */
    @Test
    fun register_activatesTheClock() {
        assertFalse(DecorClockConsumers.hasConsumers)
        DecorClockConsumers.register()
        assertEquals(1, DecorClockConsumers.activeCount.intValue)
        assertTrue(DecorClockConsumers.hasConsumers)
    }

    /** Multiple simultaneous consumers each count once. */
    @Test
    fun register_countsEachConsumer() {
        DecorClockConsumers.register()
        DecorClockConsumers.register()
        DecorClockConsumers.register()
        assertEquals(3, DecorClockConsumers.activeCount.intValue)
        assertTrue(DecorClockConsumers.hasConsumers)
    }

    /** Unregister decrements; the gate closes exactly at zero. */
    @Test
    fun unregister_atZeroClosesTheGate() {
        DecorClockConsumers.register()
        DecorClockConsumers.register()
        DecorClockConsumers.unregister()
        assertEquals(1, DecorClockConsumers.activeCount.intValue)
        assertTrue("still active with one consumer left", DecorClockConsumers.hasConsumers)
        DecorClockConsumers.unregister()
        assertEquals(0, DecorClockConsumers.activeCount.intValue)
        assertFalse(DecorClockConsumers.hasConsumers)
    }

    /**
     * The underflow guard: an unbalanced unregister (composition teardown
     * racing a re-entry) must clamp at zero, never go negative — a negative
     * count would read hasConsumers=true forever and defeat the stop-the-ticker
     * optimization this registry exists for.
     */
    @Test
    fun unregister_neverUnderflowsBelowZero() {
        DecorClockConsumers.unregister()
        DecorClockConsumers.unregister()
        assertEquals(0, DecorClockConsumers.activeCount.intValue)
        assertFalse(DecorClockConsumers.hasConsumers)
        // And the counter still works afterwards.
        DecorClockConsumers.register()
        assertEquals(1, DecorClockConsumers.activeCount.intValue)
    }

    /** A full register/unregister round trip returns to the resting state. */
    @Test
    fun roundTrip_returnsToRestingState() {
        repeat(4) { DecorClockConsumers.register() }
        repeat(4) { DecorClockConsumers.unregister() }
        assertEquals(0, DecorClockConsumers.activeCount.intValue)
        assertFalse(DecorClockConsumers.hasConsumers)
    }
}
