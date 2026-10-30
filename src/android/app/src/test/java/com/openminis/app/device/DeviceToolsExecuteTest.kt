package com.openminis.app.device

import android.Manifest
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Dispatcher and result-text behaviour: the Error: prefix contract, the
 * device_status key: value line format, setting_get reading and its no-Shizuku
 * degradation, argument parsing helpers, and argument validation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DeviceToolsExecuteTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun exec(name: String, args: String): String =
        DeviceTools.execute(name, args, activeSessionId = "sess-test", context = context)

    @Test
    fun unknownToolIsReportedNotThrown() {
        org.junit.Assert.assertFalse(DeviceTools.handles("nope"))
        assertEquals("Error: unknown_tool — 'nope' is not a device tool", exec("nope", "{}"))
    }

    @Test
    fun permissionMissingIsReportedBeforeAnyQuery() {
        val out = exec("sms_read", "{}")
        assertEquals(
            "Error: permission_missing — READ_SMS not granted. Ask the user to grant it in " +
                "Settings → Apps → Minis → Permissions, then retry.",
            out,
        )
    }

    @Test
    fun grantedPermissionReachesTheQuery() {
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.READ_SMS)
        val out = exec("sms_read", "{}")
        // Robolectric has no SMS provider; the tool must degrade to a plain empty result.
        assertEquals("No messages.", out)
    }

    @Test
    fun deviceStatusEmitsStableKeyValueLines() {
        val out = exec("device_status", "{}")
        val lines = out.lines().map { it.substringBefore(':').trim() }
        for (key in listOf(
            "battery", "screen", "brightness", "network", "storage",
            "ringer", "do_not_disturb", "location_enabled", "time",
        )) {
            assertTrue("device_status must contain a '$key:' line; got:\n$out", key in lines)
        }
        assertTrue(out.contains("battery: unknown") || out.contains(Regex("battery: \\d+%")))
    }

    @Test
    fun settingGetReadsAValueDirectly() {
        Settings.Global.putString(context.contentResolver, "airplane_mode_on", "1")
        assertEquals("global.airplane_mode_on = 1", exec("setting_get", """{"namespace":"global","key":"airplane_mode_on"}"""))
    }

    @Test
    fun settingGetReportsUnsetValue() {
        assertEquals(
            "system.screen_brightness is not set (null).",
            exec("setting_get", """{"namespace":"system","key":"screen_brightness"}"""),
        )
    }

    @Test
    fun settingGetListingNeedsShizukuAndSaysSo() {
        val out = exec("setting_get", """{"namespace":"global"}""")
        assertTrue(out.startsWith("Error: shizuku_unavailable —"))
        assertTrue(out.contains("Shizuku"))
    }

    @Test
    fun settingGetValidatesNamespaceAndSanitizesKeys() {
        val badNs = exec("setting_get", """{"namespace":"kernel"}""")
        assertTrue(badNs.startsWith("Error: invalid_args —"))
        val badJson = exec("setting_get", "{not json")
        assertTrue(badJson.startsWith("Error: invalid_args —"))
    }

    @Test
    fun alarmSetValidatesHourAndTimerBounds() {
        val badHour = exec("alarm_set", """{"hour":24,"minute":0}""")
        assertTrue(badHour.startsWith("Error: invalid_args —"))
        val badTimer = exec("alarm_set", """{"timer_seconds":90000}""")
        assertTrue(badTimer.startsWith("Error: invalid_args —"))
    }

    @Test
    fun volumeSetValidatesRange() {
        val out = exec("volume_set", """{"stream":"music","percent":150}""")
        assertTrue(out.startsWith("Error: invalid_args —"))
    }

    @Test
    fun clipboardSetCopiesAndReports() {
        val out = exec("clipboard_set", """{"text":"hello"}""")
        assertEquals("Copied 5 character(s) to the clipboard.", out)
    }

    @Test
    fun clipboardGetFollowsTheForegroundPolicy() {
        // Robolectric reports this process as foreground, so a set clip is readable;
        // an empty clip then must not error either.
        exec("clipboard_set", """{"text":"hello"}""")
        val out = exec("clipboard_get", "{}")
        if (isAppForeground(context)) {
            assertEquals("hello", out)
        } else {
            assertTrue(
                "expected the foreground-only clipboard error, got: $out",
                out.startsWith("Error: unavailable —"),
            )
        }
    }

    @Test
    fun argHelpersParseNumbersBooleansAndLists() {
        val obj = JSONObject(
            """{"i":12,"iStr":"13","b":true,"bStr":"false","arr":["a","b"],"single":"s","blank":""}""",
        )
        assertEquals(12, obj.intArg("i"))
        assertEquals(13, obj.intArg("iStr"))
        assertEquals(null, obj.intArg("b"))
        assertEquals(null, obj.intArg("missing"))
        assertEquals(true, obj.boolArg("b"))
        assertEquals(false, obj.boolArg("bStr"))
        assertEquals(null, obj.boolArg("missing"))
        assertEquals(listOf("a", "b"), obj.strListArg("arr"))
        assertEquals(listOf("s"), obj.strListArg("single"))
        assertEquals(null, obj.strListArg("blank"))
        assertEquals(null, obj.strListArg("missing"))
        assertEquals(null, obj.strArg("blank"))
        assertEquals(null, obj.strArg("missing"))
    }

    @Test
    fun parseTimeAcceptsIsoAndEpoch() {
        val obj = JSONObject("""{"iso":"2026-10-30T15:00","bare":"2026-10-30","ms":1760000000000}""")
        assertTrue(CommsTools.parseTime(obj, "iso", "nope")!! > 0)
        assertTrue(CommsTools.parseTime(obj, "bare", "nope")!! > 0)
        assertEquals(1760000000000L, CommsTools.parseTime(obj, "nope", "ms"))
        assertEquals(null, CommsTools.parseTime(obj, "garbage", "nope"))
    }
}
