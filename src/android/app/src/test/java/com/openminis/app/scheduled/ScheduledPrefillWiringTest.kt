package com.openminis.app.scheduled

import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-scheduled-tool-prefill] Source guard for the firing path, not the pieces:
 * [PrefilledToolCall] and [ScriptedToolTurn] are unit-tested on their own, but
 * a call only ever runs if every hop keeps carrying it — CLI create → stored
 * task → runner → headless prompt → the send funnel → the queue → the loop's
 * first turn. The failure this pins down is a call that validates, saves, and
 * then evaporates at whichever hop forgot it, which no single-class test can
 * see.
 */
class ScheduledPrefillWiringTest {

    private fun occurrences(haystack: String, needle: String): Int {
        var count = 0
        var i = haystack.indexOf(needle)
        while (i >= 0) {
            count++
            i = haystack.indexOf(needle, i + 1)
        }
        return count
    }

    /** The argument list of [marker] (a call that ends with `(`), parens balanced. */
    private fun callSlice(src: String, marker: String): String {
        val start = src.indexOf(marker)
        assertTrue("call not found: $marker", start >= 0)
        var depth = 1
        for (i in start + marker.length until src.length) {
            when (src[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return src.substring(start, i + 1)
                }
            }
        }
        throw AssertionError("unbalanced call: $marker")
    }

    // ── the CLI mints the call ───────────────────────────────────────────

    @Test
    fun `the CLI parses the call up front and stores it on the task`() {
        val src = ProductionSources.read("sandbox/offload/ScheduledTaskOffloadHandler.kt")
        assertTrue(
            "a bad call must fail at create, not on every unattended fire",
            src.contains("val prefill = parsePrefill(args, target, label)"),
        )
        assertTrue(src.contains("prefillToolCall = prefill,"))
        assertTrue("list must surface the stored call", src.contains("t.prefillToolCall?.let { put(\"prefill\","))
    }

    // ── the runner delivers it ───────────────────────────────────────────

    @Test
    fun `the runner runs the stored call and names it in the envelope`() {
        val src = ProductionSources.read("scheduled/ScheduledAgentRunner.kt")
        assertTrue(
            "a call that no longer validates is dropped, not handed to the loop",
            src.contains("val prefill = listOfNotNull(task.prefillToolCall?.takeIf { it.validationError() == null })"),
        )
        assertTrue(
            "the envelope tells the model the scheduler made the call",
            src.contains("prefilledTool = prefill.firstOrNull()?.toolName,"),
        )
    }

    @Test
    fun `the prompt path carries the call and the rerun path does not`() {
        val src = ProductionSources.read("scheduled/ScheduledAgentRunner.kt")
        assertEquals("exactly one hop hands it to the headless prompt", 1, occurrences(src, "prefill = prefill,"))
        val retry = callSlice(src, "HeadlessChatRunner.retry(")
        assertFalse("a rerun replays an existing turn; nothing new is sent", retry.contains("prefill"))
    }

    @Test
    fun `the headless prompt hands it to the send funnel`() {
        val src = ProductionSources.read("debug/HeadlessChatRunner.kt")
        assertTrue(src.contains("prefill: List<PrefilledToolCall> = emptyList(),"))
        assertTrue(src.contains("vm.submitPrompt(text, prefill)"))
    }

    // ── the send funnel keeps it through every detour ────────────────────

    @Test
    fun `the non-composer entry point forwards the call`() {
        val port = ProductionSources.read("session/ChatSessionPort.kt")
        assertTrue(
            "the headless RPC binds through the port, so the entry point must be on it",
            port.contains("fun submitPrompt(text: String, prefill: List<PrefilledToolCall>)"),
        )
        val vm = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertTrue(
            "the port's one implementation forwards the call down the send funnel",
            vm.contains("override fun submitPrompt(text: String, prefill: List<com.openminis.app.scheduled.PrefilledToolCall>) ="),
        )
        val src = ProductionSources.read("ui/chat/ChatViewModelSendExt.kt")
        assertTrue("a send while streaming is queued, never dropped", src.contains("enqueuePrompt(text, prefill)"))
        assertEquals(
            "both context-check detours park the call with the text",
            2,
            occurrences(src, "pendingSendPrefill = prefill"),
        )
        assertEquals("the fresh loop is the one that runs it", 1, occurrences(src, "prefill = prefill,"))
    }

    @Test
    fun `the parked call is restored by both dialog actions`() {
        val src = ProductionSources.read("ui/chat/ChatViewModel.kt")
        assertEquals(
            "compact-then-send and send-without-compacting both keep it",
            2,
            occurrences(src, "sendMessage(text, skipContextCheck = true, prefill = prefill)"),
        )
        assertTrue(
            "a queued prompt stores the call for its own loop",
            1 == occurrences(src, "prefill = prefill,"),
        )
        assertTrue(src.contains("internal var pendingSendPrefill"))
    }

    @Test
    fun `a queued fire carries its call into the resumed loop`() {
        assertTrue(
            ProductionSources.read("ui/chat/ChatViewModelDrainQueueExt.kt")
                .contains("prefill = queued.flatMap { it.prefill },"),
        )
        assertTrue(
            "the same for a prompt injected mid-loop",
            ProductionSources.read("ui/chat/ChatViewModelQueueInjectExt.kt")
                .contains("ChatViewModel.InjectedTurn(newAssistantId, queued.flatMap { it.prefill })"),
        )
    }

    // ── the loop turns it into a first turn ──────────────────────────────

    @Test
    fun `the loop resolves the call once and consumes it on the first turn`() {
        val src = ProductionSources.read("ui/chat/ChatViewModelAgentLoopExt.kt")
        assertTrue(src.contains("var pendingScriptedTurn = scriptedTurnFor(prefill)"))
        assertEquals(
            "both mid-loop injection sites re-arm it for the next loop",
            2,
            occurrences(src, "pendingScriptedTurn = scriptedTurnFor(handled.prefill)"),
        )
        assertTrue(src.contains("val scriptedTurn = pendingScriptedTurn?.also { pendingScriptedTurn = null }"))
        assertTrue(
            "the scripted turn replaces the provider call for its turn only",
            src.contains(
                "val streamSource = scriptedTurn?.asStreamChunks()?.asFlow() ?: currentProvider.streamMessage(",
            ),
        )
    }

    @Test
    fun `a scripted call is not judged by the loop detector`() {
        val src = ProductionSources.read("ui/chat/ChatViewModelAgentLoopExt.kt")
        assertTrue(
            "firing the same call every fire is the feature, not a loop",
            src.contains("if (ScriptedToolTurn.isScriptedId(id)) com.openminis.app.agent.LoopCheckResult.NONE"),
        )
    }

    @Test
    fun `a call no tool can run falls back to the model`() {
        val src = ProductionSources.read("ui/chat/ScheduledPrefillLoop.kt")
        assertTrue(src.contains("ScriptedToolTurn.from(prefill, agentTools.map { it.name }.toSet())"))
        assertTrue(src.contains("the model decides for itself"))
    }

    @Test
    fun `a turn that is not an agent loop drops the call out loud`() {
        val send = ProductionSources.read("ui/chat/ChatViewModelSendExt.kt")
        assertEquals(
            "group chat and video generation both answer somewhere other than the loop",
            2,
            occurrences(send, "reportPrefillUnused("),
        )
        assertTrue(
            "the drain's group-chat branch is reached without ever building the loop",
            ProductionSources.read("ui/chat/ChatViewModelDrainQueueExt.kt")
                .contains("reportPrefillUnused(queued.flatMap { it.prefill },"),
        )
        assertTrue(
            "the drop is a warning, not a silent return",
            ProductionSources.read("ui/chat/ScheduledPrefillLoop.kt")
                .contains("internal fun reportPrefillUnused(prefill: List<PrefilledToolCall>, why: String)"),
        )
    }
}
