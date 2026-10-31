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

    private val sp: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var panelSessionId: String?
        get() = sp.getString(KEY_PANEL_SESSION, null)
        set(value) = sp.edit().putString(KEY_PANEL_SESSION, value).apply()

    var panelUsedAtMs: Long
        get() = sp.getLong(KEY_PANEL_AT, 0L)
        set(value) = sp.edit().putLong(KEY_PANEL_AT, value).apply()

    /** Record that the panel sent into [sessionId] right now (one write). */
    fun markPanelSession(sessionId: String) {
        sp.edit()
            .putString(KEY_PANEL_SESSION, sessionId)
            .putLong(KEY_PANEL_AT, System.currentTimeMillis())
            .apply()
    }

    /** Refresh only the timestamp for an already-recorded session id. */
    fun touchPanelSession() {
        if (sp.getString(KEY_PANEL_SESSION, null) != null) {
            sp.edit().putLong(KEY_PANEL_AT, System.currentTimeMillis()).apply()
        }
    }

    companion object {
        const val PREFS_NAME = "minis_quick"
        const val KEY_PANEL_SESSION = "panel_session_id"
        const val KEY_PANEL_AT = "panel_session_at"
    }
}
