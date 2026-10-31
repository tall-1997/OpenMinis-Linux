package com.openminis.app.automation

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.openminis.app.logging.AppLogger
import com.openminis.app.scheduled.ScheduledAgentRunner

/**
 * [T-android-automation-rules] Unattended agent launch for a fired rule —
 * a thin skin over [ScheduledAgentRunner.runUnattended] (the shared
 * unattended-runner template; v3 runner convergence):
 *
 *  - the template owns the MinisApp readiness guards (a rule fire can START
 *    the process, and a half-built Application must degrade to a skipped
 *    fire, not a crash), the FGS-before-dispatch ordering and the wait=true
 *    dispatch under the caller's ceiling,
 *  - fresh-row persistence goes through the template's shared
 *    `createUnattendedSessionRow` (same-module internal visibility), which
 *    writes the tool-gate permission mode + model binding exactly like the
 *    chat UI and flips the row's `source` to [SESSION_SOURCE],
 *  - fire-and-forget: the caller gets the session id once DISPATCHED, and
 *    completion (run-row update + notification) finishes on the template's
 *    app-scoped coroutine that outlives the receiver.
 *
 * Naming: rule-triggered sessions are titled `auto_rule_<ruleId>_<ts>` (the
 * ts is the fire time in seconds) so they are greppable apart from scheduled
 * and human chats.
 */
object AutomationAgentRunner {

    private const val TAG = "AutomationAgentRunner"
    private const val RUN_TIMEOUT_MS = 10 * 60 * 1000L  // same ceiling as scheduled

    /** Session title prefix mandated for rule-triggered unattended chats. */
    const val SESSION_PREFIX = "auto_rule_"

    /** `source` column value for rule-triggered unattended chats. */
    const val SESSION_SOURCE = "automation"

    /**
     * Resolve the target session and dispatch the prompt.
     *
     * @param runId row id of the "started" run row to finalize on completion
     *   (0 = no row — notification-only completion).
     * @return the session id once the prompt has been DISPATCHED, or null
     *   when the runner couldn't start (no provider, half-built app).
     */
    suspend fun start(
        context: Context,
        rule: AutomationRuleEntity,
        spec: RuleSpec,
        action: RuleAction.Agent,
        runId: Long = 0,
        eventDescription: String = "rule trigger",
    ): String? = ScheduledAgentRunner.runUnattended(
        context = context,
        logTag = TAG,
        abortSuffix = "skipping rule ${rule.id}",
        subject = "rule ${rule.id}",
        // Machine-greppable title: auto_rule_<ruleId>_<ts seconds>.
        sessionTitle = "$SESSION_PREFIX${rule.id}_${System.currentTimeMillis() / 1000L}",
        source = SESSION_SOURCE,
        prompt = "[Automation \"${spec.name}\" triggered by $eventDescription]\n${action.prompt}",
        timeoutMs = RUN_TIMEOUT_MS,
        // The gate token comes from the rule's own metadata, normalized
        // exactly like ScheduledTask.newSessionPermissionToken.
        newSessionPermissionMode = spec.permissionMode,
        // Rules always run in a fresh session (no reuse target in v1).
        reuseSession = { null },
        onDispatched = { sessionId ->
            AppLogger.info(TAG, "running rule=${rule.id} name=\"${spec.name}\" session=$sessionId")
        },
        onCompleted = { app, sessionId, result ->
            val preview = (result.responseText ?: "").take(200).ifBlank { "(no response)" }
            val ok = result.status != "Error" && result.status != "Timeout"
            if (runId > 0) {
                app.database.automationRuleDao().updateRun(
                    runId = runId,
                    outcome = if (ok) AutomationRuleManager.OUTCOME_OK
                    else "${AutomationRuleManager.OUTCOME_ERROR_PREFIX}${result.status}",
                    sessionId = sessionId,
                    preview = preview,
                )
            }
            postCompletionNotification(app, rule, sessionId, preview)
        },
    )

    /**
     * The triggering-event fragment for the prompt preamble is passed in by
     * the manager ([start]'s eventDescription) so the unattended run knows
     * why it woke up.
     */

    private fun postCompletionNotification(
        context: Context,
        rule: AutomationRuleEntity,
        sessionId: String,
        preview: String,
    ) {
        val deepLink = Uri.parse("minis://session/$sessionId")
        val openIntent = Intent(Intent.ACTION_VIEW, deepLink).apply {
            setPackage(context.packageName)
        }
        val notificationId = ("automation_${rule.id}").hashCode() and 0x7FFFFFFF
        val contentPi = PendingIntent.getActivity(
            context,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = "Minis: ${rule.name.ifBlank { "Automation rule" }}"
        // Same lockscreen policy as ScheduledAgentRunner: the preview is model
        // output; only a benign stand-in is exposed while locked.
        val publicVersion = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("Minis")
            .setContentText(context.getString(com.openminis.app.R.string.notify_scheduled_done_locked))
            .setContentIntent(contentPi)
            .setAutoCancel(true)
            .build()
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle(title)
            .setContentText(preview)
            .setStyle(NotificationCompat.BigTextStyle().bigText(preview))
            .setContentIntent(contentPi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicVersion)
            .build()
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE)
                as android.app.NotificationManager
            nm.notify(notificationId, notification)
        } catch (e: SecurityException) {
            AppLogger.warning(TAG, "POST_NOTIFICATIONS denied — completion notice suppressed")
        }
    }

    /** Reuse the scheduled-tasks notification channel — no new channel id. */
    private const val CHANNEL_ID = com.openminis.app.scheduled.ScheduledTaskManager.CHANNEL_ID
}
