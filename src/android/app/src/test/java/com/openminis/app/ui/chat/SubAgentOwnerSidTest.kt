package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-subagent-owner-sid] Contract for the sub-agent owner-session resolution
 * used by every sub-agent tool call. A lane that loses its session id degrades
 * [com.openminis.app.security.SecurityGateHolder.intercept] to ASK — every
 * write / execute prompts even in a YOLO session — so the fallback chain is
 * security-relevant, not cosmetic. Batch context wins (it is the most specific
 * identity the spawn carried), then the parent VM's persisted id, then the
 * declared session, then the UI-active session.
 */
class SubAgentOwnerSidTest {

    @Test
    fun batchSessionWinsOverEverything() {
        assertEquals(
            "batch-1",
            subAgentOwnerSid(
                batchSession = "batch-1",
                realSessionId = "real-1",
                declaredSessionId = "declared-1",
                activeSessionId = "active-1",
            ),
        )
    }

    @Test
    fun blankBatchFallsThroughToParentRealId() {
        assertEquals(
            "real-1",
            subAgentOwnerSid(
                batchSession = "",
                realSessionId = "real-1",
                declaredSessionId = "declared-1",
                activeSessionId = "active-1",
            ),
        )
    }

    @Test
    fun blankBatchAndRealIdFallThroughToDeclared() {
        assertEquals(
            "declared-1",
            subAgentOwnerSid(
                batchSession = "",
                realSessionId = "",
                declaredSessionId = "declared-1",
                activeSessionId = "active-1",
            ),
        )
    }

    @Test
    fun onlyActiveIdLeftUsesIt() {
        assertEquals(
            "active-1",
            subAgentOwnerSid(
                batchSession = "",
                realSessionId = "",
                declaredSessionId = "",
                activeSessionId = "active-1",
            ),
        )
    }

    @Test
    fun allBlankStaysBlankSoGateFallsBackToAsk() {
        // Unknown identity must stay unknown: the gate's ASK fallback for a
        // blank caller is intentional injection defence, and this function
        // must not invent an id to defeat it.
        assertEquals(
            "",
            subAgentOwnerSid(
                batchSession = "",
                realSessionId = "",
                declaredSessionId = "",
                activeSessionId = "",
            ),
        )
    }

    @Test
    fun blankBatchWithWhitespaceFallsThrough() {
        // ifBlank semantics: a whitespace-only batch token is still blank.
        assertEquals(
            "real-1",
            subAgentOwnerSid(
                batchSession = "   ",
                realSessionId = "real-1",
                declaredSessionId = "declared-1",
                activeSessionId = "active-1",
            ),
        )
    }
}
