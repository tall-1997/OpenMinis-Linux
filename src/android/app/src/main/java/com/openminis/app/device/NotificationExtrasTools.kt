package com.openminis.app.device

import android.app.ActivityOptions
import android.app.Notification
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.service.notification.StatusBarNotification
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import com.openminis.app.offload.MinisNotificationListenerService
import org.json.JSONObject

/**
 * Notifications extras for the device tool set: dismiss, open (tap) and
 * inline-reply on top of the existing read/list tooling. All three go through
 * [MinisNotificationListenerService], which must hold the user's Notification
 * access grant. Keys come from the existing notification list tool
 * (StatusBarNotification.key).
 */
internal object NotificationExtrasTools {

    private const val TOOL_TITLE_PARAM = "tool_title"
    private const val TOOL_TITLE_DESC = "A concise 5-10 word summary shown to the user."

    private fun titleParam() = AgentToolParam("string", TOOL_TITLE_DESC)

    fun definitions(): List<AgentToolDefinition> = listOf(
        NotificationDismissTool.definition(),
        NotificationOpenTool.definition(),
        NotificationReplyTool.definition(),
    )

    fun execute(name: String, argsJson: String, context: Context): String = when (name) {
        NotificationDismissTool.NAME -> NotificationDismissTool.execute(argsJson, context)
        NotificationOpenTool.NAME -> NotificationOpenTool.execute(argsJson, context)
        NotificationReplyTool.NAME -> NotificationReplyTool.execute(argsJson, context)
        else -> DeviceArgs.fail("unknown_tool", "notifications group does not handle '$name'")
    }

    // ── shared plumbing ──

    private fun activeNotifications(): Array<StatusBarNotification>? =
        MinisNotificationListenerService.getActiveNotifications()

    private fun findByKey(key: String): StatusBarNotification? =
        activeNotifications()?.firstOrNull { it.key == key }

    /**
     * PendingIntents of other apps that start activities need the sender's
     * background-start privilege on Android 14+.
     */
    private fun sendOptions(): Bundle? = if (Build.VERSION.SDK_INT >= 34) @Suppress("DEPRECATION") {
        ActivityOptions.makeBasic()
            .setPendingIntentBackgroundActivityStartMode(ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED)
            .toBundle()
    } else null

    private fun sendPendingIntent(context: Context, pi: PendingIntent, fill: Intent? = null): Unit =
        pi.send(context, 0, fill, null, null, null, sendOptions())

    private fun titleOf(sbn: StatusBarNotification): String =
        sbn.notification.extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()

    // ── notification_dismiss ──

    internal object NotificationDismissTool {
        const val NAME = "notification_dismiss"

        fun definition(): AgentToolDefinition = AgentToolDefinition(
            name = NAME,
            description = "Dismiss (clear) status-bar notifications by key — the keys come from the " +
                "notification list tool. Pass all=true (or no keys) to dismiss every clearable " +
                "notification. Ongoing notifications cannot be dismissed. Sensitive: reads and removes " +
                "user notifications.",
            parameters = mapOf(
                TOOL_TITLE_PARAM to titleParam(),
                "keys" to AgentToolParam(
                    "array",
                    "Notification keys to dismiss (from the notification list tool); omit or pass all=true for every clearable one.",
                    items = AgentToolParam("string", "A notification key."),
                ),
                "all" to AgentToolParam("boolean", "Dismiss all clearable notifications."),
            ),
            required = listOf(TOOL_TITLE_PARAM),
            propertyOrdering = listOf(TOOL_TITLE_PARAM, "keys", "all"),
        )

        fun execute(argsJson: String, context: Context): String {
            val obj = DeviceArgs.parseOrNull(argsJson)
                ?: return DeviceArgs.fail("invalid_args", "arguments are not valid JSON")
            val keys = obj.strListArg("keys")?.toSet()
            val all = obj.boolArg("all") == true
            if (keys == null && !all) {
                return DeviceArgs.fail("invalid_args", "pass 'keys' (array) or all=true")
            }
            val active = activeNotifications()
                ?: return DevicePermission.missingError(DevicePermission.SPECIAL_NOTIFICATION_LISTENER)
            val targets = active.filter { it.isClearable && (all || keys!!.contains(it.key)) }
            var dismissed = 0
            for (t in targets) if (MinisNotificationListenerService.cancelKey(t.key)) dismissed++
            val skipped = if (all || keys == null) 0 else keys.size - dismissed
            return buildString {
                append("Dismissed $dismissed notification(s).")
                if (skipped > 0) append(" $skipped key(s) not found or not clearable (ongoing).")
            }
        }
    }

    // ── notification_open ──

    internal object NotificationOpenTool {
        const val NAME = "notification_open"

        fun definition(): AgentToolDefinition = AgentToolDefinition(
            name = NAME,
            description = "Open a notification — the same as the user tapping it — by key from the " +
                "notification list tool. If the screen is locked the user may need to unlock first " +
                "(needsUnlock). Use notification_reply to answer a message without opening it. " +
                "Writes device state.",
            parameters = mapOf(
                TOOL_TITLE_PARAM to titleParam(),
                "key" to AgentToolParam("string", "Notification key from the notification list tool."),
            ),
            required = listOf(TOOL_TITLE_PARAM, "key"),
            propertyOrdering = listOf(TOOL_TITLE_PARAM, "key"),
        )

        fun execute(argsJson: String, context: Context): String {
            val obj = DeviceArgs.parseOrNull(argsJson)
                ?: return DeviceArgs.fail("invalid_args", "arguments are not valid JSON")
            val key = obj.strArg("key") ?: return DeviceArgs.fail("invalid_args", "missing 'key'")
            if (activeNotifications() == null) {
                return DevicePermission.missingError(DevicePermission.SPECIAL_NOTIFICATION_LISTENER)
            }
            val sbn = findByKey(key) ?: return DeviceArgs.fail(
                "invalid_args",
                "no current notification with key '$key' (it may have been dismissed); list notifications again",
            )
            val pi = sbn.notification.contentIntent
                ?: return DeviceArgs.fail("unsupported", "this notification does not open anything when tapped")
            return try {
                sendPendingIntent(context, pi)
                if ((sbn.notification.flags and Notification.FLAG_AUTO_CANCEL) != 0) {
                    MinisNotificationListenerService.cancelKey(sbn.key)
                }
                "Opened the ${sbn.packageName} notification" +
                    (titleOf(sbn).takeIf { it.isNotEmpty() }?.let { " ('$it')" } ?: "") +
                    ". If the screen is locked, the user may need to unlock it first; the result of the " +
                    "tap is not observable from here."
            } catch (e: PendingIntent.CanceledException) {
                DeviceArgs.fail("failed", "the app withdrew this notification's action; open the app instead.")
            }
        }
    }

    // ── notification_reply ──

    internal object NotificationReplyTool {
        const val NAME = "notification_reply"

        fun definition(): AgentToolDefinition = AgentToolDefinition(
            name = NAME,
            description = "Reply to a chat notification through its inline Reply action (SMS, WhatsApp, " +
                "Telegram, …) without opening the app. Only works when the notification exposes a free-form " +
                "reply action. Dangerous: sends a message on your behalf — confirm the text with the user first.",
            parameters = mapOf(
                TOOL_TITLE_PARAM to titleParam(),
                "key" to AgentToolParam("string", "Notification key from the notification list tool."),
                "text" to AgentToolParam("string", "Reply text to send."),
            ),
            required = listOf(TOOL_TITLE_PARAM, "key", "text"),
            propertyOrdering = listOf(TOOL_TITLE_PARAM, "key", "text"),
        )

        fun execute(argsJson: String, context: Context): String {
            val obj = DeviceArgs.parseOrNull(argsJson)
                ?: return DeviceArgs.fail("invalid_args", "arguments are not valid JSON")
            val key = obj.strArg("key") ?: return DeviceArgs.fail("invalid_args", "missing 'key'")
            val text = obj.strArg("text") ?: return DeviceArgs.fail("invalid_args", "missing 'text'")
            if (activeNotifications() == null) {
                return DevicePermission.missingError(DevicePermission.SPECIAL_NOTIFICATION_LISTENER)
            }
            val sbn = findByKey(key) ?: return DeviceArgs.fail(
                "invalid_args",
                "no current notification with key '$key' (it may have been dismissed); list notifications again",
            )
            val action = sbn.notification.actions.orEmpty().firstOrNull { a ->
                a.remoteInputs.orEmpty().any { it.allowFreeFormInput }
            } ?: return DeviceArgs.fail(
                "unsupported",
                "this notification has no inline reply action; open the app and type there instead",
            )
            val freeForm = action.remoteInputs.filter { it.allowFreeFormInput }
            val fill = Intent().addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            RemoteInput.addResultsToIntent(
                freeForm.toTypedArray(),
                fill,
                Bundle().apply { for (ri in freeForm) putCharSequence(ri.resultKey, text) },
            )
            return try {
                sendPendingIntent(context, action.actionIntent, fill)
                "Replied to ${sbn.packageName}" +
                    (titleOf(sbn).takeIf { it.isNotEmpty() }?.let { " ('$it')" } ?: "") +
                    ": \"${text.take(200)}\". The app usually updates or clears the notification after a reply."
            } catch (e: PendingIntent.CanceledException) {
                DeviceArgs.fail(
                    "failed",
                    "the app withdrew the reply action (the conversation may have changed); list notifications again.",
                )
            }
        }
    }
}
