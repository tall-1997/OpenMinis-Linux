package com.openminis.app.provider

import com.openminis.app.data.model.LLMError
import com.openminis.app.data.model.LLMStreamChunk
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-stream-stall-watchdog] Phase-2 coverage.
 *
 * `FirstEventWatchdogTest` pins the phase-1-only contract and is left
 * untouched; these cases are about the bound that did not exist before: a
 * stream that proves itself alive and then goes quiet. In production the only
 * other limit was the transport read timeout — 600s on `OpenAIProvider`, ten
 * minutes on the Anthropic and Gemini clients.
 */
class StreamStallWatchdogTest {

    @Test
    fun `a steadily emitting stream is passed through untouched`() = runBlocking {
        val src = flow {
            repeat(5) {
                emit(LLMStreamChunk.Text("chunk$it"))
                delay(30)
            }
        }
        val out = withTimeout(5_000) {
            src.streamStallWatchdog(firstTimeoutMs = 1_000, idleTimeoutMs = 500).toList()
        }
        assertEquals(5, out.size)
        assertEquals(LLMStreamChunk.Text("chunk4"), out[4])
    }

    @Test
    fun `silence after the first chunk surfaces a stall instead of hanging`() = runBlocking {
        val src = flow {
            emit(LLMStreamChunk.Started)
            delay(30_000) // would otherwise ride out the transport read timeout
        }
        val e = assertThrows(LLMError::class.java) {
            runBlocking {
                withTimeout(5_000) {
                    src.streamStallWatchdog(firstTimeoutMs = 300, idleTimeoutMs = 300).toList()
                }
            }
        }
        assertTrue("expected TransientError, got $e", e is LLMError.TransientError)
        assertTrue(
            "expected a stall detail, got: ${(e as LLMError.TransientError).detail}",
            e.detail.contains("stalled"),
        )
    }

    @Test
    fun `idle disabled preserves the first-event-only contract`() = runBlocking {
        // The second chunk lands after firstTimeoutMs. With phase 2 off the
        // watchdog must retire at the first event and let it through.
        val src = flow {
            emit(LLMStreamChunk.Started)
            delay(400)
            emit(LLMStreamChunk.Text("still here"))
        }
        val out = withTimeout(5_000) {
            src.streamStallWatchdog(firstTimeoutMs = 150, idleTimeoutMs = 0).toList()
        }
        assertEquals(2, out.size)
    }

    @Test
    fun `a stream that never emits still fails on the first-event bound`() = runBlocking {
        val src = flow<LLMStreamChunk> { delay(30_000) }
        val e = assertThrows(LLMError::class.java) {
            runBlocking {
                withTimeout(5_000) {
                    src.streamStallWatchdog(firstTimeoutMs = 150, idleTimeoutMs = 300).toList()
                }
            }
        }
        assertTrue(e is LLMError.TransientError)
        assertTrue(
            "expected a first-event detail, got: ${(e as LLMError.TransientError).detail}",
            e.detail.contains("no first chunk"),
        )
    }

    @Test
    fun `gaps shorter than the idle bound never trip it`() = runBlocking {
        // Each gap is well under idleTimeoutMs; the deadline must be recomputed
        // from the last event rather than from stream start.
        val src = flow {
            repeat(4) {
                delay(80)
                emit(LLMStreamChunk.Text("t$it"))
            }
        }
        val out = withTimeout(5_000) {
            src.streamStallWatchdog(firstTimeoutMs = 500, idleTimeoutMs = 250).toList()
        }
        assertEquals(4, out.size)
    }

    @Test
    fun `firstEventWatchdog delegates with phase two disabled`() = runBlocking {
        val src = flow {
            emit(LLMStreamChunk.Started)
            delay(400)
            emit(LLMStreamChunk.Text("late"))
        }
        val out = withTimeout(5_000) { src.firstEventWatchdog(150).toList() }
        assertEquals(2, out.size)
    }
}
