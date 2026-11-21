package com.openminis.app.crash

import android.app.Application
import android.util.Log
import com.openminis.app.data.db.DatabaseVersionGuard
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Launch-phase async probes for MinisApp.onCreate: the crash-burst scan and
 * the read-only database schema-version probe, both dispatched to IO at the
 * top of onCreate and awaited again at their pre-async program positions.
 * (Extracted verbatim from CrashFrequencyDetector and MinisApp; the
 * [T-perf-launch-scan-async] / [T-perf-launch-probe-async] constraints moved
 * with the code they govern.)
 */
internal object LaunchPhaseProbes {

    /**
     * [T-perf-launch-scan-async] Completes once the launch scan has fully
     * landed (safe-mode flag, CrashFrequencyDetector.pendingShareFiles and
     * the force-home pref all written). Starts out already completed so an
     * [awaitLaunchScan] with no scan running is a no-op rather than a hang.
     */
    @Volatile
    private var launchScanDone: CompletableDeferred<Unit> =
        CompletableDeferred<Unit>().apply { complete(Unit) }

    /**
     * [T-perf-launch-scan-async] T-android-crash-freq-share: scan
     * filesDir/logs/ for crash-*.log + native-crash-*.log files touched in
     * the last hour; THRESHOLD+ stashes a share list for MainActivity and
     * flips safe-mode. Dispatched to IO from the very top of onCreate so
     * the directory walk hides behind the prime/probe work below; the
     * safe-mode branch awaits the scan before reading the flag, so the
     * short-circuit still decides on a final result, never an in-flight one.
     *
     * [T-perf-launch-scan-async] Run [CrashFrequencyDetector.checkAtLaunch] on
     * [scope] (the app's IO scope) instead of the main thread. The constraint
     * that makes this safe: the synchronous safe-mode consumer in
     * MinisApp.onCreate must call [awaitLaunchScan] before reading
     * CrashFrequencyDetector.isSafeMode — that blocking hand-off, not this
     * call, is what sequences the flag, the share list and the force-home
     * pref ahead of every downstream reader.
     */
    fun startLaunchScan(app: Application, scope: CoroutineScope) {
        val done = CompletableDeferred<Unit>()
        launchScanDone = done
        scope.launch {
            try {
                CrashFrequencyDetector.checkAtLaunch(app)
            } finally {
                done.complete(Unit)
            }
        }
    }

    /**
     * [T-perf-launch-scan-async] Block the caller until the background scan
     * has landed. Bounded by the same disk work the inline scan used to block
     * on, and guarantees every reader after Application.onCreate returns —
     * MainActivity's pendingShareFiles check, the onResume share prompt,
     * AppNavigation's force-home pref read — sees the final state.
     *
     * A wedged filesystem must not turn a crash-recovery probe into a launch
     * ANR, so the wait is bounded; timeout takes the conservative "not in safe
     * mode" path and lets the scan finish in the background.
     */
    fun awaitLaunchScan(): Boolean {
        val done = launchScanDone
        if (done.isCompleted) return true
        val landed = runBlocking {
            try {
                kotlinx.coroutines.withTimeoutOrNull(LAUNCH_PROBE_TIMEOUT_MS) { done.await() } != null
            } catch (_: Throwable) {
                false
            }
        }
        // The timeout branch takes the conservative "not in safe mode" path
        // and lets the scan finish in the background — but that decision is
        // invisible unless it is logged. Distinguish it from a completed scan
        // so a post-hoc investigation can tell "probe gave up" from "probe
        // decided".
        if (!landed) {
            Log.w(
                TAG,
                "launch scan did not finish within ${LAUNCH_PROBE_TIMEOUT_MS}ms — " +
                    "continuing without its verdict (scan keeps running in the background)",
            )
        }
        return landed
    }

    @Volatile
    private var dbVersionProbe: CompletableDeferred<DatabaseVersionGuard.Decision>? = null

    /**
     * [T-perf-launch-probe-async] Kick the read-only schema-version probe
     * to IO immediately; the assignment near the end of onCreate blocks on
     * its completion at the pre-async program position, so the decision
     * MainActivity reads, and the "probe before Room's first open"
     * ordering, are unchanged.
     */
    fun startDbVersionProbe(app: Application, scope: CoroutineScope) {
        dbVersionProbe = DatabaseVersionGuard.evaluateAsync(app, scope)
    }

    /**
     * [T-perf-launch-probe-async] The probe was dispatched to IO at the
     * top of onCreate; blocking here (usually a no-op — the probe has
     * long since finished behind the init work above) keeps the
     * assignment at its pre-async position: dbVersionDecision is final
     * before MainActivity reads it, and the probe still completes before
     * Room's first open in MinisAppAsyncInit.startWarmup. An exception
     * surfaces from await() exactly where the inline call would have
     * thrown.
     */
    fun awaitDbVersionProbe(): DatabaseVersionGuard.Decision {
        val probe = checkNotNull(dbVersionProbe)
        var timedOut = false
        val decision = runBlocking {
            try {
                kotlinx.coroutines.withTimeoutOrNull(LAUNCH_PROBE_TIMEOUT_MS) { probe.await() }
                    ?: run {
                        timedOut = true
                        DatabaseVersionGuard.Decision.PROCEED
                    }
            } catch (_: Throwable) {
                DatabaseVersionGuard.Decision.PROCEED
            }
        }
        // PROCEED can mean "probe evaluated cleanly" or "probe timed out and
        // we gave up" — record which, so a later Room open failure is not
        // misread as a verdict the probe actually made.
        if (timedOut) {
            Log.w(
                TAG,
                "db-version probe did not finish within ${LAUNCH_PROBE_TIMEOUT_MS}ms — " +
                    "assuming PROCEED without its verdict",
            )
        }
        return decision
    }

    /** Bound launch-critical IO hand-offs so a wedged volume cannot ANR onCreate. */
    private const val LAUNCH_PROBE_TIMEOUT_MS = 5_000L

    private const val TAG = "LaunchPhaseProbes"
}
