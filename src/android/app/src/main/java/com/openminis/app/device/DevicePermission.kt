package com.openminis.app.device

import android.Manifest
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.openminis.app.offload.MinisNotificationListenerService

/**
 * Risk class of a device tool. The label is appended verbatim to the tool
 * description's tail (prompt-cache discipline: descriptions are static).
 */
enum class DeviceToolRisk(val label: String) {
    READ_ONLY("Read-only."),
    WRITE("Writes device state."),
    SENSITIVE("Sensitive: reads personal data."),
    DANGEROUS("Dangerous: acts on your behalf."),
}

/**
 * Central permission map for the device tool set: which runtime permissions
 * and special (non-runtime) access grants each tool needs. Pure mapping is
 * separated from the Context-dependent checks so it stays unit-testable.
 *
 * Conditional needs (sound_mode needs Do Not Disturb access only when the
 * transition touches silent; clipboard_get needs foreground on API 29+) are
 * checked inline by the tools themselves, which reuse [missingError] so the
 * wording stays identical.
 */
object DevicePermission {

    const val SPECIAL_NOTIFICATION_LISTENER = "notification_listener"
    const val SPECIAL_NOTIFICATION_POLICY = "notification_policy"
    const val SPECIAL_WRITE_SETTINGS = "write_settings"

    data class Requirement(
        val risk: DeviceToolRisk,
        /** Every one of these runtime permissions must be granted. */
        val runtime: List<String> = emptyList(),
        /** At least one of these runtime permissions must be granted (location-style). */
        val runtimeAny: List<String> = emptyList(),
        /** Every one of these special-access grants must be held. */
        val special: List<String> = emptyList(),
    )

    private val RUNTIME_LABELS: Map<String, String> = mapOf(
        Manifest.permission.READ_CONTACTS to "READ_CONTACTS",
        Manifest.permission.WRITE_CONTACTS to "WRITE_CONTACTS",
        Manifest.permission.READ_CALENDAR to "READ_CALENDAR",
        Manifest.permission.WRITE_CALENDAR to "WRITE_CALENDAR",
        Manifest.permission.READ_SMS to "READ_SMS",
        Manifest.permission.SEND_SMS to "SEND_SMS",
        Manifest.permission.RECEIVE_SMS to "RECEIVE_SMS",
        Manifest.permission.READ_CALL_LOG to "READ_CALL_LOG",
        Manifest.permission.ACCESS_FINE_LOCATION to "ACCESS_FINE_LOCATION",
        Manifest.permission.ACCESS_COARSE_LOCATION to "ACCESS_COARSE_LOCATION",
        Manifest.permission.CAMERA to "CAMERA",
    )

    /** Tool name → what it requires. Kept in lockstep with the group definitions files. */
    val REQUIREMENTS: Map<String, Requirement> = mapOf(
        // system group
        "device_status" to Requirement(DeviceToolRisk.READ_ONLY),
        "setting_get" to Requirement(DeviceToolRisk.READ_ONLY),
        "sound_mode" to Requirement(DeviceToolRisk.WRITE),
        "volume_set" to Requirement(DeviceToolRisk.WRITE),
        "dnd_set" to Requirement(DeviceToolRisk.SENSITIVE, special = listOf(SPECIAL_NOTIFICATION_POLICY)),
        "brightness_set" to Requirement(DeviceToolRisk.WRITE, special = listOf(SPECIAL_WRITE_SETTINGS)),
        "flashlight" to Requirement(DeviceToolRisk.WRITE),
        "alarm_set" to Requirement(DeviceToolRisk.WRITE),
        "wifi_toggle" to Requirement(DeviceToolRisk.WRITE),
        "bluetooth_toggle" to Requirement(DeviceToolRisk.WRITE),
        "mobile_data_toggle" to Requirement(DeviceToolRisk.WRITE),
        // apps group
        "app_list" to Requirement(DeviceToolRisk.READ_ONLY),
        "app_info" to Requirement(DeviceToolRisk.READ_ONLY),
        "app_launch" to Requirement(DeviceToolRisk.WRITE),
        "app_open_link" to Requirement(DeviceToolRisk.WRITE),
        "app_send_intent" to Requirement(DeviceToolRisk.DANGEROUS),
        "share_to_app" to Requirement(DeviceToolRisk.WRITE),
        // media + clipboard group
        "media_control" to Requirement(DeviceToolRisk.WRITE),
        "clipboard_get" to Requirement(DeviceToolRisk.SENSITIVE),
        "clipboard_set" to Requirement(DeviceToolRisk.WRITE),
        // notifications group
        "notification_dismiss" to Requirement(DeviceToolRisk.SENSITIVE, special = listOf(SPECIAL_NOTIFICATION_LISTENER)),
        "notification_open" to Requirement(DeviceToolRisk.WRITE, special = listOf(SPECIAL_NOTIFICATION_LISTENER)),
        "notification_reply" to Requirement(DeviceToolRisk.DANGEROUS, special = listOf(SPECIAL_NOTIFICATION_LISTENER)),
        // comms group
        "contacts_search" to Requirement(DeviceToolRisk.SENSITIVE, runtime = listOf(Manifest.permission.READ_CONTACTS)),
        "calendar_read" to Requirement(DeviceToolRisk.SENSITIVE, runtime = listOf(Manifest.permission.READ_CALENDAR)),
        "calendar_add" to Requirement(DeviceToolRisk.WRITE, runtime = listOf(Manifest.permission.WRITE_CALENDAR)),
        "sms_read" to Requirement(DeviceToolRisk.SENSITIVE, runtime = listOf(Manifest.permission.READ_SMS)),
        "call_log_read" to Requirement(DeviceToolRisk.SENSITIVE, runtime = listOf(Manifest.permission.READ_CALL_LOG)),
        "sms_send" to Requirement(DeviceToolRisk.DANGEROUS, runtime = listOf(Manifest.permission.SEND_SMS)),
        // location
        "location_get" to Requirement(
            DeviceToolRisk.SENSITIVE,
            runtimeAny = listOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        ),
    )

    fun requirement(tool: String): Requirement? = REQUIREMENTS[tool]

    fun runtimeGranted(context: Context, permission: String): Boolean =
        context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED

    /** True when the named special-access grant is held. Unknown tokens are never "held". */
    fun hasSpecial(context: Context, token: String): Boolean = when (token) {
        SPECIAL_NOTIFICATION_LISTENER -> MinisNotificationListenerService.isEnabled(context)
        SPECIAL_NOTIFICATION_POLICY -> runCatching {
            context.getSystemService(NotificationManager::class.java)?.isNotificationPolicyAccessGranted == true
        }.getOrDefault(false)
        SPECIAL_WRITE_SETTINGS -> Settings.System.canWrite(context)
        else -> false
    }

    /**
     * Requirement tokens the tool currently lacks; empty means good to go.
     * Tokens are permission names for runtime entries and the SPECIAL_* constants otherwise.
     */
    fun missing(context: Context, tool: String): List<String> {
        val req = REQUIREMENTS[tool] ?: return emptyList()
        val out = ArrayList<String>()
        for (p in req.runtime) if (!runtimeGranted(context, p)) out.add(RUNTIME_LABELS[p] ?: p)
        if (req.runtimeAny.isNotEmpty() && req.runtimeAny.none { runtimeGranted(context, it) }) {
            out.add(req.runtimeAny.map { RUNTIME_LABELS[it] ?: it }.joinToString(" or "))
        }
        for (s in req.special) if (!hasSpecial(context, s)) out.add(s)
        return out
    }

    /** The standard, model-readable error for one missing requirement token. */
    fun missingError(token: String): String = when (token) {
        SPECIAL_NOTIFICATION_LISTENER ->
            "Error: permission_missing — Notification access not granted. Ask the user to enable Minis " +
                "under Settings → Notifications → Device & app notifications, then retry."
        SPECIAL_NOTIFICATION_POLICY ->
            "Error: permission_missing — Do Not Disturb access not granted. Ask the user to grant it under " +
                "Settings → Apps → Minis → Special app access → Do Not Disturb access, then retry."
        SPECIAL_WRITE_SETTINGS ->
            "Error: permission_missing — Modify system settings not granted. Ask the user to enable it under " +
                "Settings → Apps → Minis → Permissions, then retry."
        else ->
            "Error: permission_missing — $token not granted. Ask the user to grant it in " +
                "Settings → Apps → Minis → Permissions, then retry."
    }

    /** One line per missing requirement (multi-missing is rare; single is the common shape). */
    fun missingError(missing: List<String>): String = missing.joinToString("\n") { missingError(it) }
}
