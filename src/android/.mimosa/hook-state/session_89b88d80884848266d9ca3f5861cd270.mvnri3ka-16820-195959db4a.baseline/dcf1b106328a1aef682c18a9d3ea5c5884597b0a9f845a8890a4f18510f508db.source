package com.openminis.app.crash

import android.app.Application
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
     */
    fun awaitLaunchScan() {
        val done = launchScanDone
        if (!done.isCompleted) runBlocking { done.await() }
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
        return runBlocking { probe.await() }
    }
}
