package com.openminis.app.deeplink

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-search-jump] Session-guarded handoff of the search-result →
 * matched-message jump.
 *
 * The pane navigator can keep the previous chat's ChatScreen composition
 * alive for a frame while it swaps to the tapped session, and both
 * compositions re-run their (sessionId, revision)-keyed effect on the same
 * tick. An unguarded consume would let the stale composition steal the
 * pending focus; the guard under test here is what makes the handoff
 * single-delivery without ordering assumptions.
 */
class DeepLinkCoordinatorSearchFocusTest {

    @After
    fun reset() {
        // Object-level state — drain any focus this test set by consuming it
        // for its owning session (the only way it CAN be cleared, which is
        // itself part of the contract under test).
        DeepLinkCoordinator.pendingSearchFocus.value?.let {
            DeepLinkCoordinator.consumePendingSearchFocus(it.sessionId)
        }
    }

    @Test
    fun `consume returns the focus for the owning session and clears it`() {
        DeepLinkCoordinator.setPendingSearchFocus("s1", "m1")
        val focus = DeepLinkCoordinator.consumePendingSearchFocus("s1")
        assertNotNull(focus)
        assertEquals("s1", focus!!.sessionId)
        assertEquals("m1", focus.messageId)
        // Single delivery: a second consume (same session) finds nothing.
        assertNull(DeepLinkCoordinator.consumePendingSearchFocus("s1"))
    }

    @Test
    fun `consume for a different session returns null WITHOUT clearing`() {
        DeepLinkCoordinator.setPendingSearchFocus("s1", "m1")
        // The stale composition for s2 must not steal s1's focus…
        assertNull(DeepLinkCoordinator.consumePendingSearchFocus("s2"))
        // …and must not clear it either — s1 still finds it waiting.
        val focus = DeepLinkCoordinator.consumePendingSearchFocus("s1")
        assertNotNull(focus)
        assertEquals("m1", focus!!.messageId)
    }

    @Test
    fun `consume with nothing pending returns null`() {
        DeepLinkCoordinator.consumePendingSearchFocus("s1") // clear any residue
        assertNull(DeepLinkCoordinator.consumePendingSearchFocus("s1"))
    }

    @Test
    fun `revision increments per set so same-session re-taps re-trigger`() {
        val before = DeepLinkCoordinator.searchFocusRevision.value
        DeepLinkCoordinator.setPendingSearchFocus("s1", "m1")
        DeepLinkCoordinator.setPendingSearchFocus("s1", "m2")
        val after = DeepLinkCoordinator.searchFocusRevision.value
        // A boolean/one-shot flag would not move on the second set; the
        // counter is what lets ChatScreen's keyed effect re-run when the
        // tapped session is already mounted (no contentKey change).
        assertEquals(before + 2, after)
        // Latest value wins and is delivered once.
        val focus = DeepLinkCoordinator.consumePendingSearchFocus("s1")
        assertEquals("m2", focus!!.messageId)
    }

    @Test
    fun `pending flow exposes the focus for observers`() {
        DeepLinkCoordinator.setPendingSearchFocus("s1", "m1")
        val pending = DeepLinkCoordinator.pendingSearchFocus.value
        assertNotNull(pending)
        assertEquals("s1", pending!!.sessionId)
        DeepLinkCoordinator.consumePendingSearchFocus("s1")
        assertNull(DeepLinkCoordinator.pendingSearchFocus.value)
    }

    @Test
    fun `revision is monotonic across sessions`() {
        val before = DeepLinkCoordinator.searchFocusRevision.value
        DeepLinkCoordinator.setPendingSearchFocus("a", "m")
        DeepLinkCoordinator.setPendingSearchFocus("b", "m")
        DeepLinkCoordinator.consumePendingSearchFocus("a")
        DeepLinkCoordinator.consumePendingSearchFocus("b")
        assertTrue(DeepLinkCoordinator.searchFocusRevision.value >= before + 2)
    }
}
