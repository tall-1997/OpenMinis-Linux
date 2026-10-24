package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [T-android-agenthistory-cme] Contract tests for the agentHistory
 * threading model (see AgentHistoryAccess.kt).
 *
 * Production evidence: crash-2026-10-22 / crash-2026-10-23 both died in
 * `ArrayList$Itr.next` on a main-thread Handler callback while the agent
 * loop appended in the background — the classic
 * ConcurrentModificationException of a plain ArrayList shared across
 * threads. The fix is structural (CopyOnWriteArrayList + snapshot-first
 * reads); these tests pin the structure so a future "optimization" back to
 * ArrayList fails here instead of in production.
 */
class AgentHistoryThreadingContractTest {

    private fun msg(i: Int) = LLMMessage(role = LLMMessage.Role.USER, content = "m$i")

    /**
     * The field type IS the fix. ArrayList iterators throw CME on
     * concurrent modification; CopyOnWriteArrayList iterators walk an
     * immutable array snapshot.
     */
    @Test
    fun `agentHistory is a CopyOnWriteArrayList not an ArrayList`() {
        val field = ChatViewModel::class.java.getDeclaredField("agentHistory")
        assertTrue(
            "agentHistory must stay a CopyOnWriteArrayList — see AgentHistoryAccess.kt. " +
                "Reverting to ArrayList reintroduces the main-thread CME crash class.",
            CopyOnWriteArrayList::class.java.isAssignableFrom(field.type),
        )
    }

    /**
     * The exact crash shape: reader mid-iteration, writer appends, reader
     * continues. On ArrayList the next() call throws; on COWAL the iterator
     * finishes the snapshot it started with.
     */
    @Test
    fun `iteration continues without CME when a write lands mid-read`() {
        val list = CopyOnWriteArrayList<LLMMessage>()
        repeat(50) { list.add(msg(it)) }
        val iter = list.iterator()
        assertEquals("m0", iter.next().content)
        assertEquals("m1", iter.next().content)
        // The loop coroutine appends while the main thread is mid-iteration.
        list.add(msg(999))
        var last = iter.next()
        while (iter.hasNext()) last = iter.next()
        // Iterator saw the pre-append snapshot, exactly 50 elements.
        assertEquals("m49", last.content)
    }

    /**
     * Why AgentHistoryAccess forbids subList views of the live list: COWAL
     * subList views carry a comodification check and throw on ANY later op
     * once the parent array is swapped.
     */
    @Test
    fun `a subList view held across a parent mutation throws CME`() {
        val list = CopyOnWriteArrayList<LLMMessage>()
        repeat(10) { list.add(msg(it)) }
        val view = list.subList(0, 5)
        list.add(msg(99)) // parent array swapped underneath the view
        try {
            view.size
            fail("expected ConcurrentModificationException from the stale subList view")
        } catch (_: java.util.ConcurrentModificationException) {
            // expected — this is the pattern the access helpers eliminate
        }
    }

    /**
     * The sanctioned pattern: toList() snapshot first, then slice. The
     * slice stays valid no matter what happens to the parent afterwards.
     */
    @Test
    fun `a toList snapshot sliced with subList survives parent mutations`() {
        val list = CopyOnWriteArrayList<LLMMessage>()
        repeat(10) { list.add(msg(it)) }
        val snap = list.toList()
        val slice = snap.subList(0, 5)
        list.clear()
        list.add(msg(1000))
        assertEquals(5, slice.size)
        assertEquals("m4", slice.last().content)
    }

    /**
     * removeAgentHistoryHead's shape: sequential removeAt(0) calls are
     * individually atomic, index-stable under tail appends, and the
     * isNotEmpty() bound makes a concurrent clear() a no-op instead of an
     * IndexOutOfBounds.
     */
    @Test
    fun `head removals are index-stable and bounded by emptiness`() {
        val list = CopyOnWriteArrayList<LLMMessage>()
        repeat(10) { list.add(msg(it)) }
        list.removeAt(0)
        list.add(msg(10)) // concurrent tail append between head removals
        assertEquals("m1", list.first().content)
        assertEquals(10, list.size)
        // Over-large count (as if a concurrent clear() shrank the list):
        // the loop must stop at empty, never throw.
        var remaining = 100
        while (remaining > 0 && list.isNotEmpty()) {
            list.removeAt(0)
            remaining--
        }
        assertTrue(list.isEmpty())
    }
}
