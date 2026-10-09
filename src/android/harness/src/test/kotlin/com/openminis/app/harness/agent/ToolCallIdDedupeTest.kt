package com.openminis.app.harness.agent

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-android-seam-extraction] 循环片段三验收：同 id 并行调用的改名序、
 * delta 路由、complete 独立计数。
 */
class ToolCallIdDedupeTest {

    @Test
    fun `first start keeps the raw id, repeats rename by occurrence`() {
        val d = ToolCallIdDedupe()
        assertEquals("call-1", d.startId("call-1"))
        assertEquals("call-1-2", d.startId("call-1"))
        assertEquals("call-1-3", d.startId("call-1"))
        assertEquals("call-2", d.startId("call-2"))
    }

    @Test
    fun `input deltas route to the in-flight renamed id`() {
        val d = ToolCallIdDedupe()
        d.startId("a")
        assertEquals("a", d.inputId("a"))
        d.startId("a") // 第二个同 id start → 在飞映射翻到改名值
        assertEquals("a-2", d.inputId("a"))
        assertEquals("b", d.inputId("b")) // 未见 start 的 delta 原样透传
    }

    @Test
    fun `complete counts independently of start counts`() {
        val d = ToolCallIdDedupe()
        d.startId("x")
        d.startId("x")
        // complete 只出现一次 → 保留原 id（与 start 计数无关）
        assertEquals("x", d.completeId("x"))
        // 网关重放同一条 complete → 第二次改名
        assertEquals("x-2", d.completeId("x"))
    }
}
