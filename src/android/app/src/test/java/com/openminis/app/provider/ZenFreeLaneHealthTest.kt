package com.openminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-zen-free-lane-follow] The retirement record that replaced the shipped
 * hard-coded free table. Contract under test: a mark evicts an id from the
 * picker, expires after the TTL so a wrong mark self-heals, and round-trips
 * through the prefs serialization. No Android context in unit tests — the
 * record lives in memory and persistence is a no-op, which is exactly the
 * mode provider-level tests run in.
 */
class ZenFreeLaneHealthTest {

    private val now = 1_700_000_000_000L

    @Test
    fun `a fresh mark is dead`() {
        ZenFreeLaneHealth.recordDead("mimo-v2.5-free", now)
        assertTrue(ZenFreeLaneHealth.isDead("mimo-v2.5-free", now))
        assertTrue("mimo-v2.5-free" in ZenFreeLaneHealth.deadIds(now))
        ZenFreeLaneHealth.revive("mimo-v2.5-free")
    }

    @Test
    fun `a mark expires after the TTL`() {
        ZenFreeLaneHealth.recordDead("exo-free", now)
        val justInside = now + ZenFreeLaneHealth.DEAD_TTL_MS - 1
        val justOutside = now + ZenFreeLaneHealth.DEAD_TTL_MS
        assertTrue(ZenFreeLaneHealth.isDead("exo-free", justInside))
        assertFalse(ZenFreeLaneHealth.isDead("exo-free", justOutside))
        ZenFreeLaneHealth.revive("exo-free")
    }

    @Test
    fun `a future timestamp is not dead`() {
        // Clock skew guard: a mark stamped in the future must not read as
        // "expired" via a negative age.
        ZenFreeLaneHealth.recordDead("skew-free", now)
        assertFalse(ZenFreeLaneHealth.isDead("skew-free", now - 1))
        ZenFreeLaneHealth.revive("skew-free")
    }

    @Test
    fun `revive clears the mark and is a no-op on unknown ids`() {
        ZenFreeLaneHealth.recordDead("jev-1.13-free", now)
        ZenFreeLaneHealth.revive("jev-1.13-free")
        assertFalse(ZenFreeLaneHealth.isDead("jev-1.13-free", now))
        ZenFreeLaneHealth.revive("never-recorded") // must not throw
    }

    @Test
    fun `blank ids are ignored`() {
        ZenFreeLaneHealth.recordDead("", now)
        ZenFreeLaneHealth.recordDead("   ", now)
        assertTrue(ZenFreeLaneHealth.deadIds(now).none { it.isBlank() })
    }

    @Test
    fun `serialization round-trips`() {
        val map = mapOf("a-free" to 1L, "b-free" to Long.MAX_VALUE, "big-pickle" to 0L)
        val raw = ZenFreeLaneHealth.serializeDeadRecord(map)
        assertEquals(map, ZenFreeLaneHealth.deserializeDeadRecord(raw))
    }

    @Test
    fun `deserialization skips malformed pairs instead of failing`() {
        val raw = "a-free=1,,b-free=not-a-number,c-free=2,=3,no-equals"
        assertEquals(mapOf("a-free" to 1L, "c-free" to 2L), ZenFreeLaneHealth.deserializeDeadRecord(raw))
        assertEquals(emptyMap<String, Long>(), ZenFreeLaneHealth.deserializeDeadRecord(""))
    }
}
