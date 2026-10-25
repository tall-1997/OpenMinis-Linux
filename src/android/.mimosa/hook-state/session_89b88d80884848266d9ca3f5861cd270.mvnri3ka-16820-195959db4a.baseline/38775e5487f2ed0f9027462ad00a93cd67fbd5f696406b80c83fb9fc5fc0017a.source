package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DiscussionGraphTest {

    @Test
    fun staffKeepsSecretaryOnMainAndAddsDesignerOnlyForUi() {
        val code = DiscussionGraph.staff("fix the storage scanner hang", teammateCount = 2)
        assertFalse(code.any { it.role == "前端设计师" })
        assertEquals(-1, code.first { it.role == "秘书助理" }.providerIndex)
        assertEquals(0, code.first { it.role == "产品经理" }.providerIndex)
        assertEquals(1, code.first { it.role == "架构师" }.providerIndex)

        val ui = DiscussionGraph.staff("改设置页按钮的布局", teammateCount = 0)
        assertTrue(ui.any { it.role == "前端设计师" })
        assertTrue(ui.all { it.providerIndex == -1 })
    }

    @Test
    fun missingVerdictIsAnObjectionAndLaterAcceptClosesIt() {
        val seats = DiscussionGraph.staff("实现存储扫描", 1)
        val vague = DiscussionGraph.parseStatement("工程师", DiscussionGraph.Phase.CRITIQUE, "看起来可以")
        assertEquals(DiscussionGraph.Verdict.OBJECT, vague.verdict)
        assertEquals(
            DiscussionGraph.Phase.REVISE,
            DiscussionGraph.afterCritique(listOf(vague)),
        )
        val closed = DiscussionGraph.parseStatement(
            "工程师",
            DiscussionGraph.Phase.REVOTE,
            "VERDICT: ACCEPT\nOBJECTION: none\nASK: none",
        )
        assertTrue(DiscussionGraph.openObjections(listOf(vague, closed)).isEmpty())
        assertEquals(DiscussionGraph.Phase.MINUTES, DiscussionGraph.afterRevote())
        assertEquals(DiscussionGraph.MAX_REVISE, 1)
        assertTrue(DiscussionGraph.critics(seats).none { it.role == "产品经理" || it.role == "秘书助理" })
    }

    @Test
    fun speakerSelectorAsksOneStaffedRole() {
        val seats = DiscussionGraph.staff("实现存储扫描", 1)
        val asked = DiscussionGraph.parseStatement(
            "测试工程师",
            DiscussionGraph.Phase.CRITIQUE,
            "VERDICT: OBJECT\nOBJECTION: 验收没写断网\nASK: @产品经理",
        )
        val unstaffed = DiscussionGraph.parseStatement(
            "工程师",
            DiscussionGraph.Phase.CRITIQUE,
            "VERDICT: OBJECT\nOBJECTION: 界面空态没画\nASK: @前端设计师",
        )
        assertEquals("产品经理", DiscussionGraph.clarificationRole(seats, listOf(asked, unstaffed)))
        assertEquals(listOf("工程师", "测试工程师"), DiscussionGraph.objectorSeats(seats, listOf(asked, unstaffed)).map { it.role })
    }

    @Test
    fun discussionRejectsWritesButAllowsReadOnlyShell() {
        val tools = listOf(
            AgentToolDefinition("file_read", "read", emptyMap()),
            AgentToolDefinition("file_write", "write", emptyMap()),
            AgentToolDefinition("execute_code", "run", emptyMap()),
            AgentToolDefinition("shell_execute", "shell", emptyMap()),
            AgentToolDefinition("spawn_agent", "spawn", emptyMap()),
        )
        val allowed = DiscussionGraph.allowedTools("架构师", tools).map { it.name }
        assertTrue(allowed.contains("file_read"))
        assertTrue(allowed.contains("shell_execute"))
        assertFalse(allowed.contains("file_write"))
        assertFalse(allowed.contains("execute_code"))
        assertFalse(allowed.contains("spawn_agent"))
        assertNull(DiscussionGraph.denyExecution("shell_execute", """{"command":"ls -l"}"""))
        assertTrue(DiscussionGraph.denyExecution("shell_execute", """{"command":"echo x > out.txt"}""") != null)
        assertTrue(DiscussionGraph.denyExecution("execute_code", "{}")!!.contains("只读"))
    }

    @Test
    fun contractFallsBackAndMarkdownLeadsWithIt() {
        val open = DiscussionGraph.parseStatement(
            "测试工程师",
            DiscussionGraph.Phase.CRITIQUE,
            "VERDICT: OBJECT\nOBJECTION: 没有断网验收\nASK: none",
        )
        val contract = DiscussionGraph.fallbackContract("简报", "方案 A", listOf(open))
        assertTrue(contract.contains("【已定】"))
        assertTrue(contract.contains("没有断网验收"))
        val md = DiscussionGraph.discussionMarkdown("白板", contract)
        assertTrue(md.contains("### 执行契约"))
        assertTrue(md.contains("### Synthesis"))
        assertTrue(md.indexOf("### 执行契约") < md.indexOf("白板"))
    }
}
