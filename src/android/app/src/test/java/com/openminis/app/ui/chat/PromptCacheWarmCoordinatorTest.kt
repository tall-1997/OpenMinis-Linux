package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.LLMResponse
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.LLMUsage
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.LLMProvider
import com.openminis.app.provider.PromptCacheWarmRates
import com.openminis.app.provider.SamplingIdentity
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * [T-prompt-cache-warming] Race-and-deadline tests for the warm dispatcher
 * ([PromptCacheWarmCoordinator]): the in-flight mutex must serialise the two
 * idle triggers into ONE billable replay, the wall-clock deadline must
 * abandon (not chain) a hung warm, and the warm request must be built with
 * thinking OFF regardless of the recorded session level.
 *
 * The fake blocks its send inside [LLMProvider.sendMessageClamped] on a
 * cancellable [CompletableDeferred] gate (so the deadline cancels it cleanly
 * instead of stranded on a blocking call), and records the request shape it
 * actually received.
 */
class PromptCacheWarmCoordinatorTest {

    private val t0 = 10_000_000L

    /** Rates matching the frontier model used by the planner tests. */
    private val sampleRates = PromptCacheWarmRates.Rates(
        inputPerTokenUsd = 0.000003,
        outputPerTokenUsd = 0.000015,
        cacheReadPerTokenUsd = 0.0000003,
        cacheWritePerTokenUsd = 0.00000375,
    )

    @After
    fun tearDown() {
        PromptCacheWarmCoordinator.resetForTest()
    }

    /**
     * A provider whose warm replay blocks on [gate]; [entered] releases one
     * permit per request so the test can observe the request is INSIDE the
     * send before continuing.
     */
    private class WarmGate(private val gate: CompletableDeferred<Unit>) : LLMProvider {
        override val name = "warm-stub"
        override var model = LLMModel(id = "warm-model", displayName = "warm-model", provider = "stub")

        val sendCount = AtomicInteger(0)
        val entered = Semaphore(0)

        @Volatile var lastThinkingLevel: ThinkingLevel? = null
            private set
        @Volatile var lastMaxTokens: Int? = null
            private set

        override suspend fun sendMessageClamped(
            messages: List<LLMMessage>,
            systemPrompt: String?,
            maxTokens: Int,
            temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>,
            tools: List<AgentToolDefinition>,
            thinkingLevel: ThinkingLevel,
            extraHeaders: Map<String, String>,
        ): LLMResponse {
            lastThinkingLevel = thinkingLevel
            lastMaxTokens = maxTokens
            sendCount.incrementAndGet()
            entered.release()
            // Suspending (not blocking) so a deadline cancellation propagates.
            gate.await()
            return LLMResponse(text = "warm", stopReason = "stop", usage = null)
        }

        override fun streamMessageClamped(
            messages: List<LLMMessage>,
            systemPrompt: String?,
            maxTokens: Int,
            temperature: Double?,
            imageParts: List<LLMMessage.ImagePart>,
            tools: List<AgentToolDefinition>,
            thinkingLevel: ThinkingLevel,
            systemStablePrefixLen: Int,
            extraHeaders: Map<String, String>,
        ): Flow<LLMStreamChunk> = flow { }
    }

    private fun installPending(
        stub: WarmGate,
        nowMs: Long,
        thinkingLevel: ThinkingLevel,
        providerFamilyRaw: String,
    ) {
        SamplingIdentity.bind(stub, "inst-warm", providerFamilyRaw)
        PromptCacheWarmCoordinator.onStreamTurnSucceeded(
            provider = stub,
            messages = listOf(LLMMessage(role = LLMMessage.Role.USER, content = "hi")),
            systemPrompt = null,
            tools = emptyList(),
            thinkingLevel = thinkingLevel,
            usage = LLMUsage(inputTokens = 1_000_000, outputTokens = 1),
            isBusy = { false },
            nowMs = nowMs,
        )
    }

    private fun awaitEntered(stub: WarmGate) {
        assertTrue("warm replay did not start within 5s", stub.entered.tryAcquire(5, TimeUnit.SECONDS))
    }

    private fun awaitInFlightReleased(timeoutMs: Long = 5_000L) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (PromptCacheWarmCoordinator.isWarmInFlightForTest()) {
            assertTrue(
                "warm still in flight after ${timeoutMs}ms",
                System.currentTimeMillis() < deadline,
            )
            Thread.sleep(5)
        }
    }

    @Test
    fun `concurrent due triggers dispatch a single warm`() {
        PromptCacheWarmCoordinator.warmingEnabledForTest = true
        PromptCacheWarmCoordinator.warmRatesForTest = sampleRates
        val gate = CompletableDeferred<Unit>()
        val stub = WarmGate(gate)
        installPending(stub, nowMs = t0, thinkingLevel = ThinkingLevel.OFF, providerFamilyRaw = "anthropic")

        // First idle trigger dispatches the refresh and the warm is INSIDE
        // the send (the in-flight slot is held).
        PromptCacheWarmCoordinator.onAppForeground(t0 + 280_000)
        awaitEntered(stub)
        assertTrue(PromptCacheWarmCoordinator.isWarmInFlightForTest())
        assertEquals(1, stub.sendCount.get())

        // Second trigger inside the same idle window: the in-flight mutex
        // makes the loser skip — no second billable request.
        PromptCacheWarmCoordinator.onAppForeground(t0 + 281_000)
        assertEquals(1, stub.sendCount.get())

        // Release the warm: it completes and the in-flight slot is freed.
        gate.complete(Unit)
        awaitInFlightReleased()
        assertEquals(1, stub.sendCount.get())
    }

    @Test
    fun `warm request is built with thinking off`() {
        PromptCacheWarmCoordinator.warmingEnabledForTest = true
        PromptCacheWarmCoordinator.warmRatesForTest = sampleRates
        val gate = CompletableDeferred<Unit>()
        val stub = WarmGate(gate)
        // A thinking-enabled session on a non-Anthropic family: the planner
        // lets it through (no budget derivation from maxTokens) and the warm
        // request must still be built with OFF — the replay refreshes the
        // cache entry, it does not reproduce thinking. OpenAI TTL is 600s, so
        // the 90% due point is t0 + 540_000.
        installPending(stub, nowMs = t0, thinkingLevel = ThinkingLevel.HIGH, providerFamilyRaw = "openai")

        PromptCacheWarmCoordinator.onAppForeground(t0 + 550_000)
        awaitEntered(stub)

        assertEquals(ThinkingLevel.OFF, stub.lastThinkingLevel)
        assertEquals(1, stub.lastMaxTokens)

        gate.complete(Unit)
        awaitInFlightReleased()
    }

    @Test
    fun `warm deadline abandons the refresh and frees the in-flight slot`() {
        PromptCacheWarmCoordinator.warmingEnabledForTest = true
        PromptCacheWarmCoordinator.warmRatesForTest = sampleRates
        PromptCacheWarmCoordinator.warmDeadlineMsForTest = 150L
        val gate = CompletableDeferred<Unit>()
        val stub = WarmGate(gate)
        installPending(stub, nowMs = t0, thinkingLevel = ThinkingLevel.OFF, providerFamilyRaw = "anthropic")

        PromptCacheWarmCoordinator.onAppForeground(t0 + 280_000)
        awaitEntered(stub)
        assertTrue(PromptCacheWarmCoordinator.isWarmInFlightForTest())

        // Never released: the deadline must abandon the warm and clear the
        // slot without completing the request.
        awaitInFlightReleased()
        assertEquals(1, stub.sendCount.get())

        // The abandoned refresh did NOT re-record the ledger, so the entry is
        // still due and a later idle trigger legitimately tries again — the
        // difference from the success path (which re-arms the chain).
        PromptCacheWarmCoordinator.onAppForeground(t0 + 281_000)
        awaitEntered(stub)
        assertEquals(2, stub.sendCount.get())

        // Let the second warm finish so the coordinator stays idle.
        gate.complete(Unit)
        awaitInFlightReleased()
    }
}
