package com.openminis.app.quick

/**
 * [E-batch-quick-invocation] Pure decision logic for the quick-invocation
 * surface (half-screen panel, floating ball, QS tile). Everything here is
 * plain JVM code — no Android types — so the gesture windows, the share-text
 * extraction and the panel-session reuse rule can be unit-tested without a
 * device (see QuickPanelPolicyTest).
 *
 * The stateful callers (QuickActivity / FloatingBallService) own the clocks
 * and the pixel math inputs; this object only decides.
 */
object QuickPanelPolicy {

    /**
     * A plain re-open of the panel within this window shows the previous
     * panel session's outcome instead of a blank composer — mirrors the
     * reference harness's "recent panel session" rule.
     */
    const val RECENT_PANEL_MS: Long = 10 * 60_000L

    /**
     * Double-tap detection window for the floating ball. Matches
     * ViewConfiguration.getDoubleTapTimeout() (300 ms) but kept as a constant
     * so tests do not need a Robolectric runtime.
     */
    const val DOUBLE_TAP_WINDOW_MS: Long = 300L

    /**
     * Movement beyond this many pixels between ACTION_DOWN and ACTION_UP
     * classifies the gesture as a drag, not a tap. Mirrors the reference
     * floating ball's 12 px threshold.
     */
    const val DRAG_SLOP_PX: Int = 12

    /** Upper bound for accessibility-captured screen text fed into a prompt. */
    const val MAX_SCREEN_TEXT_CHARS: Int = 4000

    /** Result of classifying one floating-ball touch stream. */
    enum class Gesture { TAP, DRAG }

    /**
     * True when the panel should reopen the session it last sent into.
     *
     * Rules:
     *  - no recorded panel session → false (fresh empty panel),
     *  - still inside the reuse window → true,
     *  - outside the window → false (the previous conversation has aged out).
     */
    fun shouldReusePanelSession(
        lastSessionId: String?,
        lastUsedAtMs: Long,
        nowMs: Long,
        windowMs: Long = RECENT_PANEL_MS,
    ): Boolean {
        if (lastSessionId.isNullOrBlank()) return false
        if (lastUsedAtMs <= 0L) return false
        return nowMs - lastUsedAtMs in 0 until windowMs
    }

    /**
     * Extract the text a share intent carried, into the composer.
     *
     * Senders disagree on where the payload lives: browsers put the page
     * title in EXTRA_SUBJECT and the URL in EXTRA_TEXT, plain-text shares put
     * everything in EXTRA_TEXT. Both non-blank values are joined in order,
     * de-duplicated (some senders set both to the same string), and trimmed.
     */
    fun extractShareText(subject: String?, text: String?): String {
        val parts = ArrayList<String>(2)
        subject?.trim()?.takeIf { it.isNotEmpty() }?.let { parts.add(it) }
        text?.trim()?.takeIf { it.isNotEmpty() }?.let { if (parts.singleOrNull() != it) parts.add(it) }
        return parts.joinToString("\n")
    }

    /**
     * Classify a floating-ball touch stream from its endpoints. Between
     * [DRAG_SLOP_PX] on both axes it is a tap (open the panel); anything
     * further is a drag (move + edge-dock, never opens).
     */
    fun classifyGesture(downRawX: Float, downRawY: Float, upRawX: Float, upRawY: Float): Gesture {
        val dx = Math.abs(upRawX - downRawX)
        val dy = Math.abs(upRawY - downRawY)
        return if (dx < DRAG_SLOP_PX && dy < DRAG_SLOP_PX) Gesture.TAP else Gesture.DRAG
    }

    /**
     * True when [currentEventTimeMs] arrives within [windowMs] of
     * [lastTapEventTimeMs] — i.e. the second tap of a double tap. A
     * [lastTapEventTimeMs] of 0 (or negative — no pending tap) is never a
     * match, so a stale timestamp cannot fire the screen-capture path.
     */
    fun isWithinDoubleTapWindow(lastTapEventTimeMs: Long, currentEventTimeMs: Long, windowMs: Long = DOUBLE_TAP_WINDOW_MS): Boolean {
        if (lastTapEventTimeMs <= 0L) return false
        val delta = currentEventTimeMs - lastTapEventTimeMs
        return delta in 0 until windowMs
    }

    /**
     * Where the ball's left edge should sit after a drop: it snaps to the
     * nearer horizontal edge, [marginPx] in from the glass. The decision is
     * made on the ball's centre so a half-off-screen ball still docks
     * predictably.
     */
    fun dockX(candidateX: Int, ballWidthPx: Int, screenWidthPx: Int, marginPx: Int): Int {
        if (ballWidthPx <= 0 || screenWidthPx <= 0) return marginPx
        val center = candidateX + ballWidthPx / 2
        return if (center < screenWidthPx / 2) marginPx else screenWidthPx - ballWidthPx - marginPx
    }

    /** Clamp the ball's vertical position so it never leaves the screen. */
    fun clampY(candidateY: Int, ballHeightPx: Int, screenHeightPx: Int): Int {
        if (ballHeightPx <= 0 || screenHeightPx <= 0) return candidateY
        val max = (screenHeightPx - ballHeightPx).coerceAtLeast(0)
        return candidateY.coerceIn(0, max)
    }

    /**
     * Wrap captured screen text into the preamble prepended to the user's
     * prompt when the panel was opened via the ball's double tap.
     *
     * Returns null when there is nothing to add (no capture, blank text) so
     * the caller can send the user's text verbatim.
     */
    fun screenContextPreamble(screenText: String?, sourcePackage: String?): String? {
        val body = screenText?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val app = sourcePackage?.takeIf { it.isNotBlank() } ?: "unknown app"
        val clipped = if (body.length > MAX_SCREEN_TEXT_CHARS) body.take(MAX_SCREEN_TEXT_CHARS) else body
        return buildString {
            append("[The user double-tapped the Minis floating ball while looking at ")
            append(app)
            append(". Screen content:]\n")
            append(clipped)
        }
    }

    /** True when the mime type (intent type or ContentResolver-resolved) is an image. */
    fun isImageMime(mimeType: String?): Boolean = mimeType?.startsWith("image/") == true

    /**
     * Statuses from the headless runner that mean "the run did NOT finish
     * cleanly" — the completion notification's icon/text branch on this.
     */
    fun isFailedStatus(status: String?): Boolean = status == null || status == "Error" || status == "Timeout"

    /** Truncate a model reply into the notification preview, with a fallback. */
    fun buildPreview(responseText: String?, maxChars: Int = 200, fallback: String = "(no response)"): String =
        (responseText ?: "").take(maxChars).ifBlank { fallback }
}
