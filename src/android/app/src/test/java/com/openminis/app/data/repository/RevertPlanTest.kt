package com.openminis.app.data.repository

import com.openminis.app.tools.FileCheckpointStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-p9-revert] Pure-planner contract: per path, the OLDEST post-boundary
 * checkpoint owns the restore target (first-wins, mirroring opencode's
 * plan() `!files.has(file)` semantics); same-millisecond captures count
 * as pre-boundary (the fail-safe direction); created files
 * (existed=false) are planned; directory entries are skipped; the files
 * filter limits the plan; paths group by their source checkpoint.
 */
class RevertPlanTest {

    private fun cp(id: String, createdAt: Long, vararg paths: Pair<String, Boolean>) =
        FileCheckpointStore.Checkpoint(
            id = id,
            createdAt = createdAt,
            label = "test",
            source = "file_write",
            entries = paths.map { (p, existed) ->
                FileCheckpointStore.Entry(
                    path = p,
                    existed = existed,
                    payloadName = if (existed) "0.bin" else null,
                    bytes = if (existed) 10L else 0L,
                    isDir = false,
                )
            },
        )

    @Test
    fun oldestCheckpointWinsPerPath() {
        val plan = RevertPlanner.plan(
            boundaryCreatedAt = 100L,
            checkpoints = listOf(
                cp("b", 300L, "/w/a.txt" to true),
                cp("a", 200L, "/w/a.txt" to true),
            ),
        )
        assertEquals(1, plan.size)
        assertEquals("a", plan[0].checkpointId)
        assertEquals(listOf("/w/a.txt"), plan[0].paths)
    }

    @Test
    fun sameMillisecondAsBoundaryCountsAsPreBoundary() {
        assertTrue(RevertPlanner.plan(100L, listOf(cp("a", 100L, "/w/a.txt" to true))).isEmpty())
    }

    @Test
    fun preBoundaryCheckpointsAreIgnored() {
        assertTrue(RevertPlanner.plan(100L, listOf(cp("a", 50L, "/w/a.txt" to true))).isEmpty())
    }

    @Test
    fun createdFilesArePlanned() {
        val plan = RevertPlanner.plan(100L, listOf(cp("a", 200L, "/w/new.txt" to false)))
        assertEquals(1, plan.size)
        assertEquals(listOf("/w/new.txt"), plan[0].paths)
    }

    @Test
    fun directoryEntriesAreSkipped() {
        val checkpoints = listOf(
            FileCheckpointStore.Checkpoint(
                "a", 200L, "test", "file_write",
                listOf(FileCheckpointStore.Entry("/w/dir", true, null, 0L, true)),
            ),
        )
        assertTrue(RevertPlanner.plan(100L, checkpoints).isEmpty())
    }

    @Test
    fun filesFilterLimitsThePlan() {
        val plan = RevertPlanner.plan(
            100L,
            listOf(cp("a", 200L, "/w/a.txt" to true, "/w/b.txt" to true)),
            files = setOf("/w/b.txt"),
        )
        assertEquals(1, plan.size)
        assertEquals(listOf("/w/b.txt"), plan[0].paths)
    }

    @Test
    fun pathsGroupBySourceCheckpoint() {
        val plan = RevertPlanner.plan(
            100L,
            listOf(
                cp("a", 200L, "/w/a.txt" to true, "/w/b.txt" to true),
                cp("b", 300L, "/w/c.txt" to true),
            ),
        )
        assertEquals(2, plan.size)
        val byCp = plan.associate { it.checkpointId to it.paths }
        assertEquals(setOf("/w/a.txt", "/w/b.txt"), byCp.getValue("a").toSet())
        assertEquals(listOf("/w/c.txt"), byCp.getValue("b"))
    }

    @Test
    fun emptyWhenNoCheckpoints() {
        assertTrue(RevertPlanner.plan(100L, emptyList()).isEmpty())
    }
}
