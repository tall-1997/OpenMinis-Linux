package com.openminis.app.vscreen

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.os.Build
import android.view.Surface

/**
 * [T-android-vscreen3] Platform lifecycle of one virtual display's surface,
 * as told by [VirtualDisplay.Callback]. Fakes and handles that never receive
 * platform callbacks default to [RUNNING].
 */
enum class VdLifecycle { RUNNING, PAUSED, STOPPED }

/**
 * [T-android-vscreen3] Thread-safe lifecycle holder: the callback arrives on
 * an arbitrary framework thread, tool calls read it on the caller thread.
 */
class VirtualDisplayState {
    @Volatile var lifecycle: VdLifecycle = VdLifecycle.RUNNING
        private set

    @Volatile var lastEventAtMs: Long = 0L
        private set

    fun mark(next: VdLifecycle, nowMs: Long = System.currentTimeMillis()) {
        lifecycle = next
        lastEventAtMs = nowMs
    }
}

/**
 * [T-android-vscreen3] NATIVE-tier VirtualDisplay creation and lifecycle
 * reading, extracted from the manager's real factory so the manager keeps
 * registry + tier bookkeeping only.
 *
 * Two responsibilities:
 *  - creation: on API 34+ the platform replaced the deprecated positional
 *    overload with [VirtualDisplayConfig] — this is the only path that
 *    surfaces why a creation failed instead of a bare null; older builds
 *    keep the positional call. Both paths now receive a real lifecycle
 *    callback instead of the previous `null`, which is what powers the
 *    dead-screen cleanup in the screenshot lane (a platform-stopped display
 *    is destroyed and re-created rather than re-queried forever).
 *  - honest state notes: [captureBlockedReason] / [stateNote] turn the raw
 *    lifecycle into agent-facing one-liners, with [PAUSED] deliberately not
 *    blocking capture (frames are still available while paused) and only
 *    [STOPPED] meaning the surface is unrecoverable.
 */
object VirtualDisplayRuntime {

    /**
     * Create the virtual display through the best path this OS offers, with
     * the lifecycle callback wired into [state] on both paths. Returns null
     * when the platform refused (ROM policy / display limit).
     */
    fun create(
        dm: DisplayManager,
        label: String,
        width: Int,
        height: Int,
        dpi: Int,
        surface: Surface,
        flags: Int,
        state: VirtualDisplayState,
    ): VirtualDisplay? {
        val callback = callbackInto(state)
        return if (Build.VERSION.SDK_INT >= 34) {
            val config = VirtualDisplayConfig.Builder(label, width, height, dpi)
                .setFlags(flags)
                .setSurface(surface)
                .build()
            // Signature order is (config, Handler, Callback) — Handler first.
            dm.createVirtualDisplay(config, null, callback)
        } else {
            @Suppress("DEPRECATION")
            dm.createVirtualDisplay(label, width, height, dpi, surface, flags, callback, null)
        }
    }

    /** Wire [state] so every platform callback lands as a lifecycle mark. */
    fun callbackInto(state: VirtualDisplayState): VirtualDisplay.Callback =
        object : VirtualDisplay.Callback() {
            override fun onPaused() = state.mark(VdLifecycle.PAUSED)
            override fun onResumed() = state.mark(VdLifecycle.RUNNING)
            override fun onStopped() = state.mark(VdLifecycle.STOPPED)
        }

    /**
     * Why a capture must be skipped for a display in [lifecycle], or null
     * when the read path may proceed. Only STOPPED blocks: the surface is
     * gone, no frame can ever arrive again.
     */
    fun captureBlockedReason(lifecycle: VdLifecycle): String? =
        when (lifecycle) {
            VdLifecycle.STOPPED ->
                "the platform stopped this display (its surface was released)"
            VdLifecycle.RUNNING, VdLifecycle.PAUSED -> null
        }

    /** One agent-facing line for a non-running display, or null when healthy. */
    fun stateNote(lifecycle: VdLifecycle): String? =
        when (lifecycle) {
            VdLifecycle.RUNNING -> null
            VdLifecycle.PAUSED ->
                "paused by the platform (frames may be stale until it resumes)"
            VdLifecycle.STOPPED ->
                "stopped by the platform (surface released) — recreate the screen"
        }
}
