package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-memory-frontmatter] Backward compatibility is the hard requirement: a file
 * with no frontmatter must be exactly as eligible as before, and a malformed
 * block must degrade to "no metadata", never to "hide the file".
 */
class MemoryMetadataTest {

    private val day = 86_400_000L

    @Test
    fun aPlainMarkdownFileHasNoMetadataAndIsEligible() {
        val text = "<!-- 2026-11-19 10:00:00 -->\nsome note\n"
        val meta = MemoryMetadataParser.parse(text)
        assertEquals(null, meta.type)
        assertFalse(meta.disabled)
        assertEquals(null, meta.ttlDays)
        assertTrue(meta.isEligible(nowMs = 1_000_000L, fileModifiedAtMs = 0L))
    }

    @Test
    fun disabledFilesAreIneligible() {
        val text = "---\ndisabled: true\n---\nbody\n"
        assertFalse(MemoryMetadataParser.isEligible(text, 1_000L, 0L))
    }

    @Test
    fun ttlExpiresFromTheFileMtime() {
        val text = "---\nttl_days: 2\n---\nbody\n"
        val modified = 10 * day
        // One day old: still eligible.
        assertTrue(MemoryMetadataParser.isEligible(text, modified + day, modified))
        // Three days old: expired.
        assertFalse(MemoryMetadataParser.isEligible(text, modified + 3 * day, modified))
    }

    @Test
    fun aZeroOrNegativeTtlNeverExpires() {
        val text = "---\nttl_days: 0\n---\nbody\n"
        assertTrue(MemoryMetadataParser.isEligible(text, 10_000 * day, 0L))
    }

    @Test
    fun anUnterminatedBlockIsNotMetadata() {
        // A file may legitimately start with a horizontal rule; treating the
        // whole body as metadata would silently hide it.
        val text = "---\nthis is prose, not frontmatter\n"
        val meta = MemoryMetadataParser.parse(text)
        assertEquals(null, meta.type)
        assertFalse(meta.disabled)
        assertTrue(meta.isEligible(1_000L, 0L))
    }

    @Test
    fun unknownKeysAndMalformedLinesAreIgnored() {
        val text = "---\ntype: project\nunknown_key: whatever\nmalformed line\nttl_days: not-a-number\n---\nbody\n"
        val meta = MemoryMetadataParser.parse(text)
        assertEquals("project", meta.type)
        assertEquals(null, meta.ttlDays)
        assertTrue(meta.isEligible(1_000L, 0L))
    }

    @Test
    fun quotedValuesAndAllSupportedKeysParse() {
        val text = "---\ntype: \"feedback\"\nscope: 'private'\nsupersedes: earlier-note\ndisabled: false\n---\nbody\n"
        val meta = MemoryMetadataParser.parse(text)
        assertEquals("feedback", meta.type)
        assertEquals("private", meta.scope)
        assertEquals("earlier-note", meta.supersedes)
        assertFalse(meta.disabled)
    }

    @Test
    fun metadataPrecedesTheBodySoTheBodyStaysReadable() {
        val text = "---\ntype: reference\n---\n<!-- ts -->\nnote\n"
        assertTrue(text.contains("note"))
        assertEquals("reference", MemoryMetadataParser.parse(text).type)
    }
}
