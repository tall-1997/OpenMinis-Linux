package com.openminis.app.ui.chat

import com.openminis.app.provider.anthropic.AnthropicProvider
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SessionContextEstimateTest {
    @Test
    fun anEstimateFailureDoesNotCancelSessionEntryOrReplaceItsReading() = runTest {
        val original = MemoryDeliveryPrompt("previous reading", null)
        var reading = original
        var reported: Exception? = null
        var entryContinued = false
        val failure = IllegalStateException("fixture missing OAuth configuration")
        val job = launch {
            runSessionContextEstimate<MemoryDeliveryPrompt>(
                estimate = { throw failure },
                publish = { reading = it },
                reportFailure = { reported = it },
            )
            entryContinued = true
        }
        job.join()
        assertFalse(job.isCancelled)
        assertTrue(entryContinued)
        assertSame(original, reading)
        assertSame(failure, reported)
    }

    @Test
    fun apiKeySessionPublishesWithNoOAuthMetadata() = runTest {
        val provider = AnthropicProvider("test-key")
        val prompt = MemoryDeliveryPrompt("session rules", null)
        var published: MemoryDeliveryPrompt? = null
        runSessionContextEstimate(
            estimate = { prompt.withOAuthPrefix(provider) { error("unused OAuth identifier") } },
            publish = { published = it },
            reportFailure = { throw AssertionError("Session estimate must succeed", it) },
        )
        assertSame(prompt, published)
    }

    @Test
    fun ordinaryIoFailureIsReportedWithoutPublishing() = runTest {
        var published = false
        var failures = 0
        runSessionContextEstimate<Int>(
            estimate = { throw IOException("fixture unavailable config") },
            publish = { published = true },
            reportFailure = { assertTrue(it is IOException); failures++ },
        )
        assertFalse(published)
        assertEquals(1, failures)
    }

    @Test
    fun cooperativeCancellationIsNotReportedAsAnEstimateFailure() = runTest {
        var published = false
        var failures = 0
        val job = async {
            runSessionContextEstimate<Int>(
                estimate = { awaitCancellation() },
                publish = { published = true },
                reportFailure = { failures++ },
            )
        }
        runCurrent()
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertFalse(published)
        assertEquals(0, failures)
    }

    @Test
    fun explicitCancellationPropagates() = runTest {
        var failures = 0
        val result = runCatching {
            runSessionContextEstimate<Int>(
                estimate = { throw CancellationException("fixture cancellation") },
                publish = { error("cancelled result must not publish") },
                reportFailure = { failures++ },
            )
        }
        assertTrue(result.exceptionOrNull() is CancellationException)
        assertEquals(0, failures)
    }
}
