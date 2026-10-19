package com.openminis.app.scheduled

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-scheduled-triggers] Rows written by the two retired shapes must
 * read back as the trigger model — without losing their next slot, without
 * replaying a fire that already happened, and without a second way to say
 * "repeat every N" existing anywhere else.
 *
 * The migration lives in [ScheduledTask.fromJson] because that is the single
 * reader of the persisted schema. These tests pin both directions: old JSON
 * in, trigger fields out; and the new shape unchanged after a round trip.
 */
class ScheduledTriggerMigrationTest {

    private val t0 = 1_800_000_000_000L

    private fun json(vararg pairs: Pair<String, Any?>): JSONObject = JSONObject().apply {
        put("id", "a")
        put("label", "L")
        put("prompt", "p")
        put("createdAt", t0)
        pairs.forEach { (k, v) -> if (v != null) put(k, v) }
    }

    private fun task(
        kind: ScheduledTriggerKind = ScheduledTriggerKind.CALENDAR,
        delaySec: Long? = null,
        intervalSec: Long? = null,
        maxFires: Int? = null,
        anchor: Long? = t0,
        triggered: Int? = null,
        enabled: Boolean = true,
    ) = ScheduledTask(
        id = "t1",
        label = "watch",
        timeOfDayHour = 9,
        timeOfDayMinute = 0,
        repeatMode = ScheduledRepeatMode.ONCE,
        prompt = "Check: {{result}}",
        enabled = enabled,
        createdAt = t0,
        triggerKind = kind,
        delaySec = delaySec,
        intervalSec = intervalSec,
        maxFires = maxFires,
        anchorMs = anchor,
        triggeredCount = triggered,
    )

    // ── Reading old rows ─────────────────────────────────────────────────

    @Test
    fun `a row written before triggers existed is still a calendar task`() {
        val t = ScheduledTask.fromJson(json("hour" to 8, "minute" to 30, "repeatMode" to "DAILY"))
        assertEquals(ScheduledTriggerKind.CALENDAR, t.triggerKind)
        assertEquals(ScheduledRepeatMode.DAILY, t.repeatMode)
        assertNull(t.intervalSec)
        // No trigger keys are written for a calendar task, so an older build
        // reading this row sees exactly what it always did.
        assertFalse(t.toJson().has("trigger"))
    }

    @Test
    fun `a legacy interval keeps its interval and its next slot`() {
        val first = t0 + 30 * 60_000L
        val t = ScheduledTask.fromJson(
            json("repeatMode" to "INTERVAL", "intervalMinutes" to 30L, "fireAtMs" to first),
        )
        assertEquals(ScheduledTriggerKind.INTERVAL, t.triggerKind)
        assertEquals(1_800L, t.intervalSec)
        // Anchoring one interval before the stored slot resumes the same
        // rhythm instead of restarting the countdown at creation time.
        assertEquals(first, t.nextTriggerMs(t0))
        // Writing the row back persists the new shape and re-reading is a fixed
        // point: the migration is derived from the keys, so it cannot drift.
        assertEquals(t, ScheduledTask.fromJson(t.toJson()))
    }

    @Test
    fun `a legacy interval without a usable interval still reads`() {
        // These rows never fired before either (nextTriggerMs returned null for
        // intervalMinutes <= 0). Reading them as a calendar row is at worst a
        // visible task; dropping the row would be a silent loss.
        val t = ScheduledTask.fromJson(json("repeatMode" to "INTERVAL", "hour" to 7, "minute" to 5))
        assertEquals(ScheduledTriggerKind.CALENDAR, t.triggerKind)
        assertEquals(ScheduledRepeatMode.ONCE, t.repeatMode)
        assertEquals(7, t.timeOfDayHour)
    }

    @Test
    fun `a legacy one-shot reminder keeps its absolute fire time`() {
        val fireAt = t0 + 30 * 60_000L
        val t = ScheduledTask.fromJson(
            json("hour" to 9, "minute" to 0, "repeatMode" to "ONCE", "fireAtMs" to fireAt),
        )
        assertEquals(ScheduledTriggerKind.AFTER, t.triggerKind)
        assertEquals(1_800L, t.delaySec)
        assertEquals(t0, t.anchorMs)
        assertEquals(fireAt, t.nextTriggerMs(t0))
    }

    @Test
    fun `a legacy one-shot that already fired is not replayed`() {
        val t = ScheduledTask.fromJson(
            json(
                "repeatMode" to "ONCE",
                "fireAtMs" to t0 - 1_000L,
                "lastFiredAt" to t0 - 500L,
            ),
        )
        assertEquals(1, t.triggeredCount)
        assertNull(t.nextTriggerMs(t0))
    }

    @Test
    fun `an unknown trigger from a newer build reads as calendar`() {
        val t = ScheduledTask.fromJson(json("trigger" to "SOLAR_FLARE", "hour" to 6, "minute" to 0))
        assertEquals(ScheduledTriggerKind.CALENDAR, t.triggerKind)
        assertEquals(6, t.timeOfDayHour)
    }

    @Test
    fun `every trigger field survives a round trip`() {
        val t = task(ScheduledTriggerKind.INTERVAL, intervalSec = 600, maxFires = 6, triggered = 2)
        assertEquals(t, ScheduledTask.fromJson(t.toJson()))
    }

    // ── Firing ───────────────────────────────────────────────────────────

    @Test
    fun `an interval counts, re-anchors, and stops at its count`() {
        val iv = task(ScheduledTriggerKind.INTERVAL, intervalSec = 600, maxFires = 2, triggered = 0)
        assertFalse("not before its slot", ScheduledTaskManager.relativeAlarmDue(iv, t0 + 599_000))
        assertTrue(ScheduledTaskManager.relativeAlarmDue(iv, t0 + 600_000))

        val (first, armFirst) = ScheduledTaskManager.afterAlarmFire(iv, t0 + 600_000)!!
        assertEquals(1, first.triggeredCount)
        assertEquals(t0 + 600_000, first.anchorMs)
        assertTrue("next fire armed", armFirst)
        assertEquals(t0 + 1_200_000, first.nextTriggerMs(t0 + 600_000))
        assertFalse(
            "a second delivery of the same fire is not due",
            ScheduledTaskManager.relativeAlarmDue(first, t0 + 600_000),
        )

        val (last, armLast) = ScheduledTaskManager.afterAlarmFire(first, t0 + 1_200_000)!!
        assertEquals(2, last.triggeredCount)
        assertFalse("the last fire disables, it does not re-arm", last.enabled || armLast)
    }

    @Test
    fun `after fires once and disables itself`() {
        val once = task(ScheduledTriggerKind.AFTER, delaySec = 60)
        assertFalse("not before its delay", ScheduledTaskManager.relativeAlarmDue(once, t0))
        val (fired, arm) = ScheduledTaskManager.afterAlarmFire(once, t0 + 60_000)!!
        assertEquals(1, fired.triggeredCount)
        assertFalse(fired.enabled || arm)
        assertNull("calendar tasks keep their own path", ScheduledTaskManager.afterAlarmFire(task(), t0))
    }

    // ── The editor ───────────────────────────────────────────────────────

    @Test
    fun `saving from the editor keeps history and the relative trigger`() {
        val stored = task(ScheduledTriggerKind.INTERVAL, intervalSec = 600, triggered = 4)
            .copy(runHistory = listOf(ScheduledRun(t0, "s", "ok", true)), fireCount = 4)
        // What the editor's buildTask produces: it has no trigger controls and
        // rebuilds run history / counts from nothing.
        val edited = stored.copy(
            label = "renamed",
            triggerKind = ScheduledTriggerKind.CALENDAR,
            intervalSec = null,
            runHistory = emptyList(),
            fireCount = null,
            anchorMs = null,
            triggeredCount = null,
        )
        val merged = ScheduledTaskManager.mergeBookkeeping(edited, stored)
        assertEquals("renamed", merged.label)
        assertEquals(ScheduledTriggerKind.INTERVAL, merged.triggerKind)
        assertEquals(600L, merged.intervalSec)
        assertEquals(stored.runHistory, merged.runHistory)
        assertEquals(4, merged.fireCount)
        assertEquals(t0, merged.anchorMs)
        assertEquals(4, merged.triggeredCount)
    }
}
