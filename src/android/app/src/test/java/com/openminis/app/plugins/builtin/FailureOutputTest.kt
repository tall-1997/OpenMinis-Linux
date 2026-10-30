package com.openminis.app.plugins.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the failure-reason contract: a user-facing diagnosis may never be a
 * PRoot allocator dump, and the real cause (printed before the dump) must
 * survive the summary.
 *
 * The regression this exists for: a device whose Node download timed out
 * showed "node gate: HandlerEntry contains 32 bytes in 2 blocks (ref 0)" —
 * `lines.takeLast(8)` kept only the talloc leak report PRoot prints while
 * unwinding, because the dump is always last.
 */
class FailureOutputTest {

    /** A real dump shape: header + one row per allocation site. */
    private val tallocDump = listOf(
        "full talloc report on 'tail' (total   1374 bytes in    61 blocks)",
        "/bin/sh contains      576 bytes in      9 blocks (ref 0) 0x7f8c1a2b3c40",
        "HandlerEntry contains  32 bytes in      2 blocks (ref 0) 0x7f8c1a2b3d10",
        "loadparm.c:1200 contains  16 bytes in   1 blocks (ref 0) 0x7f8c1a2b3e00",
    )

    @Test
    fun `allocator rows count as noise`() {
        assertTrue(FailureOutput.isNoise("/bin/sh contains 576 bytes in 9 blocks (ref 0) 0x1"))
        assertTrue(FailureOutput.isNoise("HandlerEntry contains 32 bytes in 2 blocks (ref 0)"))
        assertTrue(FailureOutput.isNoise("full talloc report on 'tail' (total 1374 bytes in 61 blocks)"))
    }

    @Test
    fun `real causes are not noise`() {
        assertFalse(FailureOutput.isNoise("curl: (28) Connection timed out after 30001 milliseconds"))
        assertFalse(FailureOutput.isNoise("unzip: cannot find or open ofm.zip"))
        assertFalse(FailureOutput.isNoise("payload missing scripts/install.sh"))
        // A line that merely mentions bytes/blocks is not a dump row.
        assertFalse(FailureOutput.isNoise("downloaded 1457409 bytes in 3 blocks of retries"))
    }

    @Test
    fun `dump tail does not hide the cause that came before it`() {
        val lines = listOf(
            "curl: (28) Connection timed out after 30001 milliseconds",
            "node gate failed",
        ) + tallocDump
        assertEquals(
            "curl: (28) Connection timed out after 30001 milliseconds / node gate failed",
            FailureOutput.summarize(lines),
        )
    }

    @Test
    fun `a pure dump yields an empty reason, never an allocator row`() {
        // The old implementation returned "HandlerEntry contains 32 bytes…".
        assertEquals("", FailureOutput.summarize(tallocDump))
    }

    @Test
    fun `only the last meaningful lines are kept and the result is capped`() {
        val lines = (1..50).map { "step $it: ok" } + listOf(
            "line-1",
            "line-2",
            "line-3",
            "line-4",
        )
        assertEquals("line-2 / line-3 / line-4", FailureOutput.summarize(lines))
        assertEquals(10, FailureOutput.summarize(lines, maxChars = 10).length)
    }

    @Test
    fun `sanitize drops a persisted dump and keeps a real reason`() {
        assertEquals("", FailureOutput.sanitizeStoredDiagnosis(""))
        assertEquals(
            "",
            FailureOutput.sanitizeStoredDiagnosis(
                "node gate: HandlerEntry contains 32 bytes in 2 blocks (ref 0) 0x7f",
            ),
        )
        assertEquals(
            "node: exit 4 — nodesource install failed",
            FailureOutput.sanitizeStoredDiagnosis("node: exit 4 — nodesource install failed"),
        )
    }
}
