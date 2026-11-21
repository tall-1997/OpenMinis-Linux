package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * [T-receipt-aged-out] Orphans from earlier generations whose age is beyond
 * doubt settle out of the store; records of the queried generation are never
 * aged out regardless of age. Generation boundaries are the progress evidence
 * required by the settled-out double condition.
 */
class ToolReceiptStoreAgeOutTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun scope(session: String, row: String) = ToolReceiptScope.userTurn(session, row)!!
    private fun key(scope: ToolReceiptScope) = scope.call("shell_execute", "{}")!!

    @Test
    fun staleOrphansFromEarlierGenerationsSettleOut() {
        val dir = tmp.newFolder("receipts")
        var t = 1_000_000L
        val store = ToolReceiptStore(dir, now = { t })
        val gen1 = scope("session-1", "row-1")
        store.claim(key(gen1), "call-1")
        val sessionSha = ToolReceiptScope.sessionDigest("session-1")
        assertEquals(1, store.pending(sessionSha, gen1.parentSha)!!.unknown)

        // A later generation, 7h later: the orphan is from another generation
        // and stale -> settled out with an audit line; the new generation
        // starts clean.
        t += ToolReceiptStore.STALE_MS + 60_000L
        val later = ToolReceiptStore(dir, now = { t })
        val gen2 = scope("session-1", "row-2")
        assertEquals(0, later.pending(sessionSha, gen2.parentSha)!!.unknown)
        assertEquals(0, later.pending(sessionSha, gen1.parentSha)!!.unknown)
    }

    @Test
    fun liveGenerationRecordsAreNeverAgedOut() {
        val dir = tmp.newFolder("receipts2")
        var t = 1_000_000L
        val store = ToolReceiptStore(dir, now = { t })
        val gen1 = scope("session-1", "row-1")
        store.claim(key(gen1), "call-1")
        val sessionSha = ToolReceiptScope.sessionDigest("session-1")
        t += ToolReceiptStore.STALE_MS * 3
        // Same generation queried under its own parent: stays pending however
        // old it is — the guard must never settle live evidence.
        assertEquals(1, store.pending(sessionSha, gen1.parentSha)!!.unknown)
        // A NEW generation query settles the stale orphan.
        val gen2 = scope("session-1", "row-2")
        assertEquals(0, store.pending(sessionSha, gen2.parentSha)!!.unknown)
    }

    @Test
    fun freshEvidenceOfTheQueriedGenerationSurvivesCapacityPressure() {
        val dir = tmp.newFolder("receipts3")
        var t = 1_000_000L
        val store = ToolReceiptStore(dir, maxRecords = 1, now = { t })
        val gen1 = scope("session-1", "row-1")
        store.claim(key(gen1), "call-1")
        val sessionSha = ToolReceiptScope.sessionDigest("session-1")
        assertEquals(1, store.pending(sessionSha, gen1.parentSha)!!.unknown)
        // A second claim of the SAME generation recycles nothing (maxRecords=1
        // forces the space decision) — fresh UNKNOWN evidence of the live
        // generation keeps its pin instead of returning Full.
        val key2 = gen1.call("shell_execute", "{\"x\":1}")!!
        store.claim(key2, "call-2")
        assertEquals(1, store.pending(sessionSha, gen1.parentSha)!!.unknown)
    }
}
