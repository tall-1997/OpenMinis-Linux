package com.openminis.app.data.repository

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-memory-recall-multisource] The knowledge-note mirror that lets recall cover
 * more than the `memory/` markdown files. Title matches weigh double, and a
 * snippet is centred on the match so the prompt shows *why* a note was picked.
 */
class KnowledgeNoteIndexTest {

    @Before
    fun setUp() = KnowledgeNoteIndex.clearForTest()

    @After
    fun tearDown() = KnowledgeNoteIndex.clearForTest()

    @Test
    fun anEmptyMirrorFindsNothing() {
        assertTrue(KnowledgeNoteIndex.search(setOf("gradle")).isEmpty())
    }

    @Test
    fun titleMatchesOutrankBodyOnlyMatches() {
        KnowledgeNoteIndex.refresh(
            listOf(
                "Release checklist" to "unrelated prose about builds",
                "Random note" to "we should check the release checklist before tagging",
            ),
        )
        val hits = KnowledgeNoteIndex.search(setOf("release", "checklist"))
        assertEquals("Release checklist", hits.first().title)
        assertTrue("title hits weigh double", hits.first().score > hits.last().score)
    }

    @Test
    fun excerptIsCentredOnTheMatch() {
        val body = "filler ".repeat(80) + "TARGETWORD here " + "tail ".repeat(80)
        KnowledgeNoteIndex.refresh(listOf("note" to body))
        val excerpt = KnowledgeNoteIndex.search(setOf("targetword")).single().excerpt
        assertTrue(excerpt.contains("TARGETWORD"))
        assertTrue("excerpt is clipped", excerpt.length <= KnowledgeNoteIndex.EXCERPT_CHARS + 1)
    }

    @Test
    fun noKeywordMatchMeansNoHit() {
        KnowledgeNoteIndex.refresh(listOf("note" to "completely unrelated"))
        assertTrue(KnowledgeNoteIndex.search(setOf("gradle")).isEmpty())
    }

    @Test
    fun stalenessIsTtlBased() {
        KnowledgeNoteIndex.clearForTest()
        assertTrue("never loaded counts as stale", KnowledgeNoteIndex.isStale())
        KnowledgeNoteIndex.refresh(listOf("a" to "b"), nowMs = 1_000L)
        assertFalse(KnowledgeNoteIndex.isStale(nowMs = 1_000L + KnowledgeNoteIndex.TTL_MS - 1))
        assertTrue(KnowledgeNoteIndex.isStale(nowMs = 1_000L + KnowledgeNoteIndex.TTL_MS))
    }

    @Test
    fun limitCapsTheNumberOfHits() {
        KnowledgeNoteIndex.refresh((1..10).map { "note $it" to "gradle $it" })
        assertEquals(3, KnowledgeNoteIndex.search(setOf("gradle"), limit = 3).size)
    }
}
