package com.openminis.app.harness.runtime

import com.openminis.app.data.model.LLMMessage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-seam-extraction] 接缝二/三的契约验收：钉住 harness 侧消费方可以
 * 依赖的行为——历史快照隔离、追加顺序、工具执行的成败映射。
 */
class PortsContractTest {

    private class FakeConversation : ConversationPort {
        val backing = mutableListOf<LLMMessage>()
        override fun history(): List<LLMMessage> = backing.toList()
        override fun append(message: LLMMessage) { backing += message }
        override fun appendAll(messages: Collection<LLMMessage>) { backing += messages }
    }

    private class FakeToolExecutor(private val outcomes: Map<String, ToolOutcome>) : ToolExecutorPort {
        val calls = mutableListOf<Triple<String, String, String>>()
        override suspend fun execute(toolCallId: String, toolName: String, argsJson: String): ToolOutcome {
            calls += Triple(toolCallId, toolName, argsJson)
            return outcomes[toolName] ?: ToolOutcome(false, "no such tool")
        }
    }

    @Test
    fun `history is a snapshot — mutating it does not leak into the port`() {
        val port = FakeConversation()
        port.append(LLMMessage(role = LLMMessage.Role.USER, content = "a"))
        val snapshot = port.history()
        snapshot.dropLast(1) // 或任何破坏性操作
        assertEquals(1, port.history().size)
    }

    @Test
    fun `append preserves order and appendAll lands as a block`() {
        val port = FakeConversation()
        port.append(LLMMessage(role = LLMMessage.Role.USER, content = "1"))
        port.appendAll(
            listOf(
                LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "2"),
                LLMMessage(role = LLMMessage.Role.ASSISTANT, content = "3"),
            ),
        )
        assertEquals(listOf("1", "2", "3"), port.history().map { it.content })
    }

    @Test
    fun `tool executor maps outcomes and records calls`() = runBlocking {
        val executor = FakeToolExecutor(mapOf("file_read" to ToolOutcome(true, "ok")))
        val outcome = executor.execute("call-1", "file_read", """{"path":"a"}""")
        assertTrue(outcome.success)
        assertEquals("ok", outcome.output)
        assertEquals(1, executor.calls.size)
        assertEquals("call-1", executor.calls[0].first)
        assertEquals("file_read", executor.calls[0].second)
    }

    @Test
    fun `unknown tools fail closed with a readable output`() = runBlocking {
        val executor = FakeToolExecutor(emptyMap())
        val outcome = executor.execute("call-2", "nope", "{}")
        assertTrue(!outcome.success)
        assertTrue(outcome.output.isNotEmpty())
    }
}
