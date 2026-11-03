package com.openminis.app.data.repository

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-m6-pair-removal] Pure-plan tests for orphaned tool_use / tool_result
 * pairs across a truncation cut.  Mirrors the dsh P1-5 contract: the two
 * halves of one tool step live in TWO rows (assistant tool_use, user
 * toolResult carrier), and a cut between them must drain the surviving
 * half too.
 */
class PairRemovalTest {

    // ── builders ────────────────────────────────────────────────────

    private fun usePart(toolUseId: String): JSONObject =
        JSONObject()
            .put("type", "toolUse")
            .put(
                "value",
                JSONObject()
                    .put("toolUseId", toolUseId)
                    .put("name", "shell")
                    .put("input", JSONObject().put("command", "ls")),
            )

    private fun resultPart(toolUseId: String): JSONObject =
        JSONObject()
            .put("type", "toolResult")
            .put(
                "value",
                JSONObject()
                    .put("toolUseId", toolUseId)
                    .put("content", "ok"),
            )

    private fun textPart(text: String): JSONObject =
        JSONObject().put("type", "text").put("value", text)

    private fun arr(vararg parts: JSONObject): String =
        JSONArray().apply { parts.forEach { put(it) } }.toString()

    private fun kept(vararg rows: Pair<String, String>) = rows.toList()
    private fun deleted(vararg rows: Pair<String, String>) = rows.toList()
    private fun row(id: String, vararg parts: JSONObject) = id to arr(*parts)

    // ── basic no-ops ────────────────────────────────────────────────

    @Test
    fun noToolTrafficOnEitherSide_isEmptyPlan() {
        val plan = PairRemoval.plan(
            kept(row("a", textPart("hello"))),
            deleted(row("b", textPart("bye"))),
        )
        assertTrue(plan.isEmpty)
    }

    @Test
    fun deletedSideWithoutToolParts_shortCircuitsEmpty() {
        val plan = PairRemoval.plan(
            kept(row("a", usePart("t1"))),
            deleted(row("b", textPart("bye"))),
        )
        assertTrue(plan.isEmpty)
    }

    @Test
    fun unparsableKeptRow_isSkippedWithoutCrash() {
        val plan = PairRemoval.plan(
            kept("a" to "not-json[", "b" to arr(textPart("keep"), usePart("t1"))),
            deleted(row("c", resultPart("t1"))),
        )
        // Row b's tool_use lost its result → b trimmed; row a untouched.
        assertEquals(1, plan.updateParts.size)
        assertEquals("b", plan.updateParts[0].rowId)
    }

    // ── orphan tool_use → trim ──────────────────────────────────────

    @Test
    fun orphanToolUse_partIsStrippedFromItsRow() {
        val plan = PairRemoval.plan(
            kept(row("a", textPart("thinking…"), usePart("t1"))),
            deleted(row("b", resultPart("t1"))),
        )
        assertTrue(plan.removeIds.isEmpty())
        assertEquals(1, plan.updateParts.size)
        val updated = plan.updateParts[0]
        assertEquals("a", updated.rowId)
        val parts = JSONArray(updated.partsJson)
        assertEquals(1, parts.length())
        assertEquals("text", parts.getJSONObject(0).optString("type"))
    }

    @Test
    fun orphanToolUse_multipleOrphansInOneRowAllStripped() {
        val plan = PairRemoval.plan(
            kept(row("a", usePart("t1"), usePart("t2"), usePart("t3"))),
            deleted(row("b", resultPart("t1")), row("c", resultPart("t3"))),
        )
        assertEquals(1, plan.updateParts.size)
        val parts = JSONArray(plan.updateParts[0].partsJson)
        assertEquals(1, parts.length())
        assertEquals("t2", parts.getJSONObject(0).getJSONObject("value").optString("toolUseId"))
    }

    @Test
    fun orphanToolUse_combinedResponsesIdsPairViaKey() {
        // One call id, different |hash suffixes — pairing is by key().
        val plan = PairRemoval.plan(
            kept(row("a", textPart("thinking…"), usePart("call_1|x"))),
            deleted(row("b", resultPart("call_1|y"))),
        )
        assertEquals(1, plan.updateParts.size)
        assertEquals("a", plan.updateParts[0].rowId)
        val parts = JSONArray(plan.updateParts[0].partsJson)
        assertEquals(1, parts.length())
        assertEquals("text", parts.getJSONObject(0).optString("type"))
    }

    // ── orphan tool_use → whole row ─────────────────────────────────

    @Test
    fun orphanToolUse_rowWithOnlyTheToolPart_isRemovedWholesale() {
        val plan = PairRemoval.plan(
            kept(row("a", usePart("t1")), row("keep", textPart("still here"))),
            deleted(row("b", resultPart("t1"))),
        )
        assertEquals(listOf("a"), plan.removeIds)
        assertTrue(plan.updateParts.isEmpty())
    }

    // ── orphan tool_result → trim ───────────────────────────────────

    @Test
    fun orphanToolResult_partIsStripped() {
        // tool_result survived but its tool_use row was deleted (a cut that
        // lands on the assistant row itself). The carrier row keeps its text
        // part, so only the result part is stripped.
        val plan = PairRemoval.plan(
            kept(row("r", textPart("note"), resultPart("t9"))),
            deleted(row("u", usePart("t9"))),
        )
        assertEquals(1, plan.updateParts.size)
        assertEquals("r", plan.updateParts[0].rowId)
        val parts = JSONArray(plan.updateParts[0].partsJson)
        assertEquals(1, parts.length())
        assertEquals("text", parts.getJSONObject(0).optString("type"))
    }

    @Test
    fun orphanToolResult_rowWithOnlyTheResultPart_isRemovedWholesale() {
        val plan = PairRemoval.plan(
            kept(row("r", resultPart("t9"))),
            deleted(row("u", usePart("t9"))),
        )
        // Single toolResult part is the entire row → row goes away.
        assertTrue(plan.updateParts.isEmpty())
        assertEquals(listOf("r"), plan.removeIds)
    }

    // ── pairs that stay intact ──────────────────────────────────────

    @Test
    fun toolUseWhoseResultSurvivesOnKeptSide_stays() {
        val plan = PairRemoval.plan(
            kept(row("a", usePart("t1")), row("b", resultPart("t1"))),
            deleted(row("c", textPart("old"))),
        )
        assertTrue(plan.isEmpty)
    }

    @Test
    fun duplicateResultKeys_keptSideWins() {
        // Same key appears in a deleted result AND a kept result — the kept
        // one keeps the pair alive, so the use part survives.
        val plan = PairRemoval.plan(
            kept(row("a", usePart("t1")), row("b", resultPart("t1"))),
            deleted(row("c", resultPart("t1"))),
        )
        assertTrue(plan.isEmpty)
    }

    @Test
    fun unrelatedKeys_touchNothing() {
        val plan = PairRemoval.plan(
            kept(row("a", usePart("t1")), row("b", resultPart("t2"))),
            deleted(row("c", resultPart("t3")), row("d", usePart("t4"))),
        )
        assertTrue(plan.isEmpty)
    }

    // ── blank / malformed ids ───────────────────────────────────────

    @Test
    fun blankToolUseId_neverPairs() {
        val plan = PairRemoval.plan(
            kept(row("a", usePart(""), usePart("  "))),
            deleted(row("b", resultPart(""))),
        )
        assertTrue(plan.isEmpty)
    }

    @Test
    fun partWithoutValueObject_neverPairs() {
        val bare = JSONObject().put("type", "toolUse")
        val plan = PairRemoval.plan(
            kept(row("a", bare)),
            deleted(row("b", resultPart("t1"))),
        )
        assertTrue(plan.isEmpty)
    }

    // ── empty-row detection edge ────────────────────────────────────

    @Test
    fun rowBecomingEmptyOnlyWhenEveryPartDropped() {
        // text part is immune to pair logic — never removed.
        val plan = PairRemoval.plan(
            kept(row("a", textPart("keep"), usePart("t1"))),
            deleted(row("b", resultPart("t1"))),
        )
        assertEquals(1, plan.updateParts.size)
        val parts = JSONArray(plan.updateParts[0].partsJson)
        assertEquals(1, parts.length())
        assertEquals("text", parts.getJSONObject(0).optString("type"))
    }

    @Test
    fun emptyKeptList_emptyPlan() {
        val plan = PairRemoval.plan(kept(), deleted(row("b", resultPart("t1"))))
        assertTrue(plan.isEmpty)
    }

    @Test
    fun emptyDeletedList_emptyPlan() {
        val plan = PairRemoval.plan(kept(row("a", usePart("t1"))), deleted())
        assertTrue(plan.isEmpty)
    }

    @Test
    fun trimPreservesUnrelatedPartsOrder() {
        val plan = PairRemoval.plan(
            kept(row("a", textPart("one"), usePart("t1"), resultPart("t2"), textPart("two"))),
            deleted(row("b", resultPart("t1"))),
        )
        val parts = JSONArray(plan.updateParts[0].partsJson)
        assertEquals(3, parts.length())
        assertEquals("one", parts.getJSONObject(0).optString("value"))
        assertEquals("t2", parts.getJSONObject(1).getJSONObject("value").optString("toolUseId"))
        assertEquals("two", parts.getJSONObject(2).optString("value"))
    }
}