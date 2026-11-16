package com.openminis.app.ui.chat

import com.openminis.app.data.model.ThinkingLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-thinking-three-faces-unified] (P2 3-4) + [T-subagent-retry-reclamp]
 * (P2 3-10). Pins the ONE thinking contract every helper face of the main
 * session shares — spawn lanes, group-chat host/speaker, supervisor seat and
 * worker all route through [laneSessionThinkingLevel], and lane retries
 * through [laneRetryThinkingLevel]. Before the unification the faces drifted:
 * spawn followed the session level while group members and supervisor seats
 * ran at their raw model ceiling, so the same session produced helpers
 * thinking at three different depths with no documentation of whether that
 * was deliberate. The policy functions make the contract explicit and this
 * suite makes it non-regressible.
 */
class SubAgentThinkingPolicyTest {

    // ---- the shared face contract: session level, per-face ceiling, downward only ----

    @Test
    fun `face follows the session level when the model can reach it`() {
        assertEquals(
            ThinkingLevel.HIGH,
            laneSessionThinkingLevel(ThinkingLevel.HIGH, ThinkingLevel.XHIGH),
        )
        // A stronger ceiling must NOT lift a lower ask (downward-only).
        assertEquals(
            ThinkingLevel.LOW,
            laneSessionThinkingLevel(ThinkingLevel.LOW, ThinkingLevel.ULTRA),
        )
    }

    @Test
    fun `face is capped by its own model ceiling`() {
        // Session asks HIGH; a HIGH-max helper model runs HIGH, a MEDIUM-max
        // helper runs MEDIUM — the per-face clamp is what makes one session
        // depth safe on every member model.
        assertEquals(
            ThinkingLevel.HIGH,
            laneSessionThinkingLevel(ThinkingLevel.HIGH, ThinkingLevel.HIGH),
        )
        assertEquals(
            ThinkingLevel.MEDIUM,
            laneSessionThinkingLevel(ThinkingLevel.HIGH, ThinkingLevel.MEDIUM),
        )
        assertEquals(
            ThinkingLevel.XHIGH,
            laneSessionThinkingLevel(ThinkingLevel.ULTRA, ThinkingLevel.XHIGH),
        )
    }

    @Test
    fun `face keeps session OFF regardless of ceiling`() {
        assertEquals(
            ThinkingLevel.OFF,
            laneSessionThinkingLevel(ThinkingLevel.OFF, ThinkingLevel.ULTRA),
        )
    }

    @Test
    fun `all helper faces resolve identically for the same session state`() {
        // The unification invariant, stated as data: for every (session, ceiling)
        // pair the function is a pure function of the pair — the old per-face
        // rules cannot be re-introduced by editing one call site, because the
        // call sites no longer own any logic.
        val sessionLevels = ThinkingLevel.entries
        val ceilings = ThinkingLevel.entries
        for (session in sessionLevels) {
            for (ceiling in ceilings) {
                val expected = if (session.rank > ceiling.rank) ceiling else session
                assertEquals(
                    "face(session=$session, ceiling=$ceiling)",
                    expected,
                    laneSessionThinkingLevel(session, ceiling),
                )
            }
        }
    }

    // ---- retry re-clamp: shallow, downward-only, same-entry passthrough ----

    @Test
    fun `retry on the same entry keeps the resolved level verbatim`() {
        // First-attempt semantics must be byte-identical: no re-clamp on the
        // same entry, even when the ceiling could theoretically widen the ask.
        assertEquals(
            ThinkingLevel.MEDIUM,
            laneRetryThinkingLevel(ThinkingLevel.MEDIUM, entryChanged = false, newEntryCeiling = ThinkingLevel.ULTRA),
        )
        assertEquals(
            ThinkingLevel.OFF,
            laneRetryThinkingLevel(ThinkingLevel.OFF, entryChanged = false, newEntryCeiling = ThinkingLevel.HIGH),
        )
    }

    @Test
    fun `retry onto a weaker model caps the level to the new ceiling`() {
        // Rotation landed on a model whose ceiling is below the resolved level:
        // the retry must not send a tier the new model cannot honour.
        assertEquals(
            ThinkingLevel.HIGH,
            laneRetryThinkingLevel(ThinkingLevel.XHIGH, entryChanged = true, newEntryCeiling = ThinkingLevel.HIGH),
        )
        assertEquals(
            ThinkingLevel.LOW,
            laneRetryThinkingLevel(ThinkingLevel.HIGH, entryChanged = true, newEntryCeiling = ThinkingLevel.LOW),
        )
    }

    @Test
    fun `retry onto a stronger model never lifts the level`() {
        // P2 3-10's other half: rotating to a bigger model must not silently
        // upgrade the session's ask — the session level stays the authority.
        assertEquals(
            ThinkingLevel.LOW,
            laneRetryThinkingLevel(ThinkingLevel.LOW, entryChanged = true, newEntryCeiling = ThinkingLevel.MAX),
        )
        assertEquals(
            ThinkingLevel.OFF,
            laneRetryThinkingLevel(ThinkingLevel.OFF, entryChanged = true, newEntryCeiling = ThinkingLevel.ULTRA),
        )
    }

    @Test
    fun `retry re-clamp agrees with the face policy for the changed case`() {
        // The retry clamp is the face clamp applied to the SAME session the
        // lane inherited — one contract, two entry points.
        val levels = ThinkingLevel.entries
        for (level in levels) {
            for (ceiling in levels) {
                assertEquals(
                    laneSessionThinkingLevel(level, ceiling),
                    laneRetryThinkingLevel(level, entryChanged = true, newEntryCeiling = ceiling),
                )
            }
        }
        assertTrue(true)
    }
}
