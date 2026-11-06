package com.openminis.app.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import com.openminis.app.MinisApp
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Foreground service that displays a persistent notification while agent sessions
 * are actively running. On AOSP the row can show a tool name and a chronometer.
 * On a forked SystemUI the row is a single static status: a ticking or promoted
 * template is what their island leaks until the system UI restarts.
 */
class AgentForegroundService : Service() {

    companion object {
        // [T-STALL-DIAG] onStartCommand ordinal in THIS process; 1 + revival=true proves a fresh-process START_STICKY revival.
        private val onStartCommandCalls = java.util.concurrent.atomic.AtomicInteger(0)

        // [T-STALL-DIAG] Process-start reference so diagnostics report how long THIS process has been alive.
        private val processStartElapsedMs = android.os.SystemClock.elapsedRealtime()

        @Volatile
        var wakeLockHeld: Boolean = false
            internal set

        private const val TAG = "AgentForegroundService"
        private const val CHANNEL_ID = "agent_status"
        private const val PREFS = "agent_fgs"
        private const val KEY_CHANNEL_RESTORED = "channel_visible_restored"

        @Volatile
        var isRunning: Boolean = false
            private set
        private const val CHANNEL_NAME = "Agent Status"
        private const val NOTIFICATION_ID = 9001

        // [T-bg-overlay phase 2 fix] Separate channel/id for the SYSTEM_ALERT_WINDOW nudge (higher importance than the LOW FGS row).
        internal const val OVERLAY_NUDGE_CHANNEL_ID = "overlay_permission_nudge"
        internal const val OVERLAY_NUDGE_NOTIFICATION_ID = 9002

        // [T-android-dynamic-island] Framework extras key for isRequestPromotedOngoing() (Android 16), not an exported SDK constant.
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
        private const val ACTION_STOP = "com.openminis.app.STOP_AGENT_SERVICE"
        // [T-approval-action-single-source] Approval actions + request-id live in the shared ApprovalIntents object.
        private const val ACTION_APPROVE = com.openminis.app.notification.ApprovalIntents.ACTION_APPROVE
        private const val ACTION_DENY = com.openminis.app.notification.ApprovalIntents.ACTION_DENY
        private const val ACTION_INTERRUPT = "com.openminis.app.INTERRUPT_AGENT"
        private const val EXTRA_APPROVAL_ID = com.openminis.app.notification.ApprovalIntents.EXTRA_REQUEST_ID

        /** Starts or updates the foreground service with current status. */
        fun startService(context: Context) {
            // The tracker is the source of truth (a second snapshot caused stale/duplicate posts).
            val intent = Intent(context, AgentForegroundService::class.java)
            try {
                // minSdk 26 (O) — the pre-O startService() branch was unreachable.
                context.startForegroundService(intent)
            } catch (e: IllegalStateException) {
                // API 31+ backgrounded start: keep running; notification is best-effort.
                Log.w(TAG, "startForegroundService not allowed: ${e.message}")
            } catch (e: SecurityException) {
                Log.w(TAG, "startForegroundService denied: ${e.message}")
            }
        }

        /** Stops the foreground service. */
        fun stopService(context: Context) {
            val intent = Intent(context, AgentForegroundService::class.java)
            context.stopService(intent)
        }
    }

    private var startTimeMs: Long = 0L
    // Partial wake lock held ONLY while real work is in flight (active stream or running tool); released on the idle edge and by [onDestroy].
    private var wakeLock: PowerManager.WakeLock? = null

    // [Wave3 #6] 30-min hard cap; the observer renews every 25 min while work is in flight.
    private val WAKE_LOCK_TIMEOUT_MS = 30 * 60 * 1000L
    private val WAKE_LOCK_RENEW_INTERVAL_MS = 25 * 60 * 1000L
    private var wakeLockRenewJob: Job? = null

    // T-bg-overlay phase 2: floating tool-status overlay (visible only when toggle ON + SAW granted + backgrounded + tool in flight); falls back to the Phase 1 notification.
    private var overlayController: ToolOverlayController? = null
    private val overlayScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lingerJob: Job? = null

    // [T-android-overlay-completion-pending] X9: linger a completed capsule only when the turn ended while backgrounded; clears on dismissal or foreground.
    private var hasCompletionPending = false
    private var wasBusy = false

    // [T-android-hyperos-island] Background executor for the HyperOS canShowFocus binder probe (never on the main thread).
    private val islandProbeExecutor = java.util.concurrent.Executors.newSingleThreadExecutor { r ->
        Thread(r, "mi-island-probe").apply { isDaemon = true }
    }
    private var lastIsForegroundForIslandProbe = false

    override fun onCreate() {
        super.onCreate()
        // Safe-mode bail-out: reading an unassigned repository from ToolOverlayController would re-crash; skip observers and let onStartCommand satisfy the deadline + stopSelf.
        isRunning = true
        createNotificationChannel()
        startTimeMs = SystemClock.elapsedRealtime()
        // Meet the deadline first: exactly one cheap, unpromoted placeholder per instance.
        publishBootstrap()
        if (!bootstrapPosted || com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()) {
            Log.w(TAG, "foreground unavailable or safe-mode ON — skipping overlay/wake-lock bring-up")
            return
        }
        startWakeLockObserver()
        // [T-android-hyperos-island] Populate the focus-notification permission cache before the first island decision.
        islandProbeExecutor.execute {
            if (HyperOsIsland.refreshFocusPermission(this)) {
                notifyHandler.post { requestStatusRefresh() }
            }
        }
        startOverlayObserver()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // [T-STALL-DIAG] Revival probe: intent == null means START_STICKY re-created the service after process death; an empty activeSessions is the smoking gun.
        val call = onStartCommandCalls.incrementAndGet()
        val active = SessionActivityTracker.activeSessions.value
        // [T-STALL-DIAG] AppLogger (not println) — these lines carry session-id lists, in release too.
        AppLogger.info(
            TAG,
            "[T-STALL-DIAG] FGS onStartCommand#$call pid=${android.os.Process.myPid()} " +
                "revival=${intent == null} flags=$flags startId=$startId " +
                "activeSessions=${active.size}[${active.joinToString(",")}] " +
                "processAliveMs=${android.os.SystemClock.elapsedRealtime() - processStartElapsedMs} " +
                "slots=${SessionConcurrencyManager.diagSnapshot()}",
        )
        // [T-fgs-deadline-invariant] Answer every startForegroundService() with startForeground() in THIS dispatch (before any stopSelf()), or the 5-10s deadline kills the app.
        if (bootstrapPosted) {
            val anchor = try {
                buildNotification(notificationState())
            } catch (t: Throwable) {
                Log.w(TAG, "anchor build failed, using stub: ${t.message}")
                stubForegroundNotification()
            }
            applyForeground(anchor, reanchor = true)
        }
        // onCreate already satisfied the deadline with a stub; if even that failed, nothing should stay alive.
        if (!bootstrapPosted || com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()) {
            stopSelf()
            return START_NOT_STICKY
        }
        // START_STICKY restores the service, not jobs; don't leave an orphan notification after restart.
        if (intent == null &&
            SessionActivityTracker.activeSessions.value.isEmpty() &&
            SessionActivityTracker.presentSessions.value.isEmpty()
        ) {
            stopSelf()
            return START_NOT_STICKY
        }
        // [T-question-bg-notify] Answer fallback for a stale broadcast re-routed to this service.
        if (intent?.action == com.openminis.app.notification.AskUserIntents.ACTION_ANSWER) {
            val qId = intent.getStringExtra(com.openminis.app.notification.AskUserIntents.EXTRA_REQUEST_ID)
            val qLabel = intent.getStringExtra(com.openminis.app.notification.AskUserIntents.EXTRA_ANSWER_LABEL)
            if (qId != null && qLabel != null) {
                val qSession = AskUserGate.detailOf(qId)?.sessionId
                AskUserGate.answerWithLabel(qId, qLabel)
                if (qSession != null) {
                    com.openminis.app.notification.AskUserNotifier.cancelQuestion(this, qSession)
                }
            }
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_APPROVE || intent?.action == ACTION_DENY) {
            // [T-android-notif-approval] Approval action fallback for intents routed to onStartCommand.
            val id = intent.getStringExtra(EXTRA_APPROVAL_ID)
            if (id != null) {
                if (intent.action == ACTION_APPROVE) ApprovalGate.approve(id)
                else ApprovalGate.deny(id)
            }
            // Clear the approval notification on every resolution path landing here.
            if (id != null) com.openminis.app.notification.ApprovalNotifier.cancelApproval(this, id)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            // T50: Stop also cancels every running agent loop, and [T-android-approval-teardown] resolves pending approvals + clears their notifications (snapshot ids before cleanupAll).
            val stopPendingIds = ApprovalGate.pendingIds()
            ApprovalGate.cleanupAll()
            com.openminis.app.notification.ApprovalNotifier.cancelAll(this, stopPendingIds)
            // [T-question-bg-notify] Resolve pending questions (skip) + clear notifications, same orphaned-bar contract.
            val stopQuestionSessions = AskUserGate.cancelAll("interrupted")
            com.openminis.app.notification.AskUserNotifier.cancelAll(this, stopQuestionSessions)
            SessionActivityTracker.cancelAllActiveStreams()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_INTERRUPT) {
            // [T-android-interrupt] Stop the loop + clear the notification; [T-interrupt-no-pause-button] kept for stale/external intents; [T-android-approval-teardown] same approval teardown as ACTION_STOP.
            val interruptPendingIds = ApprovalGate.pendingIds()
            ApprovalGate.cleanupAll()
            com.openminis.app.notification.ApprovalNotifier.cancelAll(this, interruptPendingIds)
            // [T-question-bg-notify] Same contract as ACTION_STOP.
            val interruptQuestionSessions = AskUserGate.cancelAll("interrupted")
            com.openminis.app.notification.AskUserNotifier.cancelAll(this, interruptQuestionSessions)
            SessionActivityTracker.cancelAllActiveStreams()
            // [T-android-interrupt-clear] Clear the ongoing notification (id NOTIFICATION_ID) once all streams are stopped.
            try {
                getSystemService(NotificationManager::class.java)
                    ?.cancel(NOTIFICATION_ID)
            } catch (_: Exception) {}
            // Stop the service (it was started solely to run agents); it re-starts via startService on the next session activation.
            stopSelf()
            return START_NOT_STICKY
        }

        // The stub posted once in onCreate; later intents only update state when it changed.
        requestStatusRefresh()
        return START_STICKY
    }

    private var bootstrapPosted = false
    private fun publishBootstrap() {
        bootstrapPosted = applyForeground(stubForegroundNotification())
    }

    private fun stubForegroundNotification(): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Minis Ultra")
            .setContentText("Starting")
            .setSmallIcon(R.drawable.ic_notification_status)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setUsesChronometer(false)
            .build()

    private var foregroundAnchored = false

    private fun applyForeground(notification: Notification, reanchor: Boolean = false): Boolean {
        return try {
            // Forked SystemUI ignores later notify() on an FGS row; real run boundaries go through startForeground, cosmetic updates use notify().
            val forceStart = reanchor || !foregroundAnchored ||
                !SystemUiHost.allowsLiveNotificationTemplates()
            if (forceStart) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    startForeground(
                        NOTIFICATION_ID,
                        notification,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                    )
                } else {
                    startForeground(NOTIFICATION_ID, notification)
                }
                foregroundAnchored = true
            } else {
                // Repeating startForeground re-binds focus on HyperOS/ZUI; same-id notify updates the shade without it.
                getSystemService(NotificationManager::class.java)
                    ?.notify(NOTIFICATION_ID, notification)
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "applyForeground failed: ${t.message}")
            if (!foregroundAnchored) {
                // Bootstrap failure: stopSelf() does NOT retract the pending foreground deadline, so retract it explicitly to avoid ForegroundServiceDidNotStartInTimeException.
                try {
                    androidx.core.app.ServiceCompat.stopForeground(
                        this,
                        androidx.core.app.ServiceCompat.STOP_FOREGROUND_REMOVE,
                    )
                } catch (t2: Throwable) {
                    Log.w(TAG, "stopForeground after applyForeground failure also failed: ${t2.message}")
                }
            } else {
                // [T-fgs-reanchor] Mid-run throw: already anchored — do NOT retract; clear the anchor flag so the next publish re-binds via startForeground.
                foregroundAnchored = false
            }
            false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // Swipe-from-recents: re-anchor so OEM task removal doesn't silently end the FGS; keep alive iff a session is active, else stopSelf.
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // [T-STALL-DIAG] Record the full keep-alive decision (swipe-away may leave a service the system later revives).
        val activeAtRemoval = SessionActivityTracker.activeSessions.value
        AppLogger.info(
            TAG,
            "[T-STALL-DIAG] FGS onTaskRemoved pid=${android.os.Process.myPid()} " +
                "activeSessions=${activeAtRemoval.size}[${activeAtRemoval.joinToString(",")}] " +
                "decision=${if (activeAtRemoval.isEmpty()) "stopSelf" else "KEEP-ALIVE"} " +
                "slots=${SessionConcurrencyManager.diagSnapshot()}",
        )
        // T166: keep alive iff a stream is still running; clear presence (a chat read then swiped away is no longer a reason).
        SessionActivityTracker.clearPresence()
        if (SessionActivityTracker.activeSessions.value.isEmpty()) {
            Log.d(TAG, "onTaskRemoved with no active sessions, stopping self")
            stopSelf()
            return
        }
        Log.d(TAG, "onTaskRemoved with ${SessionActivityTracker.activeSessions.value.size} active session(s) — keeping service alive")
        // Task removal is a real OEM re-anchor, not a status tick; force one current-state publish.
        requestStatusRefresh(reanchor = true)
    }

    // [T-android-overlay-landscape-width-rotation-drift] Forward orientation flips / resizes to the overlay controller so it re-clamps into new bounds.
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        overlayController?.onConfigurationChanged()
    }

    override fun onDestroy() {
        isRunning = false
        foregroundAnchored = false
        notifyHandler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        try {
            overlayController?.hide()
        } catch (_: Throwable) {}
        overlayController = null
        overlayScope.cancel()
        islandProbeExecutor.shutdownNow() // symmetric with onCreate; no thread leak on restart
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
    }
    // T-bg-overlay phase 2: collect the status tuple into the overlay (dies with onDestroy); foreground transitions hide instantly.
    private fun startOverlayObserver() {
        val app = applicationContext as? MinisApp ?: return
        overlayController = ToolOverlayController(applicationContext).apply {
            // [T-android-overlay-reply-status-34599] Tap-to-open or X
            // dismissal clears the lingered completion state so the
            // observer's AND-gate stops re-showing the capsule on
            // subsequent emissions (e.g. a stale toolStatus flip).
            // [T-android-overlay-completion-pending] Also drop the
            // completion-pending linger — the user has either opened the
            // session or explicitly dismissed. No re-emission is needed:
            // the controller hides itself on the dismissal path, and the
            // cleared flag only matters on the NEXT applyOverlayState pass.
            onDismissByUser = {
                hasCompletionPending = false
                SessionActivityTracker.dismissOverlay()
            }
        }
        val backgroundRepo = app.backgroundSettingsRepository

        overlayScope.launch {
            combine(
                app.isAppForegroundFlow,
                SessionActivityTracker.currentToolName,
                SessionActivityTracker.currentToolStatus,
                SessionActivityTracker.isToolRunning,
                backgroundRepo.backgroundOverlayEnabled,
                SessionActivityTracker.lastToolOutcome,
                SessionActivityTracker.lastReplyExcerpt,
                SessionActivityTracker.currentSessionId,
                SessionActivityTracker.currentToolTitle,
                SessionActivityTracker.externalPickerSuppressActive,
                SessionActivityTracker.lastToolName,
                SessionActivityTracker.lastToolTitle,
                SessionActivityTracker.lastToolStatus,
                SessionActivityTracker.activeSessions,
                // [T-android-dynamic-island] Reactive dynamic-island toggle —
                // flipping it must appear/hide the overlay live (mutual
                // exclusion) without an app restart.
                backgroundRepo.dynamicIslandEnabled,
            ) { values: Array<Any?> ->
                @Suppress("UNCHECKED_CAST")
                val activeSessions = values[13] as Set<String>
                OverlayState(
                    isForeground = values[0] as Boolean,
                    toolName = values[1] as String?,
                    toolStatus = values[2] as String,
                    isRunning = values[3] as Boolean,
                    enabled = values[4] as Boolean,
                    lastOutcome = values[5] as ToolOutcome,
                    lastReplyExcerpt = values[6] as String?,
                    currentSessionId = values[7] as String?,
                    toolTitle = values[8] as String?,
                    externalPickerSuppress = values[9] as Boolean,
                    lastToolName = values[10] as String?,
                    lastToolTitle = values[11] as String?,
                    lastToolStatus = values[12] as String?,
                    hasActiveStream = activeSessions.isNotEmpty(),
                    dynamicIslandEnabled = values[14] as Boolean,
                )
            }.distinctUntilChanged().collect { state -> applyOverlayState(state) }
        }
        overlayScope.launch {
            combine(
                SessionActivityTracker.currentToolName,
                SessionActivityTracker.isToolRunning,
                SessionActivityTracker.notificationTimeline,
                SessionActivityTracker.presentSessions,
            ) { toolName, toolRunning, timeline, present ->
                // Status text is deliberately absent: token/progress chatter is
                // for the overlay, not a SystemUI notification reinflation.
                listOf(toolName, toolRunning, timeline, present)
            }.distinctUntilChanged().collect {
                requestStatusRefresh()
            }
        }
    }

    private data class OverlayState(
        val isForeground: Boolean,
        val toolName: String?,
        val toolStatus: String,
        val isRunning: Boolean,
        val enabled: Boolean,
        val lastOutcome: ToolOutcome,
        val lastReplyExcerpt: String?,
        val currentSessionId: String?,
        val toolTitle: String?,
        val externalPickerSuppress: Boolean,
        val lastToolName: String?,
        val lastToolTitle: String?,
        val lastToolStatus: String?,
        // T-android-overlay-show-if-busy: at least one session has an
        // active streamJob (assistant generating reply). Together with
        // isRunning (tool executing) this is the only signal the overlay
        // uses to decide whether to surface — the previous "linger after
        // completion" semantics are dropped per spec.
        val hasActiveStream: Boolean,
        // [T-android-dynamic-island] User toggle for the Android 16 Live
        // Updates surface. When this is ON *and* the device is capable, the
        // floating overlay is suppressed (see applyOverlayState) so the two
        // status UIs never render simultaneously.
        val dynamicIslandEnabled: Boolean,
    )

    private fun applyOverlayState(state: OverlayState) {
        val controller = overlayController ?: return
        val hasPerm = controller.hasOverlayPermission()

        // [T-android-hyperos-island] Foreground transition = the user just
        // came back from system settings, where they may have granted or
        // revoked the HyperOS focus-notification permission. Re-probe off
        // the main thread (throttled); a value flip re-evaluates the
        // island/overlay mutual exclusion on the next status pass.
        if (state.isForeground && !lastIsForegroundForIslandProbe) {
            islandProbeExecutor.execute {
                if (HyperOsIsland.refreshFocusPermission(this)) {
                    notifyHandler.post { requestStatusRefresh() }
                }
            }
        }
        lastIsForegroundForIslandProbe = state.isForeground

        // [T-android-dynamic-island] MUTUAL EXCLUSION (critical): when the
        // Android 16 Live Updates "dynamic island" surface is the active status
        // UI — i.e. the user enabled the toggle AND the device is capable
        // (canPostPromotedNotifications) — we must NOT also show the floating
        // overlay capsule, or the user sees two duplicate real-time status UIs
        // at once. This is a hard short-circuit that overrides even an
        // explicitly-enabled backgroundOverlayEnabled toggle: the higher-tier
        // dynamic-island wins. Re-checked reactively because
        // state.dynamicIslandEnabled comes through the combined flow and
        // capability is re-probed here, so toggling either the app switch or
        // the system Live-Updates grant hides/reveals the overlay live.
        val diActive = state.hasActiveStream && DynamicIslandSupport.isDynamicIslandActive(
            this, state.dynamicIslandEnabled,
        )
        if (diActive) {
            if (controller.isShown) controller.hide()
            lingerJob?.cancel()
            lingerJob = null
            hasCompletionPending = false
            wasBusy = state.hasActiveStream || state.isRunning
            // Rebuild the promoted notification only when Live Updates
            // *becomes* the active surface. The overlay observer also
            // ticks on lastReplyExcerpt / toolStatus — posting a new
            // ProgressStyle on every token is a known SystemUI crash
            // once the chip is actually materialized (app backgrounded).
            if (lastDynamicIslandActive != true) {
                lastDynamicIslandActive = true
                refreshOngoingNotification()
            }
            // [Wave3 #15] AppLogger.debug + muted category — same gate as the
            // ChatScrollFollow precedent: fires per emission, so it is muted
            // by default instead of paying a logcat line every pass.
            AppLogger.debug(
                "OverlayState",
                "applyOverlayState: dynamic-island active — overlay suppressed " +
                    "(dynamicIslandEnabled=${state.dynamicIslandEnabled})",
            )
            return
        }
        if (lastDynamicIslandActive == true) {
            lastDynamicIslandActive = false
            refreshOngoingNotification()
        }
        // [T-android-overlay-show-if-busy] Overlay surfaces while the
        // agent is actively working — either an assistant streamJob is
        // mid-generation OR a tool is executing.
        // [T-android-overlay-completion-pending] X9 brings back a linger,
        // but edge-scoped: when busy flips false WHILE the overlay is
        // eligible and the app is backgrounded, the capsule stays in a
        // completed/replied rendering until the user taps it (open or X).
        // A turn that ends in the foreground sets nothing, so
        // backgrounding later does NOT re-pop — that edge guard is the
        // fix for the old regression where lastOutcome / lastReplyExcerpt
        // (which persist across turns) re-popped the capsule on every
        // home-screen visit.
        val isBusy = state.hasActiveStream || state.isRunning
        if (wasBusy && !isBusy && !state.isForeground && state.enabled &&
            hasPerm && !state.externalPickerSuppress
        ) {
            hasCompletionPending = true
        }
        wasBusy = isBusy
        val shouldShow = state.enabled && hasPerm && !state.isForeground &&
            !state.externalPickerSuppress &&
            (isBusy || hasCompletionPending)
        // [Wave3 #15] Muted-category debug gate — was an ungated Log.d per
        // flow emission (every token / tool-status tick).
        AppLogger.debug(
            "OverlayState",
            "applyOverlayState fg=${state.isForeground} enabled=${state.enabled} " +
                "perm=$hasPerm streaming=${state.hasActiveStream} toolRunning=${state.isRunning} " +
                "externalPickerSuppress=${state.externalPickerSuppress} completionPending=$hasCompletionPending " +
                "toolName=${state.toolName} toolTitle=${state.toolTitle} shouldShow=$shouldShow shown=${controller.isShown}",
        )
        // [T-bg-overlay phase 2 fix] Permission nudge — the user opted in
        // via the Settings toggle but Android still rejects our
        // SYSTEM_ALERT_WINDOW. Post a high-importance notification that
        // deep-links to the system overlay-permission screen.
        if (state.enabled && !hasPerm && isBusy && !state.isForeground) {
            maybePostOverlayPermissionNudge()
        }
        // Foreground / toggle-off / no-perm → hide immediately, cancel
        // any (legacy) linger timer. [T-android-overlay-foreground-hide]
        // We deliberately do NOT clear tracker state on a foreground
        // transition: the user only saw the chat, they didn't explicitly
        // dismiss the capsule. If they background the app again while a
        // tool is still running OR the post-completion linger hasn't been
        // explicitly cleared (X / tap-to-open route through
        // `onDismissByUser` → `dismissOverlay()`), the capsule should
        // reappear. Toggle-off / no-perm also leave tracker state alone
        // so a subsequent re-enable picks up where we left off.
        if (state.isForeground || !state.enabled || !hasPerm || state.externalPickerSuppress) {
            // [T-android-overlay-completion-pending] Foreground means the
            // user saw the reply in-app — drop the pending linger so a
            // later backgrounding doesn't re-pop a stale completion.
            // Toggle-off / no-perm / camera-suppress keep the flag, same
            // as they leave tracker state alone (see comment above): a
            // re-enable picks up where we left off.
            if (state.isForeground) hasCompletionPending = false
            lingerJob?.cancel()
            lingerJob = null
            if (controller.isShown) controller.hide()
            return
        }

        if (shouldShow) {
            lingerJob?.cancel()
            lingerJob = null
            // [T-android-overlay-show-if-busy] When a tool is active the
            // tracker has live toolName/toolTitle/toolStatus; otherwise
            // (stream-only, no tool yet) those may be null/Idle, in which
            // case we fall back to the lastTool* snapshot from the most
            // recent tool of THIS turn so the capsule isn't a bare
            // spinner.
            val effectiveToolName = state.toolName ?: state.lastToolName
            val effectiveToolTitle = state.toolTitle ?: state.lastToolTitle
            val effectiveStatus = if (!state.toolStatus.equals("Idle", ignoreCase = true)) {
                state.toolStatus
            } else {
                state.lastToolStatus ?: state.toolStatus
            }
            if (isBusy) {
                controller.show(
                    toolName = effectiveToolName,
                    statusText = effectiveStatus,
                    isRunning = true,
                    outcome = ToolOutcome.Unknown,
                    replyExcerpt = null,
                    targetSessionId = state.currentSessionId,
                    toolTitle = effectiveToolTitle,
                )
            } else {
                // [T-android-overlay-completion-pending] Completion linger:
                // the turn ended while backgrounded. Render the controller's
                // existing completed state (outcome glyph + localized
                // completion word + reply excerpt row + visible X); it stays
                // until tap-to-open / X clears hasCompletionPending via
                // onDismissByUser, or a foreground transition does.
                controller.show(
                    toolName = effectiveToolName,
                    statusText = effectiveStatus,
                    isRunning = false,
                    outcome = state.lastOutcome,
                    replyExcerpt = state.lastReplyExcerpt,
                    targetSessionId = state.currentSessionId,
                    toolTitle = effectiveToolTitle,
                )
            }
            return
        }

        // Not busy AND no completion pending from this round — drop the
        // overlay if it's up. [T-android-overlay-completion-pending] With
        // the edge-scoped linger above, this branch now only fires when
        // the user already dismissed the completion (or none was pending,
        // e.g. the turn ended in foreground), so the old "task finished →
        // proactively hide" semantics still hold for those cases.
        if (controller.isShown) controller.hide()
    }

    /** Promotion changes are a state edge; they go through the same publisher. */
    private fun refreshOngoingNotification() = requestStatusRefresh()

    private val notifyHandler = Handler(Looper.getMainLooper())
    private val notificationPolicy = ForegroundNotificationPolicy()
    private val deferredRefresh = Runnable { requestStatusRefresh() }

    private fun notificationState(): ForegroundNotificationState {
        val timeline = SessionActivityTracker.notificationTimeline.value
        val active = timeline.activeCount
        val app = (applicationContext as? MinisApp)?.takeIf { it.subsystemsReady() }
        val userWantsIsland = app?.backgroundSettingsRepository?.dynamicIslandEnabled?.value == true
        // Forked SystemUI must never see a live template, even if the user
        // left the island switch off. HyperOS still lifts an IMPORTANCE_LOW
        // ongoing row into its own island.
        val liveTemplates = SystemUiHost.allowsLiveNotificationTemplates()
        // [T-android-hyperos-island] On HyperOS the first-party miui.focus
        // protocol replaces the AOSP promoted path. islandActive covers both
        // surfaces (it drives the policy's 20s cosmetic gap and the
        // enter/leave-live-update immediate publish); `surface` picks which
        // builder renders it.
        val hyperOsHost = !liveTemplates && HyperOsIsland.isHyperOsHost()
        val islandActive = active > 0 && DynamicIslandSupport.isDynamicIslandActive(
            this, userWantsIsland,
        )
        val promoted = islandActive
        val surface = when {
            hyperOsHost && islandActive -> NotificationSurface.HYPER_OS_FOCUS
            !liveTemplates -> NotificationSurface.OEM_QUIET
            promoted -> NotificationSurface.PROMOTED
            else -> NotificationSurface.PLAIN
        }
        val subtitle = ForegroundNotificationCopy.runningSubtitle(this, active)
        return ForegroundNotificationState(
            activeCount = active,
            toolName = SessionActivityTracker.currentToolName.value,
            isToolRunning = SessionActivityTracker.isToolRunning.value,
            startedAtMs = timeline.startedAtMs ?: startTimeMs,
            finishedAtMs = timeline.finishedAtMs?.takeIf { active == 0 },
            promoted = promoted,
            subtitle = subtitle,
            surface = surface,
        ).normalized()
    }

    /** All status updates, mode transitions and OEM re-anchors share this path. */
    private fun requestStatusRefresh(reanchor: Boolean = false) {
        if (!bootstrapPosted) return
        val state = notificationState()
        val decision = if (reanchor) ForegroundNotificationPolicy.Decision.Publish else
            notificationPolicy.decide(state, SystemClock.elapsedRealtime())
        notifyHandler.removeCallbacks(deferredRefresh)
        when (decision) {
            ForegroundNotificationPolicy.Decision.Skip -> Unit
            is ForegroundNotificationPolicy.Decision.Wait ->
                notifyHandler.postDelayed(deferredRefresh, decision.delayMs)
            ForegroundNotificationPolicy.Decision.Publish -> {
                try {
                    val notification = buildNotification(state)
                    if (applyForeground(notification, reanchor = reanchor)) {
                        notificationPolicy.markPublished(state, SystemClock.elapsedRealtime())
                        Log.d(TAG, "status posted promoted=${state.promoted} active=${state.activeCount} completed=${state.completed}")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "status notification failed: ${t.message}")
                }
            }
        }
    }

    /**
     * [Issue #2 — battery drain] Drives the partial wake lock from real
     * work state: acquired the moment a stream or tool starts, released on
     * the idle edge. StateFlow combine emits current values immediately, so
     * a service started mid-work (sandbox job, scheduled run, START_STICKY
     * revival) still acquires on the first collection.
     *
     * [Wave3 #6] While hasWork stays true a renewal loop re-acquires the
     * timed lock every [WAKE_LOCK_RENEW_INTERVAL_MS] — before the
     * [WAKE_LOCK_TIMEOUT_MS] hard cap expires. Legitimately long turns
     * (scheduled overnight agents) are renewed indefinitely; a hung state
     * that never emits the idle edge now self-expires at the cap instead of
     * holding the CPU forever.
     */
    private fun startWakeLockObserver() {
        overlayScope.launch {
            combine(
                SessionActivityTracker.activeSessions,
                SessionActivityTracker.isToolRunning,
            ) { active, toolRunning -> active.isNotEmpty() || toolRunning }
                .distinctUntilChanged()
                .collect { hasWork ->
                    updateWakeLock(hasWork)
                    wakeLockRenewJob?.cancel()
                    if (hasWork) {
                        wakeLockRenewJob = overlayScope.launch {
                            // Cancellation lands on the delay below — the only
                            // suspension point in this loop — so no explicit
                            // isActive check is needed.
                            while (true) {
                                kotlinx.coroutines.delay(WAKE_LOCK_RENEW_INTERVAL_MS)
                                // Idle edge between ticks: released + nulled —
                                // do not resurrect the lock after release.
                                if (wakeLock != null) {
                                    Log.d(TAG, "WakeLock renewed before timeout")
                                    acquireWakeLock()
                                }
                            }
                        }
                    }
                }
        }
    }

    private fun updateWakeLock(hasWork: Boolean) {
        if (hasWork) acquireWakeLock() else releaseWakeLock()
    }

    private fun acquireWakeLock() {
        try {
            val existing = wakeLock
            if (existing != null) {
                // [Wave3 #6] Renewal path: the lock is non-reference-counted,
                // so acquire(timeout) on the same object just extends its
                // expiry — no release/recreate churn.
                existing.acquire(WAKE_LOCK_TIMEOUT_MS)
                wakeLockHeld = true
                return
            }
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "minis:inference",
            ).apply {
                setReferenceCounted(false)
                // [Wave3 #6] 30-minute timeout instead of the old unbounded
                // acquire(): the renewal loop in startWakeLockObserver keeps a
                // genuinely-active turn alive, while a hung hasWork state can
                // no longer pin the CPU forever. Release paths unchanged
                // (idle edge + onDestroy).
                acquire(WAKE_LOCK_TIMEOUT_MS)
            }
            wakeLockHeld = true
            Log.d(TAG, "WakeLock acquired (PARTIAL_WAKE_LOCK, ${WAKE_LOCK_TIMEOUT_MS / 60000}min cap)")
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock acquire failed: ${e.message}")
        }
    }

    private fun releaseWakeLock() {
        try {
            wakeLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "WakeLock released")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock release failed: ${e.message}")
        } finally {
            wakeLock = null
            wakeLockHeld = false
        }
    }

    private fun createNotificationChannel() {
        // minSdk 26 (O) — the SDK_INT >= O wrapper was always true; the
        // getSharedPreferences call it guarded runs unconditionally below.
        val manager = getSystemService(NotificationManager::class.java)
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        // Undo the IMPORTANCE_MIN migration once. MIN + SECRET hides the
        // ongoing row from the status bar and MIUI island. After this,
        // never delete the channel again so a user who changes it in
        // system settings keeps that choice.
        if (!prefs.getBoolean(KEY_CHANNEL_RESTORED, false)) {
            val existing = manager.getNotificationChannel(CHANNEL_ID)
            if (existing != null && existing.importance <= NotificationManager.IMPORTANCE_MIN) {
                manager.deleteNotificationChannel(CHANNEL_ID)
                Log.i(TAG, "Restored agent_status channel to IMPORTANCE_LOW")
            }
            prefs.edit().putBoolean(KEY_CHANNEL_RESTORED, true).apply()
        }
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.bg_service_channel_name),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.bg_service_channel_description)
            setShowBadge(false)
            setSound(null, null)
            // [T-approval-lockscreen] PRIVATE, not PUBLIC. The row's own text
            // is benign (session count / tool label, no user content), and the
            // builders below attach a benign publicVersion so a lock-screen
            // row still shows; the channel no longer whitelists any future
            // content the row might carry for raw visibility.
            lockscreenVisibility = Notification.VISIBILITY_PRIVATE
        }
        // [T-bg-overlay phase 2 fix] Higher-importance channel for the
        // SYSTEM_ALERT_WINDOW permission nudge. IMPORTANCE_DEFAULT
        // gets a heads-up surface so the user actually sees that the
        // overlay they enabled needs one more grant.
        val nudgeChannel = NotificationChannel(
            OVERLAY_NUDGE_CHANNEL_ID,
            getString(R.string.bg_overlay_nudge_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = getString(R.string.bg_overlay_nudge_channel_description)
            setShowBadge(true)
        }
        manager.createNotificationChannel(channel)
        manager.createNotificationChannel(nudgeChannel)
    }

    /**
     * [T-approval-lockscreen] Benign lock-screen stand-in for the ongoing
     * status row: app name + stable subtitle only. Never carries tool
     * previews or session content, so the channel can stay
     * VISIBILITY_PRIVATE while the row remains visible.
     */
    private fun publicLockscreenRow(title: String, text: String, tap: PendingIntent): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification_status)
            .setContentTitle(title)
            .setContentText(text)
            .setContentIntent(tap)
            .setOngoing(true)
            .build()


    /** Last observed Live-Updates-active flag, for edge-triggered notify. */
    private var lastDynamicIslandActive: Boolean? = null

    private fun maybePostOverlayPermissionNudge() = OverlayPermissionNudge.maybePost(this)

    private fun buildNotification(state: ForegroundNotificationState): Notification {
        val sessionCount = state.activeCount
        val isCompleted = state.completed
        val endMs = state.finishedAtMs ?: SystemClock.elapsedRealtime()
        val elapsedMs = (endMs - state.startedAtMs).coerceAtLeast(0L)
        val elapsedSeconds = (elapsedMs / 1000).toInt()
        val minutes = elapsedSeconds / 60
        val seconds = elapsedSeconds % 60
        val timeString = String.format("%d:%02d", minutes, seconds)

        // [T-notif-tap-wrong-session] 点击回到发通知的那个会话（见 NotificationTapIntent）。
        val pendingIntent = NotificationTapIntent.build(this)

        val stopIntent = Intent(this, AgentForegroundService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val sessionLabel = resources.getQuantityString(
            R.plurals.bg_service_sessions, sessionCount, sessionCount,
        )

        if (state.surface == NotificationSurface.HYPER_OS_FOCUS) {
            return buildHyperOsFocusNotification(
                state = state,
                isCompleted = isCompleted,
                sessionLabel = sessionLabel,
                timeString = timeString,
                contentIntent = pendingIntent,
                stopPendingIntent = stopPendingIntent,
            )
        }

        if (state.surface == NotificationSurface.OEM_QUIET) {
            return buildOemQuietNotification(
                isCompleted = isCompleted,
                sessionLabel = sessionLabel,
                timeString = timeString,
                contentIntent = pendingIntent,
                stopIntent = stopPendingIntent,
                activeCount = state.activeCount,
            )
        }

        // The row only renders the stable task snapshot: transient tool output
        // stays in the in-app overlay. System chronometer owns elapsed time.
        val toolName = state.toolName
        val isToolRunning = state.isToolRunning

        // [T-android-live-update-completed] In the completed resting state the
        // title/status must stop describing work in progress. `toolName` is
        // already null by then (setInactive clears it), so the old code fell
        // through to the generic "Minis is running" title while the icon fell
        // through to the wrench (toolSmallIconRes' else branch) — a finished
        // task rendered exactly like a running one.
        val titleText = when {
            isCompleted -> getString(R.string.bg_service_notification_title_completed)
            toolName != null -> toolDisplayLabel(toolName)
            else -> getString(R.string.bg_service_notification_title)
        }
        val collapsedText = if (isCompleted) {
            getString(R.string.bg_service_notification_text_completed, sessionLabel, timeString)
        } else {
            getString(R.string.bg_service_notification_text_live, sessionLabel, state.subtitle)
        }
        val runWhenMs = System.currentTimeMillis() -
            (SystemClock.elapsedRealtime() - state.startedAtMs).coerceAtLeast(0L)

        // Static chip label + system-managed chronometer: no per-second posts.
        // [T-live-status-model] The chip's words were the literal "Minis" on
        // every device in every state. Leave the chip to the system while the
        // run clock counts up (Android already renders that clock, and a word
        // beside it is what overflows the ~7 characters it shows).
        val live = liveStatusOf(this, state, sessionLabel)

        val shortCritical = live.chipWords()

        // Promotion is selected once from the exact state submitted to the
        // policy. Presence-only/completed notifications always remain plain.
        if (state.promoted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            val promoted = buildPromotedNotification(
                titleText = titleText,
                collapsedText = collapsedText,
                shortCritical = shortCritical,
                smallIcon = statusIconRes(isCompleted),
                contentIntent = pendingIntent,
                stopIntent = stopPendingIntent,
                runWhenMs = runWhenMs,
            )
            if (promoted.hasPromotableCharacteristics()) {
                return promoted
            }
            Log.w(
                TAG,
                "ProgressStyle not promotable — posting a plain FGS row instead",
            )
        }

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(statusIconRes(isCompleted))
            .setContentTitle(titleText)
            .setContentText(collapsedText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(collapsedText))
            .setOngoing(true)
            .setWhen(runWhenMs)
            .setShowWhen(!isCompleted)
            .setUsesChronometer(!isCompleted)
            .setOnlyAlertOnce(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // [T-approval-lockscreen] Channel is PRIVATE now; keep the row on
            // the lock screen with a benign publicVersion (the collapsed text
            // is content-free — session count + elapsed only).
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicLockscreenRow(titleText, collapsedText, pendingIntent))

        // [T-android-live-update-completed] Same rule as the promoted branch:
        // no Stop once there is nothing left to stop.
        // [T-interrupt-no-pause-button] The second button used to read
        // "Pause" while ACTION_INTERRUPT actually does
        // cleanupAll + cancelAllActiveStreams + stopSelf — tapping it on an
        // overnight turn DESTROYED it. There is no real suspend in the run
        // pipeline, so the lying button is removed rather than half-wired;
        // ACTION_INTERRUPT keeps its handler (see onStartCommand) so any
        // PendingIntent already in flight still resolves sanely.
        if (!isCompleted) {
            builder.addAction(
                R.drawable.ic_notification_status,
                getString(R.string.bg_service_stop_action),
                stopPendingIntent,
            )
        }

        if (isToolRunning) {
            // Tools rarely report determinate progress (shell/browser/a11y
            // are open-ended). Always indeterminate while a tool is in
            // flight; explicitly drop progress when not, so the bar
            // disappears at idle/between-turn moments.
            builder.setProgress(0, 0, true)
        }

        return builder.build()
    }

    /**
     * [T-android-dynamic-island] Build the Android 16 promoted / Live Updates
     * variant of the ongoing status notification using the native
     * Notification.Builder (androidx.core 1.15 lacks these APIs). Requires
     * API >= 36 — callers gate on Build.VERSION.SDK_INT before invoking.
     *
     * Uses a stable progress segment for the run. Tool switches do not
     * mutate the progress view, which avoids repeated OEM chip inflation.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.BAKLAVA)
    private fun buildPromotedNotification(
        titleText: String,
        collapsedText: String,
        shortCritical: String?,
        smallIcon: Int,
        contentIntent: PendingIntent,
        stopIntent: PendingIntent,
        runWhenMs: Long,
    ): Notification {
        // [T-android-dynamic-island] A ProgressStyle only counts as a valid
        // *promotable* style when it carries at least one progress segment with
        // positive length — an empty ProgressStyle (even an indeterminate one)
        // fails Notification.hasPromotableCharacteristics() and the notification
        // silently drops to a plain ongoing row (confirmed on-device: every
        // other precondition passed, only the ProgressStyle validity failed).
        // So the segment is NOT optional: it is what keeps the chip promoted,
        // and it is why this style survives even though we show no percentage.
        //
        // Keep ProgressStyle fully specified. Invalid tracker icons and
        // indeterminate+zero-progress can crash OEM SystemUI on promotion.
        val progressStyle = Notification.ProgressStyle()
            .addProgressSegment(Notification.ProgressStyle.Segment(100))
            .setProgressTrackerIcon(Icon.createWithResource(this, R.drawable.ic_notification_status))
            .setProgressIndeterminate(false)
            .setProgress(0)

        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(smallIcon)
            .setContentTitle(titleText)
            .setContentText(collapsedText)
            .setStyle(progressStyle)
            .setOngoing(true)
            .setShowWhen(true)
            .setUsesChronometer(true)
            .setWhen(runWhenMs)
            .setOnlyAlertOnce(true)
            .setContentIntent(contentIntent)
            // [T-approval-lockscreen] PRIVATE + benign publicVersion (same
            // content-free strings) so the promoted row survives the channel
            // visibility change on the lock screen.
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicLockscreenRow(titleText, collapsedText, contentIntent))
            // Explicitly NOT colorized and NOT a group summary — both would
            // disqualify the notification from promotion.
            .setColorized(false)

        // [T-live-status-model] Null while the run clock counts up: the chip
        // already renders that clock, and words beside it overflow the ~7
        // characters Android shows.
        if (shortCritical != null) builder.setShortCriticalText(shortCritical)

        builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, R.drawable.ic_notification_status),
                getString(R.string.bg_service_stop_action),
                stopIntent,
            ).build(),
        )

        // [T-android-dynamic-island] Request the always-visible "dynamic island"
        // promotion. The public builder method `setRequestPromotedOngoing(true)`
        // is NOT in the android-36 SDK stubs yet (@FlaggedApi / not exported),
        // and — importantly — this is NOT the same as FLAG_PROMOTED_ONGOING:
        // decompiling the on-device framework showed
        // Notification.hasPromotableCharacteristics() gates on
        // isRequestPromotedOngoing(), which reads the extras boolean
        // "android.requestPromotedOngoing" — the FLAG is what the *system* sets
        // AFTER it decides to promote, not the request. So we set the extras
        // key directly (verified against the decompiled getBoolean call).
        builder.addExtras(android.os.Bundle().apply {
            putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true)
        })

        val notification = builder.build()
        if (!notification.hasPromotableCharacteristics()) {
            // Not fatal — the notification still posts as a normal ongoing FGS
            // row; it just won't get the promoted chip. Dump each individual
            // promotion precondition so the failing one is diagnosable.
            val flags = notification.flags
            Log.w(
                TAG,
                "promoted notification lacks promotable characteristics — diag: " +
                    "requestPromotedOngoing=${notification.extras.getBoolean(EXTRA_REQUEST_PROMOTED_ONGOING)} " +
                    "FLAG_ONGOING_EVENT=${(flags and Notification.FLAG_ONGOING_EVENT) != 0} " +
                    "hasTitle=${!notification.extras.getCharSequence(Notification.EXTRA_TITLE).isNullOrEmpty()} " +
                    "smallIcon=${notification.smallIcon != null} " +
                    "isGroupSummary=${(flags and Notification.FLAG_GROUP_SUMMARY) != 0} " +
                    "styleTemplate=${notification.extras.getString(Notification.EXTRA_TEMPLATE)}",
            )
        } else {
            Log.d(TAG, "promoted notification OK — hasPromotableCharacteristics=true")
        }
        return notification
    }

    /**
     * T-bg-overlay phase 1: human-readable label per tool. [Wave3 #8] the
     * duplicated English `when` lived here AND in ToolOverlayController ("Mirrors
     * …"); both now delegate to [ForegroundNotificationCopy.toolLabel] so the
     * notification and the overlay capsule read one mapping (resource-backed in
     * strings_service.xml).
     */
    private fun toolDisplayLabel(toolName: String): String =
        ForegroundNotificationCopy.toolLabel(applicationContext, toolName)

    /**
     * Status-bar icons must be an alpha mask from this app. Framework
     * `ic_menu_*` drawables are color bitmaps; OEM SystemUI decodes a new
     * one every time it rebuilds the island. Completed runs use the check.
     */
    private fun statusIconRes(isCompleted: Boolean): Int =
        if (isCompleted) R.drawable.ic_notification_completed else R.drawable.ic_notification_status

    /**
     * Static ongoing row for HyperOS, ZUI and every other forked SystemUI.
     * No chronometer, no progress, no promoted style, no changing text.
     * Their island can mirror this once; it has nothing left to tick.
     */
    /**
     * [T-android-hyperos-island] HyperOS 小米超级岛 notification: a native
     * builder (no compat wrapper — parcelable extras go straight into
     * miui.focus.pics / miui.focus.actions) carrying the miui.focus.param
     * JSON. The shade row keeps OEM_QUIET discipline — static when, no
     * chronometer, no progress — because the forked-SystemUI re-inflation
     * hazard [SystemUiHost] guards against is about the shade row, not the
     * island pipeline, which Xiaomi renders from the JSON on its own.
     *
     * Update path is the official one: same notification id +
     * `updatable=true` in the JSON; the policy's 20s cosmetic gap throttles
     * tool-name churn on the big island.
     */
    private fun buildHyperOsFocusNotification(
        state: ForegroundNotificationState,
        isCompleted: Boolean,
        sessionLabel: String,
        timeString: String,
        contentIntent: PendingIntent,
        stopPendingIntent: PendingIntent,
    ): Notification {
        val toolName = state.toolName
        // Same title semantics as the plain row: completed → "Task
        // completed", tool in flight → its display label, else the app name.
        val titleText = when {
            isCompleted -> getString(R.string.bg_service_notification_title_completed)
            toolName != null -> toolDisplayLabel(toolName)
            else -> getString(R.string.bg_service_notification_title)
        }
        val collapsedText = if (isCompleted) {
            getString(R.string.bg_service_notification_text_completed, sessionLabel, timeString)
        } else {
            getString(R.string.bg_service_notification_text_live, sessionLabel, state.subtitle)
        }

        // 模板库 guidance: big-island text ≤4 CJK chars, longer text
        // degrades to small font then clips. The tool display label and
        // "Task completed" are the short forms; the subtitle ("1 task
        // running") is the trailing small word.
        // [T-live-status-model] Ticker/AOD/big-island text come from the shared
        // model, not from literals and not from the long shade title: the
        // template guide caps the big island at ~4 CJK chars, and handing it
        // "Minis is using Shell" degraded to a smaller font and then clipped.
        val live = liveStatusOf(this, state, sessionLabel)

        val content = HyperOsIsland.IslandContent(
            business = "agent",
            ticker = live.ticker(),
            aodTitle = live.ticker(),
            bigTitle = live.islandHeadline(),
            bigContent = live.islandCaption(),
            baseTitle = titleText,
            baseContent = collapsedText,
            hintTitle = live.islandCaption(),
        )
        val focusJson = HyperOsIsland.buildFocusParamJson(content)

        // [T-interrupt-no-pause-button] The shade row's second button used to
        // read "Pause" while ACTION_INTERRUPT stops and destroys every running
        // turn. No real suspend exists yet, so the lying button is dropped —
        // only Stop remains, matching ACTION_STOP's behaviour. The
        // ACTION_INTERRUPT handler in onStartCommand stays for compat.

        // 模板库 rule: miui.focus.pics icons must be COLOR content icons
        // (square, >=88*88px). The status-bar small icon is a white alpha
        // mask — on the island it renders as an invisible white blob (the
        // "应用图标没有" symptom on HyperOS 4). The launcher icon is the
        // color icon every app ships; it also matches the protocol's
        // picInfo.type=1 fallback ("系统直接展示应用桌面图标").
        val launcherIcon = android.graphics.drawable.Icon.createWithResource(
            this, applicationInfo.icon,
        )
        // White alpha-mask small icon for the shade row only — the shade
        // tints it; the island must never see it (see launcherIcon note).
        val statusIcon = android.graphics.drawable.Icon.createWithResource(
            this, statusIconRes(isCompleted),
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(statusIcon)
            .setContentTitle(titleText)
            .setContentText(collapsedText)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setContentIntent(contentIntent)
            .setCategory(Notification.CATEGORY_SERVICE)
            // [T-approval-lockscreen] PRIVATE + benign publicVersion (see the
            // promoted builder note); the shade-row text is static app strings.
            .setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(publicLockscreenRow(titleText, collapsedText, contentIntent))

        // Shade-row buttons keep the white status icon — the shade tints
        // small icons itself. The island button is separate: hintInfo carries
        // ONE actionInfo, so only Stop goes to the island (方式一: the system
        // renders the miui.focus.actions Action's Icon + Title — color icon,
        // real label). No Stop once there is nothing left to stop.
        // [T-interrupt-no-pause-button] The "Pause" action was removed here
        // too — see the note above buildHyperOsFocusNotification.
        val islandActions = mutableMapOf<String, Notification.Action>()
        if (!isCompleted) {
            val stopAction = Notification.Action.Builder(
                launcherIcon,
                getString(R.string.bg_service_stop_action),
                stopPendingIntent,
            ).build()
            builder.addAction(stopAction)
            islandActions[HyperOsIsland.ACTION_STOP] = stopAction
        }

        HyperOsIsland.attachFocusExtras(
            builder = builder,
            json = focusJson,
            icons = mapOf(
                HyperOsIsland.PIC_SMALL to launcherIcon,
                HyperOsIsland.PIC_BIG to launcherIcon,
                HyperOsIsland.PIC_TICKER to launcherIcon,
                HyperOsIsland.PIC_AOD to launcherIcon,
            ),
            islandActions = islandActions,
        )
        return builder.build()
    }

    private fun buildOemQuietNotification(
        isCompleted: Boolean,
        sessionLabel: String,
        timeString: String,
        contentIntent: PendingIntent,
        stopIntent: PendingIntent,
        activeCount: Int,
    ): Notification {
        val title = if (isCompleted) {
            getString(R.string.bg_service_notification_title_completed)
        } else {
            getString(R.string.bg_service_notification_title)
        }
        // [T-android-hyperos-island] Fix: the completed row used the TITLE
        // string ("Task completed") as its body text — a copy-paste slip
        // from the title branch above. Use the real completed body.
        // 运行中的行必须报真实会话数：之前硬编码 "1 task running"，
        // 多会话并行时通知栏/灵动岛一直在撒谎。
        val text = if (isCompleted) {
            getString(R.string.bg_service_notification_text_completed, sessionLabel, timeString)
        } else {
            ForegroundNotificationCopy.runningSubtitle(this, activeCount)
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(statusIconRes(isCompleted))
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setUsesChronometer(false)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            // [T-approval-lockscreen] Benign private + publicVersion, as for
            // the plain row (this builder's text is static app strings).
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPublicVersion(publicLockscreenRow(title, text, contentIntent))
        if (!isCompleted) {
            builder.addAction(
                R.drawable.ic_notification_status,
                getString(R.string.bg_service_stop_action),
                stopIntent,
            )
        }
        return builder.build()
    }
}
