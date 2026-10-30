package com.openminis.app.device

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Permission-gate behaviour of the device tool set against a Robolectric
 * context: runtime permission checks, special (non-runtime) access grants,
 * and the exact model-readable error wording.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class DevicePermissionTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun missingErrorUsesTheStandardWording() {
        assertEquals(
            "Error: permission_missing — READ_CONTACTS not granted. Ask the user to grant it in " +
                "Settings → Apps → Minis → Permissions, then retry.",
            DevicePermission.missingError("READ_CONTACTS"),
        )
    }

    @Test
    fun specialAccessErrorsNameTheirOwnGrantPage() {
        val listener = DevicePermission.missingError(DevicePermission.SPECIAL_NOTIFICATION_LISTENER)
        assertTrue(listener.startsWith("Error: permission_missing — Notification access not granted."))
        assertTrue(listener.contains("Device & app notifications"))

        val policy = DevicePermission.missingError(DevicePermission.SPECIAL_NOTIFICATION_POLICY)
        assertTrue(policy.startsWith("Error: permission_missing — Do Not Disturb access not granted."))

        val writeSettings = DevicePermission.missingError(DevicePermission.SPECIAL_WRITE_SETTINGS)
        assertTrue(writeSettings.startsWith("Error: permission_missing — Modify system settings not granted."))
    }

    @Test
    fun runtimePermissionGating() {
        assertTrue(DevicePermission.missing(context, "contacts_search").contains("READ_CONTACTS"))
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.READ_CONTACTS)
        assertEquals(emptyList<String>(), DevicePermission.missing(context, "contacts_search"))
    }

    @Test
    fun locationAcceptsEitherFineOrCoarse() {
        val missing = DevicePermission.missing(context, "location_get")
        assertEquals(listOf("ACCESS_FINE_LOCATION or ACCESS_COARSE_LOCATION"), missing)
        shadowOf(context as android.app.Application).grantPermissions(Manifest.permission.ACCESS_COARSE_LOCATION)
        assertEquals(emptyList<String>(), DevicePermission.missing(context, "location_get"))
    }

    @Test
    fun dndSetNeedsNotificationPolicyAccess() {
        assertTrue(DevicePermission.missing(context, "dnd_set").contains(DevicePermission.SPECIAL_NOTIFICATION_POLICY))
        val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        shadowOf(nm).setNotificationPolicyAccessGranted(true)
        assertEquals(emptyList<String>(), DevicePermission.missing(context, "dnd_set"))
    }

    @Test
    fun notificationToolsNeedTheListenerGrant() {
        for (tool in listOf("notification_dismiss", "notification_open", "notification_reply")) {
            assertTrue(
                "$tool must require Notification access",
                DevicePermission.missing(context, tool).contains(DevicePermission.SPECIAL_NOTIFICATION_LISTENER),
            )
        }
        // Enabling the grant in settings flips the special-access check.
        Settings.Secure.putString(
            context.contentResolver,
            "enabled_notification_listeners",
            "${context.packageName}/com.openminis.app.offload.MinisNotificationListenerService",
        )
        assertTrue(DevicePermission.hasSpecial(context, DevicePermission.SPECIAL_NOTIFICATION_LISTENER))
        assertFalse(DevicePermission.missing(context, "notification_dismiss").contains(DevicePermission.SPECIAL_NOTIFICATION_LISTENER))
    }

    @Test
    fun readToolsNeedNothing() {
        for (tool in listOf("device_status", "setting_get", "app_list", "app_info")) {
            assertEquals("$tool must be permission-free", emptyList<String>(), DevicePermission.missing(context, tool))
        }
    }

    @Test
    fun unknownToolHasNoRequirements() {
        assertEquals(emptyList<String>(), DevicePermission.missing(context, "nope"))
        assertEquals(null, DevicePermission.requirement("nope"))
    }
}
