package com.openminis.app.automation

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-automation-rules] Serialization + normalization round-trips for
 * the RuleSpec JSON dialect the `automation_rule_*` tools exchange with the
 * model. Pure JVM (org.json artifact, no Robolectric) — mirrors
 * ScheduledTaskJsonCompatTest.
 */
class RuleSpecJsonTest {

    @Test
    fun timeTriggerRoundTripKeepsEveryVariant() {
        val spec = RuleSpec(
            name = "brief",
            trigger = Trigger.Time(at = "07:30", days = listOf(1, 5)),
            action = RuleAction.Agent("hello"),
        )
        val back = RuleSpec.fromJson(spec.toJsonString())
        assertNotNull(back)
        val t = back!!.trigger as Trigger.Time
        assertEquals("07:30", t.at)
        assertEquals(listOf(1, 5), t.days)
        assertEquals(0, t.everyMinutes)
        assertNull(t.onceAtEpochMs)
    }

    @Test
    fun intervalAndOnceTriggersRoundTrip() {
        val interval = RuleSpec(
            name = "i",
            trigger = Trigger.Time(everyMinutes = 30),
            action = RuleAction.Agent("p"),
        ).toJson().let { Trigger.fromJson(it.getJSONObject("trigger")) }
        assertTrue(interval is Trigger.Time && interval.everyMinutes == 30L)

        val once = RuleSpec(
            name = "o",
            trigger = Trigger.Time(onceAtEpochMs = 1_700_000_000_000),
            action = RuleAction.Agent("p"),
        ).toJson().let { Trigger.fromJson(it.getJSONObject("trigger")) }
        assertTrue(once is Trigger.Time && once.onceAtEpochMs == 1_700_000_000_000L)
    }

    @Test
    fun eventTriggersRoundTripOptionalFields() {
        val wifi = Trigger.fromJson(
            JSONObject().put("type", "wifi_connect").put("ssid", "Home"),
        ) as Trigger.WifiConnect
        assertEquals("Home", wifi.ssid)

        val wifiNoSsid = Trigger.fromJson(JSONObject().put("type", "wifi_connect"))
            as Trigger.WifiConnect
        assertNull(wifiNoSsid.ssid)

        val bt = Trigger.fromJson(
            JSONObject().put("type", "bluetooth_connect").put("deviceName", "Car"),
        ) as Trigger.BluetoothConnect
        assertEquals("Car", bt.deviceName)

        val notif = Trigger.fromJson(
            JSONObject().put("type", "notification").put("packageName", "com.x").put("keyword", "otp"),
        ) as Trigger.NotificationReceived
        assertEquals("com.x", notif.packageName)
        assertEquals("otp", notif.keyword)
    }

    @Test
    fun conditionsAndActionsRoundTrip() {
        val spec = RuleSpec(
            name = "c",
            trigger = Trigger.PowerConnected,
            conditions = listOf(
                Condition.TimeRange("08:00", "22:00"),
                Condition.DaysOfWeek(listOf(1, 2, 3, 4, 5)),
                Condition.BatteryAtLeast(30),
                Condition.Charging(true),
            ),
            action = RuleAction.Steps(listOf(Step("shell_execute", """{"command":"ls"}"""))),
            allowedTools = listOf("shell_execute"),
            permissionMode = "YOYO",
        )
        val back = RuleSpec.fromJson(spec.toJsonString())!!
        assertEquals(4, back.conditions.size)
        assertEquals(Condition.TimeRange("08:00", "22:00"), back.conditions[0])
        assertEquals(listOf(1, 2, 3, 4, 5), (back.conditions[1] as Condition.DaysOfWeek).days)
        assertEquals(Condition.BatteryAtLeast(30), back.conditions[2])
        assertEquals(Condition.Charging(true), back.conditions[3])
        val steps = back.action as RuleAction.Steps
        assertEquals("shell_execute", steps.steps[0].tool)
        assertEquals("""{"command":"ls"}""", steps.steps[0].argsJson)
        assertEquals("YOYO", back.permissionMode)
    }

    @Test
    fun unknownDiscriminatorParsesAsUnknownInsteadOfThrowing() {
        val t = Trigger.fromJson(JSONObject().put("type", "nfc_tap"))
        assertTrue(t is Trigger.Unknown)
        assertEquals("nfc_tap", (t as Trigger.Unknown).rawType)
    }

    @Test
    fun allowedToolsOmittedWhenEmpty() {
        val json = RuleSpec(
            name = "n",
            trigger = Trigger.ScreenUnlock,
            action = RuleAction.Agent("p"),
        ).toJson()
        assertEquals(false, json.has("allowedTools"))
        assertEquals(false, json.has("permissionMode"))
    }

    @Test
    fun corruptJsonParsesToNull() {
        assertNull(RuleSpec.fromJson("not json"))
        assertNull(RuleSpec.fromJson(JSONObject().put("name", "x").toString()))
    }
}
