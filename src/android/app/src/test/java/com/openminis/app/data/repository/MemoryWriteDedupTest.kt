package com.openminis.app.data.repository

import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [T-memory-dedup-on-write] Write-side signature dedup: a byte-identical
 * entry (modulo whitespace) already in TODAY's log is a model echo — the
 * second write must be skipped, not doubled into recall.
 */
class MemoryWriteDedupTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun repo() = MemoryRepository(tmp.root)

    @Test
    fun `identical entry is skipped on the second write`() {
        val repo = repo()
        val first = repo.writeMemory("用户偏好：深色模式")
        assertTrue(first.startsWith("Memory saved"))
        val second = repo.writeMemory("用户偏好：深色模式")
        assertTrue("duplicate must be skipped: $second", second.startsWith("Skipped"))
    }

    @Test
    fun `trailing whitespace drift does not bypass the dedup`() {
        val repo = repo()
        assertTrue(repo.writeMemory("A/B 测试结论：方案一更好").startsWith("Memory saved"))
        // Line-preserving normalization keeps interior spacing (two code
        // variants must NOT merge) but trailing blank-line drift is still
        // the same entry.
        val drifted = "A/B 测试结论：方案一更好\n\n"
        assertTrue("trailing-drift duplicate must be skipped", repo.writeMemory(drifted).startsWith("Skipped"))
    }

    @Test
    fun `different content writes normally`() {
        val repo = repo()
        assertTrue(repo.writeMemory("偏好 A").startsWith("Memory saved"))
        assertTrue(repo.writeMemory("偏好 B").startsWith("Memory saved"))
    }

    @Test
    fun `dedup is per-day only`() {
        // Entries live in today's file; nothing to compare against on the first
        // write of any day. Covered implicitly by the fresh-folder writes above —
        // this test pins that a dedup skip never blocks a FIRST write.
        val repo = repo()
        val first = repo.writeMemory("首条")
        assertTrue(first.startsWith("Memory saved"))
    }
}
