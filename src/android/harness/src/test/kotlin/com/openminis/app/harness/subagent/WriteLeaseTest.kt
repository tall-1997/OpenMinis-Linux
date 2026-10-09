package com.openminis.app.harness.subagent

import com.openminis.app.harness.HarnessTool
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-seam-extraction] 写租约闸门验收：规则与上游逐条对齐——
 * 未声明=只读拒写、整工作区租约全放、`../` 逃顶层拒、前缀匹配、
 * 非结构化写工具与 mcp 不受租约约束、写意图判定保守。
 */
class WriteLeaseTest {

    private fun args(path: String) = JsonObject(mapOf("path" to JsonPrimitive(path)))

    @Test
    fun `undeclared write paths means read only lane`() {
        val verdict = WriteLeaseGate.check(WriteLease.NONE, HarnessTool.WRITE, "file_write", args("docs/a.md"))
        assertTrue(verdict is LeaseVerdict.Denied)
        assertTrue((verdict as LeaseVerdict.Denied).reason.contains("只读任务"))
    }

    @Test
    fun `whole workspace lease allows everything inside`() {
        assertTrue(WriteLeaseGate.check(WriteLease(listOf("*")), HarnessTool.EDIT, "file_edit", args("any/where.md")) is LeaseVerdict.Allowed)
        assertTrue(WriteLeaseGate.check(WriteLease(listOf(".")), HarnessTool.WRITE, "file_write", args("x.md")) is LeaseVerdict.Allowed)
    }

    @Test
    fun `dot dot escape out of workspace top is denied`() {
        val verdict = WriteLeaseGate.check(WriteLease(listOf("docs")), HarnessTool.WRITE, "file_write", args("../etc/passwd"))
        assertTrue(verdict is LeaseVerdict.Denied)
        assertTrue((verdict as LeaseVerdict.Denied).reason.contains("逃出工作区顶层"))
    }

    @Test
    fun `prefix scope matches exactly or as a directory`() {
        val lease = WriteLease(listOf("docs/reports"))
        assertTrue(WriteLeaseGate.check(lease, HarnessTool.WRITE, "file_write", args("docs/reports")) is LeaseVerdict.Allowed)
        assertTrue(WriteLeaseGate.check(lease, HarnessTool.WRITE, "file_write", args("docs/reports/q3.md")) is LeaseVerdict.Allowed)
        val sibling = WriteLeaseGate.check(lease, HarnessTool.WRITE, "file_write", args("docs/reports2.md"))
        assertTrue("reports2.md is not inside reports/", sibling is LeaseVerdict.Denied)
        assertTrue((sibling as LeaseVerdict.Denied).reason.contains("docs/reports"))
    }

    @Test
    fun `missing path argument defers to the schema layer`() {
        assertTrue(
            WriteLeaseGate.check(WriteLease(listOf("docs")), HarnessTool.WRITE, "file_write", JsonObject(emptyMap()))
                is LeaseVerdict.Allowed,
        )
    }

    @Test
    fun `non write tools and mcp tools are outside the lease`() {
        assertTrue(WriteLeaseGate.check(WriteLease.NONE, HarnessTool.READ, "file_read", args("any.md")) is LeaseVerdict.Allowed)
        assertTrue(WriteLeaseGate.check(WriteLease.NONE, HarnessTool.BASE, "shell_execute", JsonObject(emptyMap())) is LeaseVerdict.Allowed)
        assertTrue(
            WriteLeaseGate.check(WriteLease.NONE, HarnessTool.WRITE, "mcp__fs__write", args("any.md")) is LeaseVerdict.Allowed,
        )
    }

    @Test
    fun `download lease reads the destination key`() {
        val verdict = WriteLeaseGate.check(WriteLease(listOf("dl")), HarnessTool.DOWNLOAD, "download",
            JsonObject(mapOf("destination" to JsonPrimitive("elsewhere/f.bin"))))
        assertTrue(verdict is LeaseVerdict.Denied)
    }

    @Test
    fun `write intent detection stays conservative`() {
        assertTrue(declaresWriteIntent("把结果落盘到报告里"))
        assertTrue(declaresWriteIntent("write the file report.md"))
        assertTrue(declaresWriteIntent("保存到 output.txt"))
        // 高频动词无文件名佐证不算
        assertFalse(declaresWriteIntent("给出修改代码的建议"))
        // 纯分析任务
        assertFalse(declaresWriteIntent("分析这段日志并总结"))
    }

    @Test
    fun `target label joins tool and path for summaries`() {
        assertEquals("file_write docs/a.md", WriteLeaseGate.targetLabel("file_write", args("docs/a.md")))
    }
}
