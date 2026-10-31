package com.openminis.app.quick

import android.annotation.SuppressLint
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import kotlin.math.min

/**
 * [E-batch-quick-invocation] Edge-docked floating ball (48 dp) that opens the
 * quick panel from anywhere. TYPE_APPLICATION_OVERLAY + a specialUse
 * foreground service (see the manifest report).
 *
 * Gestures (classification lives in [QuickPanelPolicy], unit-tested):
 *  - single tap → QuickActivity (plain summon),
 *  - double tap → read the current screen through MinisAccessibilityService
 *    (<= 4000 chars) BEFORE the panel covers it, then open QuickActivity with
 *    the capture attached; when accessibility is off the panel degrades to a
 *    pre-filled generic prompt,
 *  - drag → move freely, snap to the nearer horizontal edge on release.
 *
 * v1 scope notes (deliberate):
 *  - no run-status capsule / pulse: the ball is an entry point only,
 *  - hidden while the app's own UI is in front via ProcessLifecycleOwner
 *    (started/stopped callbacks on the process lifecycle),
 *  - the enabled switch lives in [QuickPrefs]; the service checks it on every
 *    (re)start so a START_STICKY restart cannot resurrect a disabled ball.
 */
class FloatingBallService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var ballView: QuickBallView? = null
    private var destroyed = false
    private var appInFront = false

    private val frontObserver = object : androidx.lifecycle.LifecycleEventObserver {
        override fun onStateChanged(source: androidx.lifecycle.LifecycleOwner, event: androidx.lifecycle.Lifecycle.Event) {
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> { appInFront = true; render() }
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> { appInFront = false; render() }
                else -> {}
            }
        }
    }

    @SuppressLint("ClickableViewAccessibility", "InflateParams")
    override fun onCreate() {
        super.onCreate()
        // Foreground first: with the specialUse type the service must call
        // startForeground within the FGS timeout or the system kills us.
        QuickNotifications.ensureChannels(this)
        val notification = androidx.core.app.NotificationCompat.Builder(this, QuickNotifications.CHANNEL_BALL)
            .setSmallIcon(android.R.drawable.ic_menu_manage)
            .setContentTitle(getString(R.string.quick_ball_notification_title))
            .setContentText(getString(R.string.quick_ball_notification_text))
            .setOngoing(true)
            .setSilent(true)
            .build()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                QuickNotifications.BALL_NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(QuickNotifications.BALL_NOTIFICATION_ID, notification)
        }
        if (!Settings.canDrawOverlays(this)) {
            AppLogger.info(TAG, "overlay permission missing — stopping")
            stopSelf()
            return
        }
        windowManager = getSystemService(WindowManager::class.java)
        addBallView()
        // Hide while our own UI is in front (ProcessLifecycleOwner reports
        // process-level start/stop, which is exactly "our UI is visible").
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(frontObserver)
        AppLogger.info(TAG, "floating ball shown")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Restart after process death — onCreate re-checks the overlay grant
        // and the prefs switch, so a disabled ball never comes back.
        return START_STICKY
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun addBallView() {
        val wm = windowManager ?: return
        val density = resources.displayMetrics.density
        val size = (BALL_SIZE_DP * density).toInt()
        val margin = (DOCK_MARGIN_DP * density).toInt()
        val screen = screenSize()
        val layoutParams = WindowManager.LayoutParams(
            size,
            size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = QuickPanelPolicy.dockX(screen.first - size - margin, size, screen.first, margin)
            y = QuickPanelPolicy.clampY((screen.second / 3), size, screen.second)
        }

        val view = QuickBallView(this).apply {
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
            contentDescription = getString(R.string.quick_settings_title)
        }
        var downRawX = 0f
        var downRawY = 0f
        var startX = 0
        var startY = 0
        var dragging = false
        var lastTapEventTime = 0L
        val singleTapRunnable = Runnable { openQuickPanel(fromScreen = false) }
        val doubleTapWindow = QuickPanelPolicy.DOUBLE_TAP_WINDOW_MS

        view.setOnTouchListener { v, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    downRawX = event.rawX
                    downRawY = event.rawY
                    startX = layoutParams.x
                    startY = layoutParams.y
                    dragging = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x = startX + (event.rawX - downRawX).toInt()
                    layoutParams.y = startY + (event.rawY - downRawY).toInt()
                    runCatching { wm.updateViewLayout(v, layoutParams) }
                    if (!dragging && QuickPanelPolicy.classifyGesture(
                            downRawX, downRawY, event.rawX, event.rawY,
                        ) == QuickPanelPolicy.Gesture.DRAG
                    ) {
                        dragging = true
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val gesture = QuickPanelPolicy.classifyGesture(downRawX, downRawY, event.rawX, event.rawY)
                    if (gesture == QuickPanelPolicy.Gesture.DRAG) {
                        // Snap to the nearer edge; re-read the size on every
                        // drop (rotation / fold moves the far edge).
                        val screenNow = screenSize()
                        layoutParams.x = QuickPanelPolicy.dockX(
                            layoutParams.x, layoutParams.width, screenNow.first, margin,
                        )
                        layoutParams.y = QuickPanelPolicy.clampY(
                            layoutParams.y, layoutParams.height, screenNow.second,
                        )
                        runCatching { wm.updateViewLayout(v, layoutParams) }
                    } else if (QuickPanelPolicy.isWithinDoubleTapWindow(lastTapEventTime, event.eventTime, doubleTapWindow)) {
                        // Second tap inside the window: capture the screen now,
                        // while the ball is still the only overlay, then open
                        // the panel with the context attached.
                        handler.removeCallbacks(singleTapRunnable)
                        lastTapEventTime = 0L
                        ScreenContext.captureFromAccessibility()
                        openQuickPanel(fromScreen = true)
                    } else {
                        // First tap: wait out the double-tap window before the
                        // single-tap action fires.
                        lastTapEventTime = event.eventTime
                        handler.postDelayed(singleTapRunnable, doubleTapWindow)
                    }
                    true
                }
                MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    layoutParams.x = QuickPanelPolicy.dockX(startX, layoutParams.width, screenSize().first, margin)
                    runCatching { wm.updateViewLayout(v, layoutParams) }
                    true
                }
                else -> false
            }
        }
        runCatching { wm.addView(view, layoutParams) }
            .onFailure {
                AppLogger.warning(TAG, "addView failed: ${it.message}")
                stopSelf()
                return
            }
        ballView = view
    }

    private fun openQuickPanel(fromScreen: Boolean) {
        val intent = Intent(this, QuickActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .putExtra(QuickActivity.EXTRA_FROM_BALL, true)
        if (fromScreen) intent.putExtra(QuickActivity.EXTRA_FROM_SCREEN, true)
        runCatching { startActivity(intent) }
            .onFailure { AppLogger.warning(TAG, "open panel failed: ${it.message}") }
    }

    /** Visibility from the current state — the only place that touches it. */
    private fun render() {
        if (destroyed) return
        ballView?.visibility = if (appInFront) View.GONE else View.VISIBLE
    }

    /** Full window size (displayMetrics excludes insets on some devices). */
    private fun screenSize(): Pair<Int, Int> {
        val wm = windowManager ?: return resources.displayMetrics.let { it.widthPixels to it.heightPixels }
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = wm.currentWindowMetrics.bounds
            bounds.width() to bounds.height()
        } else {
            @Suppress("DEPRECATION")
            val metrics = android.util.DisplayMetrics()
            @Suppress("DEPRECATION")
            wm.defaultDisplay.getRealMetrics(metrics)
            metrics.widthPixels to metrics.heightPixels
        }
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        val view = ballView ?: return
        val lp = view.layoutParams as? WindowManager.LayoutParams ?: return
        val wm = windowManager ?: return
        val margin = (DOCK_MARGIN_DP * resources.displayMetrics.density).toInt()
        val (w, h) = screenSize()
        lp.x = QuickPanelPolicy.dockX(lp.x, lp.width, w, margin)
        lp.y = QuickPanelPolicy.clampY(lp.y, lp.height, h)
        runCatching { wm.updateViewLayout(view, lp) }
    }

    override fun onDestroy() {
        destroyed = true
        androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.removeObserver(frontObserver)
        handler.removeCallbacksAndMessages(null)
        ballView?.let { view ->
            runCatching { getSystemService(WindowManager::class.java).removeView(view) }
        }
        ballView = null
        AppLogger.info(TAG, "floating ball removed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * The ball itself: a translucent dark disc with a four-point spark, drawn
     * entirely in code so no drawable resources are needed.
     */
    private class QuickBallView(context: Context) : View(context) {

        private val discPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = DISC_COLOR
            style = Paint.Style.FILL
        }
        private val sparkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = SPARK_COLOR
            style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val cx = width / 2f
            val cy = height / 2f
            val radius = min(width, height) / 2f
            canvas.drawCircle(cx, cy, radius, discPaint)
            // Four-point star (compass spark), ~55% of the radius.
            val r = radius * 0.55f
            val path = android.graphics.Path().apply {
                moveTo(cx, cy - r)
                quadTo(cx, cy, cx + r, cy)
                quadTo(cx, cy, cx, cy + r)
                quadTo(cx, cy, cx - r, cy)
                quadTo(cx, cy, cx, cy - r)
                close()
            }
            canvas.drawPath(path, sparkPaint)
        }

        private companion object {
            const val DISC_COLOR = 0xE6111820.toInt()
            const val SPARK_COLOR = 0xFF4C8DFF.toInt()
        }
    }

    companion object {
        private const val TAG = "FloatingBall"
        const val BALL_SIZE_DP = 48
        const val DOCK_MARGIN_DP = 8

        /**
         * Start or stop the ball per the prefs switch. Safe to call from
         * Settings (toggle) — with the overlay grant missing the pref is left
         * untouched and nothing starts, so the switch cannot lie.
         *
         * @return true when the service is (or was already) running.
         */
        fun setEnabled(context: Context, on: Boolean): Boolean {
            if (on && !Settings.canDrawOverlays(context)) return false
            val prefs = QuickPrefs(context)
            prefs.ballEnabled = on
            val intent = Intent(context, FloatingBallService::class.java)
            return if (on) {
                try {
                    context.startForegroundService(intent)
                    true
                } catch (t: Throwable) {
                    AppLogger.warning(TAG, "startForegroundService failed: ${t.message}")
                    false
                }
            } else {
                context.stopService(intent)
                false
            }
        }

        /**
         * Re-sync the running service with the stored switch (called on any
         * quick-surface entry — the service may have died without a restart).
         */
        fun ensureRunning(context: Context) {
            if (QuickPrefs(context).ballEnabled && Settings.canDrawOverlays(context)) {
                setEnabled(context, true)
            }
        }
    }
}
