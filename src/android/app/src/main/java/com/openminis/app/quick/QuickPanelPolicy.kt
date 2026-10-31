package com.openminis.app.quick

/**
 * [E-batch-quick-invocation] Pure decision logic for the quick-invocation
 * surface (half-screen panel, QS tile). Everything here is plain JVM code —
 * no Android types — so the share-text extraction, the panel-session reuse
 * rule and the notification helpers can be unit-tested without a device
 * (see QuickPanelPolicyTest).
 *
 * The stateful caller (QuickActivity) owns the clocks; this object only
 * decides.
 */
object QuickPanelPolicy {

    /**
     * A plain re-open of the panel within this window shows the previous
     * panel session's outcome instead of a blank composer — mirrors the
     * reference harness's "recent panel session" rule.
     */
    const val RECENT_PANEL_MS: Long = 10 * 60_000L

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
