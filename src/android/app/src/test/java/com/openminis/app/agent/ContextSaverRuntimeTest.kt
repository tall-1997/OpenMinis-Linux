package com.openminis.app.agent

import com.openminis.app.data.ContextSaverPrefs
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-tool-result-aging] Contracts for the gating half of the saver.
 *
 * What is pinned here is the promise the send path makes to the provider:
 *
 *  - a request that is not big enough goes out **byte-identical** (same list
 *    instance), so prefix caching is untouched and the feature costs nothing
 *    when it is not needed;
 *  - OFF is a hard no-op at any size;
 *  - the stats describe *this* request only, so the Token Usage row can never
 *    claim a session-cumulative saving that never happened.
 *
 * The object is process-wide, so every case pins and clears `modeOverride`
 * itself — order independence matters because Gradle runs classes in its own
 * order and a leaked override would silently disable a sibling test's gate.
 */
class ContextSaverRuntimeTest {

    @Before
    fun setUp() {
        ContextSaverRuntime.resetForTest()
    }

    @After
    fun tearDown() {
        ContextSaverRuntime.resetForTest()
    }

    private fun result(id: String, chars: Int): AgentContentPart.ToolResult = AgentContentPart.ToolResult(
        id = id,
        name = "shell_execute",
        content = "x".repeat(chars),
        isError = false,
    )

    /** [count] assistant messages, each carrying one big tool result. */
    private fun history(count: Int, chars: Int = 8_000): List<LLMMessage> = (1..count).map { i ->
        LLMMessage(
            role = LLMMessage.Role.ASSISTANT,
            content = "",
            contentParts = listOf(result("t$i", chars)),
        )
    }

    private fun agedCount(messages: List<LLMMessage>): Int = messages.sumOf { m ->
        m.contentParts.count { it is AgentContentPart.ToolResult && ToolResultAging.isAged(it.content) }
    }

    // ------------------------------------------------------------------ gate

    @Test
    fun `short history crosses no gate and keeps the same instance`() {
        ContextSaverRuntime.modeOverride = ContextSaverPrefs.Mode.BALANCED
        val history = history(2)
        // Reported usage and the char floor both sit far under the 30k trigger.
        val out = ContextSaverRuntime.age(history, windowTokens = 0, reportedTokens = 120)
        assertSame(history, out)
        assertEquals(0, ContextSaverRuntime.stats.value.aged)
        assertEquals(ContextSaverPrefs.Mode.BALANCED.id, ContextSaverRuntime.stats.value.mode)
    }

    @Test
    fun `reported usage above the trigger runs the pass`() {
        ContextSaverRuntime.modeOverride = ContextSaverPrefs.Mode.BALANCED
        val history = history(10)
        // BALANCED keeps 6 verbatim → the 4 oldest fold.
        val out = ContextSaverRuntime.age(history, windowTokens = 0, reportedTokens = 40_000)
        assertEquals(4, agedCount(out))
        val stats = ContextSaverRuntime.stats.value
        assertEquals(4, stats.aged)
        assertTrue(stats.savedChars > 0)
        assertEquals(ContextSaverPrefs.Mode.BALANCED.triggerTokens, stats.triggerTokens)
    }

    @Test
    fun `char floor fires when usage was never reported`() {
        ContextSaverRuntime.modeOverride = ContextSaverPrefs.Mode.BALANCED
        // 20 x 8000 chars ≈ 53k tokens by the 3-chars/token floor → passes 30k
        // even though reportedTokens is 0 (fresh session, no usage chunk yet).
        val history = history(20)
        val out = ContextSaverRuntime.age(history, windowTokens = 0, reportedTokens = 0)
        assertTrue(agedCount(out) > 0)
    }

    @Test
    fun `off mode is a no-op at any size`() {
        ContextSaverRuntime.modeOverride = ContextSaverPrefs.Mode.OFF
        val history = history(30)
        val out = ContextSaverRuntime.age(history, windowTokens = 128_000, reportedTokens = 120_000)
        assertSame(history, out)
        assertEquals(0, ContextSaverRuntime.stats.value.aged)
        assertEquals(ContextSaverPrefs.Mode.OFF.id, ContextSaverRuntime.stats.value.mode)
    }

    @Test
    fun `aggressive folds more than balanced on the same history`() {
        ContextSaverRuntime.modeOverride = ContextSaverPrefs.Mode.AGGRESSIVE
        val history = history(10)
        val aggressive = agedCount(ContextSaverRuntime.age(history, 0, 40_000))

        ContextSaverRuntime.resetForTest()
        ContextSaverRuntime.modeOverride = ContextSaverPrefs.Mode.BALANCED
        val balanced = agedCount(ContextSaverRuntime.age(history, 0, 40_000))

        // AGGRESSIVE keeps 3 verbatim vs BALANCED's 6.
        assertTrue("aggressive=$aggressive balanced=$balanced", aggressive > balanced)
    }

    @Test
    fun `small window lowers the trigger through the window ratio`() {
        ContextSaverRuntime.modeOverride = ContextSaverPrefs.Mode.BALANCED
        val history = history(8)
        // 8k-token window → 0.45 * 8000 = 3600, so 4000 reported tokens fire.
        val out = ContextSaverRuntime.age(history, windowTokens = 8_000, reportedTokens = 4_000)
        assertTrue(agedCount(out) > 0)
        assertEquals(3_600, ContextSaverRuntime.stats.value.triggerTokens)
    }

    @Test
    fun `stats are per request and go quiet once the history is already folded`() {
        ContextSaverRuntime.modeOverride = ContextSaverPrefs.Mode.BALANCED
        val once = ContextSaverRuntime.age(history(10), 0, 40_000)
        val firstAged = ContextSaverRuntime.stats.value.aged
        assertTrue(firstAged > 0)

        // Second pass over the already-aged list: idempotent → zero, and the
        // list may be returned as the same instance.
        val twice = ContextSaverRuntime.age(once, 0, 40_000)
        assertEquals(0, ContextSaverRuntime.stats.value.aged)
        assertEquals(0L, ContextSaverRuntime.stats.value.savedChars)
        if (twice === once) assertSame(once, twice)
    }
}
