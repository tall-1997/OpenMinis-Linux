package com.openminis.app.quick

import android.view.accessibility.AccessibilityNodeInfo
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.logging.AppLogger

/**
 * [E-batch-quick-invocation] "What is on the screen right now", captured at
 * the moment the floating ball is double-tapped — BEFORE the quick panel
 * covers the screen (once our own window is frontmost the accessibility tree
 * contains our panel, not the app the user was reading).
 *
 * Text is read through the existing [MinisAccessibilityService]; when the
 * service is disabled the capture yields null and the caller degrades to
 * opening the panel with a generic "look at my screen" prompt pre-filled.
 */
object ScreenContext {

    private const val TAG = "ScreenContext"

    @Volatile
    var text: String? = null
        private set

    @Volatile
    var sourcePackage: String? = null
        private set

    val hasCapture: Boolean get() = text != null

    /** True when the accessibility service is connected and can be read. */
    fun isAvailable(): Boolean = MinisAccessibilityService.getInstance() != null

    /**
     * Snapshot the current screen text via accessibility. Called from the
     * floating-ball double-tap path while the ball is still the only overlay.
     *
     * Everything that can throw inside the a11y stack (stale nodes, windows
     * closing mid-walk, guards) is swallowed: a failed capture must degrade to
     * "no context", never crash the ball's touch handler.
     */
    fun captureFromAccessibility() {
        val svc = MinisAccessibilityService.getInstance()
        if (svc == null) {
            AppLogger.info(TAG, "no accessibility service — screen context unavailable")
            clear()
            return
        }
        try {
            val roots = svc.rootNodes()
            val (fgPackage, _) = svc.foregroundPackage()
            val adapter = roots.mapNotNull { it?.toNode() }
            // Never let our own overlay labels (ball/panel) leak into the prompt.
            val collected = ScreenTextWalker().collect(adapter, ownPackage = svc.packageName)
            if (collected.isBlank()) {
                clear()
            } else {
                text = collected
                sourcePackage = fgPackage
            }
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "screen capture failed: ${t.message}")
            clear()
        }
    }

    /** Drop the capture (panel destroyed / consumed). */
    fun clear() {
        text = null
        sourcePackage = null
    }

    /**
     * Adapter from [AccessibilityNodeInfo] to the walker's facade. Children
     * are resolved lazily — a large screen only pays for the nodes the walk
     * actually reaches, and the walk is capped anyway.
     */
    private fun AccessibilityNodeInfo.toNode(): ScreenTextWalker.Node {
        val outer = this
        return object : ScreenTextWalker.Node {
            override val packageName: String? = runCatching { outer.packageName?.toString() }.getOrNull()
            override val text: String? = runCatching { outer.text?.toString() }.getOrNull()
            override val contentDescription: String? =
                runCatching { outer.contentDescription?.toString() }.getOrNull()
            override val children: List<ScreenTextWalker.Node> by lazy {
                val out = ArrayList<ScreenTextWalker.Node>()
                for (i in 0 until outer.childCount) {
                    runCatching { outer.getChild(i) }.getOrNull()?.let { out.add(it.toNode()) }
                }
                out
            }
        }
    }
}
