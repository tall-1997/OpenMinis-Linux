package com.openminis.app.scheduled

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-scheduled-triggers] The driver half of ON_COMPLETION: who waits,
 * when an upstream counts as finished, and what gets pasted into
 * `{{result}}`. The firing itself is [ScheduledCompletionTriggers.onUpstreamFinished],
 * which needs a Context — covered by the pure parts it delegates to.
 */
class ScheduledCompletionTriggersTest {

    private val t0 = 1_800_000_000_000L

    private fun task(
        id: String = "dep",
        kind: ScheduledTriggerKind = ScheduledTriggerKind.ON_COMPLETION,
        of: String? = "up",
        enabled: Boolean = true,
        intervalSec: Long? = null,
        maxFires: Int? = null,
        triggered: Int? = null,
        repeat: ScheduledRepeatMode = ScheduledRepeatMode.DAILY,
        endDateMs: Long? = null,
    ) = ScheduledTask(
        id = id,
        label = "waiting",
        timeOfDayHour = 9,
        timeOfDayMinute = 0,
        repeatMode = repeat,
        prompt = "Check: {{result}}",
        enabled = enabled,
        createdAt = t0,
        triggerKind = kind,
        intervalSec = intervalSec,
        maxFires = maxFires,
        triggeredCount = triggered,
        onCompletionOf = of,
        endDateMs = endDateMs,
    )

    @Test
    fun `only enabled tasks waiting on that id are dependents`() {
        val waiting = task()
        val other = task(id = "b", of = "someone-else")
        val disabled = task(id = "c", enabled = false)
        val calendar = task(id = "d", kind = ScheduledTriggerKind.CALENDAR, of = null)

        val deps = ScheduledCompletionTriggers.dependents(
            listOf(waiting, other, disabled, calendar),
            "up",
        )
        assertEquals(listOf("dep"), deps.map { it.id })
    }

    @Test
    fun `a one-shot upstream is finished by its run`() {
        assertTrue(ScheduledCompletionTriggers.scheduleFinished(task(kind = ScheduledTriggerKind.AFTER), t0))
        assertTrue(
            ScheduledCompletionTriggers.scheduleFinished(
                task(kind = ScheduledTriggerKind.ON_COMPLETION, of = null),
                t0,
            ),
        )
        assertTrue(
            ScheduledCompletionTriggers.scheduleFinished(
                task(kind = ScheduledTriggerKind.CALENDAR, repeat = ScheduledRepeatMode.ONCE),
                t0,
            ),
        )
    }

    @Test
    fun `an interval upstream finishes only when its count is used up`() {
        val running = task(
            kind = ScheduledTriggerKind.INTERVAL,
            intervalSec = 600,
            maxFires = 6,
            triggered = 2,
        )
        assertFalse("check 2 of 6 must not release dependents", ScheduledCompletionTriggers.scheduleFinished(running, t0))
        val done = running.copy(triggeredCount = 6)
        assertTrue(ScheduledCompletionTriggers.scheduleFinished(done, t0))
        // Unbounded: never "finished", only deleted — same as a cron job on iOS.
        assertFalse(
            ScheduledCompletionTriggers.scheduleFinished(
                running.copy(maxFires = null, triggeredCount = 99),
                t0,
            ),
        )
    }

    @Test
    fun `a repeating calendar upstream finishes only after its end date`() {
        assertFalse(
            ScheduledCompletionTriggers.scheduleFinished(
                task(kind = ScheduledTriggerKind.CALENDAR, endDateMs = null),
                t0,
            ),
        )
        // End date a week back: no slot left, so its schedule is over.
        assertTrue(
            ScheduledCompletionTriggers.scheduleFinished(
                task(kind = ScheduledTriggerKind.CALENDAR, endDateMs = t0 - 7 * 86_400_000L),
                t0,
            ),
        )
    }

    @Test
    fun `a deleted upstream finishes nothing`() {
        assertFalse(ScheduledCompletionTriggers.scheduleFinished(null, t0))
    }

    @Test
    fun `the result is substituted and capped`() {
        assertEquals("Check: done", ScheduledCompletionTriggers.substitute("Check: {{result}}", "done"))
        assertEquals("Check: ", ScheduledCompletionTriggers.substitute("Check: {{result}}", null))
        val long = "x".repeat(ScheduledCompletionTriggers.RESULT_CAP_CHARS + 50)
        assertEquals(
            ScheduledCompletionTriggers.RESULT_CAP_CHARS,
            ScheduledCompletionTriggers.substitute("{{result}}", long).length,
        )
    }
}
