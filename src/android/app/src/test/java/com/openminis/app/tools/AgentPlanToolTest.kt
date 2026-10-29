package com.openminis.app.tools

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Session-isolated independent plan list — no cursor, no global state,
 * just add / update / remove / list / clear.
 */
class AgentPlanToolTest {

    private val sidA = "chat-aaa"
    private val sidB = "chat-bbb"

    /**
     * The store retires a fully-done board after a wall-clock linger, and this
     * class leaves all-done boards behind on purpose. Freeze the clock so the
     * mechanics under test here stay independent of suite timing.
     */
    @Before
    fun freezeRetirementClock() {
        AgentPlanStore.nowMs = { FIXED_NOW }
    }

    @After
    fun restoreRetirementClock() {
        AgentPlanStore.nowMs = { System.currentTimeMillis() }
    }

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
    fun addNormalizesStatusAliases() {
        // [T-plan-status-aliases] A status word from another ecosystem used
        // to throw and stall the board. Aliases fold into the four canonical
        // states; anything unrecognized degrades to pending.
        AgentPlanStore.clear(sidA, null)
        assertEquals("done", AgentPlanStore.add(sidA, null, "a", status = "completed").status)
        assertEquals("active", AgentPlanStore.add(sidA, null, "b", status = "in_progress").status)
        assertEquals("failed", AgentPlanStore.add(sidA, null, "c", status = "cancelled").status)
        assertEquals("pending", AgentPlanStore.add(sidA, null, "d", status = "bogus").status)
        assertEquals("pending", AgentPlanStore.add(sidA, null, "e").status)
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

    // ── [T-plan-target-resolution] update/remove resolve number / title / id ──

    @Test
    fun updateResolvesByOneBasedNumber() {
        AgentPlanStore.clear(sidA, null)
        AgentPlanStore.add(sidA, null, "first")
        AgentPlanStore.add(sidA, null, "second")
        val u = AgentPlanStore.update(sidA, null, "2", status = "done")
        assertNotNull(u)
        assertEquals("second", u!!.title)
        assertEquals("done", AgentPlanStore.list(sidA, null)[1].status)
        assertEquals("pending", AgentPlanStore.list(sidA, null)[0].status)
    }

    @Test
    fun removeResolvesByNumberAndShiftsPositions() {
        AgentPlanStore.clear(sidA, null)
        AgentPlanStore.add(sidA, null, "first")
        AgentPlanStore.add(sidA, null, "second")
        AgentPlanStore.add(sidA, null, "third")
        assertTrue(AgentPlanStore.remove(sidA, null, "2"))
        assertEquals(listOf("first", "third"), AgentPlanStore.list(sidA, null).map { it.title })
        assertEquals(listOf(0, 1), AgentPlanStore.list(sidA, null).map { it.position })
    }

    @Test
    fun updateResolvesByExactTitleAndUniquePrefix() {
        AgentPlanStore.clear(sidA, null)
        AgentPlanStore.add(sidA, null, "alpha-one")
        AgentPlanStore.add(sidA, null, "beta-two")
        val byTitle = AgentPlanStore.update(sidA, null, "beta-two", status = "active")
        assertNotNull(byTitle)
        assertEquals("active", byTitle!!.status)
        // Case-insensitive unique prefix also lands on the right row.
        val byPrefix = AgentPlanStore.update(sidA, null, "ALPHA", status = "done")
        assertNotNull(byPrefix)
        assertEquals("alpha-one", byPrefix!!.title)
        assertEquals("done", byPrefix.status)
    }

    @Test
    fun resolutionRefusesAmbiguousTargetsInsteadOfHittingTheWrongRow() {
        AgentPlanStore.clear(sidA, null)
        AgentPlanStore.add(sidA, null, "alpha-x")
        AgentPlanStore.add(sidA, null, "alpha-y")
        // "alpha" is a prefix of two rows: must be ambiguous, never a silent
        // hit on the first match (the old silent-wrong-row failure mode).
        assertNull(AgentPlanStore.update(sidA, null, "alpha", status = "done"))
        assertNull(AgentPlanStore.update(sidA, null, "alpha-z"))
        // The exact number ladder still works alongside titles that look numeric.
        AgentPlanStore.add(sidA, null, "1")
        assertNotNull(AgentPlanStore.update(sidA, null, "2", status = "done"))
    }

    @Test
    fun updateNormalizesStatusAliases() {
        AgentPlanStore.clear(sidA, null)
        val p = AgentPlanStore.add(sidA, null, "one")
        val u = AgentPlanStore.update(sidA, null, p.id, status = "completed")
        assertEquals("done", u!!.status)
        assertEquals("active", AgentPlanStore.update(sidA, null, p.title, status = "in_progress")!!.status)
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

    // ── migrate (draft→real key drift, T-plan-mode-draft-drift) ──

    @Test
    fun migrateCarriesBoardToNewKeyAndClearsOld() {
        AgentPlanStore.clear(sidA, null)
        AgentPlanStore.clear(sidB, null)
        val p1 = AgentPlanStore.add(sidA, null, "task-1", "d1", "active")
        val p2 = AgentPlanStore.add(sidA, null, "task-2", "d2", "pending")
        AgentPlanStore.migrate(sidA, sidB, null)
        val moved = AgentPlanStore.list(sidB, null)
        assertEquals(2, moved.size)
        // id and order survive the move
        assertEquals(listOf(p1.id, p2.id), moved.map { it.id })
        assertEquals("active", moved[0].status)
        assertEquals(0, AgentPlanStore.list(sidA, null).size)
    }

    @Test
    fun migrateIsNoOpForEmptyOldBoardAndSameKeys() {
        AgentPlanStore.clear(sidA, null)
        AgentPlanStore.clear(sidB, null)
        AgentPlanStore.migrate(sidA, sidB, null)
        assertEquals(0, AgentPlanStore.list(sidB, null).size)
        AgentPlanStore.add(sidA, null, "task-1")
        AgentPlanStore.migrate(sidA, sidA, null)
        assertEquals(1, AgentPlanStore.list(sidA, null).size)
        AgentPlanStore.clear(sidA, null)
    }

    // ── render ──

    @Test
    fun renderEmptyList() {
        AgentPlanStore.clear(sidA, null)
        val text = AgentPlanStore.render(sidA, null)
        // Not the old neutral "(no plan items)": that read as "board is fine,
        // nothing to do" and hid the fact that no board had ever been created.
        assertTrue(text, text.startsWith("(no plan items"))
        assertTrue(text, text.contains("no board yet"))
        assertTrue(text, text.contains("op=add"))
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

    // ── [T-plan-render-id-truncation] render must show the FULL id ──
    //
    // render() used to print `{${p.id.take(8)}}`. The model reads that id back
    // for update/remove, but update/remove match the id EXACTLY — so a
    // truncated id either missed ("plan item not found") or, with ids sharing
    // an 8-char prefix, silently hit the wrong row. The id shown must be the
    // id that round-trips, verbatim.

    @Test
    fun renderShowsFullIdNotTruncated() {
        AgentPlanStore.clear(sidA, null)
        val p = AgentPlanStore.add(sidA, null, "round trip")
        val r = AgentPlanStore.render(sidA, null)
        // ${p.id} needs braces: "$p.id" would render p.toString() + ".id".
        assertTrue(r, r.contains("{${p.id}}"))
        assertFalse(r, r.contains("{${p.id.take(8)}}}"))
    }

    @Test
    fun idFromRenderRoundTripsThroughUpdateAndRemove() {
        AgentPlanStore.clear(sidA, null)
        AgentPlanStore.add(sidA, null, "first")
        AgentPlanStore.add(sidA, null, "second")
        // Extract the id exactly the way the model does: from the render text.
        val id = Regex("\\{([^}]+)}").findAll(AgentPlanStore.render(sidA, null)).first().groupValues[1]
        val u = AgentPlanStore.update(sidA, null, id, status = "done")
        assertNotNull(u)
        assertEquals(id, u!!.id)
        assertTrue(AgentPlanStore.remove(sidA, null, id))
        assertEquals(1, AgentPlanStore.list(sidA, null).size)
    }

    @Test
    fun executeUpdateWithIdFromToolOutputSucceeds() {
        AgentPlanStore.clear(sidA, null)
        val added = AgentPlanTool.execute("""{"op":"add","title":"via tool"}""", sidA, null)
        assertTrue(added.success)
        val id = Regex("\\{([^}]+)}").findAll(added.output).first().groupValues[1]
        val u = AgentPlanTool.execute("""{"op":"update","id":"$id","status":"done"}""", sidA, null)
        assertTrue(u.output, u.success)
        assertEquals("done", AgentPlanStore.list(sidA, null).single().status)
    }

    // ── tool definition & execute ──

    @Test
    fun definitionHasCleanOps() {
        val d = AgentPlanTool.definition()
        assertEquals("agent_plan", d.name)
        val ops = d.parameters["op"]!!.enumValues
        assertTrue("only independent-list ops", ops!!.toSet() == setOf("add", "update", "remove", "list", "clear", "replace"))
        assertFalse("no cursor-based ops", ops.contains("advance"))
        assertFalse("no legacy ops", ops.contains("set"))
        // replace carries a typed array schema (not a free-text blob): the
        // provider formats it as a proper JSON array for the model.
        val plans = d.parameters["plans"]!!
        assertEquals("array", plans.type)
        assertEquals("object", plans.items!!.type)
        assertTrue("title is required on each row", plans.items!!.required!!.contains("title"))
        assertNotNull(plans.items!!.properties!!["status"])
    }

    @Test
    fun theDescriptionCarriesTheReplaceAndTriggerContract() {
        val description = AgentPlanTool.definition().description
        assertTrue(description, description.contains("full-list REPLACE"))
        assertTrue(description, description.contains("3+ steps"))
        assertTrue(description, description.contains("op=replace"))
        assertTrue(description, description.contains("ids are optional"))
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

    // ── [T-plan-board-replace] full-list replace ──

    @Test
    fun replaceBuildsTheBoardInOneCall() {
        AgentPlanStore.clear(sidA, null)
        val built = AgentPlanStore.replace(sidA, null, listOf(
            AgentPlanStore.PlanInput(title = "recon"),
            AgentPlanStore.PlanInput(title = "implement"),
            AgentPlanStore.PlanInput(title = "ship", status = "active"),
        ))
        assertEquals(3, built.size)
        assertEquals(listOf("recon", "implement", "ship"), built.map { it.title })
        assertEquals(listOf(0, 1, 2), built.map { it.position })
        assertEquals("active", AgentPlanStore.list(sidA, null)[2].status)
    }

    @Test
    fun replaceRewritesTheWholeBoardIncludingStatusFlips() {
        AgentPlanStore.clear(sidA, null)
        val first = AgentPlanStore.add(sidA, null, "a")
        val second = AgentPlanStore.add(sidA, null, "b")
        // One call: flip a done, keep b, drop nothing by re-listing both, and
        // reorder them. Reused ids stay stable, new rows mint ids.
        val rebuilt = AgentPlanStore.replace(sidA, null, listOf(
            AgentPlanStore.PlanInput(id = second.id, title = "b", status = "active"),
            AgentPlanStore.PlanInput(id = first.id, title = "a", status = "done"),
            AgentPlanStore.PlanInput(title = "c"),
        ))
        assertEquals(3, rebuilt.size)
        assertEquals(listOf("b", "a", "c"), rebuilt.map { it.title })
        assertEquals(listOf("active", "done", "pending"), rebuilt.map { it.status })
        // id reuse keeps identity (and createdAt) across replaces.
        assertEquals(second.createdAt, rebuilt[0].createdAt)
        assertEquals(first.id, rebuilt[1].id)
        assertFalse("new rows mint a fresh id", rebuilt[2].id == first.id || rebuilt[2].id == second.id)
    }

    @Test
    fun replaceEmptyListClearsTheBoard() {
        AgentPlanStore.clear(sidA, null)
        AgentPlanStore.add(sidA, null, "one")
        assertEquals(1, AgentPlanStore.list(sidA, null).size)
        AgentPlanStore.replace(sidA, null, emptyList())
        assertEquals(0, AgentPlanStore.list(sidA, null).size)
    }

    @Test
    fun replaceRejectsBlankTitleAndDuplicateIds() {
        AgentPlanStore.clear(sidA, null)
        try {
            AgentPlanStore.replace(sidA, null, listOf(AgentPlanStore.PlanInput(title = "  ")))
            org.junit.Assert.fail("blank title must throw")
        } catch (_: Exception) {}
        val id = "shared-id"
        try {
            AgentPlanStore.replace(sidA, null, listOf(
                AgentPlanStore.PlanInput(id = id, title = "x"),
                AgentPlanStore.PlanInput(id = id, title = "y"),
            ))
            org.junit.Assert.fail("duplicate ids must throw")
        } catch (_: Exception) {}
    }

    @Test
    fun replaceNormalizesStatusAliases() {
        AgentPlanStore.clear(sidA, null)
        val built = AgentPlanStore.replace(sidA, null, listOf(
            AgentPlanStore.PlanInput(title = "a", status = "completed"),
            AgentPlanStore.PlanInput(title = "b", status = "in_progress"),
        ))
        assertEquals(listOf("done", "active"), built.map { it.status })
    }

    @Test
    fun executeReplaceBuildsBoardFromTypedArray() {
        AgentPlanStore.clear(sidA, null)
        val result = AgentPlanTool.execute(
            """{"op":"replace","plans":[{"title":"one"},{"title":"two","status":"done"}]}""",
            sidA,
            null,
        )
        assertTrue(result.output, result.success)
        assertEquals(2, AgentPlanStore.list(sidA, null).size)
        assertEquals("done", AgentPlanStore.list(sidA, null)[1].status)
        assertTrue(result.output, result.output.contains("two"))
    }

    @Test
    fun executeReplaceAcceptsPlansAsJsonStringFallback() {
        AgentPlanStore.clear(sidA, null)
        // Raw string: each \" below is ONE backslash on the wire, so the
        // outer JSONObject decodes plans to the string [{"title":"flat"}],
        // which the inner JSONArray then parses. Two backslashes (\\") would
        // end the plans string early and break the outer parse.
        val result = AgentPlanTool.execute(
            """{"op":"replace","plans":"[{\"title\":\"flat\"}]"}""",
            sidA,
            null,
        )
        assertTrue(result.output, result.success)
        assertEquals("flat", AgentPlanStore.list(sidA, null).single().title)
    }

    @Test
    fun executeReplaceWithoutPlansReportsError() {
        val result = AgentPlanTool.execute("""{"op":"replace"}""", sidA, null)
        assertFalse(result.success)
        assertTrue(result.output, result.output.contains("plans"))
    }

    @Test
    fun executeUpdateByNumberSucceedsAndMissReportsTheLiveBoard() {
        AgentPlanStore.clear(sidA, null)
        AgentPlanTool.execute("""{"op":"replace","plans":[{"title":"one"},{"title":"two"}]}""", sidA, null)
        // The snapshot prints 1-based numbers: targeting by number needs no id.
        val byNumber = AgentPlanTool.execute("""{"op":"update","id":"2","status":"done"}""", sidA, null)
        assertTrue(byNumber.output, byNumber.success)
        assertEquals("done", AgentPlanStore.list(sidA, null)[1].status)
        // A miss names the ref AND prints the live board so the model can
        // self-correct instead of silently dropping the advance.
        val miss = AgentPlanTool.execute("""{"op":"update","id":"nope","status":"done"}""", sidA, null)
        assertFalse(miss.success)
        assertTrue(miss.output, miss.output.contains("nope"))
        assertTrue(miss.output, miss.output.contains("two"))
    }

    // ── [T-plan-store-silent-loss] persistence failure must be loud ──

    @Test
    fun decodePlansRejectsCorruptJson() {
        // A corrupt board used to decode to an empty list, and the next add()
        // overwrote the file — silently destroying every persisted plan.
        try {
            AgentPlanStore.decodePlans("{not a plan board")
            org.junit.Assert.fail("corrupt plan file must throw, not reset to empty")
        } catch (e: Exception) {
            assertTrue(e.message?.isNotEmpty() == true)
        }
    }

    @Test
    fun encodePlansCapsAtMaxPlans() {
        val plans = (1..150).map { AgentPlanStore.Plan(title = "t$it") }
        val decoded = AgentPlanStore.decodePlans(AgentPlanStore.encodePlans(plans))
        assertEquals(100, decoded.size)
    }

    private companion object {
        const val FIXED_NOW = 1_700_000_000_000L
    }
}
