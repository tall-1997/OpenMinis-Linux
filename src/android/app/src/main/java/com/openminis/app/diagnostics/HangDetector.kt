package com.openminis.app.diagnostics

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.util.Log
import com.openminis.app.util.IsoTime
import java.io.File
import java.io.FileWriter
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Main-thread hang watchdog.
 *
 * Posts a heartbeat task to the main looper at a fixed cadence and waits on a
 * background thread for it to fire. If the heartbeat fails to land within
 * [HANG_THRESHOLD_MS], the main thread is considered hung — capture its stack
 * trace, append a record to a daily `stall-<date>.log` file under the app's
 * logs/ dir, increment a persisted hang counter, and continue.
 *
 * After [HANG_LIMIT_FOR_BREAKER] hangs accumulate the [shouldForceHomeOnLaunch]
 * gate flips true. AppNavigation reads it on cold start and overrides the
 * user's "open last session" / "open new chat" launch preference to "open
 * home" (mode 3) so the user isn't trapped in a loop where every cold start
 * lands on a session that hangs the UI again.
 *
 * The counter resets when the user successfully runs a chat session for
 * [RESET_AFTER_QUIET_MS] without another hang firing — see [markHealthyTick].
 *
 * No iOS counterpart yet (intentional — iOS only has the DEBUG-mode RPC hang
 * detector at src/ios/Debug/DebugRPCHangDetector.swift; this is a production
 * circuit breaker).
 */
object HangDetector {

    private const val TAG = "HangDetector"

    /** Heartbeat cadence — how often the watchdog pings the main thread. */
    private const val HEARTBEAT_INTERVAL_MS = 1_000L

    /** A hang fires once the main thread has missed a heartbeat for this long. */
    private const val HANG_THRESHOLD_MS = 3_000L

    /**
     * [T-android-hangdetector-midhang-sample] While a hang episode is still
     * ongoing, re-sample the main-thread stack this often. Multiple samples
     * across one long hang show whether the thread is stuck in ONE frame
     * (a single blocking call) or churning through related frames (a loop)
     * — and guarantee samples land while the work is actually on the stack.
     * Replaces the old MIN_GAP_BETWEEN_LOGS_MS single-shot dedupe, whose one
     * trip-time snapshot was frequently an idle stack (background process
     * freezes resume with a huge heartbeat gap but an already-idle main
     * thread — that's why historical stall logs were full of
     * nativePollOnce frames).
     */
    private const val MID_HANG_RESAMPLE_MS = 3_000L

    /**
     * [T-android-hangdetector-full-dump] Episodes at or above this duration
     * also persist a full all-threads dump (names + states + stacks). The
     * 19:48 episode on 2026-10-05 lasted 62 minutes with the main thread
     * parked in nativePollOnce — its stack shows WHERE it is waiting but
     * not WHAT it is waiting on (a lock, a binder call, a frozen IO
     * thread); the holder only appears in the full dump. 15s is comfortably
     * above any GC/sync hiccup and well below the episodes that motivated
     * this.
     */
    private const val FULL_DUMP_THRESHOLD_MS = 15_000L

    /** Once `count >= this`, AppNavigation forces launch mode = home. */
    private const val HANG_LIMIT_FOR_BREAKER = 3

    /**
     * [T-android-hangdetector-freeze-gate] Heartbeat gaps above this ceiling are
     * process-freeze / deep-sleep artifacts, not live ANRs: a genuinely stuck
     * main thread is killed by the system (or by the 600s read watchdog upstream)
     * long before a gap this large can accumulate, and 6/6 recorded stall episodes
     * (48s, 6.6min, 63min, 11.4min) were all cached-app-freezer resumes — single
     * mid-hang sample, post-recovery idle ~0.5s later, zero escalation re-samples.
     * Such episodes are still LOGGED (the thaw stack is diagnostic gold) but no
     * longer COUNTED, so an overnight freeze cannot trip the render breaker (>=2)
     * or the force-home breaker (>=3).
     */
    private const val FREEZE_GAP_CEILING_MS = 30_000L

    /**
     * [T-android-render-breaker] Once `count >= this`, streaming markdown
     * rendering degrades to plain text until the hang count resets (quiet
     * period or manual reset). Deliberately one step EARLIER than the launch
     * breaker: the ANR-loop baseline (minis-2026-06-10.log) shows the system
     * kills the process between hang #2 and #3, so a count-3 gate never fires
     * in the scenario it exists for.
     */
    private const val RENDER_DEGRADE_HANG_COUNT = 2

    /** Quiet period (no hang firing) after which the count resets. */
    private const val RESET_AFTER_QUIET_MS = 10_000L

    private const val PREFS_NAME = "hang_detector_prefs"
    private const val KEY_HANG_COUNT = "hang_count"
    private const val KEY_LAST_HANG_AT = "last_hang_at_ms"

    private const val STALL_LOG_DIR = "logs"
    private const val STALL_LOG_PREFIX = "stall-"

    private val mainHandler = Handler(Looper.getMainLooper())
    private val started = AtomicBoolean(false)

    /** Heartbeat ticks bumped by the main-thread side of each ping. */
    private val lastHeartbeatAt = AtomicLong(0L)
    private val lastLogAt = AtomicLong(0L)

    private var appContext: Context? = null

    /**
     * [T-android-mainthread-prefs-hang] Prefs wrapper used by [markHealthyTick],
     * the only HangDetector prefs touch on the MAIN thread (ChatScreen calls
     * it on a healthy cadence). A wedged filesystem on that first read would
     * re-trip the detector via the very recovery path that exists to clear
     * it. All other HangDetector prefs access runs on the watchdog thread or
     * cold-start paths, where the blocking read is acceptable.
     */
    private val asyncPrefs by lazy {
        com.openminis.app.crash.AsyncPrefs.create(
            appContext ?: throw IllegalStateException("HangDetector.start not called"),
            PREFS_NAME,
        )
    }

    /**
     * Set by the application after start. Diagnostics must not import the
     * sandbox package; the hook is how a counted hang kills a live guest
     * instead of only degrading markdown.
     */
    @Volatile var hasLiveWorkload: () -> Boolean = { false }
    @Volatile var onCountedHang: (count: Int, durationMs: Long) -> Unit = { _, _ -> }

    private val _renderBreakerActive = MutableStateFlow(false)

    /**
     * [T-android-render-breaker] Live signal that streaming markdown rendering
     * should degrade to plain text (main thread has hung
     * [RENDER_DEGRADE_HANG_COUNT]+ times recently). Consumed by the chat
     * renderer (LargeContentGuard); cleared by the same quiet-period / manual
     * resets that clear the hang count. Seeded from the persisted count at
     * [start] so a relaunch mid-loop starts degraded instead of hanging again
     * before the first in-process hang fires.
     */
    val renderBreakerActive: StateFlow<Boolean> = _renderBreakerActive.asStateFlow()

    /** Start the watchdog. Idempotent; safe to call from MinisApp.onCreate(). */
    fun start(context: Context) {
        if (!started.compareAndSet(false, true)) return
        appContext = context.applicationContext
        lastHeartbeatAt.set(nowElapsed())
        // [T-android-render-breaker] Seed the render breaker from the
        // PERSISTED hang count: in the ANR-kill loop the process never lives
        // long enough to accumulate 2 in-process hangs, but the count
        // survives restarts — so a relaunch mid-loop starts with degraded
        // streaming rendering instead of hanging once more first.
        if (currentHangCount(context) >= RENDER_DEGRADE_HANG_COUNT) {
            _renderBreakerActive.value = true
            Log.w(TAG, "render breaker seeded ACTIVE from persisted hang count")
        }
        scheduleHeartbeat()
        // Use a non-daemon thread so the watchdog isn't reaped while the app
        // is still alive but scheduled out. Daemon threads also die earlier
        // when the JVM is winding down, which can suppress the very stalls
        // we want to capture.
        thread(name = "HangDetector-watch", isDaemon = false) { watchLoop() }
        registerUnfreezeHeartbeatReset(context.applicationContext)
        // [T-HANG-DIAG] echo via stdout *and* logcat so the start banner
        // shows up regardless of whether the user has Settings → Logging
        // enabled. AppLogger replaces System.out with its file-writing
        // PrintStream when logging is on; when logging is off this still
        // surfaces under `adb logcat`. Same pattern is used by recordHang
        // so its output is also captured both ways.
        val banner = "[T-HANG-DIAG] HangDetector started: threshold=${HANG_THRESHOLD_MS}ms " +
            "interval=${HEARTBEAT_INTERVAL_MS}ms limit=$HANG_LIMIT_FOR_BREAKER"
        println(banner)
        Log.i(TAG, banner)
    }

    /**
     * Called by long-running healthy UI surfaces (e.g. ChatScreen) to confirm
     * the main thread has been responsive for [RESET_AFTER_QUIET_MS] since the
     * last hang. Cheap on the hot path — early-returns when the count is
     * already 0.
     */
    fun markHealthyTick() {
        if (appContext == null) return
        // [T-android-mainthread-prefs-hang] AsyncPrefs keeps this off the
        // disk on the main thread. Before the async load lands, getInt
        // returns 0 → early-return — the correct conservative answer (a
        // hang count written this process is mirrored in the in-memory
        // overrides, so a real count is never hidden).
        if (asyncPrefs.getInt(KEY_HANG_COUNT, 0) == 0) return
        val lastHangAt = asyncPrefs.getLong(KEY_LAST_HANG_AT, 0L)
        if (lastHangAt > 0 && System.currentTimeMillis() - lastHangAt < RESET_AFTER_QUIET_MS) return
        asyncPrefs.putInt(KEY_HANG_COUNT, 0)
        asyncPrefs.putLong(KEY_LAST_HANG_AT, 0L)
        // [T-android-render-breaker] Healthy again — restore full rendering.
        _renderBreakerActive.value = false
        Log.i(TAG, "hang count reset after quiet period")
    }

    /**
     * AppNavigation calls this on cold start. Returns true once the breaker
     * threshold is hit, asking the launch resolver to ignore the user's
     * "open last session / open new chat" preference and land on the home
     * screen instead — the only safe destination when the previous launches
     * have been hanging.
     */
    fun shouldForceHomeOnLaunch(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_HANG_COUNT, 0) >= HANG_LIMIT_FOR_BREAKER
    }

    /** Manual reset (Settings → "Reset hang counter") — clears immediately. */
    fun resetHangCount(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putInt(KEY_HANG_COUNT, 0)
            .putLong(KEY_LAST_HANG_AT, 0L)
            .apply()
        // [T-android-render-breaker] Manual reset also restores full rendering.
        _renderBreakerActive.value = false
    }

    fun currentHangCount(context: Context): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt(KEY_HANG_COUNT, 0)

    // -- internals -----------------------------------------------------------

    private fun nowElapsed(): Long = SystemClock.elapsedRealtime()

    /**
     * API 35+: when the UID is unfrozen, reset the heartbeat so the first
     * watchdog tick after thaw does not see a multi-minute gap. The 30s
     * freeze gate remains as a belt-and-suspenders for the race where
     * [watchLoop] runs before this callback.
     */
    private fun registerUnfreezeHeartbeatReset(context: Context) {
        if (Build.VERSION.SDK_INT < 35) return
        try {
            val am = context.getSystemService(ActivityManager::class.java) ?: return
            val myUid = Process.myUid()
            // Reflect so compileSdk stubs that omit the API 35 freeze
            // listener still compile; no-op if the method is missing.
            val listenerClass = Class.forName(
                "android.app.ActivityManager\$OnUidFrozenStateChangedListener",
            )
            val unfrozen = try {
                ActivityManager::class.java.getField("UID_FROZEN_STATE_UNFROZEN").getInt(null)
            } catch (_: Throwable) {
                2
            }
            val proxy = java.lang.reflect.Proxy.newProxyInstance(
                listenerClass.classLoader,
                arrayOf(listenerClass),
            ) { _, method, args ->
                if (method.name == "onUidFrozenStateChanged" && args != null && args.size >= 2) {
                    val uids = args[0] as IntArray
                    val states = args[1] as IntArray
                    for (i in uids.indices) {
                        if (uids[i] == myUid && states[i] == unfrozen) {
                            lastHeartbeatAt.set(nowElapsed())
                            Log.i(TAG, "UID unfrozen — heartbeat reset")
                        }
                    }
                }
                null
            }
            ActivityManager::class.java.getMethod(
                "addOnUidFrozenStateChangedListener",
                java.util.concurrent.Executor::class.java,
                listenerClass,
            ).invoke(am, context.mainExecutor, proxy)
        } catch (t: Throwable) {
            Log.w(TAG, "failed to register freeze listener: ${t.message}")
        }
    }

    private fun scheduleHeartbeat() {
        mainHandler.postDelayed({
            lastHeartbeatAt.set(nowElapsed())
            scheduleHeartbeat()
        }, HEARTBEAT_INTERVAL_MS)
    }

    private fun watchLoop() {
        // [T-HANG-DIAG] one-line confirmation that the watchdog thread itself
        // actually entered its loop — distinct from start() which only proves
        // the thread was *spawned*.
        println("[T-HANG-DIAG] HangDetector watchLoop entered")
        var ticks = 0L
        // [T-android-hangdetector-midhang-sample] Episode state: a hang
        // episode starts when the heartbeat gap first crosses the threshold
        // and ends when a heartbeat lands again. The episode is COUNTED once
        // (breakers depend on count semantics) but SAMPLED repeatedly.
        var hangActive = false
        var lastSampleAt = 0L
        var escalation = 0
        var episodePeakSinceMs = 0L
        while (true) {
            try {
                Thread.sleep(500)
            } catch (e: InterruptedException) {
                return
            }
            ticks++
            val now = nowElapsed()
            val since = now - lastHeartbeatAt.get()
            com.openminis.app.sandbox.kernel.remediation.StallSignal.observe(since)
            // [T-HANG-DIAG] every 30 ticks (~15s) emit a liveness ping so we
            // can confirm the watchdog is alive even when nothing hangs.
            // Volume is intentionally tiny (~4 lines / minute).
            if (ticks % 30L == 0L) {
                println("[T-HANG-DIAG] HangDetector tick=$ticks sinceHeartbeat=${since}ms")
            }
            if (since < HANG_THRESHOLD_MS) {
                com.openminis.app.sandbox.kernel.remediation.StallSignal.clear()
                if (hangActive) {
                    // Episode over — the heartbeat landed. One labeled
                    // post-recovery snapshot closes the record (its stack is
                    // expectedly idle; it documents WHEN the thread came
                    // back and the episode's peak gap).
                    hangActive = false
                    writeStallSample("post-recovery", episodePeakSinceMs, escalation)
                    println(
                        "[T-HANG-DIAG] hang episode ENDED peak=${episodePeakSinceMs}ms midHangSamples=${escalation + 1}",
                    )
                }
                continue
            }
            if (!hangActive) {
                hangActive = true
                escalation = 0
                episodePeakSinceMs = since
                lastSampleAt = now
                lastLogAt.set(now)
                // [T-android-hangdetector-freeze-gate] Freeze/deep-sleep resumes
                // produce elapsedRealtime gaps of minutes; a live main-thread ANR
                // cannot survive that long. Log the episode, but only let it
                // feed the breakers when the gap is plausibly a real hang.
                val workloadLive = hasLiveWorkload()
                val sample = com.openminis.app.sandbox.GuestWorkloadPolicy.shouldSampleHang(
                    since,
                    FREEZE_GAP_CEILING_MS,
                    workloadLive,
                )
                if (!sample) {
                    println(
                        "[T-HANG-DIAG] freeze artifact: gap=${since}ms > ${FREEZE_GAP_CEILING_MS}ms — " +
                            "log sampling reduced; counter still increments",
                    )
                } else if (since > FREEZE_GAP_CEILING_MS) {
                    println(
                        "[T-HANG-DIAG] long gap=${since}ms counted: sandbox work is live",
                    )
                }
                recordHang(
                    durationMs = since,
                    counts = com.openminis.app.sandbox.GuestWorkloadPolicy.countsHang(
                        since,
                        FREEZE_GAP_CEILING_MS,
                        workloadLive,
                    ),
                    sample = sample,
                )
                continue
            }
            episodePeakSinceMs = maxOf(episodePeakSinceMs, since)
            if (now - lastSampleAt >= MID_HANG_RESAMPLE_MS) {
                lastSampleAt = now
                escalation++
                val sample = com.openminis.app.sandbox.GuestWorkloadPolicy.shouldSampleHang(
                    since,
                    FREEZE_GAP_CEILING_MS,
                    hasLiveWorkload(),
                )
                if (sample) writeStallSample("mid-hang", since, escalation)
            }
        }
    }

    /**
     * [T-android-hangdetector-midhang-sample] Capture the MAIN thread's stack
     * right now and persist it: full ~25 frames into stall-<date>.log, a
     * compact top-5 line into stdout/logcat (feeds the daily AppLogger file)
     * tagged [JankDiag] for grep. Thread.getStackTrace on a hung thread is
     * safe and cheap (VM suspends just that thread for the walk); no count /
     * breaker side effects — those live in [recordHang].
     */
    private fun writeStallSample(label: String, durationMs: Long, escalation: Int) {
        val ctx = appContext ?: return
        val mainStack = try {
            Looper.getMainLooper().thread.stackTrace
        } catch (t: Throwable) {
            arrayOf<StackTraceElement>()
        }
        val ts = IsoTime.formatLocalTimeMillis()
        val date = IsoTime.formatLocalDate()
        val builder = StringBuilder()
        builder.append(
            "===== HANG @ $ts (duration ~${durationMs}ms) sample=$label escalation=$escalation =====\n",
        )
        builder.append("thread: main\n")
        for (frame in mainStack.take(25)) builder.append("  at $frame\n")
        builder.append("\n")

        val top5 = mainStack.take(5).joinToString(" <- ") {
            "${it.className.substringAfterLast('.')}.${it.methodName}:${it.lineNumber}"
        }
        // [T-android-content-perf-diag] Attach the currently-rendering large
        // message's structural fingerprint (if any) so a Matcher/Pattern stall
        // stack maps straight to "this message, this content shape" from the log.
        val renderFields = ContentDiag.currentRenderLogFields()
        println(
            "[T-HANG-DIAG][JankDiag] sample=$label escalation=$escalation duration=${durationMs}ms top5: $top5$renderFields",
        )

        // [T-android-hangdetector-full-dump] Main-thread stack alone cannot
        // identify a lock-holder or a wedged IO thread; on long hangs also
        // persist every thread's name/state/stack so the next stall log
        // names a suspect instead of "main idle in nativePollOnce".
        if (durationMs >= FULL_DUMP_THRESHOLD_MS) {
            builder.append("----- all threads @ $ts (escalation=$escalation) -----\n")
            val all = try {
                Thread.getAllStackTraces()
            } catch (t: Throwable) {
                emptyMap<Thread, Array<StackTraceElement>>()
            }
            for ((thread, stack) in all.toSortedMap(compareBy { it.name })) {
                builder.append("thread: ${thread.name} state=${thread.state} daemon=${thread.isDaemon}\n")
                for (frame in stack.take(12)) builder.append("  at $frame\n")
            }
            builder.append("\n")
        }

        try {
            val dir = File(ctx.filesDir, STALL_LOG_DIR).also { it.mkdirs() }
            val file = File(dir, "$STALL_LOG_PREFIX$date.log")
            FileWriter(file, /* append = */ true).use { it.write(builder.toString()) }
        } catch (t: Throwable) {
            val msg = "[T-HANG-DIAG] FAILED to write stall log: ${t.javaClass.simpleName}: ${t.message}"
            println(msg)
            Log.w(TAG, msg)
        }
    }

    fun tripRenderBreaker() {
        if (!_renderBreakerActive.value) {
            _renderBreakerActive.value = true
            Log.w(TAG, "render breaker TRIPPED by remediation loop")
        }
    }

    private fun recordHang(durationMs: Long, counts: Boolean, sample: Boolean = true) {
        val ctx = appContext ?: return
        // [T-android-hangdetector-midhang-sample] The trip-time stack IS a
        // mid-hang sample (the heartbeat is 3s stale and the main thread is
        // still stuck); the watchdog keeps re-sampling every
        // MID_HANG_RESAMPLE_MS via writeStallSample while the episode lasts.
        // A freeze-sized gap still counts. Sampling is the only thing the
        // ceiling is allowed to reduce.
        if (sample) writeStallSample("mid-hang", durationMs, escalation = 0)
        if (!counts) return

        val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val newCount = prefs.getInt(KEY_HANG_COUNT, 0) + 1
        prefs.edit()
            .putInt(KEY_HANG_COUNT, newCount)
            .putLong(KEY_LAST_HANG_AT, System.currentTimeMillis())
            .apply()
        // [T-android-render-breaker] Trip the render degrade one hang BEFORE
        // the system would ANR-kill us (baseline showed death between #2/#3).
        if (newCount >= RENDER_DEGRADE_HANG_COUNT && !_renderBreakerActive.value) {
            _renderBreakerActive.value = true
            Log.w(TAG, "render breaker TRIPPED at hang count=$newCount — streaming markdown degrades to plain text")
        }
        val hook = onCountedHang
        thread(name = "HangDetector-breaker", isDaemon = true) {
            runCatching { hook(newCount, durationMs) }
                .onFailure { Log.w(TAG, "hang breaker hook failed: ${it.message}") }
        }
        val tail = "hang detected duration=${durationMs}ms count=$newCount " +
            "breakerActive=${newCount >= HANG_LIMIT_FOR_BREAKER}"
        println("[T-HANG-DIAG] $tail")
        Log.w(TAG, tail)
    }
}
