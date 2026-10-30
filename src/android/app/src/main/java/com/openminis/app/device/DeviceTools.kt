package com.openminis.app.device

import android.content.Context
import com.openminis.app.data.model.AgentToolDefinition

/**
 * Stable entry point of the "device capability" tool set (batch A). Registration
 * and dispatch wiring live elsewhere; this object only guarantees:
 *
 *  - [definitions] — one [AgentToolDefinition] per tool, static English
 *    descriptions (prompt-cache discipline: no runtime state in them), each
 *    ending with its risk label.
 *  - [handles] — whether a tool name belongs to this set.
 *  - [execute] — runs one tool and returns model-readable English text;
 *    failures always start with `Error: <code> — <message>`. Permission
 *    requirements are checked centrally in [DevicePermission] before routing.
 *
 * `activeSessionId` is accepted for signature stability (session-scoped
 * callers); no current device tool scopes its result by session.
 */
object DeviceTools {

    fun definitions(): List<AgentToolDefinition> =
        SystemTools.definitions() +
            AppTools.definitions() +
            MediaClipboardTools.definitions() +
            NotificationExtrasTools.definitions() +
            CommsTools.definitions()

    fun handles(name: String): Boolean = DevicePermission.REQUIREMENTS.containsKey(name)

    fun execute(
        name: String,
        argsJson: String,
        activeSessionId: String?,
        context: Context,
    ): String {
        if (!handles(name)) {
            return DeviceArgs.fail("unknown_tool", "'$name' is not a device tool")
        }
        val missing = DevicePermission.missing(context, name)
        if (missing.isNotEmpty()) return DevicePermission.missingError(missing)
        return try {
            when (name) {
                in SYSTEM_NAMES -> SystemTools.execute(name, argsJson, context)
                in APP_NAMES -> AppTools.execute(name, argsJson, context)
                in MEDIA_NAMES -> MediaClipboardTools.execute(name, argsJson, context)
                in NOTIFICATION_NAMES -> NotificationExtrasTools.execute(name, argsJson, context)
                else -> CommsTools.execute(name, argsJson, context)
            }
        } catch (t: Throwable) {
            DeviceArgs.fail("failed", "${t.javaClass.simpleName}: ${t.message ?: "unexpected error"}")
        }
    }

    private val SYSTEM_NAMES = setOf(
        "device_status", "setting_get", "sound_mode", "volume_set", "dnd_set", "brightness_set",
        "flashlight", "alarm_set", "wifi_toggle", "bluetooth_toggle", "mobile_data_toggle",
    )
    private val APP_NAMES = setOf(
        "app_list", "app_info", "app_launch", "app_open_link", "app_send_intent", "share_to_app",
    )
    private val MEDIA_NAMES = setOf("media_control", "clipboard_get", "clipboard_set")
    private val NOTIFICATION_NAMES = setOf("notification_dismiss", "notification_open", "notification_reply")
}
