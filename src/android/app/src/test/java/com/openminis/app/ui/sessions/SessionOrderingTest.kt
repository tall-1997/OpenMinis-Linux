package com.openminis.app.ui.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-attention-order] A chat that stopped to ask something must not sit below a
 * chat that is merely new — that is the one ordering rule that matters here.
 */
class SessionOrderingTest {

    private var clock = 1_000L

    private fun session(id: String, updatedAt: Long = clock++, pinnedAt: Long? = null) = SessionOrdering.Key(id, updatedAt, pinnedAt)

    private fun ids(list: List<SessionOrdering.Key>) = list.map { it.id }

    @Test
    fun nothingNoteworthyKeepsTheInputOrder() {
        val a = session("a", updatedAt = 3)
        val b = session("b", updatedAt = 2)
        val c = session("c", updatedAt = 1)
        assertEquals(listOf("a", "b", "c"), ids(SessionOrdering.sorted(listOf(a, b, c), { it }, emptySet())))
    }

    @Test
    fun aWaitingSessionFloatsAboveANewerIdleOne() {
        val idle = session("idle", updatedAt = 9)
        val waiting = session("waiting", updatedAt = 1)
        val sorted = SessionOrdering.sorted(listOf(idle, waiting), { it }, setOf("waiting"))
        assertEquals(listOf("waiting", "idle"), ids(sorted))
    }

    @Test
    fun aPinnedChatCannotBuryWorkThatWaitsOnAPerson() {
        val pinned = session("pinned", pinnedAt = 5)
        val waiting = session("waiting")
        val sorted = SessionOrdering.sorted(listOf(pinned, waiting), { it }, setOf("waiting"))
        assertEquals(listOf("waiting", "pinned"), ids(sorted))
    }

    @Test
    fun pinnedSortsAboveOrdinaryChatsByPinTime() {
        val old = session("old", pinnedAt = 1)
        val fresh = session("fresh", pinnedAt = 2)
        val plain = session("plain")
        val sorted = SessionOrdering.sorted(listOf(plain, old, fresh), { it }, emptySet())
        assertEquals(listOf("fresh", "old", "plain"), ids(sorted))
    }

    @Test
    fun withinTheAttentionTierNewestComesFirst() {
        val older = session("older", updatedAt = 1)
        val newer = session("newer", updatedAt = 2)
        val sorted = SessionOrdering.sorted(listOf(older, newer), { it }, setOf("older", "newer"))
        assertEquals(listOf("newer", "older"), ids(sorted))
    }

    @Test
    fun anEmptyOrSingleListIsReturnedUnchanged() {
        val one = session("only")
        assertEquals(listOf("only"), ids(SessionOrdering.sorted(listOf(one), { it }, setOf("only"))))
        assertEquals(emptyList<String>(), ids(SessionOrdering.sorted(emptyList(), { it }, emptySet())))
    }

    @Test
    fun needsPersonMirrorsTheSet() {
        val s = session("s")
        assertTrue(SessionOrdering.needsPerson(s.id, setOf("s")))
        assertFalse(SessionOrdering.needsPerson(s.id, emptySet()))
    }
}
