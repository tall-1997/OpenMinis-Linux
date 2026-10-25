package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-android-scrollbtn-turn-walk] Pins the up-button's turn-walk selection
 * rule, ported from iOS `scrollToPreviousUserTurn` (dcdec3c5).
 *
 * The decision now lives in production code
 * ([ChatHistoryWindow.previousUserTurnTarget]) — this test calls it directly
 * instead of mirroring it, so the test and the button can no longer drift
 * apart. The composable wraps the decision with (a) the window-edge retry
 * (load one older page, re-resolve — modelled below as two decision calls)
 * and (b) the direct key-index jump (FlatKeys + lazyIndexOfOldestFirstKey,
 * no viewport scanning — the old unbounded seek is what ANR'd repeated taps).
 */
class UpButtonTurnWalkTest {

    /** u1 a1 u2 a2 u3 a3 — three turns, assistant reply after each. */
    private val convo = listOf(
        "u1" to true, "a1" to false,
        "u2" to true, "a2" to false,
        "u3" to true, "a3" to false,
    )

    @Test
    fun `first tap anchors to the current turn rather than stepping back`() {
        // Viewport is showing a3, i.e. inside turn 3. The first tap must land on
        // u3 — NOT u2 — so the user sees the start of the turn they're reading.
        val target = ChatHistoryWindow.previousUserTurnTarget(
            loaded = convo,
            topMessageId = "a3",
            lastJumpedUserId = null,
            fullyVisibleUserIds = emptySet(),
        )
        assertEquals("u3", target)
    }

    @Test
    fun `a repeated tap walks one turn further back`() {
        // Same viewport, but we already jumped to u3 — now step to u2.
        val target = ChatHistoryWindow.previousUserTurnTarget(
            loaded = convo,
            topMessageId = "u3",
            lastJumpedUserId = "u3",
            fullyVisibleUserIds = emptySet(),
        )
        assertEquals("u2", target)
    }

    @Test
    fun `walking continues turn by turn`() {
        val target = ChatHistoryWindow.previousUserTurnTarget(
            loaded = convo,
            topMessageId = "u2",
            lastJumpedUserId = "u2",
            fullyVisibleUserIds = emptySet(),
        )
        assertEquals("u1", target)
    }

    @Test
    fun `the first turn is a floor - no overscroll past it`() {
        val target = ChatHistoryWindow.previousUserTurnTarget(
            loaded = convo,
            topMessageId = "u1",
            lastJumpedUserId = "u1",
            fullyVisibleUserIds = emptySet(),
        )
        assertEquals("u1", target)
    }

    /** Five turns — needed for the clamp case, where a 3-turn fixture is too
     *  small for the fixed and unfixed rules to give different answers. */
    private val longConvo = (1..5).flatMap {
        listOf("u$it" to true, "a$it" to false)
    }

    @Test
    fun `at the end of content the walk still advances instead of oscillating`() {
        // Device-observed stall (taps 6/7 of the 7-turn session): near the oldest
        // rows a LazyColumn CLAMPS, so the target never reaches the viewport top
        // and the anchor keeps recomputing to the OLDEST visible turn (u1) even
        // though we last jumped to u4. Continuing from lastJumped instead
        // advances one turn per tap.
        val target = ChatHistoryWindow.previousUserTurnTarget(
            loaded = longConvo,
            topMessageId = "u1",
            lastJumpedUserId = "u4",
            fullyVisibleUserIds = emptySet(),
        )
        assertEquals("u3", target)
    }

    @Test
    fun `a fresh tap does not skip ahead`() {
        // A FIRST tap must never step back: with no lastJumped, the target is
        // still the turn the user is reading.
        val target = ChatHistoryWindow.previousUserTurnTarget(
            loaded = convo,
            topMessageId = "u2",
            lastJumpedUserId = null,
            fullyVisibleUserIds = emptySet(),
        )
        assertEquals("u2", target)
    }

    @Test
    fun `a stale lastJumped that is not the current anchor re-anchors`() {
        // This is the post-drag state: lastJumpedUserId is reset to null, so the
        // next tap re-anchors to the current turn rather than continuing.
        val target = ChatHistoryWindow.previousUserTurnTarget(
            loaded = convo,
            topMessageId = "a2",
            lastJumpedUserId = null,
            fullyVisibleUserIds = emptySet(),
        )
        assertEquals("u2", target)
    }

    @Test
    fun `an unloaded top row anchors to the oldest loaded turn`() {
        // The viewport top is the OLDEST content on screen; a top row whose
        // message isn't loaded (or is a synthetic __ row) means the user is
        // at/above the start of the window — anchor on the oldest loaded turn.
        val target = ChatHistoryWindow.previousUserTurnTarget(
            loaded = convo,
            topMessageId = null,
            lastJumpedUserId = null,
            fullyVisibleUserIds = emptySet(),
        )
        assertEquals("u1", target)
    }

    @Test
    fun `a conversation with no user turns yields no target`() {
        val noUsers = listOf("a1" to false, "a2" to false)
        val target = ChatHistoryWindow.previousUserTurnTarget(
            loaded = noUsers,
            topMessageId = "a2",
            lastJumpedUserId = null,
            fullyVisibleUserIds = emptySet(),
        )
        assertNull(target)
    }

    @Test
    fun `fully visible turns are skipped - a half scrolled out turn is still unread`() {
        // u3 and u2 are fully on screen: the first tap must go past both to u1.
        val target = ChatHistoryWindow.previousUserTurnTarget(
            loaded = convo,
            topMessageId = "a3",
            lastJumpedUserId = null,
            fullyVisibleUserIds = setOf("u3", "u2"),
        )
        assertEquals("u1", target)
    }

    @Test
    fun `window edge retry - after one older page the walk continues past the floor`() {
        // [T-android-upbtn-window-edge] The walk floor is the oldest LOADED
        // turn. The button's pairing: the walk reached the floor (u1) and
        // older history exists → load one page → re-resolve with
        // lastJumped=u1 against the GROWN window → u0 (previously unloaded)
        // becomes reachable instead of the tap bouncing off the edge.
        val first = ChatHistoryWindow.previousUserTurnTarget(
            loaded = convo,
            topMessageId = "a3",
            lastJumpedUserId = "u1",
            fullyVisibleUserIds = emptySet(),
        )
        assertEquals("u1", first)
        val grownWindow = listOf("u0" to true, "a0" to false) + convo
        val second = ChatHistoryWindow.previousUserTurnTarget(
            loaded = grownWindow,
            topMessageId = "a3",
            lastJumpedUserId = first,
            fullyVisibleUserIds = emptySet(),
        )
        assertEquals("u0", second)
    }

    @Test
    fun `row keys resolve through FlatKeys - dedupe suffixes never reach the id`() {
        // [T-android-flatkeys] The up-button parses viewport row keys through
        // the same FlatKeys the builder writes — the historical desync (dedupe
        // suffixing UserBubble.message.id itself) made these lookups match
        // nothing and stranded the walk on the oldest loaded turn.
        assertEquals("a3", FlatKeys.parse("mdblock:a3:text_a3_0:1")?.messageId)
        assertEquals("u3", FlatKeys.parse("user:u3")?.messageId)
        assertEquals("a3", FlatKeys.parse("thinking:a3")?.messageId)
        assertEquals("u3", FlatKeys.parse("user:u3#2")?.messageId)
        assertNull(FlatKeys.parse("__resume_banner__"))
        assertNull(FlatKeys.parse("__load_older__"))
    }
}
