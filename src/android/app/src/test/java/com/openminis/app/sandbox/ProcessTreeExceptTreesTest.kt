package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Pure-JVM tests for [ProcessTree.exceptTrees] — the [T-job-tree-spare]
 * subtraction that keeps a model-started background job's tree out of every
 * kill sweep (user Stop, watchdog timeout, process cap, memory pressure).
 */
class ProcessTreeExceptTreesTest {

    private fun link(pid: Int, ppid: Int, tracer: Int = 0) = ProcLink(pid, ppid, tracer)

    // The persistent shell's tree: proot(100) -> bash(110) -> cmd(120), and a
    // job the model backgrounded: setsid sh(200) -> child(210). The tree walk
    // from 100 reaches 200 via ppid and 210 via ppid=200.
    private val links = listOf(
        link(110, 100),
        link(120, 110),
        link(200, 110),
        link(210, 200),
    )

    @Test
    fun `job tree and its descendants are spared from the shell tree`() {
        val victims = setOf(100, 110, 120, 200, 210)
        val spared = ProcessTree.exceptTrees(victims, setOf(200), links)
        assertEquals(setOf(100, 110, 120), spared)
    }

    @Test
    fun `job descendants are spared even when the sweep reached them via tracer`() {
        // A PRoot reparenting artifact can hand the sweep pids whose only link
        // into the shell tree is a tracer edge; whatever route the walk took,
        // a pid inside a job's tree must come out of the victim set.
        val victims = setOf(100, 110, 200, 210)
        val spared = ProcessTree.exceptTrees(victims, setOf(200), links)
        assertEquals(setOf(100, 110), spared)
    }

    @Test
    fun `empty job roots return the victim set untouched`() {
        val victims = setOf(100, 110)
        assertSame(victims, ProcessTree.exceptTrees(victims, emptySet(), links))
    }

    @Test
    fun `non-positive job roots are skipped`() {
        val victims = setOf(100, 110)
        assertEquals(victims, ProcessTree.exceptTrees(victims, setOf(0, -1), links))
    }

    @Test
    fun `input set is never mutated`() {
        val victims = LinkedHashSet(setOf(100, 110, 200))
        ProcessTree.exceptTrees(victims, setOf(200), links)
        assertEquals(setOf(100, 110, 200), victims)
    }

    @Test
    fun `disjoint victims are returned as-is`() {
        val victims = setOf(100, 110)
        assertEquals(victims, ProcessTree.exceptTrees(victims, setOf(200), links))
    }
}
