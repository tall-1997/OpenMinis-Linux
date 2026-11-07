package com.openminis.app.vscreen

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.hardware.display.VirtualDisplayConfig
import android.media.Image
import android.media.ImageReader
import android.os.Build
import android.view.Display
import android.view.Surface
import androidx.annotation.RequiresApi
import com.openminis.app.logging.AppLogger
import com.openminis.app.vscreen.VirtualScreenManager.DisplayHandle
import com.openminis.app.vscreen.VirtualScreenManager.DisplayHandleFactory

/**
 * [T-android-vscreen-vd] VirtualDisplay runtime: creation through the modern
 * config API, plus the lifecycle the platform reports back.
 *
 * Why this file exists (Android reference, API 34+):
 *  - `DisplayManager.createVirtualDisplay(String, int, int, int, Surface, int,
 *    Callback, Handler)` is deprecated since 34; the replacement is
 *    [VirtualDisplayConfig] + `createVirtualDisplay(config, handler, callback)`.
 *    The config makes every property explicit (name, size, density, flags,
 *    surface) instead of relying on a positional argument list.
 *  - [VirtualDisplay.Callback] tells us when the platform pauses, resumes or
 *    stops our display. A stopped display is dead: its surface receives no more
 *    frames and every later capture silently returns null. Without the callback
 *    the tool layer kept reporting a healthy display that could never produce a
 *    frame again.
 *
 * The state below is written on the callback thread and read by tool calls, so
 * the two volatile fields are the only cross-thread contract.
 */
enum class VdLifecycle { RUNNING, PAUSED, STOPPED }

/** Callback-written lifecycle of one display; read by the manager and the tool. */
internal class VirtualDisplayState {
    @Volatile
    var lifecycle: VdLifecycle = VdLifecycle.RUNNING
        private set

    @Volatile
    var lastEventAtMs: Long = 0L
        private set

    fun mark(next: VdLifecycle) {
        lifecycle = next
        lastEventAtMs = System.currentTimeMillis()
    }
}

/** Physical size + density of the device's main display. */
internal data class DisplaySpec(val width: Int, val height: Int, val dpi: Int)

internal object VirtualDisplayRuntime {
    private const val TAG = "VirtualDisplayRuntime"

    /**
     * The MAIN display's real mode, so a virtual screen created without
     * explicit geometry matches the phone instead of an arbitrary 1080x1920.
     * `Display.getMode()` is API 23+ (minSdk is 26) and reports the physical
     * panel size, unlike the app-window metrics a window-scoped API returns.
     */
    fun physicalSpec(context: Context): DisplaySpec? = runCatching {
        val dm = context.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            ?: return@runCatching null
        val display = dm.getDisplay(Display.DEFAULT_DISPLAY) ?: return@runCatching null
        val mode = display.mode ?: return@runCatching null
        if (mode.physicalWidth <= 0 || mode.physicalHeight <= 0) return@runCatching null
        DisplaySpec(
            width = mode.physicalWidth,
            height = mode.physicalHeight,
            dpi = context.resources.displayMetrics.densityDpi,
        )
    }.getOrNull()

    /**
     * Create the display and wire [state] to the platform callback.
     *
     * API 34+ takes the config; older releases keep the positional overload so
     * the same callback still reports lifecycle. A null result means the device
     * refused the display (ROM policy / display limit) — the caller closes the
     * reader it built for the surface.
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
        val callback = object : VirtualDisplay.Callback() {
            override fun onPaused() {
                state.mark(VdLifecycle.PAUSED)
                AppLogger.info(TAG, "display paused: $label")
            }

            override fun onResumed() {
                state.mark(VdLifecycle.RUNNING)
                AppLogger.info(TAG, "display resumed: $label")
            }

            override fun onStopped() {
                state.mark(VdLifecycle.STOPPED)
                AppLogger.warning(TAG, "display stopped by the platform: $label")
            }
        }
        return runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                Api34.create(dm, label, width, height, dpi, surface, flags, callback)
            } else {
                @Suppress("DEPRECATION")
                dm.createVirtualDisplay(label, width, height, dpi, surface, flags, callback, null)
            }
        }.onFailure {
            AppLogger.warning(TAG, "createVirtualDisplay($label) failed: ${it.message}")
        }.getOrNull()
    }

    /** API-34 config path, kept in its own object so old releases never load it. */
    @RequiresApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    private object Api34 {
        fun create(
            dm: DisplayManager,
            label: String,
            width: Int,
            height: Int,
            dpi: Int,
            surface: Surface,
            flags: Int,
            callback: VirtualDisplay.Callback,
        ): VirtualDisplay? {
            val config = VirtualDisplayConfig.Builder(label, width, height, dpi)
                .setFlags(flags)
                .setSurface(surface)
                .build()
            return dm.createVirtualDisplay(config, null, callback)
        }
    }

    /**
     * Why a capture cannot work at [lifecycle], or null when it may proceed.
     * PAUSED still holds the last frame, so a screenshot there is legitimate.
     */
    fun captureBlockedReason(lifecycle: VdLifecycle): String? = when (lifecycle) {
        VdLifecycle.STOPPED ->
            "the system stopped this virtual display (VirtualDisplay.Callback.onStopped) - " +
                "its surface is dead and no frame can arrive again; destroy it and create a new one"
        else -> null
    }

    /** Model-facing state note for list/create output, or null while healthy. */
    fun stateNote(lifecycle: VdLifecycle): String? = when (lifecycle) {
        VdLifecycle.RUNNING -> null
        VdLifecycle.PAUSED ->
            "paused by the system: the last frame is still readable, but no new frames arrive until it resumes"
        VdLifecycle.STOPPED ->
            "stopped by the system: the display is dead and must be recreated"
    }

    // ── root-assisted geometry ────────────────────────────────────────────

    private val WM_SIZE = Regex("""^(Physical|Override) size:\s*(\d+)x(\d+)\s*$""")
    private val WM_DENSITY = Regex("""^(Physical|Override) density:\s*(\d+)\s*$""")

    /**
     * `wm size` output → the size the USER sees: an override wins over the
     * panel, because a display running at the override must be matched at the
     * override or apps get the wrong metrics.
     */
    fun parseWmSize(output: String): Pair<Int, Int>? {
        var physical: Pair<Int, Int>? = null
        var override: Pair<Int, Int>? = null
        for (line in output.lineSequence()) {
            val m = WM_SIZE.find(line.trim()) ?: continue
            val value = m.groupValues[2].toInt() to m.groupValues[3].toInt()
            if (m.groupValues[1] == "Override") override = value else physical = value
        }
        return override ?: physical
    }

    /** `wm density` output → the override density when one is set. */
    fun parseWmDensity(output: String): Int? {
        var physical: Int? = null
        var override: Int? = null
        for (line in output.lineSequence()) {
            val m = WM_DENSITY.find(line.trim()) ?: continue
            val value = m.groupValues[2].toInt()
            if (m.groupValues[1] == "Override") override = value else physical = value
        }
        return override ?: physical
    }

    /**
     * Geometry for a display whose caller omitted it: [physicalSpec] first,
     * refined by `wm size` / `wm density` when the shell (Shizuku or host su,
     * i.e. KSU/Magisk) answers. `Display.getMode()` only reports the panel;
     * `wm` also reports a user-set resolution/density override, which is what
     * the phone actually renders at. The shell is best-effort — no root just
     * means the panel spec is used.
     */
    fun specWithRootOverride(context: Context, shell: (String) -> String?): DisplaySpec? {
        val base = physicalSpec(context)
        val out = runCatching { shell("wm size; wm density") }.getOrNull()
        val size = out?.let(::parseWmSize)
        val dpi = out?.let(::parseWmDensity)
        if (size == null && dpi == null && base == null) return null
        return DisplaySpec(
            width = size?.first ?: base?.width ?: 0,
            height = size?.second ?: base?.height ?: 0,
            dpi = dpi ?: base?.dpi ?: 0,
        )
    }

    /**
     * The production [DisplayHandleFactory]: an ImageReader surface attached AT
     * CREATE (a surface-less display is put to sleep by the platform and drops
     * every injected input event), a lifecycle state wired to the callback, and
     * one release path that closes both.
     */
    fun handleFactory(app: Context): DisplayHandleFactory =
        DisplayHandleFactory { label, w, h, dpi ->
            val dm = app.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
                ?: return@DisplayHandleFactory null
            val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            val state = VirtualDisplayState()
            val vd = create(
                dm, label, w, h, dpi, reader.surface,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY, state,
            )
            if (vd == null) {
                runCatching { reader.close() }
                null
            } else {
                object : DisplayHandle {
                    override val displayId: Int = vd.display.displayId
                    override val lifecycle: VdLifecycle get() = state.lifecycle
                    override fun acquireLatestFrame(): Image? =
                        runCatching { reader.acquireLatestImage() }.getOrNull()
                    override fun release() {
                        runCatching { vd.release() }
                        runCatching { reader.close() }
                    }
                }
            }
        }
}
