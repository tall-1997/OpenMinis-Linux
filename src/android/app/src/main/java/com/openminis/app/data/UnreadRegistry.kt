package com.openminis.app.data

import com.openminis.app.config.Prefs
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
 *
 * [T-prefs-typed] Reads/writes go through [Prefs], so the file name and the
 * dynamic key prefix are both declared in `PrefsRegistry` instead of being
 * string literals that a typo would silently turn into a new empty file.
 */
object UnreadRegistry {

    private const val PREFS = "minis_unread_registry"
    private const val KEY_PREFIX = "lastReadAt:"

    /** In-memory mirror so repeated row reads do not hit SharedPreferences. */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Long>()

    /** Bumped on every markRead so Compose readers re-derive. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision.asStateFlow()

    /**
     * No [android.content.Context] parameter any more: [Prefs] is installed once
     * in `MinisApp.onCreate`, so call sites cannot pass the wrong Context and the
     * backend is shared process-wide. Signature change is source-compatible for
     * `UnreadRegistry.lastReadAtMs(sessionId)` but NOT for the old two-arg form —
     * every caller was updated in the same commit.
     */
    private val prefs get() = Prefs.file(PREFS)

    fun lastReadAtMs(sessionId: String): Long = cache.getOrPut(sessionId) {
        prefs.long(KEY_PREFIX + sessionId, 0L)
    }

    /** The user is looking at the chat (screen entered, or a new row streamed in while mounted). */
    fun markRead(sessionId: String) {
        if (sessionId.isBlank()) return
        val now = System.currentTimeMillis()
        cache[sessionId] = now
        prefs.set(KEY_PREFIX + sessionId, now)
        // [FIX-R2-11, review §三 异步与并发] Atomic bump: concurrent markRead /
        // forget must not lose a revision (read-modify-write between threads).
        _revision.update { it + 1 }
    }

    fun forget(sessionId: String) {
        cache.remove(sessionId)
        prefs.remove(KEY_PREFIX + sessionId)
        _revision.update { it + 1 }
    }

    /** [sessionUpdatedAt] newer than the read stamp = unseen reply. Pure. */
    fun isUnread(sessionUpdatedAt: Long, lastReadAtMs: Long): Boolean =
        sessionUpdatedAt > lastReadAtMs
}
