package com.openminis.app.harness.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-seam-extraction] 循环片段一（执行前拒绝）的验收：文案逐字保留
 * （modelMessage 是给模型的自愈指令）、部件字段正确、检测器记账落位。
 */
class ToolCallPreflightTest {

    @Test
    fun `invalid rejection keeps the self-correction wording verbatim`() {
        val detector = ToolLoopDetector()
        val rejection = ToolCallPreflight.rejectInvalid(
            toolCallId = "call-1", toolName = "file_write",
            validationError = "Missing required field: path",
            params = mapOf("path" to null), detector = detector,
        )
        assertEquals("Blocked invalid tool call", rejection.uiMessage)
        assertTrue(rejection.modelMessage.startsWith("Error: Tool call rejected before execution. Missing required field: path"))
        assertTrue(rejection.modelMessage.contains("Do not retry with the same empty arguments."))
        assertEquals("call-1", rejection.toolResultPart.id)
        assertEquals("file_write", rejection.toolResultPart.name)
        assertTrue(rejection.toolResultPart.isError)
        assertEquals(rejection.modelMessage, rejection.toolResultPart.content)
    }

    @Test
    fun `truncated rejection carries the finish reason and re-issue guidance`() {
        val detector = ToolLoopDetector()
        val rejection = ToolCallPreflight.rejectTruncated(
            toolCallId = "call-2", toolName = "shell_execute",
            finishReason = "length", params = emptyMap(), detector = detector,
        )
        assertTrue(rejection.modelMessage.contains("finish_reason=length"))
        assertTrue(rejection.modelMessage.contains("re-issue the call with complete arguments"))
        assertTrue(rejection.toolResultPart.isError)
        assertEquals(rejection.modelMessage, rejection.uiMessage)
    }

    @Test
    fun `rejections feed the loop detector so repeats escalate`() {
        val detector = ToolLoopDetector(ToolLoopConfig(
            historySize = 30, warningThreshold = 2, criticalThreshold = 4,
            unknownToolThreshold = 10, globalCircuitBreakerThreshold = 30,
        ))
        repeat(3) {
            ToolCallPreflight.rejectInvalid(
                toolCallId = "call-$it", toolName = "file_write",
                validationError = "empty", params = mapOf("path" to ""), detector = detector,
            )
        }
        // 同参同错连拒三次（阈值 2）：检测器必须已经给出 WARNING 级以上的意见。
        val verdict = detector.record(
            toolName = "file_write", params = mapOf("path" to ""),
            result = null, errorMessage = "empty", toolCallId = "call-3",
        )
        assertTrue(verdict.level != Level.NONE)
    }
}
