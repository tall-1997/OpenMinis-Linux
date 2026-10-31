package com.openminis.app.quick

import android.content.Context
import com.openminis.app.MinisApp
import com.openminis.app.logging.AppLogger
import com.openminis.app.scheduled.ScheduledAgentRunner
import com.openminis.app.session.InputAttachment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * [E-batch-quick-invocation] Unattended agent launch for the quick panel —
 * third runner of the [ScheduledAgentRunner] family (scheduled tasks,
 * automation rules, now the quick panel), riding the shared
 * [ScheduledAgentRunner.runUnattended] template (v3 runner convergence):
 *
 *  - the template owns the MinisApp readiness guards (the panel can start the
 *    process; a half-built Application must degrade to "send failed", not a
 *    crash), the FGS-before-dispatch ordering and the 10-minute ceiling,
 *  - session resolution reuses the app's NEWEST conversation and only creates
 *    a fresh row when none exists (source="quick" so the UI session list can
 *    tell the origin) — new rows go through the template's shared
 *    `createUnattendedSessionRow`, so memory flag / permission mode /
 *    default-group binding are written exactly like every other unattended
 *    fire,
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
     * dispatches through the shared template.
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
        // Panel contract: the failure callback fires SYNCHRONOUSLY on the
        // caller thread when the process is not even ready (the template's
        // guard would bounce it through the main looper), so pre-check here;
        // the template re-checks idempotently for the other runners.
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
            ScheduledAgentRunner.runUnattended(
                context = app,
                logTag = TAG,
                abortSuffix = "quick send aborted",
                subject = "quick",
                sessionTitle = "",
                source = SESSION_SOURCE,
                prompt = text,
                attachments = attachments,
                timeoutMs = RUN_TIMEOUT_MS,
                reuseSession = { a ->
                    resolveSessionId(a)?.also { QuickPrefs(a).markPanelSession(it) }
                        ?: run {
                            AppLogger.error(TAG, "no session resolved — quick send aborted")
                            null
                        }
                },
                onDispatched = { sessionId ->
                    AppLogger.info(
                        TAG,
                        "quick prompt dispatched session=$sessionId chars=${text.length} att=${attachments.size}",
                    )
                },
                onReady = { sessionId -> main.post { onDispatched(sessionId) } },
                onCompleted = { app, sessionId, result ->
                    val ok = !QuickPanelPolicy.isFailedStatus(result.status)
                    val preview = QuickPanelPolicy.buildPreview(result.responseText)
                    QuickNotifications.postCompletion(app, sessionId, preview, ok)
                },
            )
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
     * New-session creation with the shared unattended write-through semantics
     * ([ScheduledAgentRunner.createUnattendedSessionRow] →
     * persistNewSessionRow): global memory flag, ASK permission gate, default
     * primary group binding (credential-aware seed), `source` column flipped
     * to "quick" so the session list can tell the origin. The label is left
     * blank so the chat UI derives the title from the first user turn,
     * exactly like a hand-created chat.
     */
    internal suspend fun createQuickSession(
        repo: com.openminis.app.data.repository.ChatRepository,
        providers: com.openminis.app.data.repository.ProviderRepository,
        context: Context,
    ): String? = ScheduledAgentRunner.createUnattendedSessionRow(
        repo = repo,
        providers = providers,
        appContext = context,
        sessionTitle = "",
        source = SESSION_SOURCE,
        permissionMode = null,
        taskId = "quick",
        logTag = TAG,
        subject = "quick send",
    )
}
