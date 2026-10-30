package com.openminis.app.automation

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.openminis.app.MinisApp
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.logging.AppLogger
import com.openminis.app.scheduled.ScheduledAgentRunner
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.service.AgentForegroundService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [T-android-automation-rules] Unattended agent launch for a fired rule —
 * mirrors the shape of [ScheduledAgentRunner] (the scheduled-task headless
 * path) instead of reinventing it:
 *
 *  - same MinisApp readiness guards (a rule fire can START the process, and a
 *    half-built Application must degrade to a skipped fire, not a crash),
 *  - same new-session persistence via ScheduledAgentRunner's internal
 *    `persistNewSessionRow` (same-module internal visibility), which writes
 *    the tool-gate permission mode + model binding exactly like the chat UI,
 *  - same FGS-before-dispatch ordering and the same 10-minute ceiling,
 *  - fire-and-forget: the caller gets the session id once DISPATCHED, and
 *    completion (run-row update + notification) finishes on an app-scoped
 *    coroutine that outlives the receiver.
 *
 * Naming: rule-triggered sessions are titled `auto_rule_<ruleId>_<ts>` (the
 * ts is the fire time in seconds) so they are greppable apart from scheduled
 * and human chats; the row's `source` column is then flipped to
 * "automation" for the same reason.
 */
object AutomationAgentRunner {

    private const val TAG = "AutomationAgentRunner"
    private const val RUN_TIMEOUT_MS = 10 * 60 * 1000L  // same ceiling as scheduled

    /** Session title prefix mandated for rule-triggered unattended chats. */
    const val SESSION_PREFIX = "auto_rule_"

    /** Outlives the editor / receiver so the loop + completion still finish. */
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
    ): String? {
        val app = context.applicationContext as? MinisApp ?: run {
            AppLogger.error(TAG, "Application is not MinisApp — skipping rule ${rule.id}")
            return null
        }
        if (!app.subsystemsReady()) {
            AppLogger.error(
                TAG,
                "MinisApp subsystems not initialized (safe-mode or failed init) — skipping rule ${rule.id}",
            )
            return null
        }

        val firedAt = System.currentTimeMillis()
        val sessionId = withContext(Dispatchers.IO) { resolveSessionId(app, rule, spec, firedAt) }
            ?: return null

        // Only start the foreground service after the rule has a valid
        // target; an aborted run must not leave an orphan status row.
        AgentForegroundService.startService(app)

        val promptText = "[Automation \"${spec.name}\" triggered by $eventDescription]\n${action.prompt}"
        AppLogger.info(TAG, "running rule=${rule.id} name=\"${spec.name}\" session=$sessionId")

        bgScope.launch {
            val result = runCatching {
                HeadlessChatRunner.prompt(
                    context = app,
                    sessionId = sessionId,
                    text = promptText,
                    attachments = emptyList(),
                    thinkingLevel = null,
                    wait = true,
                    timeoutMs = RUN_TIMEOUT_MS,
                )
            }.getOrElse { t ->
                AppLogger.error(TAG, "rule ${rule.id} dispatch failed: ${t.message}")
                HeadlessChatRunner.PromptResult(
                    status = "Error",
                    responseText = "Error: ${t.message}",
                    timedOut = false,
                )
            }
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
        }
        return sessionId
    }

    /**
     * NewSession resolution copied from ScheduledAgentRunner.resolveSessionId's
     * NewSession branch: rule metadata carries no model pin in v1, so the
     * session boots through the user's default primary group (credential-aware
     * member filter), degrading to the first visible entry.
     */
    private suspend fun resolveSessionId(
        app: MinisApp,
        rule: AutomationRuleEntity,
        spec: RuleSpec,
        firedAt: Long,
    ): String? {
        val defaultGroupId = app.providerRepository.defaultPrimaryGroupId
        val seedModelId: String = defaultGroupId
            ?.let { gid -> app.providerRepository.group(gid) }
            ?.let { group -> app.providerRepository.availableMemberEntries(group).firstOrNull()?.model?.id }
            ?: app.providerRepository.allVisibleEntries().firstOrNull()?.baseModel?.id
            ?: run {
                AppLogger.warning(TAG, "rule ${rule.id}: no provider — abort")
                return null
            }
        val bindingToWrite: String? = defaultGroupId?.let { """{"type":"group","groupId":"$it"}""" }

        // Machine-greppable title: auto_rule_<ruleId>_<ts seconds>.
        val synthetic = ScheduledTask(
            id = rule.id,
            label = "$SESSION_PREFIX${rule.id}_${firedAt / 1000L}",
            timeOfDayHour = 0,
            timeOfDayMinute = 0,
            repeatMode = com.openminis.app.scheduled.ScheduledRepeatMode.ONCE,
            prompt = "",
            // The gate token comes from the rule's own metadata, normalized
            // exactly like ScheduledTask.newSessionPermissionToken.
            newSessionPermissionMode = spec.permissionMode,
        )
        val session = ScheduledAgentRunner.persistNewSessionRow(
            repo = app.chatRepository,
            context = app,
            task = synthetic,
            seedModelId = seedModelId,
            bindingToWrite = bindingToWrite,
        )
        // Distinguish automation fires from scheduled fires at a glance.
        app.chatRepository.dao.updateSource(session.id, "automation")
        return session.id
    }

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
