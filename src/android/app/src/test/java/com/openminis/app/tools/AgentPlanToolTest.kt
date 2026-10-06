package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session-isolated independent plan list — no cursor, no global state,
 * just add / update / remove / list / clear.
 */
class AgentPlanToolTest {

    private val sidA = "chat-aaa"
    private val sidB = "chat-bbb"

    // ── add ──

    @Test
    fun addAppendsToSessionList() {
        AgentPlanStore.clear(sidA, null)
        AgentPlanStore.add(sidA, null, "task-1")
        assertEquals(1, AgentPlanStore.list(sidA, null).size)
        AgentPlanStore.add(sidA, null, "task-2", status = "active")
        assertEquals(2, AgentPlanStore.list(sidA, null).size)
    }

    @Test
    fun addRejectsBlankTitle() {
        try { AgentPlanStore.add(sidA, null, "  "); assertFalse(true) } catch (_: Exception) {}
    }

    @Test
    fun addRejectsBadStatus() {
        try { AgentPlanStore.add(sidA, null, "x", status = "bogus"); assertFalse(true) } catch (_: Exception) {}
    }

    // ── session isolation ──

    @Test
    fun twoSessionsDoNotClobberEachOther() {
        AgentPlanStore.clear(sidA, null)
        AgentPlanStore.clear(sidB, null)
        AgentPlanStore.add(sidA, null, "a-1")
        AgentPlanStore.add(sidB, null, "b-1")
        assertEquals(1, AgentPlanStore.list(sidA, null).size)
        assertEquals(1, AgentPlanStore.list(sidB, null).size)
        assertEquals("a-1", AgentPlanStore.list(sidA, null)[0].title)
        assertEquals("b-1", AgentPlanStore.list(sidB, null)[0].title)
    }

    // ── update ──

    @Test
    fun updateReturnsPlanOnSuccess() {
        AgentPlanStore.clear(sidA, null)
        val p = AgentPlanStore.add(sidA, null, "old title", status = "pending")
        val u = AgentPlanStore.update(sidA, null, p.id, title = "new title", status = "done")
        assertNotNull(u)
        assertEquals("new title", u!!.title)
        assertEquals("done", u.status)
    }

    @Test
    fun updateOnMissingIdReturnsNull() {
        val u = AgentPlanStore.update(sidA, null, "nope", title = "x")
        assertNull(u)
    }

    // ── remove & clear ──

    @Test
    fun removeReturnsBoolAndShiftsPositions() {
        AgentPlanStore.clear(sidA, null)
        val a = AgentPlanStore.add(sidA, null, "a")
        val b = AgentPlanStore.add(sidA, null, "b")
        assertTrue(AgentPlanStore.remove(sidA, null, a.id))
        assertEquals(1, AgentPlanStore.list(sidA, null).size)
        assertEquals(0, AgentPlanStore.list(sidA, null)[0].position)
        // re-add should get the next position
        AgentPlanStore.add(sidA, null, "c")
        assertEquals(2, AgentPlanStore.list(sidA, null).size)
    }

    @Test
    fun removeMissingReturnsFalse() {
        assertFalse(AgentPlanStore.remove(sidA, null, "no-such-id"))
    }

    @Test
    fun clearEmptiesList() {
        AgentPlanStore.add(sidA, null, "t1")
        assertEquals(1, AgentPlanStore.list(sidA, null).size)
        AgentPlanStore.clear(sidA, null)
        assertEquals(0, AgentPlanStore.list(sidA, null).size)
    }

    // ── render ──

    @Test
    fun renderEmptyList() {
        AgentPlanStore.clear(sidA, null)
        assertEquals("(no plan items)", AgentPlanStore.render(sidA, null))
    }

    @Test
    fun renderShowsStatusMarks() {
        AgentPlanStore.clear(sidA, null)
        AgentPlanStore.add(sidA, null, "pending one")
        AgentPlanStore.add(sidA, null, "active one", status = "active")
        AgentPlanStore.add(sidA, null, "done one", status = "done")
        AgentPlanStore.add(sidA, null, "failed one", status = "failed")
        val r = AgentPlanStore.render(sidA, null)
        assertTrue(r, r.contains("[>]"))
        assertTrue(r, r.contains("[x]"))
        assertTrue(r, r.contains("[!]"))
        assertTrue(r, r.contains("[ ]"))
    }

    // ── tool definition & execute ──

    @Test
    fun definitionHasCleanOps() {
        val d = AgentPlanTool.definition()
        assertEquals("agent_plan", d.name)
        val ops = d.parameters["op"]!!.enumValues
        assertTrue("only independent-list ops", ops!!.toSet() == setOf("add", "update", "remove", "list", "clear"))
        assertFalse("no cursor-based ops", ops.contains("advance"))
        assertFalse("no legacy ops", ops.contains("set"))
    }

    @Test
    fun executeListReturnsRender() {
        AgentPlanStore.clear(sidA, null)
        AgentPlanStore.add(sidA, null, "only task")
        val result = AgentPlanTool.execute("""{"op":"list"}""", sidA, null)
        assertTrue(result.success)
        assertTrue(result.output, result.output.contains("only task"))
    }

    @Test
    fun executeAddReportsSuccess() {
        AgentPlanStore.clear(sidA, null)
        val result = AgentPlanTool.execute("""{"op":"add","title":"new task","status":"active"}""", sidA, null)
        assertTrue(result.success)
        assertTrue(result.output, result.output.contains("new task"))
        assertEquals(1, AgentPlanStore.list(sidA, null).size)
    }

    @Test
    fun executeUpdateAndRemove() {
        AgentPlanStore.clear(sidA, null)
        AgentPlanStore.add(sidA, null, "old", status = "pending")
        val p = AgentPlanStore.list(sidA, null).single()
        val u = AgentPlanTool.execute("""{"op":"update","id":"${p.id}","status":"done"}""", sidA, null)
        assertTrue(u.success)
        assertEquals("done", AgentPlanStore.list(sidA, null).single().status)
        val r = AgentPlanTool.execute("""{"op":"remove","id":"${p.id}"}""", sidA, null)
        assertTrue(r.success)
        assertEquals(0, AgentPlanStore.list(sidA, null).size)
    }
}