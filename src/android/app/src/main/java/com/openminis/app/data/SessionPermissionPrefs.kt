package com.openminis.app.data

import android.content.Context
import com.openminis.app.security.SessionPermissionMode

/**
 * [T-perm-inherit] The last permission mode the user picked, remembered across
 * sessions.
 *
 * Two things were broken about session permissions on Android:
 *
 *1. A brand-new chat always started at the shipped default, so a user who
 * deliberately chose a mode for their work had to re-pick it on every new
 * conversation (ZCode inherits the chosen mode for new sessions in the same
 * project).
 *2. The per-session write was a `viewModelScope.launch`, so leaving the chat
 * right after tapping the pill cancelled the write and the row kept the old
 * mode (see [com.openminis.app.ui.chat.ChatViewModel.setSessionPermissionMode],
 * which now writes on an application-scoped dispatcher instead).
 *
 * This store holds only the DEFAULT for chats that carry no explicit choice;
 * a session row's own `permission_mode` always wins.
 */
internal object SessionPermissionPrefs {
    private const val PREFS = "session-permission"
    private const val KEY_MODE = "lastMode"

    @Volatile private var prefs: android.content.SharedPreferences? = null

    fun prime(context: Context) {
        prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /** The mode a chat with no stored choice starts in. Unprimed reads fall back to the safest tier. */
    fun current(): SessionPermissionMode =
        SessionPermissionMode.fromStored(prefs?.getString(KEY_MODE, null))

    /** Remember the user's choice so the next new chat starts where they left off. */
    fun remember(mode: SessionPermissionMode) {
        prefs?.edit()?.putString(KEY_MODE, mode.toStoredToken())?.apply()
    }

    /** Tests: drop the stored choice. */
    internal fun clearForTest() {
        prefs?.edit()?.remove(KEY_MODE)?.apply()
    }
}
