package com.openminis.app.quick

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.logging.AppLogger
import com.openminis.app.scheduled.ScheduledAgentRunner
import com.openminis.app.scheduled.ScheduledRepeatMode
import com.openminis.app.scheduled.ScheduledTask
import com.openminis.app.service.AgentForegroundService
import com.openminis.app.session.InputAttachment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * [E-batch-quick-invocation] Unattended agent launch for the quick panel —
 * third runner of the [ScheduledAgentRunner] family (scheduled tasks,
 * automation rules, now the quick panel), deliberately mirroring their shape:
 *
 *  - same MinisApp readiness guards (the panel can start the process; a
 *    half-built Application must degrade to "send failed", not a crash),
 *  - session resolution reuses the app's NEWEST conversation and only creates
 *    a fresh row when none exists (source="quick" so the UI session list can
 *    tell the origin) — new rows go through ScheduledAgentRunner's internal
 *    `persistNewSessionRow`, so memory flag / permission mode / default-group
 *    binding are written exactly like every other unattended fire,
 *  - same FGS-before-dispatch ordering and the same 10-minute ceiling,
 *  - fire-and-forget: the prompt is dispatched and waited out on an
 *    app-scoped coroutine that outlives the panel, and the outcome arrives as
 *    the system notification (QuickNotifications.postCompletion).
 *
 * Concurrency: per-session serialisation is inherited from
 * HeadlessChatRunner's VM cache — two sends into the same session queue via
 * ChatViewModel.enqueuePrompt.
 */
object QuickPromptDispatcher {

    private const val TAG = "QuickPromptDispatcher"
    private const val RUN_TIMEOUT_MS = 10 * 60 * 1000L

    /** `source` column value for sessions created by the quick panel. */
    const val SESSION_SOURCE = "quick"

    /** Outlives the panel activity so the loop + notification still finish. */
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Fire-and-forget entry the panel's send button calls.
     *
     * Resolves the target session, records it in [QuickPrefs] (drives the
     * 10-minute panel-reopen window), starts the agent foreground service and
     * dispatches on [bgScope].
     *
     * @param onDispatched invoked ONCE on the main thread with the session id
     *   the prompt was dispatched into, or null when the runner could not
     *   even start (no provider, half-built app) — the panel toasts and stays
     *   editable in that case.
     */
    fun send(
        context: Context,
        text: String,
        attachments: List<InputAttachment> = emptyList(),
        onDispatched: (String?) -> Unit = {},
    ) {
        val app = context.applicationContext as? MinisApp ?: run {
            AppLogger.error(TAG, "Application is not MinisApp — quick send aborted")
            onDispatched(null)
            return
        }
        if (!app.subsystemsReady()) {
            AppLogger.error(TAG, "MinisApp subsystems not initialized — quick send aborted")
            onDispatched(null)
            return
        }
        val main = android.os.Handler(android.os.Looper.getMainLooper())
        bgScope.launch {
            val sessionId = resolveSessionId(app)
            if (sessionId == null) {
                AppLogger.error(TAG, "no session resolved — quick send aborted")
                main.post { onDispatched(null) }
                return@launch
            }
            QuickPrefs(app).markPanelSession(sessionId)
            // Same ordering as the other runners: FGS up before dispatch so an
            // in-flight turn is visible and the process is protected.
            AgentForegroundService.startService(app)
            AppLogger.info(
                TAG,
                "quick prompt dispatched session=$sessionId chars=${text.length} att=${attachments.size}",
            )
            main.post { onDispatched(sessionId) }
            val result = runCatching {
                HeadlessChatRunner.prompt(
                    context = app,
                    sessionId = sessionId,
                    text = text,
                    attachments = attachments,
                    thinkingLevel = null,
                    wait = true,
                    timeoutMs = RUN_TIMEOUT_MS,
                )
            }.getOrElse { t ->
                AppLogger.error(TAG, "quick dispatch failed: ${t.message}")
                HeadlessChatRunner.PromptResult(
                    status = "Error",
                    responseText = "Error: ${t.message}",
                    timedOut = false,
                )
            }
            val ok = !QuickPanelPolicy.isFailedStatus(result.status)
            val preview = QuickPanelPolicy.buildPreview(result.responseText)
            QuickNotifications.postCompletion(app, sessionId, preview, ok)
        }
    }

    /**
     * Target session for a quick send: the app's newest conversation when one
     * exists, else a fresh row created with the exact semantics of the other
     * unattended runners. Internal + repo-parameterized seam for tests.
     */
    suspend fun resolveSessionId(app: MinisApp): String? =
        resolveSessionId(app.chatRepository, app.providerRepository, app)

    internal suspend fun resolveSessionId(
        repo: com.openminis.app.data.repository.ChatRepository,
        providers: com.openminis.app.data.repository.ProviderRepository,
        context: Context,
    ): String? {
        val newest = repo.dao.newestSession()?.id
        if (newest != null) return newest
        return createQuickSession(repo, providers, context)
    }

    /**
     * New-session creation with the same write-through semantics as
     * [ScheduledAgentRunner.persistNewSessionRow]: global memory flag, ASK
     * permission gate, default primary group binding (credential-aware seed),
     * `source` column flipped to "quick" so the session list can tell the
     * origin. The label is left blank so the chat UI derives the title from
     * the first user turn, exactly like a hand-created chat.
     */
    internal suspend fun createQuickSession(
        repo: com.openminis.app.data.repository.ChatRepository,
        providers: com.openminis.app.data.repository.ProviderRepository,
        context: Context,
    ): String? {
        val defaultGroupId = providers.defaultPrimaryGroupId
        val seedModelId: String = defaultGroupId
            ?.let { gid -> providers.group(gid) }
            ?.let { group -> providers.availableMemberEntries(group).firstOrNull()?.model?.id }
            ?: providers.allVisibleEntries().firstOrNull()?.baseModel?.id
            ?: run {
                AppLogger.warning(TAG, "no provider configured — cannot create quick session")
                return null
            }
        val bindingToWrite: String? = defaultGroupId?.let { """{"type":"group","groupId":"$it"}""" }
        val synthetic = ScheduledTask(
            id = "quick",
            label = "",
            timeOfDayHour = 0,
            timeOfDayMinute = 0,
            repeatMode = ScheduledRepeatMode.ONCE,
            prompt = "",
        )
        val session = ScheduledAgentRunner.persistNewSessionRow(
            repo = repo,
            context = context,
            task = synthetic,
            seedModelId = seedModelId,
            bindingToWrite = bindingToWrite,
        )
        repo.dao.updateSource(session.id, SESSION_SOURCE)
        return session.id
    }
}
