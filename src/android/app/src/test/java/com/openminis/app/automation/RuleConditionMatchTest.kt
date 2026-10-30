package com.openminis.app.automation

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

/**
 * [T-android-automation-rules] Fire-time condition evaluation and trigger/event
 * matching. All checks take a fixed [RuleEnv] (and Calendar-derived
 * expectations built from the same instant) so the JVM tests are exact.
 */
class RuleConditionMatchTest {

    /** 2024-01-01 (Monday) 14:30 local. */
    private val now: Long = Calendar.getInstance().apply {
        set(2024, Calendar.JANUARY, 1, 14, 30, 0)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private fun env(
        battery: Int? = 80,
        charging: Boolean? = false,
    ) = RuleEnv(nowMs = now, batteryPercent = battery, charging = charging)

    // ── conditions ───────────────────────────────────────────────────────

    @Test
    fun timeRangeInclusiveBounds() {
        assertTrue(Condition.TimeRange("14:30", "15:00").holds(env()))
        assertTrue(Condition.TimeRange("00:00", "14:30").holds(env()))
        assertFalse(Condition.TimeRange("15:00", "16:00").holds(env()))
    }

    @Test
    fun unparseableTimeRangeNeverHolds() {
        assertFalse(Condition.TimeRange("abc", "15:00").holds(env()))
        assertFalse(Condition.TimeRange("08:00", "").holds(env()))
    }

    @Test
    fun daysOfWeekMatchesIsoMonday() {
        assertTrue(Condition.DaysOfWeek(listOf(1)).holds(env()))
        assertTrue(Condition.DaysOfWeek(listOf(2, 4, 1)).holds(env()))
        assertFalse(Condition.DaysOfWeek(listOf(2, 3)).holds(env()))
        assertFalse(Condition.DaysOfWeek(emptyList()).holds(env()))
    }

    @Test
    fun batteryAtLeastNeedsSnapshot() {
        assertTrue(Condition.BatteryAtLeast(50).holds(env(battery = 50)))
        assertFalse(Condition.BatteryAtLeast(51).holds(env(battery = 50)))
        assertFalse(Condition.BatteryAtLeast(0).holds(env(battery = null)))
    }

    @Test
    fun chargingConditionComparesSnapshot() {
        assertTrue(Condition.Charging(false).holds(env(charging = false)))
        assertFalse(Condition.Charging(true).holds(env(charging = false)))
        assertFalse(Condition.Charging(true).holds(env(charging = null)))
    }

    // ── trigger/event matching ───────────────────────────────────────────

    @Test
    fun wifiMatchesNullOrEqualSsid() {
        assertTrue(matches(Trigger.WifiConnect(null), AutomationEvent.Wifi(true, "Home")))
        assertTrue(matches(Trigger.WifiConnect("home"), AutomationEvent.Wifi(true, "Home")))
        assertFalse(matches(Trigger.WifiConnect("Office"), AutomationEvent.Wifi(true, "Home")))
        assertFalse(matches(Trigger.WifiConnect(null), AutomationEvent.Wifi(false, "Home")))
    }

    @Test
    fun bluetoothMatchesByContainment() {
        assertTrue(matches(Trigger.BluetoothConnect("Car"), AutomationEvent.Bluetooth(true, "Car Audio")))
        assertFalse(matches(Trigger.BluetoothConnect("Boat"), AutomationEvent.Bluetooth(true, "Car Audio")))
        assertFalse(matches(Trigger.BluetoothConnect(null), AutomationEvent.Bluetooth(false, null)))
    }

    @Test
    fun batteryLowMatchesOnlyDownwardCrossingWhileDischarging() {
        assertTrue(
            matches(Trigger.BatteryLow(20), AutomationEvent.Battery(percent = 20, previous = 21, charging = false)),
        )
        // Already below (no crossing).
        assertFalse(
            matches(Trigger.BatteryLow(20), AutomationEvent.Battery(percent = 19, previous = 19, charging = false)),
        )
        // Charging — user plugged in, not a low-battery event.
        assertFalse(
            matches(Trigger.BatteryLow(20), AutomationEvent.Battery(percent = 20, previous = 21, charging = true)),
        )
        // First snapshot after monitor start — no previous, no transition.
        assertFalse(
            matches(Trigger.BatteryLow(20), AutomationEvent.Battery(percent = 15, previous = null, charging = false)),
        )
    }

    @Test
    fun screenEventsMatchExactKind() {
        assertTrue(matches(Trigger.ScreenOn, AutomationEvent.Screen("on")))
        assertTrue(matches(Trigger.ScreenOff, AutomationEvent.Screen("off")))
        assertTrue(matches(Trigger.ScreenUnlock, AutomationEvent.Screen("unlock")))
        assertFalse(matches(Trigger.ScreenOn, AutomationEvent.Screen("off")))
    }

    @Test
    fun notificationMatchesOptionalFilters() {
        val ev = AutomationEvent.Notification("com.bank", "Payment", "Your OTP is 1234")
        assertTrue(matches(Trigger.NotificationReceived(null, null), ev))
        assertTrue(matches(Trigger.NotificationReceived("com.bank", null), ev))
        assertTrue(matches(Trigger.NotificationReceived(null, "otp"), ev))
        assertFalse(matches(Trigger.NotificationReceived("com.other", null), ev))
        assertFalse(matches(Trigger.NotificationReceived(null, "invoice"), ev))
    }

    @Test
    fun appOpenedMatchesExactPackage() {
        assertTrue(matches(Trigger.AppOpened("com.a"), AutomationEvent.AppOpened("com.a")))
        assertFalse(matches(Trigger.AppOpened("com.a"), AutomationEvent.AppOpened("com.b")))
    }

    @Test
    fun powerMatchesConnectOnly() {
        assertTrue(matches(Trigger.PowerConnected, AutomationEvent.Power(true)))
        assertFalse(matches(Trigger.PowerConnected, AutomationEvent.Power(false)))
    }

    @Test
    fun unknownTriggerNeverMatches() {
        assertFalse(matches(Trigger.Unknown("geofence"), AutomationEvent.Power(true)))
    }

    @Test
    fun eventDescribeIsStableForPromptPreamble() {
        assertEquals(
            "notification from com.bank: Payment — Your OTP is 1234",
            AutomationEvent.Notification("com.bank", "Payment", "Your OTP is 1234").describe(),
        )
        assertEquals("battery at 15%", AutomationEvent.Battery(15, 16, false).describe())
        assertEquals("screen unlocked", AutomationEvent.Screen("unlock").describe())
    }

    @Test
    fun summarizeCoversTriggerKinds() {
        assertEquals("Daily 07:30", Trigger.Time(at = "07:30").summarize())
        assertEquals("Every 30m", Trigger.Time(everyMinutes = 30).summarize())
        assertEquals("Every 2h", Trigger.Time(everyMinutes = 120).summarize())
        assertEquals("Once", Trigger.Time(onceAtEpochMs = 1L).summarize())
        assertEquals("Wi-Fi Home", Trigger.WifiConnect("Home").summarize())
        assertEquals("Battery low 20%", Trigger.BatteryLow(20).summarize())
        assertEquals("App opened com.a", Trigger.AppOpened("com.a").summarize())
    }
}
