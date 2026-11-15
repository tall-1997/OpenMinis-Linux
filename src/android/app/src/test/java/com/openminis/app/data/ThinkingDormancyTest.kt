package com.openminis.app.data

import com.openminis.app.data.model.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-thinking-dormant-restore] (P2 1-8) Pure core of the ceiling-sync
 * park/restore cycle. Pins the three behaviours the fix is made of: demotion
 * PARKS the original choice instead of destroying it, a widened ceiling
 * restores the park exactly once, and every user authority outranks the park
 * (represented here by the caller clearing the slot before calling sync —
 * the VM wiring guarantees that ordering).
 */
class ThinkingDormancyTest {

    // ---- encode / decode round-trip ----

    @Test
    fun `encode keeps enabled levels and folds OFF to null`() {
        assertEquals("HIGH", ThinkingDormancy.encode(ThinkingLevel.HIGH))
        assertEquals("ULTRA", ThinkingDormancy.encode(ThinkingLevel.ULTRA))
    }

    @Test
    fun `encode OFF is null`() {
        assertNull(ThinkingDormancy.encode(ThinkingLevel.OFF))
        assertNull(ThinkingDormancy.encode(null))
    }

    @Test
    fun `decode round-trips every parkable level`() {
        for (level in ThinkingLevel.entries) {
            val encoded = ThinkingDormancy.encode(level)
            if (level == ThinkingLevel.OFF) {
                assertNull("OFF is never parked: $level", encoded)
            } else {
                assertEquals(level, ThinkingDormancy.decode(encoded))
            }
        }
    }

    @Test
    fun `decode tolerates stale and unknown names`() {
        assertNull(ThinkingDormancy.decode(null))
        assertNull(ThinkingDormancy.decode(""))
        assertNull(ThinkingDormancy.decode("SOMETHING_FUTURE"))
        // A legacy row that somehow parked OFF decodes to "no park".
        assertNull(ThinkingDormancy.decode("OFF"))
    }

    // ---- sync: demote parks, widened ceiling restores, else passthrough ----

    @Test
    fun `demotion parks the original choice instead of erasing it`() {
        val result = ThinkingDormancy.sync(
            current = ThinkingLevel.HIGH,
            ceiling = ThinkingLevel.OFF,
            dormant = null,
        )
        assertEquals(ThinkingLevel.OFF, result.level)
        assertEquals(ThinkingLevel.HIGH, result.dormant)
        assertEquals(true, result.changed)
    }

    @Test
    fun `demotion to a mid ceiling parks the original choice`() {
        val result = ThinkingDormancy.sync(
            current = ThinkingLevel.XHIGH,
            ceiling = ThinkingLevel.MEDIUM,
            dormant = null,
        )
        assertEquals(ThinkingLevel.MEDIUM, result.level)
        assertEquals(ThinkingLevel.XHIGH, result.dormant)
        assertEquals(true, result.changed)
    }

    @Test
    fun `demotion never parks OFF`() {
        val result = ThinkingDormancy.sync(
            current = ThinkingLevel.OFF,
            ceiling = ThinkingLevel.OFF,
            dormant = null,
        )
        assertEquals(ThinkingLevel.OFF, result.level)
        assertNull(result.dormant)
        assertEquals(false, result.changed)
    }

    @Test
    fun `widened ceiling restores the parked choice exactly once`() {
        val result = ThinkingDormancy.sync(
            current = ThinkingLevel.OFF,
            ceiling = ThinkingLevel.ULTRA,
            dormant = ThinkingLevel.HIGH,
        )
        assertEquals(ThinkingLevel.HIGH, result.level)
        assertNull("the park must clear on restore", result.dormant)
        assertEquals(true, result.changed)
    }

    @Test
    fun `parked choice above the widened ceiling stays parked`() {
        val result = ThinkingDormancy.sync(
            current = ThinkingLevel.LOW,
            ceiling = ThinkingLevel.MEDIUM,
            dormant = ThinkingLevel.XHIGH,
        )
        // Nothing to clamp and the ceiling still cannot reach the park.
        assertEquals(ThinkingLevel.LOW, result.level)
        assertEquals(ThinkingLevel.XHIGH, result.dormant)
        assertEquals(false, result.changed)
    }

    @Test
    fun `no clamp and no park is a passthrough with no persistence`() {
        val result = ThinkingDormancy.sync(
            current = ThinkingLevel.MEDIUM,
            ceiling = ThinkingLevel.MAX,
            dormant = null,
        )
        assertEquals(ThinkingLevel.MEDIUM, result.level)
        assertNull(result.dormant)
        assertEquals(false, result.changed)
    }

    @Test
    fun `full cycle demote then restore returns the original level`() {
        // The end-to-end property P2 1-8 asks for: lower the ceiling, raise it
        // back, and the user's level came back without a re-pick.
        val demoted = ThinkingDormancy.sync(
            current = ThinkingLevel.HIGH,
            ceiling = ThinkingLevel.OFF,
            dormant = null,
        )
        val restored = ThinkingDormancy.sync(
            current = demoted.level,
            ceiling = ThinkingLevel.ULTRA,
            dormant = demoted.dormant,
        )
        assertEquals(ThinkingLevel.HIGH, restored.level)
        assertNull(restored.dormant)
        assertEquals(true, restored.changed)
    }

    @Test
    fun `restore only fires when nothing else clamps first`() {
        // A park that itself exceeds the CURRENT ceiling cannot restore — the
        // clamp branch wins (the park is not newer than the ceiling).
        val result = ThinkingDormancy.sync(
            current = ThinkingLevel.HIGH,
            ceiling = ThinkingLevel.MEDIUM,
            dormant = ThinkingLevel.XHIGH,
        )
        assertEquals(ThinkingLevel.MEDIUM, result.level)
        assertEquals(ThinkingLevel.HIGH, result.dormant)
        assertEquals(true, result.changed)
    }
}
