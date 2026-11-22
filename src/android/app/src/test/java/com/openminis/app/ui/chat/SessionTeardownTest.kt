package com.openminis.app.ui.chat

import com.openminis.app.goal.GoalDriftStore
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * [T-session-sidecar-cleanup] Deleting a chat must drop every sidecar file
 * the chat produced — the four per-session stores at the filesDir root that
 * [com.openminis.app.sandbox.SessionWorkspace.deleteEntire] cannot reach.
 */
class SessionTeardownTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun seedAll(sessionId: String) {
        val filesDir = tmp.root
        // goal-drift/<safe>.json
        GoalDriftStore.save(filesDir, sessionId, GoalDriftStore.DriftState(lastBoardSha = "fp", stallTurns = 3))
        // session-journal/<safe>.jsonl (≤4MB)
        val journalFile = File(File(filesDir, "session-journal"), "$sessionId.jsonl")
        journalFile.parentFile?.mkdirs()
        journalFile.writeText("{}\n")
        // notices/<safe>.json
        NoticeLedgerStore.save(filesDir, sessionId, NoticeLedgerStore.State(recallKeys = setOf("k")))
        // working-memory/<safe>.jsonl (durable log) + in-memory ledger
        val ledger = WorkingMemoryStore.ledgerFor(sessionId, filesDir)
        ledger.append(turn = 0, tool = "shell", argsDigest = "d", argsSummary = "seed", resultHead = "ok")
        // tool-receipts/<sessionId>.jsonl (legacy)
        val legacyDir = File(filesDir, "tool-receipts").apply { mkdirs() }
        legacyDir.resolve("$sessionId.jsonl").writeText("{}\n")
    }

    @Test
    fun `deleteAll removes every sidecar file`() {
        val sid = "session-clean-1"
        seedAll(sid)
        SessionTeardown.deleteAll(tmp.root, sid)

        assertFalse("goal-drift residue", GoalDriftStore.load(tmp.root, sid) != GoalDriftStore.DriftState())
        assertFalse("notices residue", File(File(tmp.root, "notices"), "$sid.json").exists())
        assertFalse(
            "working-memory residue",
            WorkingMemoryStore.fileForTest(tmp.root, sid).exists(),
        )
        assertFalse("session-journal residue", File(File(tmp.root, "session-journal"), "$sid.jsonl").exists())
        assertFalse("legacy receipts residue", File(File(tmp.root, "tool-receipts"), "$sid.jsonl").exists())
    }

    @Test
    fun `deleteAll also clears the in-memory working-memory ledger`() {
        val sid = "session-clean-2"
        val ledger = WorkingMemoryStore.ledgerFor(sid, tmp.root)
        ledger.append(turn = 0, tool = "shell", argsDigest = "d", argsSummary = "seed", resultHead = "ok")

        SessionTeardown.deleteAll(tmp.root, sid)

        // A fresh ledger for the same id must not resurrect the deleted one's
        // in-memory entries (the durable log was dropped with it).
        assertTrue(WorkingMemoryStore.ledgerFor(sid, tmp.root).size() == 0)
    }

    @Test
    fun `unknown session id is a silent no-op`() {
        SessionTeardown.deleteAll(tmp.root, "never-existed")
        // Nothing thrown, nothing created.
        assertFalse(File(tmp.root, "goal-drift").exists())
        assertFalse(File(tmp.root, "notices").exists())
        assertFalse(File(tmp.root, "working-memory").exists())
    }

    @Test
    fun `legacy receipts with ambiguous names are deliberately kept`() {
        // The old writer replaced unsafe characters with '_', so two different
        // sessions can share one file name — deleting it from under a live
        // session is exactly what the disambiguation exists to prevent.
        val shared = File(File(tmp.root, "tool-receipts").apply { mkdirs() }, "session_a.jsonl")
        shared.writeText("{}\n")

        SessionTeardown.deleteAll(tmp.root, "session_a")

        assertTrue("ambiguous legacy file must survive the janitor", shared.exists())
    }
}
