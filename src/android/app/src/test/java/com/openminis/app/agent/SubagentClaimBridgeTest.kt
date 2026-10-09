package com.openminis.app.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-subagent-claim-seam] 桥的验收：轨迹清单足以驱动全链路裁定；
 * 无 claim 块 fail-open；凭据只认成功调用；协议块不进父上下文。
 */
class SubagentClaimBridgeTest {

    private val reportWithClaim = """
        报告正文：任务已完成。

        ```json
        {
          "status": "complete",
          "summary": "完成了",
          "acceptance_criteria": [
            {"type": "verification", "claim": "测试通过", "command": "gradlew test"},
            {"type": "files", "claim": "写了报告", "paths": ["docs/report.md"]},
            {"type": "manual", "claim": "需用户真机确认"}
          ]
        }
        ```
    """.trimIndent()

    private fun receipts() = listOf(
        SubagentClaimBridge.LaneToolReceipt("c1", "shell_execute", """{"command":"gradlew test"}""", true),
        SubagentClaimBridge.LaneToolReceipt("c2", "file_write", """{"path":"docs/report.md"}""", true),
        SubagentClaimBridge.LaneToolReceipt("c3", "file_write", """{"path":"docs/other.md"}""", false),
    )

    @Test
    fun `claim without receipts trail still adjudicates and downgrades on manual`() {
        val result = SubagentClaimBridge.adjudicateLaneReport(reportWithClaim, receipts())
        assertNotNull(result)
        result!!
        assertEquals("complete", result.claimedStatus)
        // manual 永不背书 → complete 降级 partial
        assertEquals("partial", result.adjudicatedStatus)
        assertTrue(result.downgraded)
        assertTrue(result.adjudication.verdicts[0].backed)
        assertTrue(result.adjudication.verdicts[1].backed)
        assertFalse(result.adjudication.verdicts[2].backed)
    }

    @Test
    fun `clean report strips the protocol block`() {
        val result = SubagentClaimBridge.adjudicateLaneReport(reportWithClaim, receipts())!!
        assertTrue(result.cleanReport.startsWith("报告正文"))
        assertFalse(result.cleanReport.contains("acceptance_criteria"))
        assertTrue(result.adjudicationSection.contains("完成主张核验"))
    }

    @Test
    fun `failed writes do not back a files criterion`() {
        val report = """
            x
            ```json
            {"status":"complete","acceptance_criteria":[{"type":"files","claim":"改了 other","paths":["docs/other.md"]}]}
            ```
        """.trimIndent()
        val result = SubagentClaimBridge.adjudicateLaneReport(report, receipts())!!
        assertFalse(result.adjudication.verdicts.single().backed)
        assertEquals("partial", result.adjudicatedStatus)
    }

    @Test
    fun `no claim block means fail open null`() {
        assertNull(SubagentClaimBridge.adjudicateLaneReport("纯文本报告，没有协议块。", receipts()))
    }

    @Test
    fun `transcript pairs calls with results`() {
        val messages = SubagentClaimBridge.transcriptOf(receipts())
        assertEquals(6, messages.size)
        val call = messages[0] as com.openminis.app.harness.ToolCall
        val result = messages[1] as com.openminis.app.harness.ToolResult
        assertEquals(call.id, result.toolCallId)
        assertTrue(result.success)
        assertFalse((messages[5] as com.openminis.app.harness.ToolResult).success)
    }
}
