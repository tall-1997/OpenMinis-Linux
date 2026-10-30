package com.openminis.app.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-automation-rules] Validation contract of [RuleValidation]: every
 * rejection is explicit and model-readable, normalizations are idempotent,
 * and the allowedTools whitelist is filtered to tool names this build exposes.
 */
class RuleValidationTest {

    private val known = setOf("shell_execute", "file_read", "web_search", "cronjob")
    private val now = 1_700_000_000_000L

    private fun ok(spec: RuleSpec): RuleSpec {
        val r = RuleValidation.validate(spec, known, now)
        assertEquals(
            "expected valid, got problems: ${r.problems}",
            true,
            r.spec != null,
        )
        return r.spec!!
    }

    private fun problems(spec: RuleSpec): List<String> {
        val r = RuleValidation.validate(spec, known, now)
        assertNull("expected rejection, got ${r.spec}", r.spec)
        return r.problems
    }

    private fun base(trigger: Trigger = Trigger.Time(everyMinutes = 30)) = RuleSpec(
        name = "r",
        trigger = trigger,
        action = RuleAction.Agent("do it"),
    )

    @Test
    fun everyMinutesBelowFifteenRejected() {
        val p = problems(base(Trigger.Time(everyMinutes = 14)))
        assertTrue(p.any { it.contains("at least 15") })
    }

    @Test
    fun everyMinutesFifteenAccepted() {
        val s = ok(base(Trigger.Time(everyMinutes = 15)))
        assertEquals(15L, (s.trigger as Trigger.Time).everyMinutes)
    }

    @Test
    fun geofenceTriggerRejectedExplicitly() {
        val p = problems(base(Trigger.Unknown("geofence")))
        assertTrue(p.any { it.contains("geofence") && it.contains("not supported") })
    }

    @Test
    fun unknownTriggerTypeRejected() {
        val p = problems(base(Trigger.Unknown("nfc_tap")))
        assertTrue(p.any { it.contains("nfc_tap") })
    }

    @Test
    fun invertedTimeRangeRejected() {
        val p = problems(
            base().copy(conditions = listOf(Condition.TimeRange("22:00", "06:00"))),
        )
        assertTrue(p.any { it.contains("time_range.from must be earlier") })
    }

    @Test
    fun forwardTimeRangeAccepted() {
        val s = ok(base().copy(conditions = listOf(Condition.TimeRange("8:05", "22:00"))))
        // normalized to zero-padded HH:mm (single-digit hour is lenient)
        assertEquals(Condition.TimeRange("08:05", "22:00"), s.conditions[0])
    }

    @Test
    fun allowedToolsFilteredToKnownNames() {
        val s = ok(
            base().copy(allowedTools = listOf(" shell_execute ", "", "web_search", "made_up_tool", "cronjob")),
        )
        assertEquals(listOf("shell_execute", "web_search", "cronjob"), s.allowedTools)
    }

    @Test
    fun daysMustBeIsoOneToSeven() {
        val p = problems(base(Trigger.Time(at = "07:30", days = listOf(0, 8))))
        assertTrue(p.any { it.contains("1..7") })
    }

    @Test
    fun daysRequireAtTimeTrigger() {
        val p = problems(base(Trigger.Time(everyMinutes = 30, days = listOf(1))))
        assertTrue(p.any { it.contains("only applies together with") })
    }

    @Test
    fun timeTriggerNeedsExactlyOneVariant() {
        val p = problems(base(Trigger.Time()))
        assertTrue(p.any { it.contains("exactly one of") })
    }

    @Test
    fun oneShotInPastRejected() {
        val p = problems(base(Trigger.Time(onceAtEpochMs = now - 1)))
        assertTrue(p.any { it.contains("in the past") })
    }

    @Test
    fun blankPromptRejected() {
        val p = problems(base().copy(action = RuleAction.Agent("  ")))
        assertTrue(p.any { it.contains("non-empty prompt") })
    }

    @Test
    fun emptyStepsActionRejected() {
        val p = problems(base().copy(action = RuleAction.Steps(emptyList())))
        assertTrue(p.any { it.contains("at least one step") })
    }

    @Test
    fun batteryLowPercentBounds() {
        assertTrue(problems(base(Trigger.BatteryLow(0))).isNotEmpty())
        assertTrue(problems(base(Trigger.BatteryLow(100))).isNotEmpty())
        assertNotNull(RuleValidation.validate(base(Trigger.BatteryLow(1)), known, now).spec)
        assertNotNull(RuleValidation.validate(base(Trigger.BatteryLow(99)), known, now).spec)
    }

    @Test
    fun permissionModeNormalizesToSessionTokens() {
        assertEquals("YOYO", ok(base().copy(permissionMode = "yoyo")).permissionMode)
        assertEquals("ASK", ok(base().copy(permissionMode = "whatever")).permissionMode)
        assertNull(ok(base().copy(permissionMode = " ")).permissionMode)
    }

    @Test
    fun blankNameRejectedAndWhitespaceTrimmed() {
        assertTrue(problems(base().copy(name = "   ")).any { it.contains("name") })
        assertEquals("r", ok(base().copy(name = "  r ")).name)
    }

    @Test
    fun optionalBlankStringsDropped() {
        val s = ok(
            base(Trigger.WifiConnect(ssid = "  "))
                .copy(conditions = emptyList()),
        )
        assertNull((s.trigger as Trigger.WifiConnect).ssid)
    }
}
