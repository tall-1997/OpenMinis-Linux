package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [T-memory-recall-io] One prompt build must cost ONE sidecar write, and the
 * keyword extractor must stay bounded on both axes (query length, function
 * words). Both are prompt-path costs paid on every turn.
 */
class MemoryRecallEngineBatchTest {

    private fun tempDir(): File = Files.createTempDirectory("recall-batch").toFile()

    private fun engine() = MemoryRecallEngine.fromDir(tempDir())!!

    private fun hit(source: String, line: String) = MemoryRecallEngine.RecallHit(
        source = source,
        line = line,
        day = source.removeSuffix(".md"),
        score = 1.0,
        recallCount = 0,
        archived = false,
    )

    @Test
    fun onePromptBuildWritesTheSidecarOnce() {
        val engine = engine()
        engine.formatAsPromptFragment(
            listOf(
                hit("2026-11-04.md", "line one"),
                hit("2026-11-04.md", "line two"),
                hit("2026-11-03.md", "line three"),
            ),
        )
        assertEquals("one build, one write", 1, engine.saveCountForTest)
        val snapshot = engine.recallCountsSnapshot()
        assertEquals(1, snapshot["2026-11-04:line one"])
        assertEquals(1, snapshot["2026-11-04:line two"])
        assertEquals(1, snapshot["2026-11-03:line three"])
    }

    @Test
    fun anEmptyHitListWritesNothing() {
        val engine = engine()
        engine.formatAsPromptFragment(emptyList())
        assertEquals(0, engine.saveCountForTest)
    }

    @Test
    fun queryIsCappedAtTheCharacterLimit() {
        val engine = engine()
        val insideCap = "alpha"
        val beyondCap = "zzzzunicorn"
        val query = insideCap + " " + "b".repeat(MemoryRecallEngine.QUERY_CHAR_CAP) + " " + beyondCap
        val keywords = engine.keywordsForTest(query)
        assertTrue("token before the cap must survive", keywords.contains(insideCap))
        assertFalse("token past the cap must not be tokenized", keywords.contains(beyondCap))
    }

    @Test
    fun englishStopwordsAreDropped() {
        val keywords = engine().keywordsForTest("the quick brown fox and the lazy dog")
        assertFalse(keywords.contains("the"))
        assertFalse(keywords.contains("and"))
        assertTrue(keywords.contains("quick"))
        assertTrue(keywords.contains("brown"))
    }

    @Test
    fun cjkFunctionGramsAreDroppedButContentGramsSurvive() {
        val keywords = engine().keywordsForTest("我们在讨论记忆检索")
        assertFalse("a 2-gram of two function chars is noise", keywords.contains("我们"))
        assertTrue("content 2-grams must survive", keywords.contains("记忆"))
    }
}
