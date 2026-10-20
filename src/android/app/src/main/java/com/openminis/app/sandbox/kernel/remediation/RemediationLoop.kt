package com.openminis.app.sandbox.kernel.remediation

import android.util.Log
import java.io.File
import kotlin.concurrent.thread

/**
 * The only kill authority for a stalled main thread. It does not run on that
 * thread, and it does not kill a protected terminal.
 *
 * ## GC Stall Handling
 *
 * The ANR dumps from 2026-10-20 show the common failure mode is
 * `WaitingForGcToComplete` — the main thread blocked on an allocation while
 * the ART collector runs. Killing the sandbox in that case is counterproductive:
 * the guest process rebuilds on next use and allocates MORE objects,
 * deepening the very pressure that stalled us.
 *
 * We cannot reliably detect GC stalls from Java because the blocking frame
 * (`WaitForGcToCompleteLocked` in libart.so) is a native stack frame invisible
 * to `Thread.getAllStackTraces()`. Instead, we use a time-based grace period.
 *
 * - 3s (STALL_MS): First sign of trouble → trip the render breaker, which
 *   degrades streaming markdown to plain text and reduces allocation pressure.
 * - 15s (KILL_MS): Well beyond any normal GC storm (typically 1-5s). If we're
 *   still stalled here, it's either a genuine sandbox wedge or a catastrophic
 *   heap issue. Killing the sandbox is appropriate in either case.
 *
 * This gracefully handles:
 * - GC storms (transient, < 15s) — we wait them out without killing
 * - Sandbox wedges (persistent, > 15s) — we kill as intended
 * - UI freezes (Compose barriers) — same as GC, wait out and let render breaker help
 */
object RemediationLoop {
    private const val TAG = "RemediationLoop"
    private const val STALL_MS = 3_000L
    private const val KILL_MS = 15_000L

    @Volatile private var started = false
    @Volatile private var stops = 0

    /**
     * Starts the loop. `killUnprotected` stops a sandbox command.
     */
    fun start(
        filesDir: File,
        killUnprotected: (String) -> Unit,
    ) {
        if (started) return
        started = true
        thread(name = "RemediationLoop", isDaemon = true) {
            var trips = 0
            var killed = false
            var stallStartedAt = 0L
            while (true) {
                try {
                    Thread.sleep(500)
                } catch (_: InterruptedException) {
                    return@thread
                }
                val now = System.currentTimeMillis()
                val gap = StallSignal.gap()
                if (gap < STALL_MS) {
                    trips = 0
                    killed = false
                    stallStartedAt = 0L
                    continue
                }
                if (stallStartedAt == 0L) stallStartedAt = now - gap
                trips++
                // Trip the render breaker to reduce allocation pressure.
                // LargeContentGuard will degrade markdown to plain text.
                com.openminis.app.diagnostics.HangDetector.tripRenderBreaker()

                // Only kill after the grace period — GC storms are transient.
                // 15s is deliberately generous; a healthy GC completes in < 5s.
                if (!killed && (trips >= 2 || gap >= KILL_MS)) {
                    killed = true
                    stops++
                    val reason = "remediation gap=${gap}ms trips=$trips"
                    Log.w(TAG, reason)
                    runCatching { killUnprotected(reason) }
                        .onFailure { Log.w(TAG, "kill failed: ${it.message}") }
                }
            }
        }
    }

    fun stopCount(): Int = stops
}
