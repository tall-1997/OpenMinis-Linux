package com.openminis.app.ui.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Viewport ownership of the chat timeline. Exactly two states:
 *
 * - [Pinned] — the viewport follows the newest edge. Content-driven scrolls
 *   are allowed here: the streaming glide, send snaps, resume/retry
 *   force-bottom, trailing-row pins, reserve recovery.
 * - [Reading] — the user is reading history. **No content event may issue a
 *   scroll command**: streaming ticks, new messages, system rows, tail
 *   attach, retry/resume, stream-end self-sizing. The viewport stays glued
 *   to whatever row the user landed on via Compose's native key-based
 *   anchoring (guaranteed by the stable keys in [FlatKeys] /
 *   buildFlatChatItems — new rows join at the newest end and only shift
 *   indices, which LazyListState follows by key).
 *
 * [T-android-scroll-policy] This replaces the old `userScrolledAway`
 * boolean whose twelve-plus scattered mutation sites contradicted each
 * other: the load-older pill never set it (so "reading" looked like
 * "pinned" and every stream tick yanked the viewport back to the bottom),
 * resume/retry cleared it unconditionally, the drag-stop handler cleared it
 * inside a 1.5 s window, and an anchor-restore effect actively re-scrolled
 * to stale snapshots. That web of exceptions is the root cause of the
 * reported "翻看历史时新消息到达导致自动跳屏" bug family.
 *
 * The policy holds NO viewport access — ChatScreen translates transitions
 * into scroll commands. Transitions are the ONLY writers; every consumer
 * reads [mode].
 */
sealed interface ScrollMode {
    data object Pinned : ScrollMode

    data class Reading(val anchorKey: String?, val anchorOffset: Int) : ScrollMode {
        override fun toString(): String = "Reading(anchor=$anchorKey, off=$anchorOffset)"
    }
}

internal class ChatScrollPolicy {
    private val _mode = MutableStateFlow<ScrollMode>(ScrollMode.Pinned)
    val mode: StateFlow<ScrollMode> = _mode.asStateFlow()

    val current: ScrollMode get() = _mode.value
    val isPinned: Boolean get() = _mode.value is ScrollMode.Pinned

    /**
     * Finger lifted from a drag. Landing at the bottom (re)engages follow;
     * anywhere else is Reading, anchored on the row the user stopped at.
     */
    fun onDragStop(atBottom: Boolean, anchorKey: String?, anchorOffset: Int) {
        _mode.value = if (atBottom) {
            ScrollMode.Pinned
        } else {
            ScrollMode.Reading(anchorKey, anchorOffset)
        }
    }

    /**
     * A drag or fling finished away from the bottom while still [Pinned].
     * DragInteraction.Stop fires at finger lift — before the fling carries
     * the viewport off-bottom — so the scroll-settle edge is the
     * authoritative checkpoint. Programmatic jumps never set
     * isScrollInProgress and streaming content growth is not a gesture, so
     * callers must exclude those (a growing row is not intent).
     */
    fun rearmIfDraggedAway(anchorKey: String?, anchorOffset: Int) {
        if (_mode.value is ScrollMode.Pinned) {
            _mode.value = ScrollMode.Reading(anchorKey, anchorOffset)
        }
    }

    /** Explicit return to the bottom: FAB-down, send, IME-at-bottom, session entry. */
    fun pin() {
        _mode.value = ScrollMode.Pinned
    }

    /** A deliberate browse (pill, up-button) landed — record where. */
    fun landReading(anchorKey: String?, anchorOffset: Int) {
        _mode.value = ScrollMode.Reading(anchorKey, anchorOffset)
    }

    /** Session switch: the new session starts pinned to its tail. */
    fun reset() {
        _mode.value = ScrollMode.Pinned
    }

    /**
     * Whether a content-driven force-scroll request (resume / retry / rerun
     * emitted from the ViewModel while the user was elsewhere) may move the
     * viewport. While reading, it must not.
     */
    fun allowsContentScroll(): Boolean = isPinned
}
