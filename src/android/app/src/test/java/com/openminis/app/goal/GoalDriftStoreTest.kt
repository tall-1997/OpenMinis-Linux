package com.openminis.app.goal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [T-goal-drift-reconciler] The stall counter is the drift signal's memory —
 * it must survive a process restart mid-task, sanitize hostile session ids,
 * and treat a fresh state as "no file" rather than an empty tombstone.
 */
class GoalDriftStoreTest {

    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun `drift state survives a reload`() {
        GoalDriftStore.save(
            tmp.root,
            "session-a",
            GoalDriftStore.DriftState(lastBoardSha = "fp-1", stallTurns = 7, nudgedBoardSha = null),
        )
        val reloaded = GoalDriftStore.load(tmp.root, "session-a")
        assertEquals("fp-1", reloaded.lastBoardSha)
        assertEquals(7, reloaded.stallTurns)
        assertEquals(null, reloaded.nudgedBoardSha)
    }

    @Test
    fun `the delivered-once marker survives a reload`() {
        GoalDriftStore.save(
            tmp.root,
            "session-a",
            GoalDriftStore.DriftState(lastBoardSha = "fp-2", stallTurns = 8, nudgedBoardSha = "fp-2"),
        )
        val reloaded = GoalDriftStore.load(tmp.root, "session-a")
        assertEquals("fp-2", reloaded.nudgedBoardSha)
    }

    @Test
    fun `legacy mixed turn counts reset without forgetting a delivered fingerprint`() {
        val dir = tmp.newFolder("goal-drift")
        dir.resolve("session-a.json").writeText(
            """{"lastBoardSha":"fp-1","stallTurns":9,"nudgedBoardSha":"fp-1"}""",
        )
        val reloaded = GoalDriftStore.load(tmp.root, "session-a")
        assertEquals(GoalDriftStore.DriftState(lastBoardSha = "fp-1", nudgedBoardSha = "fp-1"), reloaded)
    }

    @Test
    fun `a fresh state deletes the file instead of tombstoning`() {
        GoalDriftStore.save(tmp.root, "session-a", GoalDriftStore.DriftState(lastBoardSha = "fp-1", stallTurns = 3))
        val file = tmp.root.resolve("goal-drift/session-a.json")
        assertTrue(file.exists())

        GoalDriftStore.save(tmp.root, "session-a", GoalDriftStore.DriftState())
        assertFalse(
            "a cleared reconciler must leave no residue — a stale tombstone would resurrect a stall",
            file.exists(),
        )
    }

    @Test
    fun `a corrupt file reads as fresh state`() {
        val dir = tmp.newFolder("goal-drift")
        dir.resolve("broken.json").writeText("{ not json at all")
        assertEquals(GoalDriftStore.DriftState(), GoalDriftStore.load(tmp.root, "broken"))
    }

    @Test
    fun `session ids are sanitized into safe file names`() {
        GoalDriftStore.save(tmp.root, "session/../with/slashes", GoalDriftStore.DriftState(stallTurns = 1))
        val files = tmp.root.resolve("goal-drift").list()!!.toList()
        assertTrue(
            "the sanitized file must live flat in the dir",
            files.all { it.matches(Regex("[A-Za-z0-9._-]+\\.json")) },
        )
        assertEquals(1, GoalDriftStore.load(tmp.root, "session/../with/slashes").stallTurns)
    }

    @Test
    fun `negative stall counters read back clamped`() {
        GoalDriftStore.save(tmp.root, "session-a", GoalDriftStore.DriftState(lastBoardSha = "fp-1", stallTurns = -3))
        assertEquals(0, GoalDriftStore.load(tmp.root, "session-a").stallTurns)
    }
}
