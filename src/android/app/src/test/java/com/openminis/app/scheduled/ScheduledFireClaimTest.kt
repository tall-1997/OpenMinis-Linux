package com.openminis.app.scheduled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-android-scheduled-fire-claim] A calendar ONCE row is claimed by disabling
 * it. AlarmManager hands the same PendingIntent to the receiver again when a
 * cold start races the delivery, and a second claim of the same fire means the
 * task runs twice — a duplicate bill on a paid model, and a duplicate side
 * effect for anything that writes.
 *
 * The flip itself is atomic because the caller runs it inside
 * [ScheduledTaskStore.update]; what is pinned here is the decision, so a
 * rewrite of the caller cannot quietly turn "already disabled" into "run it".
 */
class ScheduledFireClaimTest {

    private fun row(enabled: Boolean) = ScheduledTask(
        id = "once",
        label = "send the report",
        timeOfDayHour = 9,
        timeOfDayMinute = 0,
        repeatMode = ScheduledRepeatMode.ONCE,
        prompt = "p",
        createdAt = 1_800_000_000_000L,
        enabled = enabled,
    )

    @Test
    fun `the first delivery disables the row and runs`() {
        val claimed = ScheduledTaskManager.claimOnceFire(row(enabled = true))

        assertEquals(false, claimed?.enabled)
        // Everything else must survive: the row is kept in the list so the user
        // can still read its last result and re-enable it.
        assertEquals("once", claimed?.id)
        assertEquals("send the report", claimed?.label)
        assertEquals("p", claimed?.prompt)
    }

    @Test
    fun `a duplicate delivery of a row that already fired does not run`() {
        // Already disabled: this delivery belongs to a fire that happened.
        assertNull(ScheduledTaskManager.claimOnceFire(row(enabled = false)))
    }

    @Test
    fun `a row disabled by the user is not woken up by its old alarm`() {
        val stored = row(enabled = false)
        assertFalse(stored.enabled)
        assertNull(ScheduledTaskManager.claimOnceFire(stored))
    }
}
