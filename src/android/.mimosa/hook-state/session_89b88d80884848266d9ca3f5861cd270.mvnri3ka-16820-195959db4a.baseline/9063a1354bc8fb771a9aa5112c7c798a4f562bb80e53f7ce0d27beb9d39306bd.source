package com.openminis.app.tools

import org.junit.Assert.assertTrue
import org.junit.Test

class PlanDiscussionLiveMarkdownTest {

    @Test
    fun liveMarkdownIncludesStatusTaskAndBoard() {
        val md = PlanDiscussionOrchestrator.liveMarkdown(
            status = "架构师：方案",
            userText = "fix the storage scanner hang",
            board = "用户请求:\nfix the storage scanner hang\n",
        )
        assertTrue(md.contains("计划讨论（进行中）"))
        assertTrue(md.contains("**状态：** 架构师：方案"))
        assertTrue(md.contains("fix the storage scanner hang"))
        assertTrue(md.contains("用户请求:"))
    }

    @Test
    fun discussionMarkdownKeepsContractAndBoard() {
        val contract = "【已定】\n做 B"
        val board = """
用户请求:
fix hang

### 方案（架构师）
做 B
""".trimIndent()
        val md = PlanDiscussionOrchestrator.discussionMarkdown(board + "\n" + contract)
        assertTrue(md.contains("计划讨论（角色图）"))
        assertTrue(md.contains("### 执行契约"))
        assertTrue(md.contains("### Synthesis"))
        assertTrue(md.contains("做 B"))
        assertTrue(md.contains("### 方案（架构师）"))
    }
}
