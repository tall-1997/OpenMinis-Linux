package com.openminis.app.ui.chat

import com.openminis.app.data.ThinkingDormancy
import com.openminis.app.data.model.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-thinking-sync-notice] (P2-8) Pure-function pins for the ceiling-sync
 * notice selector: demote parks the old choice (dormant != null) → CLAMPED
 * notice ("temporarily lowered to match the ceiling"); restore clears the
 * park (dormant == null) → RESTORED notice ("ceiling restored, back to
 * X"). The collector emits both system-info lines from exactly these two
 * `changed` branches — extracting the choice keeps the wording selection
 * JVM-tested (the string resources themselves are covered by the locale gate).
 */
class ThinkingSyncNoticeTest {

    @Test
    fun `demotion (park created) maps to the clamped notice`() {
        val demote = ThinkingDormancy.sync(
            current = ThinkingLevel.HIGH,
            ceiling = ThinkingLevel.OFF,
            dormant = null,
        )
        assertEquals(ThinkingSyncNotice.CLAMPED, thinkingSyncNotice(demote))
    }

    @Test
    fun `demotion to a mid ceiling still maps to the clamped notice`() {
        val demote = ThinkingDormancy.sync(
            current = ThinkingLevel.XHIGH,
            ceiling = ThinkingLevel.MEDIUM,
            dormant = null,
        )
        assertEquals(ThinkingSyncNotice.CLAMPED, thinkingSyncNotice(demote))
    }

    @Test
    fun `restore (park cleared) maps to the restored notice`() {
        val restore = ThinkingDormancy.sync(
            current = ThinkingLevel.OFF,
            ceiling = ThinkingLevel.ULTRA,
            dormant = ThinkingLevel.HIGH,
        )
        assertEquals(ThinkingSyncNotice.RESTORED, thinkingSyncNotice(restore))
    }

    @Test
    fun `a passthrough decision still has a total outcome`() {
        // changed=false never reaches the notice site (guarded by
        // decision.changed); the selector stays total for future branches.
        val passthrough = ThinkingDormancy.sync(
            current = ThinkingLevel.MEDIUM,
            ceiling = ThinkingLevel.MAX,
            dormant = null,
        )
        assertEquals(ThinkingSyncNotice.RESTORED, thinkingSyncNotice(passthrough))
    }
}
