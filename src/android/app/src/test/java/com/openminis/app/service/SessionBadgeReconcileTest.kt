package com.openminis.app.service

import com.openminis.app.service.SessionBadgeStore.SessionBadgeState.PAUSED
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-android-paused-badge-reconcile-single-source] Tests for the collapsed
 * reconcile entry point [SessionBadgeStore.reconcileFromDb] (the former
 * byte-identical cold-start + foreground copies in MinisApp, now one function).
 *
 * Runs without a Context: the store's persist layer stays null until
 * `init()` is called, so `mutate` works purely in memory. The pin-downs:
 *
 *  1. interrupted − active: a session that is ACTIVELY STREAMING is never
 *     flagged PAUSED (the T-android-session-paused-badge-hardkill rule);
 *  2. interrupted-only sessions gain PAUSED;
 *  3. sessions no longer interrupted LOSE their stale PAUSED;
 *  4. reconcile is idempotent — a second run with the same inputs is a no-op.
 */
class SessionBadgeReconcileTest {

    @Before
    fun reset() {
        SessionBadgeStore.byId.value.keys.toList().forEach { SessionBadgeStore.clear(it) }
    }

    @After
    fun cleanup() {
        SessionBadgeStore.byId.value.keys.toList().forEach { SessionBadgeStore.clear(it) }
    }

    /** Active sessions are excluded from the interrupted set before reconcile. */
    @Test
    fun reconcile_excludesActiveSessions() {
        SessionBadgeStore.reconcileFromDb(interrupted = setOf("s-active", "s-idle"), active = setOf("s-active"))
        val byId = SessionBadgeStore.byId.value
        assertEquals(PAUSED, SessionBadgeStore.headFor("s-idle"))
        assertFalse("active session must never be flagged paused", byId.containsKey("s-active"))
    }

    /** Interrupted-only sessions gain the PAUSED badge at the queue head. */
    @Test
    fun reconcile_addsPausedForInterruptedSessions() {
        SessionBadgeStore.reconcileFromDb(interrupted = setOf("s1", "s2"), active = emptySet())
        assertEquals(PAUSED, SessionBadgeStore.headFor("s1"))
        assertEquals(PAUSED, SessionBadgeStore.headFor("s2"))
    }

    /** A session that is no longer interrupted loses its stale PAUSED badge. */
    @Test
    fun reconcile_clearsStalePausedWhenNoLongerInterrupted() {
        SessionBadgeStore.push("s1", PAUSED)
        SessionBadgeStore.reconcileFromDb(interrupted = emptySet(), active = emptySet())
        assertEquals(null, SessionBadgeStore.headFor("s1"))
        assertFalse(SessionBadgeStore.byId.value.containsKey("s1"))
    }

    /** Same inputs twice → same state; reconcile does not churn the store. */
    @Test
    fun reconcile_isIdempotent() {
        SessionBadgeStore.reconcileFromDb(interrupted = setOf("s1"), active = emptySet())
        val once = SessionBadgeStore.byId.value
        SessionBadgeStore.reconcileFromDb(interrupted = setOf("s1"), active = emptySet())
        assertEquals(once, SessionBadgeStore.byId.value)
        assertEquals(PAUSED, SessionBadgeStore.headFor("s1"))
    }

    /** Non-PAUSED queued states survive a reconcile that clears PAUSED. */
    @Test
    fun reconcile_preservesOtherQueuedStates() {
        SessionBadgeStore.push("s1", PAUSED)
        SessionBadgeStore.push("s1", SessionBadgeStore.SessionBadgeState.ICLOUD_SYNCING)
        // Queue head is now ICLOUD_SYNCING; a reconcile that finds the session
        // no longer interrupted must drop only PAUSED.
        SessionBadgeStore.reconcileFromDb(interrupted = emptySet(), active = emptySet())
        assertEquals(
            listOf(SessionBadgeStore.SessionBadgeState.ICLOUD_SYNCING),
            SessionBadgeStore.byId.value["s1"],
        )
    }
}
