package com.openminis.app.harness.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-seam-extraction] 循环片段（子代理扇出规划）的验收：
 * 首个 spawn 触发整批扇出、peer 顺序与波内序号、写者计数聚合、
 * 无 spawn 时零扇出。
 */
class SubAgentFanOutPlannerTest {

    private val spawnNames = setOf("spawn_agent", "run_subagent")

    private fun isSpawn(name: String) = name in spawnNames

    @Test
    fun `no spawn calls means no fan-out`() {
        val plan = SubAgentFanOutPlanner.plan(
            toolCalls = listOf(
                Triple("c1", "file_read", "{}"),
                Triple("c2", "shell_execute", "{}"),
            ),
            isSpawnTool = ::isSpawn,
            batchWriterCount = { 0 },
        )
        assertFalse(plan.fanOut)
        assertTrue(plan.peers.isEmpty())
        assertEquals(0, plan.writerCount)
    }

    @Test
    fun `spawn peers keep tool-call order and carry wave metadata`() {
        val plan = SubAgentFanOutPlanner.plan(
            toolCalls = listOf(
                Triple("c1", "file_read", "{}"),
                Triple("c2", "spawn_agent", """{"kind":"worker"}"""),
                Triple("c3", "spawn_agent", """{"kind":"explore"}"""),
                Triple("c4", "run_subagent", """{"kind":"worker"}"""),
            ),
            isSpawnTool = ::isSpawn,
            batchWriterCount = { args -> if (args.contains("worker")) 1 else 0 },
        )
        assertTrue(plan.fanOut)
        assertEquals(listOf("c2", "c3", "c4"), plan.peers.map { it.toolCallId })
        assertEquals(3, plan.peers[0].waveSize)
        assertEquals(0, plan.peers[0].waveIndex)
        assertEquals(2, plan.peers[2].waveIndex)
        // 两个 worker 批次 + 一个 explore 批次
        assertEquals(2, plan.writerCount)
    }

    @Test
    fun `writer count sums across multi-spawn batches`() {
        val plan = SubAgentFanOutPlanner.plan(
            toolCalls = listOf(
                Triple("c1", "spawn_agent", """{"spawns":[{"kind":"worker"},{"kind":"worker"},{"kind":"explore"}]}"""),
            ),
            isSpawnTool = ::isSpawn,
            batchWriterCount = { args -> if (args.contains("spawns")) 2 else 0 },
        )
        assertTrue(plan.fanOut)
        assertEquals(2, plan.writerCount)
        assertEquals(1, plan.peers.size)
        assertEquals(1, plan.peers[0].waveSize)
    }
}
