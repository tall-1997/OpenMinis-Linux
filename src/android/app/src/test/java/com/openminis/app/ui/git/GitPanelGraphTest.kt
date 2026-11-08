package com.openminis.app.ui.git

import com.openminis.app.git.GitCli.GitCommit
import com.openminis.app.git.GitCli.GitFileEntry
import com.openminis.app.git.GitCli.GitStatus
import com.openminis.app.git.GitCli.NumStat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-function lock for the panel's derived data: lane layout, stats
 * aggregation, remote display form and the local history filter. Everything
 * here runs without Android, so the graph math stays regression-proof.
 */
class GitPanelGraphTest {

    private fun commit(hash: String, parents: List<String> = emptyList()) =
        GitCommit(
            hash = hash,
            shortHash = hash.take(7),
            author = "a",
            epochSeconds = 1L,
            subject = "s",
            parents = parents,
        )

    // ------------------------------------------------------------ graphLayout

    @Test
    fun `linear history keeps one straight lane`() {
        val rows = graphLayout(listOf(commit("c3", listOf("c2")), commit("c2", listOf("c1")), commit("c1")))
        assertEquals(listOf(0, 0, 0), rows.map { it.lane })
        assertEquals(listOf(listOf(0), listOf(0), listOf(0)), rows.map { it.activeLanes })
        assertTrue(rows.none { it.isMerge })
    }

    @Test
    fun `merge commit draws the merge ring and connector lane`() {
        val commits = listOf(
            commit("m", listOf("a", "b")),
            commit("a", listOf("base")),
            commit("b", listOf("base")),
            commit("base"),
        )
        val rows = graphLayout(commits)
        assertTrue(rows[0].isMerge)
        assertEquals(0, rows[0].lane)
        // both parents' connectors cross the merge row
        assertEquals(2, rows[0].activeLanes.size)
        // side parent takes a second lane
        assertTrue(rows.map { it.lane }.any { it >= 1 })
    }

    @Test
    fun `fork point carries the diamond flag`() {
        val commits = listOf(
            commit("b2", listOf("base")),
            commit("b1", listOf("base")),
            commit("base"),
        )
        val rows = graphLayout(commits)
        assertTrue(rows[2].isFork)
        assertFalse(rows[0].isFork)
    }

    @Test
    fun `empty and single commit windows degrade cleanly`() {
        assertTrue(graphLayout(emptyList()).isEmpty())
        val one = graphLayout(listOf(commit("c1")))
        assertEquals(1, one.size)
        assertEquals(0, one[0].lane)
        assertEquals(listOf(0), one[0].activeLanes)
        assertFalse(one[0].isMerge)
    }

    @Test
    fun `first-parent chain stays in lane zero while side rails own new lanes`() {
        val commits = listOf(
            commit("h5", listOf("h4")),
            commit("h4", listOf("h3")),
            commit("h3", listOf("h2")),
            commit("h2", listOf("h1", "s1")),
            commit("s1", listOf("h1")),
            commit("h1"),
        )
        val rows = graphLayout(commits)
        // h1 sits at lane 0 and stays there through h5 (first-parent chain)
        assertEquals(0, rows.last().lane)
        assertEquals(0, rows[0].lane)
        // s1 and h2's connector keep lane 1 alive through the merge row (h2)
        assertEquals(listOf(0, 1), rows[3].activeLanes)
        assertEquals(1, rows[4].lane)
    }

    // ------------------------------------------------------------ changesTotals

    @Test
    fun `totals merge staged and unstaged numstat maps by row kind`() {
        val status = GitStatus(
            entries = listOf(
                GitFileEntry(path = "a.kt", code = 'M', staged = true),
                GitFileEntry(path = "b.kt", code = 'M', staged = false),
                GitFileEntry(path = "c.bin", code = 'M', staged = false),
            ),
        )
        val t = changesTotals(
            status,
            staged = mapOf("a.kt" to NumStat(12, 3)),
            unstaged = mapOf("b.kt" to NumStat(0, 9), "c.bin" to NumStat(-1, -1, binary = true)),
        )
        assertEquals(3, t.files)
        assertEquals(12, t.added)
        assertEquals(12, t.deleted)
    }

    @Test
    fun `totals treat null status and missing stat rows as zero`() {
        assertEquals(FileStatTotals(), changesTotals(null, emptyMap(), emptyMap()))
        val t = changesTotals(
            GitStatus(entries = listOf(GitFileEntry(path = "x", code = '?', staged = false, untracked = true))),
            emptyMap(),
            emptyMap(),
        )
        assertEquals(1, t.files)
        assertEquals(0, t.added)
        assertEquals(0, t.deleted)
    }

    // ------------------------------------------------------------ displayRemote

    @Test
    fun `remote display form strips scheme user colon and dot git`() {
        assertEquals("github.com/tall-1997/OpenMinis-Linux", displayRemote("https://github.com/tall-1997/OpenMinis-Linux.git"))
        assertEquals("github.com/tall-1997/OpenMinis-Linux", displayRemote("git@github.com:tall-1997/OpenMinis-Linux.git"))
        assertEquals("gitlab.com/group/repo", displayRemote("https://gitlab.com/group/repo"))
    }

    @Test
    fun `remote display form handles blank and bare host`() {
        assertNull(displayRemote(null))
        assertNull(displayRemote("   "))
        assertNull(displayRemote("https://"))
        assertEquals("github.com", displayRemote("https://github.com.git"))
    }

    // ------------------------------------------------------------ filterHistory

    @Test
    fun `filter matches subject author and hash case-insensitively`() {
        val log = listOf(
            commit("aabbccd").copy(subject = "Fix null crash"),
            commit("eeddccb").copy(author = "Alice"),
            commit("ffeeaab").copy(subject = "WIP"),
        )
        assertEquals(1, filterHistory(log, "null crash").size)
        assertEquals(1, filterHistory(log, "ALICE").size)
        assertEquals(1, filterHistory(log, "aabbccd").size)
        assertEquals(1, filterHistory(log, "aabb").size) // shortHash
    }

    @Test
    fun `filter returns the window untouched on blank query`() {
        val log = listOf(commit("a"), commit("b"))
        assertEquals(log, filterHistory(log, ""))
        assertEquals(log, filterHistory(log, "   "))
    }

    @Test
    fun `filter can drop everything`() {
        assertTrue(filterHistory(listOf(commit("a")), "zzz-no-match").isEmpty())
    }
}
