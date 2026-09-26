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
 * are actively running. Shows session count, current tool name, and elapsed time.
 */
class AgentForegroundService : Service() {

    companion object {
        /**
         * [T-STALL-DIAG] How many times onStartCommand has run in THIS process.
         * A START_STICKY revival lands in a fresh process, so a value of 1 with
         * `revival=true` proves the system re-created the service after the
         * process died — distinguishing that from an ordinary in-process
         * restart (which would show an increasing count).
         */
        private val onStartCommandCalls = java.util.concurrent.atomic.AtomicInteger(0)

        /**
         * [T-STALL-DIAG] Process-start reference so every diagnostic line can
         * report how long THIS process has been alive. Pairs with the pid to
         * tell "same dirty process the user failed to kill" apart from "clean
         * cold start" when comparing against `adb shell ps`.
         */
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

        // [T-bg-overlay phase 2 fix] Separate channel + notification id
        // for the SYSTEM_ALERT_WINDOW permission nudge so it can have a
        // higher importance than the ongoing FGS status row (which is
        // intentionally LOW so it doesn't make sound on every tool).
        private const val OVERLAY_NUDGE_CHANNEL_ID = "overlay_permission_nudge"
        private const val OVERLAY_NUDGE_NOTIFICATION_ID = 9002

        // [T-android-dynamic-island] Framework extras key read by
        // Notification.isRequestPromotedOngoing() (Android 16). Not exported as
        // a public SDK constant; value verified by decompiling the on-device
        // framework.jar (const-string "android.requestPromotedOngoing").
        private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"
        private const val ACTION_STOP = "com.openminis.app.STOP_AGENT_SERVICE"
        // Reuse the shared constants from ApprovalNotifier instead of duplicating —
        // keeps the approve/deny strings in sync without a circular dependency
        // (this file cannot import ApprovalNotifier because that would create a
        // cyclic compilation order against the receiver). These string values
        // MUST match ApprovalNotifier.Companion's copies verbatim.
        private const val ACTION_APPROVE = "com.openminis.app.APPROVE_TOOL"
        private const val ACTION_DENY = "com.openminis.app.DENY_TOOL"
        private const val ACTION_INTERRUPT = "com.openminis.app.INTERRUPT_AGENT"
        private const val EXTRA_APPROVAL_ID = "approval_request_id"

        /**
         * Starts or updates the foreground service with current status.
         */
        fun startService(context: Context) {
            // The tracker is the source of truth; carrying a second status
            // snapshot in start intents caused stale/duplicate foreground posts.
            val intent = Intent(context, AgentForegroundService::class.java)
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(intent)
                } else {
                    context.startService(intent)
                }
            } catch (e: IllegalStateException) {
                // API 31+ ForegroundServiceStartNotAllowedException when the
                // process is backgrounded. The job keeps running; the
                // notification is best-effort and must not crash the caller.
                Log.w(TAG, "startForegroundService not allowed: ${e.message}")
            } catch (e: SecurityException) {
                Log.w(TAG, "startForegroundService denied: ${e.message}")
            }
        }

        /**
         * Stops the foreground service.
         */
        fun stopService(context: Context) {
            val intent = Intent(context, AgentForegroundService::class.java)
            context.stopService(intent)
        }
    }

    private var startTimeMs: Long = 0L
    /**
     * Partial wake lock acquired while the foreground service is alive.
     * Required because Android can put the CPU to sleep even with a
     * foreground service running — Doze can suspend non-FGS background
     * threads, and on some OEM ROMs (MIUI, EMUI, ColorOS) the CPU
     * throttles aggressively after screen-off. Without this lock, long
     * shell commands can stall mid-stream when the device sleeps.
     *
     * Held only while the service runs; released in [onDestroy] so we
     * never leak across orientation changes or process restarts.
     */
    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * T-bg-overlay phase 2: floating tool-status overlay manager + its
     * collector scope. The overlay is *aspirational* — visible only when:
     *   1. User toggled `backgroundOverlayEnabled` ON, AND
     *   2. SYSTEM_ALERT_WINDOW is granted, AND
     *   3. App is currently backgrounded, AND
     *   4. A tool is in flight (isToolRunning), OR was recently in flight
     *      and we're in the 30-second linger window.
     * Falls back silently to Phase 1 notification when any precondition
     * fails. Bound to the service lifetime so onDestroy tears everything
     * down deterministically.
     */
    private var overlayController: ToolOverlayController? = null
    private val overlayScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var lingerJob: Job? = null

    // [T-android-overlay-completion-pending] X9: linger a completed-state
    // capsule after the busy edge, but ONLY when the turn ended while the
    // app was backgrounded (and the overlay was eligible to show). Scoping
    // the flag to that exact busy→idle edge is what keeps the old
    // pre-show-if-busy regression out: lastOutcome / lastReplyExcerpt
    // persist across turns in the tracker, so any rule that consults them
    // without an edge guard re-pops the capsule on every later home-screen
    // visit. The flag clears on user dismissal (tap-to-open / X) and on
    // foreground (the user saw the reply in-app).
    private var hasCompletionPending = false
    private var wasBusy = false

    override fun onCreate() {
        super.onCreate()
        // Safe-mode bail-out. When CrashFrequencyDetector tripped in
        // MinisApp.onCreate, the Application skipped its lateinit init
        // for repositories — but a sticky FG service that was running
        // pre-crash will still be re-created by the system on the next
        // process spawn. Reading MinisApp.backgroundSettingsRepository
        // from ToolOverlayController.<init> here would throw
        // UninitializedPropertyAccessException and write a second crash
        // log, which is exactly the "detection logic recursively
        // crashing" pattern. Skip the overlay observer and let
        // onStartCommand satisfy the FG-deadline + stopSelf.
        isRunning = true
        createNotificationChannel()
        startTimeMs = SystemClock.elapsedRealtime()
        // Meet the deadline before creating PendingIntents or starting observers.
        // Exactly one cheap, unpromoted placeholder per service instance.
        publishBootstrap()
        if (!bootstrapPosted || com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()) {
            Log.w(TAG, "foreground unavailable or safe-mode ON — skipping overlay/wake-lock bring-up")
            return
        }
        acquireWakeLock()
        startOverlayObserver()
        Log.d(TAG, "Service created")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // [T-STALL-DIAG] Revival probe. `intent == null` means the SYSTEM
        // re-created this service under START_STICKY after the process died —
        // the suspected "dirty shell" path behind "killing the app doesn't
        // help". Log the call ordinal, whether this is a revival, and the
        // in-memory tracker state, which is what a revived process CANNOT have
        // restored (SessionActivityTracker is a plain object + StateFlow).
        //
        // Reading `activeSessions` empty on a revival is the smoking gun: the
        // service is being kept alive for streams that no longer exist.
        val call = onStartCommandCalls.incrementAndGet()
        val active = SessionActivityTracker.activeSessions.value
        println(
            "[T-STALL-DIAG] FGS onStartCommand#$call pid=${android.os.Process.myPid()} " +
                "revival=${intent == null} flags=$flags startId=$startId " +
                "activeSessions=${active.size}[${active.joinToString(",")}] " +
                "processAliveMs=${android.os.SystemClock.elapsedRealtime() - processStartElapsedMs} " +
                "slots=${SessionConcurrencyManager.diagSnapshot()}",
        )
        // onCreate already satisfied the foreground deadline with a plain stub.
        // If even the stub failed, no background service should remain alive.
        if (!bootstrapPosted || com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()) {
            stopSelf()
            return START_NOT_STICKY
        }
        // START_STICKY restores the service, not in-memory agent jobs. Do not
        // leave an orphan foreground notification after a process restart.
        if (intent == null &&
            SessionActivityTracker.activeSessions.value.isEmpty() &&
            SessionActivityTracker.presentSessions.value.isEmpty()
        ) {
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_APPROVE || intent?.action == ACTION_DENY) {
            // [T-android-notif-approval] Approval notification action fallback.
            // PendingIntent targets ApprovalBroadcastReceiver (getBroadcast), which
            // calls ApprovalGate.approve/deny directly. This service handler catches
            // intents routed to onStartCommand instead (e.g. when the system
            // re-routes a stale broadcast to the foreground service).
            val id = intent.getStringExtra(EXTRA_APPROVAL_ID)
            if (id != null) {
                if (intent.action == ACTION_APPROVE) ApprovalGate.approve(id)
                else ApprovalGate.deny(id)
            }
            // Clear the approval notification on every resolution path that
            // lands here — the receiver owns its own tap path, but if the
            // system delivers this intent to onStartCommand we still need
            // the bar entry gone.
            if (id != null) com.openminis.app.notification.ApprovalNotifier.cancelApproval(this, id)
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_STOP) {
            // T50: the notification's Stop action — also cancel every
            // running agent loop. Without this, stopSelf() alone leaves
            // streamJobs running until the OS reclaims the process; the
            // user taps Stop and sees the notification go away but tools
            // keep firing in the background. SessionActivityTracker holds
            // the per-session cancel callbacks registered by each VM at
            // streamJob start.
            //
            // [T-android-approval-teardown] Also resolve every pending
            // ApprovalGate request and clear its notification: stopping the
            // agent must not leave an orphaned "needs approval" bar entry
            // whose buttons wake nothing. Snapshot the ids first —
            // cleanupAll empties the queue — then cancel those notifications.
            val stopPendingIds = ApprovalGate.pendingIds()
            ApprovalGate.cleanupAll()
            com.openminis.app.notification.ApprovalNotifier.cancelAll(this, stopPendingIds)
            SessionActivityTracker.cancelAllActiveStreams()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent?.action == ACTION_INTERRUPT) {
            // [T-android-interrupt] Pause the running agent loop AND clear the
            // notification — the user explicitly asked for a stop/pause, so the
            // ongoing status row must go too. If any sessions remain (they
            // were never cancelled) the service re-anchors itself below.
            // [T-android-approval-teardown] Same contract as ACTION_STOP:
            // an interrupt resolves every pending approval and clears its
            // notification before the service tears down.
            val interruptPendingIds = ApprovalGate.pendingIds()
            ApprovalGate.cleanupAll()
            com.openminis.app.notification.ApprovalNotifier.cancelAll(this, interruptPendingIds)
            SessionActivityTracker.cancelAllActiveStreams()
            // [T-android-interrupt-clear] The ongoing notification is the
            // visual representation of "agent is running". If we just stopped
            // all active streams, keeping the notification lying would leave
            // a stale "Minis is working…" pill with no way to dismiss it.
            // The notification manager id is NOTIFICATION_ID.
            try {
                getSystemService(NotificationManager::class.java)
                    ?.cancel(NOTIFICATION_ID)
            } catch (_: Exception) {}
            // Stop the service — it was started solely to run agents. If a
            // new agent later registers with SessionActivityTracker, the
            // service will be re-started via startService from the session
            // activation path. Leaving it alive here is a dead service
            // burning CPU / battery.
            stopSelf()
            return START_NOT_STICKY
        }

        // The stub was posted once in onCreate. Subsequent start intents only
        // update the visible state if it actually changed; never replay it.
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
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()

    private fun applyForeground(notification: Notification): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            true
        } catch (t: Throwable) {
            Log.w(TAG, "applyForeground failed: ${t.message}")
            false
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * Swipe-from-recents handler. Default Service behaviour on some OEM
     * builds is to silently end the service when the task is removed
     * even if it's a foreground service — we lose the streamJob, the
     * notification disappears, and the user thinks "Stop" was tapped.
     *
     * Re-anchor the service to its own intent and call startForeground
     * again. AOSP keeps it alive across task removal as long as at least
     * one active session is registered; if zero sessions remain (the
     * task removal raced a natural completion), [stopSelf] cleans up.
     */
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        // [T-STALL-DIAG] Record the FULL keep-alive decision. The branch taken
        // here decides whether a swipe-away leaves a service behind that the
        // system will later revive with START_STICKY.
        val activeAtRemoval = SessionActivityTracker.activeSessions.value
        println(
            "[T-STALL-DIAG] FGS onTaskRemoved pid=${android.os.Process.myPid()} " +
                "activeSessions=${activeAtRemoval.size}[${activeAtRemoval.joinToString(",")}] " +
                "decision=${if (activeAtRemoval.isEmpty()) "stopSelf" else "KEEP-ALIVE"} " +
                "slots=${SessionConcurrencyManager.diagSnapshot()}",
        )
        // T166: swiping from recents kills the Activity but the FG
        // service should survive iff a stream is still running. Pure
        // presence (user was reading a chat, then swiped away) is no
        // longer a reason to keep alive — they explicitly dismissed
        // the app, so clear presence here and re-evaluate.
        SessionActivityTracker.clearPresence()
        if (SessionActivityTracker.activeSessions.value.isEmpty()) {
            Log.d(TAG, "onTaskRemoved with no active sessions, stopping self")
            stopSelf()
            return
        }
        Log.d(TAG, "onTaskRemoved with ${SessionActivityTracker.activeSessions.value.size} active session(s) — keeping service alive")
        // Task removal is a real OEM re-anchor event, not a status tick.
        // Force exactly one current-state publish through the same pipeline.
        requestStatusRefresh(reanchor = true)
    }

    // [T-android-overlay-landscape-width-rotation-drift] A Service receives
    // raw configuration changes regardless of any manifest configChanges
    // filter. Forward orientation flips / multi-window resizes to the
    // overlay controller so the WindowManager capsule (which is not
    // recreated on rotation) re-clamps itself back into the new screen
    // bounds and picks up the new capped width.
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        overlayController?.onConfigurationChanged()
    }

    override fun onDestroy() {
        isRunning = false
        notifyHandler.removeCallbacksAndMessages(null)
        releaseWakeLock()
        try {
            overlayController?.hide()
        } catch (_: Throwable) {}
        overlayController = null
        overlayScope.cancel()
        super.onDestroy()
        Log.d(TAG, "Service destroyed")
    }

    /**
     * T-bg-overlay phase 2: collect the (foreground, toolName, toolStatus,
     * isToolRunning, toggle) tuple and reflect it into the overlay. We
     * combine() inside the service so the collector dies cleanly with
     * onDestroy and we never leak views across service restarts.
     *
     * Linger behaviour: when [SessionActivityTracker.isToolRunning] flips
     * false (tool finished), we don't hide immediately — Phase 2 spec
     * wants 30 s of "task ended" feedback while the app stays
     * backgrounded. Foreground transitions always hide instantly so the
     * overlay doesn't draw on top of the chat itself.
     */
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
                SessionActivityTracker.cameraSuppressActive,
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
                    cameraSuppress = values[9] as Boolean,
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
        val cameraSuppress: Boolean,
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
            Log.d(
                TAG,
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
            hasPerm && !state.cameraSuppress
        ) {
            hasCompletionPending = true
        }
        wasBusy = isBusy
        val shouldShow = state.enabled && hasPerm && !state.isForeground &&
            !state.cameraSuppress &&
            (isBusy || hasCompletionPending)
        Log.d(
            TAG,
            "applyOverlayState fg=${state.isForeground} enabled=${state.enabled} " +
                "perm=$hasPerm streaming=${state.hasActiveStream} toolRunning=${state.isRunning} " +
                "cameraSuppress=${state.cameraSuppress} completionPending=$hasCompletionPending " +
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
        if (state.isForeground || !state.enabled || !hasPerm || state.cameraSuppress) {
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
        val promoted = active > 0 && DynamicIslandSupport.isDynamicIslandActive(
            this, app?.backgroundSettingsRepository?.dynamicIslandEnabled?.value == true,
        )
        // A live-update chip does not show ephemeral tool output or elapsed
        // text. The system chronometer renders time without posting anything.
        val subtitle = when {
            active == 0 -> getString(R.string.notif_in_session)
            active == 1 -> getString(R.string.notif_one_task_running)
            else -> getString(R.string.notif_n_tasks_running, active)
        }
        return ForegroundNotificationState(
            activeCount = active,
            toolName = SessionActivityTracker.currentToolName.value,
            isToolRunning = SessionActivityTracker.isToolRunning.value,
            startedAtMs = timeline.startedAtMs ?: startTimeMs,
            finishedAtMs = timeline.finishedAtMs?.takeIf { active == 0 },
            promoted = promoted,
            subtitle = subtitle,
        ).stableForPromotion()
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
                    if (applyForeground(notification)) {
                        notificationPolicy.markPublished(state, SystemClock.elapsedRealtime())
                        Log.d(TAG, "status posted promoted=${state.promoted} active=${state.activeCount} completed=${state.completed}")
                    }
                } catch (t: Throwable) {
                    Log.w(TAG, "status notification failed: ${t.message}")
                }
            }
        }
    }

    private fun acquireWakeLock() {
        if (wakeLock != null) return
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                "minis:inference",
            ).apply {
                setReferenceCounted(false)
                // No timeout — release happens deterministically in onDestroy
                // when SessionActivityTracker reports zero active sessions.
                acquire()
            }
            wakeLockHeld = true
            Log.d(TAG, "WakeLock acquired (PARTIAL_WAKE_LOCK)")
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
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
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
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
    }

    /**
     * [T-bg-overlay phase 2 fix] Throttle for the SAW permission nudge.
     * `true` after the first emission this service lifetime so a single
     * agent loop that calls 10 tools doesn't post 10 identical
     * heads-up notifications. Reset on service destroy so a fresh
     * launch after the user has had a chance to think about it can
     * remind them again.
     */
    private var overlayNudgePosted: Boolean = false

    /** Last observed Live-Updates-active flag, for edge-triggered notify. */
    private var lastDynamicIslandActive: Boolean? = null

    private fun maybePostOverlayPermissionNudge() {
        if (overlayNudgePosted) return
        overlayNudgePosted = true
        try {
            val mgrIntent = Intent(
                android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                android.net.Uri.parse("package:$packageName"),
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            val pi = PendingIntent.getActivity(
                this,
                2,
                mgrIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notif = NotificationCompat.Builder(this, OVERLAY_NUDGE_CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(getString(R.string.bg_overlay_nudge_title))
                .setContentText(getString(R.string.bg_overlay_nudge_body))
                .setStyle(
                    NotificationCompat.BigTextStyle()
                        .bigText(getString(R.string.bg_overlay_nudge_body)),
                )
                .setContentIntent(pi)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .setCategory(NotificationCompat.CATEGORY_RECOMMENDATION)
                .build()
            val mgr = getSystemService(NotificationManager::class.java)
            mgr?.notify(OVERLAY_NUDGE_NOTIFICATION_ID, notif)
            Log.d(TAG, "overlay permission nudge posted (SAW not granted but toggle is ON)")
        } catch (e: Throwable) {
            Log.w(TAG, "overlay permission nudge failed: ${e.message}", e)
        }
    }

    private fun buildNotification(state: ForegroundNotificationState): Notification {
        val sessionCount = state.activeCount
        val isCompleted = state.completed
        val endMs = state.finishedAtMs ?: SystemClock.elapsedRealtime()
        val elapsedMs = (endMs - state.startedAtMs).coerceAtLeast(0L)
        val elapsedSeconds = (elapsedMs / 1000).toInt()
        val minutes = elapsedSeconds / 60
        val seconds = elapsedSeconds % 60
        val timeString = String.format("%d:%02d", minutes, seconds)

        val mainIntent = Intent(this, Class.forName("com.openminis.app.MainActivity")).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            mainIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

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
        val shortCritical = "Minis"

        // Promotion is selected once from the exact state submitted to the
        // policy. Presence-only/completed notifications always remain plain.
        if (state.promoted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.BAKLAVA) {
            val promoted = buildPromotedNotification(
                titleText = titleText,
                collapsedText = collapsedText,
                shortCritical = shortCritical,
                smallIcon = smallIconRes(toolName, isCompleted),
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
            .setSmallIcon(smallIconRes(toolName, isCompleted))
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

        // [T-android-live-update-completed] Same rule as the promoted branch:
        // no Stop once there is nothing left to stop.
        if (!isCompleted) {
            builder.addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                getString(R.string.bg_service_stop_action),
                stopPendingIntent,
            )
            // Interrupt: pause the agent loop.
            val interruptIntent = Intent(this, AgentForegroundService::class.java).apply {
                action = ACTION_INTERRUPT
            }
            val interruptPi = PendingIntent.getService(
                this, 1, interruptIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            builder.addAction(
                android.R.drawable.ic_menu_manage, "Pause", interruptPi,
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
        shortCritical: String,
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
            .setProgressTrackerIcon(Icon.createWithResource(this, smallIcon))
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
            // Explicitly NOT colorized and NOT a group summary — both would
            // disqualify the notification from promotion.
            .setColorized(false)
            .setShortCriticalText(shortCritical)

        builder.addAction(
            Notification.Action.Builder(
                Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
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
     * T-bg-overlay phase 1: human-readable label per tool, mirroring
     * `ChatScreen.kt:5974 toolTitleLabel` so the notification's title
     * matches what the in-app FloatingToolStatusBar shows. Falls back
     * to the raw tool name for unknowns rather than a generic string,
     * so the user still gets a hint about what's running.
     */
    private fun toolDisplayLabel(toolName: String): String = when (toolName) {
        "shell_execute" -> "Minis is using Shell"
        "file_read" -> "Minis is reading File"
        "file_write" -> "Minis is using Editor"
        "file_edit" -> "Minis is editing File"
        "browser_use" -> "Minis is using Browser"
        "read_image" -> "Minis is reading Image"
        "memory_write", "memory_get" -> "Minis is using Memory"
        "web_search" -> "Minis is using Search"
        else -> "Minis is using $toolName"
    }

    /**
     * T-bg-overlay phase 1: pick a system small-icon hint per tool kind.
     * Notification small icons must be tintable monochrome — we use
     * built-in framework drawables instead of pulling in app icon
     * resources to avoid the Android < 24 "white square" fallback for
     * vector drawables. The default (`ic_menu_manage`) preserves the
     * pre-T pixel-identical look for idle / between-turn rebuilds.
     */
    /**
     * [T-android-live-update-completed] Small icon for the ongoing notification.
     * Once the task has finished, show a checkmark instead of a tool glyph — the
     * icon is the most glanceable part of the Live Update chip, and leaving the
     * generic wrench there made a completed task read as still-running.
     */
    private fun smallIconRes(toolName: String?, isCompleted: Boolean): Int =
        if (isCompleted) R.drawable.ic_notification_completed else toolSmallIconRes(toolName)

    private fun toolSmallIconRes(toolName: String?): Int = when (toolName) {
        "shell_execute" -> android.R.drawable.ic_menu_edit
        "file_read", "read_image" -> android.R.drawable.ic_menu_view
        "file_write", "file_edit" -> android.R.drawable.ic_menu_edit
        "browser_use" -> android.R.drawable.ic_menu_compass
        "memory_write", "memory_get" -> android.R.drawable.ic_menu_save
        "web_search" -> android.R.drawable.ic_menu_search
        else -> android.R.drawable.ic_menu_manage
    }
}
