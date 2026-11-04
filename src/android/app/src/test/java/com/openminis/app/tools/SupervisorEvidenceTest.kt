package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-m9-supervisor-experience] Delivery-evidence gate rules from
 * docs/superpowers/specs/2026-10-24-supervisor-worker-loop-design.md §3.9:
 * the review message that carries DONE must also carry a `## Delivery`
 * section, and every claimed item is checked against disk and the report
 * corpus before the session may land in DONE.
 */
class SupervisorEvidenceTest {

    private val corpus = """
        worker report: wrote build/out.apk
        test run: 42 tests passed, 0 failed
        log line: lateinit property foo was not initialized
    """.trimIndent()

    private fun parse(text: String) = SupervisorEvidence.parseDelivery(text)

    @Test
    fun parseDelivery_returnsNullWithoutMarker() {
        assertNull(parse("DONE — verified: source matches spec, quality good"))
    }

    @Test
    fun parseDelivery_parsesAllThreeClasses() {
        val claim = parse(
            """
            DONE — verified: source matches spec, quality good

            ## Delivery
            files: build/out.apk, notes.md
            tests: 42 tests passed
            log: no fatal lines
            """.trimIndent()
        )!!
        assertEquals(listOf("build/out.apk", "notes.md"), claim.files)
        assertEquals(listOf("42 tests passed"), claim.testNotes)
        assertEquals(listOf("no fatal lines"), claim.logNotes)
    }

    @Test
    fun parseDelivery_isCaseInsensitiveOnClassPrefixes() {
        val claim = parse(
            """
            ## Delivery
            Files: build/out.apk
            TESTS: 42 tests passed
            Log: no fatal lines
            """.trimIndent()
        )!!
        assertEquals(listOf("build/out.apk"), claim.files)
        assertEquals(listOf("42 tests passed"), claim.testNotes)
        assertEquals(listOf("no fatal lines"), claim.logNotes)
    }

    @Test
    fun parseDelivery_sectionStopsAtNextHeader() {
        val claim = parse(
            """
            ## Delivery
            files: build/out.apk
            ## Verification
            files: should/not/count.txt
            """.trimIndent()
        )!!
        assertEquals(listOf("build/out.apk"), claim.files)
        assertTrue(claim.testNotes.isEmpty())
        assertTrue(claim.logNotes.isEmpty())
    }

    @Test
    fun parseDelivery_ignoresProseInsideSection() {
        val claim = parse(
            """
            ## Delivery
            The deliverable sits under the workspace root.
            files: build/out.apk
            """.trimIndent()
        )!!
        assertEquals(listOf("build/out.apk"), claim.files)
    }

    @Test
    fun parseDelivery_returnsNullWhenAllClassesAbsent() {
        assertNull(parse("## Delivery\nsome prose only\n"))
    }

    @Test
    fun parseDelivery_splitsCommaSeparatedItems() {
        val claim = parse("## Delivery\nfiles: a.txt, b/c.txt\ntests: one, two\nlog: x, y\n")!!
        assertEquals(listOf("a.txt", "b/c.txt"), claim.files)
        assertEquals(listOf("one", "two"), claim.testNotes)
        assertEquals(listOf("x", "y"), claim.logNotes)
    }

    @Test
    fun verify_passesWhenEverythingChecksOut() {
        val claim = parse(
            """
            ## Delivery
            files: build/out.apk
            tests: 42 tests passed
            log: lateinit property foo
            """.trimIndent()
        )!!
        val check = SupervisorEvidence.verify(claim, { it == "build/out.apk" }, corpus)
        assertTrue(check is SupervisorEvidence.Check.Pass)
    }

    @Test
    fun verify_reportsMissingFile() {
        val claim = parse("## Delivery\nfiles: build/out.apk\ntests: 42 tests passed\nlog: lateinit property foo\n")!!
        val check = SupervisorEvidence.verify(claim, { false }, corpus)
        assertTrue(check is SupervisorEvidence.Check.Fail)
        val fail = check as SupervisorEvidence.Check.Fail
        assertTrue(fail.reasons.any { it.contains("file not found: build/out.apk") })
    }

    @Test
    fun verify_requiresEveryEvidenceClass() {
        val claim = parse("## Delivery\nfiles: build/out.apk\n")!!
        val check = SupervisorEvidence.verify(claim, { true }, corpus)
        assertTrue(check is SupervisorEvidence.Check.Fail)
        val reasons = (check as SupervisorEvidence.Check.Fail).reasons
        assertTrue(reasons.any { it == "no test evidence listed" })
        assertTrue(reasons.any { it == "no log evidence listed" })
    }

    @Test
    fun verify_matchesLongTokensCaseInsensitively() {
        val claim = parse("## Delivery\nfiles: build/out.apk\ntests: 42 PASSED\negg: ignored\nlog: LATEINIT PROPERTY\n")!!
        val check = SupervisorEvidence.verify(claim, { it == "build/out.apk" }, corpus)
        // "passed" matches "passed", "lateinit"/"property" match the corpus
        // line — all case-insensitively, so the claim passes.
        assertTrue(check is SupervisorEvidence.Check.Pass)
    }

    @Test
    fun verify_noteNotInCorpusFails() {
        val claim = parse("## Delivery\nfiles: build/out.apk\ntests: 42 tests passed\nlog: kernel panic reboot\n")!!
        val check = SupervisorEvidence.verify(claim, { true }, corpus)
        assertTrue(check is SupervisorEvidence.Check.Fail)
        val reasons = (check as SupervisorEvidence.Check.Fail).reasons
        assertTrue(reasons.any { it.contains("kernel panic reboot") })
    }

    @Test
    fun verify_shortNoteMatchesAsWhole() {
        // No token of 4+ chars: the whole note must appear in the corpus.
        val claim = parse("## Delivery\nfiles: build/out.apk\ntests: zzz\nlog: bar\n")!!
        val check = SupervisorEvidence.verify(claim, { true }, corpus)
        assertTrue(check is SupervisorEvidence.Check.Fail)
        val reasons = (check as SupervisorEvidence.Check.Fail).reasons
        assertTrue(reasons.any { it.contains("zzz") })
        assertTrue(reasons.any { it.contains("bar") })
    }

    @Test
    fun normalizePath_unifiesSeparatorsAndDotPrefix() {
        assertEquals("a/b/c.txt", SupervisorEvidence.normalizePath("a\\b//c.txt"))
        assertEquals("a.txt", SupervisorEvidence.normalizePath("././a.txt"))
        assertEquals("a.txt", SupervisorEvidence.normalizePath("  a.txt  "))
    }

    @Test
    fun bounceNote_namesEveryFailedItem() {
        val fail = SupervisorEvidence.Check.Fail(listOf("file not found: x", "no log evidence listed"))
        val note = SupervisorEvidence.bounceNote(fail)
        assertTrue(note.contains("file not found: x"))
        assertTrue(note.contains("no log evidence listed"))
        assertTrue(note.contains("resend DONE"))
    }

    @Test
    fun emptyClaimIsEmptyAndNullParsed() {
        val claim = SupervisorEvidence.DeliveryClaim(emptyList(), emptyList(), emptyList())
        assertTrue(claim.isEmpty)
        val check = SupervisorEvidence.verify(claim, { true }, corpus)
        assertTrue(check is SupervisorEvidence.Check.Fail)
        assertFalse((check as SupervisorEvidence.Check.Fail).reasons.isEmpty())
    }
}
