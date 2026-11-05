package com.openminis.app.scheduled

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.debug.HeadlessChatRunner
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.repository.listFolders
import com.openminis.app.data.repository.setFolderForSessions
import com.openminis.app.service.AgentForegroundService
import com.openminis.app.session.InputAttachment
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [T-android-scheduled-tasks-design] Headless agent launch for scheduled
 * tasks. Mirrors the shape of iOS SendPromptIntent.perform():
 *
 *  - resolve a session id (NEW_SESSION → create row; APPEND_TO → reuse)
 *  - run the prompt through the existing agent loop (via [HeadlessChatRunner])
 *  - post a "completed" notification with a deep-link back to the session
 *  - hand the result preview back to [ScheduledTaskManager] for the row
 *
 * Concurrency: serialised per-session by [HeadlessChatRunner]'s VM cache —
 * two scheduled prompts hitting the same session id are queued via
 * ChatViewModel.enqueuePrompt.
 */
object ScheduledAgentRunner {

    private const val TAG = "ScheduledAgentRunner"
    private const val RUN_TIMEOUT_MS = 10 * 60 * 1000L  // 10 min ceiling

    /**
     * App-scoped scope for fire-and-forget completion work when a caller asks
     * NOT to wait (the "Run now" button). Outlives the editor screen so the
     * agent loop + completion notification finish even after the user leaves.
     */
    private val bgScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Fire a scheduled task.
     *
     * [P2-template-reuse] The whole body is now [runUnattended] with:
     *  - `reuseSession` = [resolveSessionId] (the template resolves off-main,
     *    same `withContext(Dispatchers.IO)` wrapper as before);
     *  - `allowFreshRow=false` — resolveSessionId's null is an ABORT (target
     *    session gone / no provider), never a "mint a default row" request;
     *  - `dispatchOverride` = the RerunMessage→retry / prompt branch;
     *  - `waitForCompletion` = the caller's flag — true keeps the historic
     *    INLINE suspend-until-done semantics (main-thread wait, GH#197);
     *  - completion (markFired + notification) in [onCompleted].
     *
     * Sequence parity check (old line order vs template): readiness guards →
     * IO session resolve → FGS only after a valid target → running log →
     * dispatch under RUN_TIMEOUT_MS with identical runCatching error shaping
     * → markFired → completion notification. All preserved.
     *
     * @param waitForCompletion when true, suspend until the agent loop
     *   finishes, then mark-fired + post the completion notification before
     *   returning. When false, return as soon as the session is resolved and
     *   the prompt is dispatched — the agent keeps running in the background
     *   and the completion (mark-fired + notification) is finished off an
     *   app-scoped coroutine. Mirrors iOS SendPromptIntent's waitForResult
     *   flag.
     *
     *   [GH#197] NEVER pass true from a BroadcastReceiver. Waiting here can
     *   take up to [RUN_TIMEOUT_MS] (10 min) and the wait lands on the main
     *   thread (HeadlessChatRunner.prompt/retry are
     *   `withContext(Dispatchers.Main)`), so a receiver that waits blows its
     *   ~10s broadcast budget and gets the whole process ANR-killed, along
     *   with every PRoot sandbox child. The alarm path passing the default
     *   `true` was exactly that bug. Waiting is only safe off a broadcast —
     *   e.g. the minis-scheduled CLI, which runs in its own offload thread.
     * @return the session id once the action has been DISPATCHED (resolved +
     *   prompt sent), or null when the runner couldn't even start (no provider,
     *   target chat gone, MinisApp not initialized).
     */
    suspend fun run(
        context: Context,
        task: ScheduledTask,
        waitForCompletion: Boolean = true,
    ): String? {
        // [T-android-scheduled-lateinit-crash-156] The MinisApp
        // half-initialization guard lives in the template now
        // (`as? MinisApp` + subsystemsReady) — a skipped run leaves the task
        // scheduled and its next occurrence armed by the receiver, so the
        // fire self-heals on the following launch.
        return runUnattended(
            context = context,
            logTag = TAG,
            abortSuffix = "skipping task ${task.id}",
            subject = "task ${task.id}",
            sessionTitle = task.label.ifBlank { "Scheduled task" },
            source = "scheduled",
            prompt = task.prompt,
            timeoutMs = RUN_TIMEOUT_MS,
            reuseSession = { app -> resolveSessionId(app, task) },
            allowFreshRow = false,
            dispatchOverride = { app, sessionId -> dispatchForTask(app, task, sessionId) },
            waitForCompletion = waitForCompletion,
            onDispatched = { sessionId ->
                AppLogger.info(
                    TAG,
                    "running task=${task.id} label=\"${task.label}\" " +
                        "mode=${task.targetMode.encode()} session=$sessionId wait=$waitForCompletion",
                )
            },
            onCompleted = { app, sessionId, result ->
                val preview = (result.responseText ?: "").take(200).ifBlank { "(no response)" }
                val ok = result.status != "Error" && result.status != "Timeout"
                ScheduledTaskManager(app).markFired(task.id, sessionId, preview, ok = ok)
                postCompletionNotification(app, task, sessionId, preview)
            },
        )
    }

    /**
     * [T-android-scheduled-tasks-full] Branch on target mode, mirroring the iOS
     * App Intent set:
     *   NewSession / AppendToSession → HeadlessChatRunner.prompt (the prompt is
     *     sent as a fresh user turn; for append it lands in the existing
     *     session, for new it's the first turn of the freshly-created one).
     *   RerunMessage → HeadlessChatRunner.retry from the chosen message id (the
     *     message is replayed; task.prompt is ignored, matching iOS
     *     RetryRunIntent which has no prompt param).
     *
     * Error shaping moved to the [runUnattended] template — this only selects
     * the transport. Always dispatched with wait=true (the template decides
     * inline-vs-bgScope).
     */
    private suspend fun dispatchForTask(
        app: MinisApp,
        task: ScheduledTask,
        sessionId: String,
    ): HeadlessChatRunner.PromptResult {
        val mode = task.targetMode
        return if (mode is ScheduledTargetMode.RerunMessage) {
            HeadlessChatRunner.retry(
                context = app,
                sessionId = sessionId,
                messageId = mode.messageId,
                wait = true,
                timeoutMs = RUN_TIMEOUT_MS,
            )
        } else {
            HeadlessChatRunner.prompt(
                context = app,
                sessionId = sessionId,
                text = task.prompt,
                attachments = emptyList(),
                thinkingLevel = null,
                wait = true,
                timeoutMs = RUN_TIMEOUT_MS,
            )
        }
    }

    private suspend fun resolveSessionId(app: MinisApp, task: ScheduledTask): String? {
        return when (val mode = task.targetMode) {
            is ScheduledTargetMode.AppendToSession -> {
                // Follow-up: the target session must still exist. If the user
                // deleted it, abort rather than silently spawning a new chat.
                if (app.chatRepository.getSession(mode.sessionId) == null) {
                    AppLogger.warning(TAG, "task ${task.id}: follow-up session ${mode.sessionId} gone — abort")
                    null
                } else {
                    mode.sessionId
                }
            }
            is ScheduledTargetMode.RerunMessage -> {
                if (app.chatRepository.getSession(mode.sessionId) == null) {
                    AppLogger.warning(TAG, "task ${task.id}: re-run session ${mode.sessionId} gone — abort")
                    null
                } else {
                    mode.sessionId
                }
            }
            ScheduledTargetMode.NewSession -> {
                // Resolution priority (mirrors the UI's normal-chat boot):
                //   1. task.modelBinding (user picked a group or specific entry
                //      on the task editor) → write it through verbatim so the
                //      new session lights up the same group/entry chip.
                //   2. legacy task.modelId (pre-binding, kept for backcompat) →
                //      pin to that entry, no binding.
                //   3. default primary group → write a group binding so the
                //      session boots through the user's default group.
                //   4. first visible entry → last-resort fallback.
                val explicitBinding = task.modelBinding
                val pinnedModelId = task.modelId
                val defaultGroupId =
                    if (explicitBinding == null && pinnedModelId == null) {
                        app.providerRepository.defaultPrimaryGroupId
                    } else null

                // Derive the seed modelId for the session row (must be valid
                // even before restoreFromBinding runs).
                val seedModelId: String = pinnedModelId
                    ?: run {
                        // Try to peek a member from whichever binding we'll
                        // write — explicit takes priority, then default group.
                        val groupIdForSeed: String? = explicitBinding
                            ?.let { parseGroupIdFromBinding(it) }
                            ?: defaultGroupId
                        val entryIdForSeed: String? = explicitBinding
                            ?.let { parseEntryIdFromBinding(it) }

                        // Entry binding → use that entry's model id.
                        entryIdForSeed
                            ?.let { eid -> app.providerRepository.config.value.modelEntries.firstOrNull { it.id == eid }?.model?.id }
                            ?: groupIdForSeed
                                ?.let { gid -> app.providerRepository.group(gid) }
                                // [T-android-group-resolve-skip-uncredentialed]
                                // Credential-aware filter — a scheduled run
                                // seeded with an uncredentialed member would
                                // fail unattended, with no user around to see
                                // the auth error.
                                ?.let { g -> app.providerRepository.availableMemberEntries(g).firstOrNull()?.model?.id }
                    }
                    ?: app.providerRepository.allVisibleEntries().firstOrNull()?.baseModel?.id
                    ?: run {
                        AppLogger.warning(TAG, "task ${task.id}: no provider — abort")
                        return null
                    }
                val bindingToWrite: String? = explicitBinding
                    ?: defaultGroupId?.let { """{"type":"group","groupId":"$it"}""" }
                return persistNewSessionRow(app.chatRepository, app, task, seedModelId, bindingToWrite).id
            }
        }
    }

    /**
     * [T-scheduled-new-session-permission] Create the session row for a
     * NewSession fire and write through everything the task pinned:
     *   - tool-gate permission mode → `permission_mode` column ("YOYO" or
     *     "ASK", same tokens ChatViewModel.setSessionPermissionMode writes —
     *     null on the task normalizes to ASK, so pre-feature tasks keep the
     *     app-default gate),
     *   - model binding (pre-existing behaviour),
     *   - session group ("folder") → filed via [ChatRepository.setFolderForSessions],
     *     the same path the chat screen's "New Chat in Group" uses, so the
     *     SessionWorkspace owner map and the on-disk project dirs are
     *     provisioned identically. A folder dissolved after the task was
     *     saved degrades to unfiled instead of writing a ghost folder_id.
     *
     * Internal and repo-parameterized so the Robolectric suite can drive the
     * exact persistence path against an in-memory Room DB without a MinisApp.
     */
    internal suspend fun persistNewSessionRow(
        repo: com.openminis.app.data.repository.ChatRepository,
        context: Context,
        task: ScheduledTask,
        seedModelId: String,
        bindingToWrite: String?,
    ): com.openminis.app.data.db.ChatSessionEntity {
        val title = task.label.ifBlank { "Scheduled task" }
        val memoryOn = com.openminis.app.data.MemoryGlobalPrefs.isGlobalEnabled(context)
        val session = repo.createSession(
            modelId = seedModelId,
            title = title,
            memoryEnabled = memoryOn,
            permissionMode = task.newSessionPermissionToken(),
        )
        repo.dao.updateSource(session.id, "scheduled")
        if (bindingToWrite != null) {
            repo.updateSessionBinding(session.id, bindingToWrite, seedModelId)
        }
        if (task.newSessionFolderId != null) {
            val folderId = task.resolvedNewSessionFolderId(
                repo.listFolders().map { it.id }.toSet(),
            )
            if (folderId != null) {
                repo.setFolderForSessions(folderId, listOf(session.id))
            } else {
                AppLogger.warning(
                    TAG,
                    "task ${task.id}: folder ${task.newSessionFolderId} gone — session left unfiled",
                )
            }
        }
        return session
    }

    // ── [v3] unattended-runner template ──────────────────────────────────

    /**
     * Shared skeleton of the unattended runners (scheduled tasks, automation
     * rules, quick panel): MinisApp readiness guard → session resolution →
     * foreground service BEFORE dispatch → wait=true prompt under the
     * caller's own timeout → completion callback. The three runners used to
     * copy this sequence line by line; the template owns the ordering and
     * every caller keeps its identity.
     *
     * Firing semantics are unchanged per runner: returns the session id once
     * DISPATCHED (null when the runner could not even start — no provider,
     * half-built app), and the agent loop keeps running on [bgScope] after
     * this returns.
     *
     * @param logTag caller's log tag (each runner logs under its own name).
     * @param abortSuffix guard-failure log suffix — the full line is
     *   "Application is not MinisApp — <abortSuffix>" (e.g. "skipping rule
     *   r1" / "quick send aborted").
     * @param subject mid-flow log subject: dispatch-failure line is
     *   "<subject> dispatch failed: …", no-provider line is "<subject>: no
     *   provider — abort".
     * @param sessionTitle label for a freshly created row
     *   ([persistNewSessionRow] blanks it to "Scheduled task" when empty,
     *   exactly like the quick panel's row).
     * @param source `source` column value written for a freshly created row
     *   (over [persistNewSessionRow]'s "scheduled" default).
     * @param maxTurns reserved for a per-runner turn cap — no runner sets it
     *   today (HeadlessChatRunner.prompt does not take one yet); plumbed
     *   through here when the runner loop grows one.
     * @param timeoutMs the caller's own ceiling (all three use 10 min today,
     *   kept a per-caller argument so a future per-runner change stays local).
     * @param newSessionPermissionMode tool-gate token for a fresh row
     *   (null = app default), written through [persistNewSessionRow].
     * @param reuseSession hook returning an existing session id, or null to
     *   create a fresh row via [createUnattendedSessionRow] on the IO
     *   dispatcher (all runners resolve sessions off-main). When
     *   [allowFreshRow] is false a null return aborts instead of creating a
     *   row — for runners whose resolver encodes "abort" as null (scheduled
     *   tasks validate the target session themselves and never want a
     *   default row minted underneath them).
     * @param allowFreshRow false disables the create-fresh-row fallback: a
     *   null [reuseSession] result is a terminal abort (onReady(null)).
     * @param dispatchOverride replaces the template's default
     *   [HeadlessChatRunner.prompt] call for runners that dispatch
     *   differently (scheduled tasks re-run via [HeadlessChatRunner.retry]).
     *   The runCatching → PromptResult("Error") shaping ALWAYS stays in the
     *   template, so every runner reports dispatch failures the same way.
     * @param waitForCompletion false (default) dispatches on [bgScope] and
     *   returns right after the prompt is handed off — fire-and-forget, with
     *   [onCompleted] running when the loop finishes. true runs the dispatch
     *   INLINE in the caller's coroutine and returns only after
     *   [onCompleted]: the scheduled-task path relies on this main-thread
     *   wait semantics (the CLI caller blocks until the run is done; a
     *   broadcast receiver must NOT use this — GH#197).
     * @param onReady invoked exactly once with the dispatch outcome — the
     *   session id, or null on any abort before dispatch. Quick bounces it to
     *   the main looper; automation relies on the return value.
     * @param onDispatched synchronous pre-prompt hook (logging seam), after
     *   the foreground service is up.
     * @param onCompleted completion hook with the resolved app/session/result.
     */
    internal suspend fun runUnattended(
        context: Context,
        logTag: String,
        abortSuffix: String,
        subject: String,
        sessionTitle: String,
        source: String,
        prompt: String,
        attachments: List<InputAttachment> = emptyList(),
        maxTurns: Int? = null,
        timeoutMs: Long = RUN_TIMEOUT_MS,
        newSessionPermissionMode: String? = null,
        reuseSession: suspend (MinisApp) -> String? = { null },
        allowFreshRow: Boolean = true,
        dispatchOverride: (suspend (MinisApp, String) -> HeadlessChatRunner.PromptResult)? = null,
        waitForCompletion: Boolean = false,
        onReady: (String?) -> Unit = {},
        onDispatched: (String) -> Unit = {},
        onCompleted: suspend (MinisApp, String, HeadlessChatRunner.PromptResult) -> Unit = { _, _, _ -> },
    ): String? {
        val app = context.applicationContext as? MinisApp ?: run {
            AppLogger.error(logTag, "Application is not MinisApp — $abortSuffix")
            onReady(null)
            return null
        }
        if (!app.subsystemsReady()) {
            AppLogger.error(
                logTag,
                "MinisApp subsystems not initialized (safe-mode or failed init) — $abortSuffix",
            )
            onReady(null)
            return null
        }
        val sessionId = withContext(Dispatchers.IO) {
            reuseSession(app) ?: run {
                if (allowFreshRow) createUnattendedSessionRow(
                    repo = app.chatRepository,
                    providers = app.providerRepository,
                    appContext = app,
                    sessionTitle = sessionTitle,
                    source = source,
                    permissionMode = newSessionPermissionMode,
                    taskId = subject,
                    logTag = logTag,
                    subject = subject,
                ) else null
            }
        }
        if (sessionId == null) {
            onReady(null)
            return null
        }
        // Only start the foreground service after the runner has a valid
        // target; an aborted run must not leave an orphan status row.
        AgentForegroundService.startService(app)
        onDispatched(sessionId)
        onReady(sessionId)

        val dispatchCall: suspend (MinisApp, String) -> HeadlessChatRunner.PromptResult =
            dispatchOverride ?: { a, sid ->
                HeadlessChatRunner.prompt(
                    context = a,
                    sessionId = sid,
                    text = prompt,
                    attachments = attachments,
                    thinkingLevel = null,
                    wait = true,
                    timeoutMs = timeoutMs,
                )
            }

        if (waitForCompletion) {
            // [P2-template-reuse] Inline dispatch: the caller's coroutine
            // suspends until the agent loop finishes (HeadlessChatRunner is
            // withContext(Dispatchers.Main), so the wait lands on the main
            // thread exactly like the pre-template scheduled-task path).
            // onCompleted runs before the return, in the caller's context.
            val result = runCatching {
                dispatchCall(app, sessionId)
            }.getOrElse { t ->
                AppLogger.error(logTag, "${subject} dispatch failed: ${t.message}")
                HeadlessChatRunner.PromptResult(
                    status = "Error",
                    responseText = "Error: ${t.message}",
                    timedOut = false,
                )
            }
            onCompleted(app, sessionId, result)
            return sessionId
        }

        bgScope.launch {
            val result = runCatching {
                dispatchCall(app, sessionId)
            }.getOrElse { t ->
                AppLogger.error(logTag, "${subject} dispatch failed: ${t.message}")
                HeadlessChatRunner.PromptResult(
                    status = "Error",
                    responseText = "Error: ${t.message}",
                    timedOut = false,
                )
            }
            onCompleted(app, sessionId, result)
        }
        return sessionId
    }

    /**
     * Fresh-row creation for an unattended fire, shared by the automation and
     * quick-panel paths (repo-parameterized so tests can drive it without a
     * MinisApp): credential-aware seed model through the default primary
     * group, default-group binding write-through, [persistNewSessionRow]
     * persistence, then the `source` column flip that marks the origin.
     * Returns null (with a log) when no provider is configured.
     */
    internal suspend fun createUnattendedSessionRow(
        repo: com.openminis.app.data.repository.ChatRepository,
        providers: com.openminis.app.data.repository.ProviderRepository,
        appContext: Context,
        sessionTitle: String,
        source: String,
        permissionMode: String?,
        taskId: String,
        logTag: String,
        subject: String,
    ): String? {
        val defaultGroupId = providers.defaultPrimaryGroupId
        val seedModelId: String = defaultGroupId
            ?.let { gid -> providers.group(gid) }
            ?.let { group -> providers.availableMemberEntries(group).firstOrNull()?.model?.id }
            ?: providers.allVisibleEntries().firstOrNull()?.baseModel?.id
            ?: run {
                AppLogger.warning(logTag, "$subject: no provider — abort")
                return null
            }
        val bindingToWrite: String? = defaultGroupId?.let { """{"type":"group","groupId":"$it"}""" }
        val synthetic = ScheduledTask(
            id = taskId,
            label = sessionTitle,
            timeOfDayHour = 0,
            timeOfDayMinute = 0,
            repeatMode = ScheduledRepeatMode.ONCE,
            prompt = "",
            newSessionPermissionMode = permissionMode,
        )
        val session = persistNewSessionRow(
            repo = repo,
            context = appContext,
            task = synthetic,
            seedModelId = seedModelId,
            bindingToWrite = bindingToWrite,
        )
        // Mark the origin over persistNewSessionRow's "scheduled" default —
        // the same two-step write the per-runner code used to do.
        repo.dao.updateSource(session.id, source)
        return session.id
    }

    // Mirrors ChatViewModel.restoreFromBinding's JSON shape. Robust against
    // malformed JSON — both parsers return null and the caller fall-backs.
    private fun parseGroupIdFromBinding(json: String): String? = runCatching {
        val o = org.json.JSONObject(json)
        if (o.optString("type") == "group") {
            o.optString("groupId").takeIf { it.isNotEmpty() }
        } else null
    }.getOrNull()

    private fun parseEntryIdFromBinding(json: String): String? = runCatching {
        val o = org.json.JSONObject(json)
        if (o.optString("type") == "entry") {
            o.optString("entryId").takeIf { it.isNotEmpty() }
        } else null
    }.getOrNull()

    private fun postCompletionNotification(
        context: Context,
        task: ScheduledTask,
        sessionId: String,
        preview: String,
    ) {
        val deepLink = Uri.parse("minis://session/$sessionId")
        val openIntent = Intent(Intent.ACTION_VIEW, deepLink).apply {
            setPackage(context.packageName)
        }
        val notificationId = task.id.hashCode() and 0x7FFFFFFF
        val contentPi = PendingIntent.getActivity(
            context,
            notificationId,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val title = "Minis: ${task.label.ifBlank { "Scheduled task" }}"
        // [T-approval-lockscreen] The 200-char agent-reply preview is model
        // output the user may not want exposed on a locked screen (a scheduled
        // run can echo anything that passed through the chat). Post the full
        // row unlocked, and only a benign stand-in on the lock screen.
        val publicVersion = NotificationCompat.Builder(context, ScheduledTaskManager.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_recent_history)
            .setContentTitle("Minis")
            .setContentText(context.getString(R.string.notify_scheduled_done_locked))
            .setContentIntent(contentPi)
            .setAutoCancel(true)
            .build()
        val notification = NotificationCompat.Builder(context, ScheduledTaskManager.CHANNEL_ID)
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
}
