package com.openminis.app.provider

import com.openminis.app.data.model.AssistantTextPhase.COMMENTARY
import com.openminis.app.data.model.AssistantTextPhase.FINAL_ANSWER
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

    @Test
    fun `stream trace replays text item phases and late updates in order`() = runBlocking {
        val chunks = listOf(
            LLMStreamChunk.Text("Looking", COMMENTARY, "msg_work"),
            LLMStreamChunk.Text("Done", itemId = "msg_answer"),
            LLMStreamChunk.TextPhase("msg_answer", FINAL_ANSWER),
            LLMStreamChunk.Finished("stop"),
        )
        assertEquals(chunks, StreamTrace.replay(StreamTrace.decode(StreamTrace.encode(chunks))).toList())
    }

    @Test
    fun `stream trace reads old text and ignores unknown phase metadata`() {
        val decoded = StreamTrace.decode("""
            [
                {"kind":"text","text":"legacy"},
                {"kind":"text","text":"unknown","itemId":"msg_1","phase":"FUTURE"},
                {"kind":"text_phase","itemId":"msg_1","phase":"FUTURE"},
                {"kind":"text_phase","itemId":null,"phase":"FINAL_ANSWER"},
                {"kind":"finished","stopReason":"stop"}
            ]
        """.trimIndent())

        assertEquals(
            listOf(
                LLMStreamChunk.Text("legacy"),
                LLMStreamChunk.Text("unknown", itemId = "msg_1"),
                LLMStreamChunk.Finished("stop"),
            ),
            decoded,
        )
    }

    // ── [T-watchdog-real-error-preserved] the timeout boundary's captured ──
    // ── upstream error: answer-type verdicts win, everything else —       ──
    // ── cascade or transport alike — yields to the stall ([T-watchdog-    ──
    // ── answer-whitelist]).                                               ──

    @Test
    fun `a real provider verdict at the timeout boundary outranks the stall`() {
        // Every ANSWER-type kind the provider itself must have produced.
        // LLMError kinds are identity classes (no equals override): bind the
        // instance first and assert on THAT instance, or two structurally
        // identical constructions would compare unequal by reference.
        val rateLimit = LLMError.RateLimited(retryAfterSeconds = 30, detail = "[429] too many requests")
        assertEquals(rateLimit, realProviderVerdict(rateLimit))
        val invalidKey = LLMError.InvalidApiKey("quota exceeded")
        assertEquals(invalidKey, realProviderVerdict(invalidKey))
        val fiveHundred = LLMError.ProviderError("[503] service unavailable")
        assertEquals(fiveHundred, realProviderVerdict(fiveHundred))
        val decoding = LLMError.DecodingError(java.io.IOException("bad SSE frame"))
        assertEquals(decoding, realProviderVerdict(decoding))
        val contextLength = LLMError.ContextLengthExceeded("prompt too long")
        assertEquals(contextLength, realProviderVerdict(contextLength))
        val contentFiltered = LLMError.ContentFiltered("policy refusal")
        assertEquals(contentFiltered, realProviderVerdict(contentFiltered))
        // Wrapped by the channel/coroutine machinery — the chain walk finds it.
        val wrapped = RuntimeException("FlowAbortedException", rateLimit)
        assertEquals(rateLimit, realProviderVerdict(wrapped))
    }

    @Test
    fun `cancel cascade mappings lose to the timeout verdict`() {
        // OpenAI-family mapper: teardown TransientError with NO cause.
        val teardown = LLMError.TransientError(
            com.openminis.app.provider.openai.CANCELED_STREAM_TEARDOWN_PREFIX +
                " — retrying on a fresh connection",
        )
        assertEquals(null, realProviderVerdict(teardown))
        // Anthropic/Gemini mappers: NetworkError keeping the OkHttp cancel.
        assertEquals(
            null,
            realProviderVerdict(LLMError.NetworkError(java.io.IOException("Canceled"))),
        )
        // Raw cascade before any mapping — no LLMError in the chain at all.
        assertEquals(null, realProviderVerdict(java.io.IOException("Canceled")))
        assertEquals(null, realProviderVerdict(java.net.SocketException("Socket closed")))
        // [T-watchdog-answer-whitelist] A GENUINE transport death is the same
        // shape the teardown produces (NetworkError(cause=SocketException)) —
        // no message text separates the two, so transport-shaped errors never
        // outrank the stall, wrapped or not.
        assertEquals(
            null,
            realProviderVerdict(LLMError.NetworkError(java.io.IOException("Connection reset by peer"))),
        )
        assertEquals(
            null,
            realProviderVerdict(RuntimeException("FlowAbortedException", LLMError.NetworkError(java.net.SocketException("Socket closed")))),
        )
        // The remaining non-answer kinds: the watchdog's own lifecycle shapes.
        assertEquals(null, realProviderVerdict(LLMError.TransientError("stream stalled", stalledAfterFirstEvent = true)))
        assertEquals(null, realProviderVerdict(LLMError.Timeout("read timed out")))
        assertEquals(null, realProviderVerdict(LLMError.Unknown(java.io.IOException("boom"))))
        assertEquals(null, realProviderVerdict(LLMError.Cancelled()))
        assertEquals(null, realProviderVerdict(null))
    }

    @Test
    fun `a real error thrown before the deadline still propagates unchanged`() = runBlocking {
        val failing = kotlinx.coroutines.flow.flow<LLMStreamChunk> {
            emit(LLMStreamChunk.Started)
            kotlinx.coroutines.delay(50L)
            throw LLMError.RateLimited(retryAfterSeconds = 7)
        }
        val e = assertThrows(LLMError::class.java) {
            runBlocking {
                withTimeout(2_000L) {
                    failing.firstEventWatchdog(timeoutMs = 60_000L).toList()
                }
            }
        }
        // The watchdog (timedOutPhase null) must pass the provider's verdict
        // through as-is — never rewrite it into a stall.
        assertTrue(e is LLMError.RateLimited)
    }
}
