package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMStreamChunk
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FirstEventWatchdogTest {

    @Test
    fun `passes chunks through unchanged`() = runBlocking {
        val chunks = listOf(
            LLMStreamChunk.Started,
            LLMStreamChunk.ThinkingDelta("hmm"),
            LLMStreamChunk.Text("hi"),
            LLMStreamChunk.Finished(null),
        )
        val out = StreamTrace.replay(chunks).firstEventWatchdog(60_000L).toList()
        assertEquals(chunks.size, out.size)
        assertEquals(LLMStreamChunk.Text("hi"), out[2])
    }

    @Test
    fun `retires after the first chunk, a late stop is not killed`() = runBlocking {
        // First chunk arrives, then the stream stalls for a long time.
        // The watchdog must NOT kill it: it guards first-event only.
        val slow = kotlinx.coroutines.flow.flow {
            emit(LLMStreamChunk.Started)
            kotlinx.coroutines.delay(200L)
            emit(LLMStreamChunk.Text("still here"))
        }
        val out = withTimeout(2_000L) {
            slow.firstEventWatchdog(timeoutMs = 100L).toList()
        }
        assertEquals(2, out.size)
    }

    @Test
    fun `silent stream surfaces TransientError instead of hanging`() = runBlocking {
        val silent = kotlinx.coroutines.flow.flow<LLMStreamChunk> {
            kotlinx.coroutines.delay(10_000L) // never emits
        }
        val e = assertThrows(LLMError::class.java) {
            runBlocking {
                withTimeout(2_000L) {
                    silent.firstEventWatchdog(timeoutMs = 100L).toList()
                }
            }
        }
        assertTrue(e is LLMError.TransientError)
    }

    @Test
    fun `stream trace round-trips chunks`() {
        val chunks = listOf(
            LLMStreamChunk.Started,
            LLMStreamChunk.ThinkingDelta("thinking aloud"),
            LLMStreamChunk.Text("answer"),
            LLMStreamChunk.ToolUseStart("c1", "shell_execute"),
            LLMStreamChunk.ToolCallComplete("c1", "shell_execute", org.json.JSONObject().put("command", "ls")),
            LLMStreamChunk.Finished(null),
        )
        val encoded = StreamTrace.encode(chunks)
        val decoded = StreamTrace.decode(encoded)
        assertEquals(chunks.size, decoded.size)
        assertEquals(chunks[1], decoded[1])
        val origTool = chunks[4] as LLMStreamChunk.ToolCallComplete
        val backTool = decoded[4] as LLMStreamChunk.ToolCallComplete
        assertEquals(origTool.id, backTool.id)
        assertEquals(origTool.name, backTool.name)
        assertEquals(origTool.args.toString(), backTool.args.toString())
    }
}
