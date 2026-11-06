package com.openminis.app.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * [T-wave-e-unread-dot] Which chats carry a reply the user has not seen.
 *
 * Minis has no `unread` column (the Wave-E brief assumed one); this registry
 * is the minimal equivalent that needs no schema work:
 *
 *  - `lastReadAtMs` per session, persisted in prefs — stamped when the user
 *    has the chat open ([markRead]).
 *  - A session is UNREAD when its latest activity ([ChatSessionEntity.updatedAt],
 *    bumped by any appended message) is newer than its stamp. Derived, so a
 *    killed process loses nothing and there is no second write path.
 *  - [SessionActivityTracker]'s completion hook stamps nothing — the plain
 *    updatedAt comparison already covers "a reply landed while I was away",
 *    whether the app was foreground or background.
 *
 * Pure derivation lives in [isUnread] so tests need no Android.
 */
object UnreadRegistry {

    private const val PREFS = "minis_unread_registry"
    private const val KEY_PREFIX = "lastReadAt:"

    /** In-memory mirror so repeated row reads do not hit SharedPreferences. */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Bumped on every markRead so Compose readers re-derive. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    fun lastReadAtMs(context: Context, sessionId: String): Long = cache.getOrPut(sessionId) {
        prefs(context).getLong(KEY_PREFIX + sessionId, 0L)
    }

    /** The user is looking at the chat (screen entered, or a new row streamed in while mounted). */
    fun markRead(context: Context, sessionId: String) {
        if (sessionId.isBlank()) return
        val now = System.currentTimeMillis()
        cache[sessionId] = now
        prefs(context).edit().putLong(KEY_PREFIX + sessionId, now).apply()
        // [FIX-R2-11, review §三 异步与并发] Atomic bump: concurrent markRead /
        // forget must not lose a revision (read-modify-write between threads).
        _revision.update { it + 1 }
    }

    fun forget(context: Context, sessionId: String) {
        cache.remove(sessionId)
        prefs(context).edit().remove(KEY_PREFIX + sessionId).apply()
        _revision.update { it + 1 }
    }

    /** [sessionUpdatedAt] newer than the read stamp = unseen reply. Pure. */
    fun isUnread(sessionUpdatedAt: Long, lastReadAtMs: Long): Boolean =
        sessionUpdatedAt > lastReadAtMs

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
