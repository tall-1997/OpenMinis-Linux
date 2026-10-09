package com.openminis.app.agent

import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-seam-extraction] lane 租约映射验收：宿主工具名/参数 JSON 翻译到
 * harness 闸门后，拒绝文案可解释、放行路径不拦、非写工具不问租约。
 */
class LaneWriteLeaseTest {

    @Test
    fun `write outside the declared lease is denied with guidance`() {
        val denied = LaneWriteLease.deniedResult(listOf("/var/minis/workspace/docs"), "file_write",
            """{"path":"/var/minis/workspace/other/a.md"}""")!!
        assertTrue(denied.output.contains("写租约范围"))
        assertTrue(!denied.success)
    }

    @Test
    fun `write inside the lease passes through`() {
        assertNull(
            LaneWriteLease.deniedResult(listOf("/var/minis/workspace/docs"), "file_write",
                """{"path":"/var/minis/workspace/docs/a.md"}"""),
        )
    }

    @Test
    fun `shell and read tools never hit the lease`() {
        assertNull(LaneWriteLease.deniedResult(listOf("docs"), "shell_execute", """{"command":"rm -rf /"}"""))
        assertNull(LaneWriteLease.deniedResult(emptyList(), "file_read", """{"path":"any.md"}"""))
    }

    @Test
    fun `undeclared lease denies structured writes`() {
        val denied = LaneWriteLease.deniedResult(emptyList(), "file_edit",
            """{"path":"docs/a.md","old_string":"x","new_string":"y"}""")!!
        assertTrue(denied.output.contains("只读任务"))
    }

    @Test
    fun `malformed args json degrades to schema layer not a crash`() {
        assertNull(LaneWriteLease.deniedResult(listOf("docs"), "file_write", "{not json"))
    }
}
