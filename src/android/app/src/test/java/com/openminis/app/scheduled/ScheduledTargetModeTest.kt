package com.openminis.app.scheduled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-android-scheduled-default-follow-up] This fork's three targets must keep
 * round-tripping through the stored string form — a create that lands as
 * follow-up is only useful if the fire path decodes the same session id back.
 */
class ScheduledTargetModeTest {
    @Test
    fun `every target mode round-trips through encode and decode`() {
        val modes = listOf(
            ScheduledTargetMode.NewSession,
            ScheduledTargetMode.AppendToSession("S1"),
            ScheduledTargetMode.RerunMessage("S1", "M1"),
        )
        for (m in modes) assertEquals(m, ScheduledTargetMode.decode(m.encode()))
        assertEquals("APPEND_TO:S1", ScheduledTargetMode.AppendToSession("S1").encode())
        assertEquals("S1", ScheduledTargetMode.AppendToSession("S1").sessionIdOrNull)
        assertNull(ScheduledTargetMode.NewSession.sessionIdOrNull)
        assertEquals("S1", ScheduledTargetMode.RerunMessage("S1", "M1").sessionIdOrNull)
        assertEquals(ScheduledTargetMode.NewSession, ScheduledTargetMode.decode("garbage"))
    }
}
