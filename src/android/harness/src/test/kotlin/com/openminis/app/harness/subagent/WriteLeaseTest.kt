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
    fun `non write tools are outside the lease`() {
        assertTrue(WriteLeaseGate.check(WriteLease.NONE, HarnessTool.READ, "file_read", args("any.md")) is LeaseVerdict.Allowed)
        assertTrue(WriteLeaseGate.check(WriteLease.NONE, HarnessTool.BASE, "shell_execute", JsonObject(emptyMap())) is LeaseVerdict.Allowed)
    }

    // ——— [T-p2-writelease-mcp-gap] mcp__* 不再无条件放行 ———

    @Test
    fun `mcp tools are denied on a read only lane`() {
        // 副作用面无法验证：只读 lane 一律拒（旧实现对 mcp__ 写工具完整放行）。
        val verdict = WriteLeaseGate.check(WriteLease.NONE, HarnessTool.WRITE, "mcp__fs__write", args("any.md"))
        assertTrue(verdict is LeaseVerdict.Denied)
        assertTrue((verdict as LeaseVerdict.Denied).reason.contains("副作用面"))
    }

    @Test
    fun `write shaped mcp tools are denied under a scoped lease`() {
        val verdict = WriteLeaseGate.check(
            WriteLease(listOf("docs")), HarnessTool.BASE, "mcp__fs__save_file", args("docs/x.md"),
        )
        assertTrue("路径参数不受租约约束的写形 mcp 工具必须拒", verdict is LeaseVerdict.Denied)
    }

    @Test
    fun `read shaped mcp tools stay allowed under a scoped lease`() {
        assertTrue(
            WriteLeaseGate.check(
                WriteLease(listOf("docs")), HarnessTool.BASE, "mcp__search__query", JsonObject(emptyMap()),
            ) is LeaseVerdict.Allowed,
        )
    }

    // ——— [T-p2-writelease-relative-scope] 相对 target 与 scope 同根解析 ———

    @Test
    fun `relative target resolves against the workspace root`() {
        // 模型照旧文案改用相对路径的场景：docs/reports 在租约 /var/minis/workspace/docs 内
        val lease = WriteLease(listOf("docs"))
        assertTrue(
            "相对路径必须解析到工作区根后再比对 scope",
            WriteLeaseGate.check(lease, HarnessTool.WRITE, "file_write", args("docs/reports/q3.md")) is LeaseVerdict.Allowed,
        )
    }

    @Test
    fun `workspace rooted target matches absolute scope`() {
        val lease = WriteLease(listOf("/var/minis/workspace/docs"))
        val v1 = WriteLeaseGate.check(lease, HarnessTool.WRITE, "file_write", args("docs/a.md"))
        assertTrue("relative target verdict=$v1 normalized=${lease.normalized}", v1 is LeaseVerdict.Allowed)
        val v2 = WriteLeaseGate.check(lease, HarnessTool.WRITE, "file_write", args("/var/minis/workspace/docs/a.md"))
        assertTrue("absolute target verdict=$v2", v2 is LeaseVerdict.Allowed)
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
