package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [T-memory-recall-anti-nag] The live-recall block re-announced the same few
 * memories every turn: the engine's memo keys on the query string, but the
 * prompt query folds in the assistant tail, which changes every turn — the
 * memo could never hit. These tests pin the hit-keyed cross-turn dedupe:
 * a memory stops being injected after [MemoryRecallEngine.RECALL_REPEAT_CAP]
 * consecutive announcements and re-arms when it drops out of the hits.
 *
 * Harness: recall tokenizes queries with words AND 2-grams ("some" →
 * so/om/me), so fixture lines must have DISJOINT 2-gram sets or every query
 * cross-hits both lines and per-line streaks are untestable. "cabbage
 * palace" / "milking pony" / the zzqq-xq-wv filler were chosen so no 2-gram
 * of any query appears in the other line.
 */
class MemoryRecallEngineAntiNagTest {

    /** Engine over a temp memory dir holding one diary file with [lines]. */
    private fun engineWith(vararg lines: String): MemoryRecallEngine {
        val dir = Files.createTempDirectory("recall-antinag").toFile()
        File(dir, "2026-11-04.md").writeText(lines.joinToString("\n"))
        return MemoryRecallEngine.fromDir(dir)!!
    }

    @Test
    fun aMemoryStopsBeingInjectedAfterTheRepeatCap() {
        val engine = engineWith("cabbage palace")
        val cap = MemoryRecallEngine.RECALL_REPEAT_CAP
        repeat(cap) { turn ->
            val fresh = engine.recallForPrompt("cabbage palace zzqq turn$turn")
            assertEquals("turn ${turn + 1}: still within cap, announced", 1, fresh.size)
        }
        val after = engine.recallForPrompt("cabbage palace zzqq beyond")
        assertTrue("turn ${cap + 1}: the memory has been read, stay silent", after.isEmpty())
    }

    @Test
    fun aMemoryThatDropsOutReArmsAndIsAnnouncedOnceMore() {
        val engine = engineWith("cabbage palace", "milking pony")
        val cap = MemoryRecallEngine.RECALL_REPEAT_CAP
        repeat(cap) { turn ->
            engine.recallForPrompt("cabbage palace zzqq turn$turn")
        }
        // Task pivot: the memory leaves the hit set for a turn (a query that
        // matches nothing in the diary). This round must CLEAR the capped
        // counter — an early return here kept the memory silenced forever.
        val pivoted = engine.recallForPrompt("zzqq xq wv")
        assertTrue("nothing matches the pivot query", pivoted.isEmpty())
        // It becomes relevant again: announced once (the pivot resets the
        // counter), and the consecutive counting restarts from one — it takes
        // another full CAP rounds before it falls silent again.
        val again = engine.recallForPrompt("cabbage palace zzqq renewed")
        assertEquals("re-armed: announced once after the pivot", 1, again.size)
        val secondTurn = engine.recallForPrompt("cabbage palace zzqq encore")
        assertEquals("re-armed counting restarts at one", 1, secondTurn.size)
        engine.recallForPrompt("cabbage palace zzqq third")
        val capped = engine.recallForPrompt("cabbage palace zzqq fourth")
        assertTrue("capped anew after three consecutive turns", capped.isEmpty())
    }

    @Test
    fun uncappedMemoriesKeepFlowingWhileOneIsSilenced() {
        val engine = engineWith("cabbage palace", "milking pony")
        val cap = MemoryRecallEngine.RECALL_REPEAT_CAP
        repeat(cap) { turn ->
            engine.recallForPrompt("cabbage palace zzqq turn$turn")
        }
        // A NEW memory joins the hits: it must be announced even though the
        // settled one is already silenced.
        val fresh = engine.recallForPrompt("milking pony zzqq fresh")
        assertEquals("only the new memory flows", 1, fresh.size)
        assertTrue(fresh[0].line.contains("milking"))
    }

    @Test
    fun emptyOrNoMatchQueriesBypassTheDedupe() {
        val engine = engineWith("cabbage palace")
        assertTrue(engine.recallForPrompt("").isEmpty())
        assertTrue(engine.recallForPrompt("zzqq xq wv nomatch").isEmpty())
        // State untouched by empty rounds: the memory is announced from a
        // cold start afterwards.
        assertEquals(1, engine.recallForPrompt("cabbage palace zzqq now").size)
    }
}
