package com.openminis.app.quick

import android.content.Context
import android.content.SharedPreferences

/**
 * [E-batch-quick-invocation] Self-owned preferences for the quick-invocation
 * surface. Deliberately NOT wired into the app's settings repository: the
 * panel's session-reuse timestamps live in this one file so the quick/
 * package stays self-contained (no MinisApp edits).
 *
 * Keys:
 *  - [KEY_PANEL_SESSION] / [KEY_PANEL_AT] — the session the panel last sent
 *    into and when; drives the 10-minute reuse window in
 *    [QuickPanelPolicy.shouldReusePanelSession].
 */
class QuickPrefs(context: Context) {

    private val sp: com.openminis.app.config.PrefsHandle =
        com.openminis.app.config.Prefs.file(PREFS_NAME)

    var panelSessionId: String?
        get() = sp.stringOrNull(KEY_PANEL_SESSION)
        set(value) { sp.set(KEY_PANEL_SESSION, value) }

    var panelUsedAtMs: Long
        get() = sp.long(KEY_PANEL_AT, 0L)
        set(value) { sp.set(KEY_PANEL_AT, value) }

    /** Record that the panel sent into [sessionId] right now (one write). */
    fun markPanelSession(sessionId: String) {
        // session + 时间戳成对：只写 session 会让「多久之前」显示错的旧值
        sp.write {
            putString(KEY_PANEL_SESSION, sessionId)
            putLong(KEY_PANEL_AT, System.currentTimeMillis())
        }
    }

    /** Refresh only the timestamp for an already-recorded session id. */
    fun touchPanelSession() {
        if (sp.stringOrNull(KEY_PANEL_SESSION) != null) {
            sp.set(KEY_PANEL_AT, System.currentTimeMillis())
        }
    }

    companion object {
        const val PREFS_NAME = "minis_quick"
        const val KEY_PANEL_SESSION = "panel_session_id"
        const val KEY_PANEL_AT = "panel_session_at"
    }
}
