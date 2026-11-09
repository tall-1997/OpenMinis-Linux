package com.openminis.app.data.repository

import com.openminis.app.data.db.ChatDao

/**
 * [T-android-session-last-message-live-tool-call] Update ONLY the session's
 * `last_message` preview (and `updated_at`) from an in-progress assistant
 * turn's parts_json — WITHOUT inserting a message row. The agent loop
 * persists the authoritative assistant row only at turn end (after tools
 * execute); during a long tool call the session list would otherwise show a
 * stale preview (or "No messages yet" for a turn with no prior text). This
 * pushes the live tool-call summary / partial text into the list the moment
 * the model emits it, mirroring how iOS overlays the live VM's last message.
 *
 * Uses the same [PreviewText.extractTextPreview] as appendMessage, so a
 * text-only turn shows its text and a tool-only turn shows the tool summary.
 * No-op when the payload yields no preview (avoids overwriting a good
 * preview with null).
 */
internal suspend fun updateSessionPreviewIn(
    dao: ChatDao,
    sessionId: String,
    partsJson: String,
) {
    val preview = PreviewText.extractTextPreview(partsJson) ?: return
    dao.updateLastMessage(sessionId, preview, System.currentTimeMillis())
}

/**
 * [T-session-list-stub-preview] One-shot repair for sessions whose
 * `last_message` column still holds the offload placeholder written by the
 * pre-fix appendMessage ("body stored (N bytes)" / "body omitted; store
 * failed"). Rebuilds each affected session's preview from its last message
 * row's `preview` cell — the same source the fixed write path uses — and
 * clears the column when nothing human survives (a tool-result last row
 * keeps no preview by design, and the placeholder must not survive either).
 *
 * Cheap to run: the LIKE scan only matches sessions that still carry a
 * stub, and a repaired session stops matching. Fired once per session-list
 * entry; the first collect reads the repaired rows because the repair lands
 * before the observer starts.
 *
 * Returns the number of sessions rewritten (0 when the store is clean).
 */
internal suspend fun repairStubSessionPreviewsIn(dao: ChatDao): Int {
    val tails = dao.stubPreviewTails()
    if (tails.isEmpty()) return 0
    for (tail in tails) {
        dao.repairLastMessage(
            tail.sessionId,
            PreviewText.sessionListPreview(tail.partsJson, tail.preview),
        )
    }
    return tails.size
}
