package com.openminis.app.backup

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 备份到本地文件夹时进度回调逐百分比追加，一个大文件能堆上万行，
 * 历史页一次加载全部记录就卡死。连续进度行只留最后一条。
 */
class BackupLogCompactionTest {

    private fun line(message: String, problem: Boolean = false) =
        BackupHistory.LogEntry(at = 0L, message = message, isProblem = problem)

    @Test
    fun `a run of progress lines collapses to its last line`() {
        val log = (1..100).map { line("Copying to 下载… $it%") } + line("Copying to 下载… done")
        val out = BackupLogCompaction.compact(log, maxLines = 200)
        assertEquals(listOf("Copying to 下载… done"), out.map { it.message })
    }

    @Test
    fun `progress runs for different destinations each keep their last line`() {
        val log = listOf(
            line("Sending to NAS… 10%"), line("Sending to NAS… 100%"),
            line("Copying to 下载… 5%"), line("Copying to 下载… 100%"),
        )
        val out = BackupLogCompaction.compact(log, maxLines = 200)
        assertEquals(listOf("Sending to NAS… 100%", "Copying to 下载… 100%"), out.map { it.message })
    }

    @Test
    fun `problems and ordinary lines are kept verbatim`() {
        val log = listOf(
            line("Packing files"),
            line("Copying to 下载… 50%"),
            line("Copying to 下载… failed: no space", problem = true),
            line("Finished"),
        )
        val out = BackupLogCompaction.compact(log, maxLines = 200)
        // 失败是该目的地的最终状态，替换掉前面的进度行，不与「50%」并列。
        assertEquals(
            listOf("Packing files", "Copying to 下载… failed: no space", "Finished"),
            out.map { it.message },
        )
        assertEquals(true, out[1].isProblem)
    }

    @Test
    fun `output is capped at maxLines keeping the tail`() {
        val log = (1..10).map { line("step $it") }
        val out = BackupLogCompaction.compact(log, maxLines = 3)
        assertEquals(listOf("step 8", "step 9", "step 10"), out.map { it.message })
    }
}
