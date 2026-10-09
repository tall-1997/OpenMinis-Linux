package com.openminis.app.harness.checkpoint

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Adapted from taixu CheckpointByteBudgetTest (GPL-3.0-or-later).
 * https://github.com/wkbin/taixu
 *
 * checkpoint 字节预算测试：超预算按最旧整轮淘汰，当前轮受保护；预算内不误删；
 * 会话之间互不影响。
 */
class CheckpointByteBudgetTest {

    @get:Rule
    val temp = TemporaryFolder()

    private fun checkpoint(turn: Int, content: String) = Checkpoint(
        turn = turn,
        time = 1L,
        prompt = "turn $turn",
        files = listOf(FileSnap(path = "a.txt", content = content)),
    )

    private fun remainingTurns(sessionDir: File): List<Int> =
        sessionDir.listFiles { f -> f.isDirectory }
            ?.mapNotNull { it.name.toIntOrNull() }
            ?.sorted()
            .orEmpty()

    private fun totalBytes(sessionDir: File): Long =
        sessionDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    @Test
    fun `budget keeps newest turns and drops oldest`() {
        val root = temp.newFolder("ckpt")
        val persistence = FileCheckpointPersistence(root, maxSessionBytes = 2_500L)
        // 每轮快照 ~1.2KB，写 3 轮超 2.5KB 预算 → 最早的轮被整轮淘汰
        (1..3).forEach { turn -> persistence.write("s", checkpoint(turn, "内容".repeat(turn * 200))) }

        val sessionDir = File(root, "s")
        val turns = remainingTurns(sessionDir)
        assertTrue("应淘汰最旧轮：$turns", 1 !in turns)
        assertTrue("当前轮必须保留", 3 in turns)
        assertTrue("预算应收敛", totalBytes(sessionDir) <= 2_500L + 1_300L)
    }

    @Test
    fun `within budget nothing is removed`() {
        val root = temp.newFolder("ckpt")
        val persistence = FileCheckpointPersistence(root, maxSessionBytes = 10_000L)
        (1..3).forEach { turn -> persistence.write("s", checkpoint(turn, "小内容")) }
        assertEquals(listOf(1, 2, 3), remainingTurns(File(root, "s")))
    }

    @Test
    fun `different sessions are budgeted independently`() {
        val root = temp.newFolder("ckpt")
        val persistence = FileCheckpointPersistence(root, maxSessionBytes = 2_500L)
        (1..3).forEach { turn -> persistence.write("a", checkpoint(turn, "内容".repeat(turn * 200))) }
        persistence.write("b", checkpoint(1, "小"))
        assertFalse(remainingTurns(File(root, "b")).isEmpty())
    }
}
