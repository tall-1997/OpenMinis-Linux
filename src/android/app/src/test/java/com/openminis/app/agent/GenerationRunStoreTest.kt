package com.openminis.app.agent

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [T-generation-run] Ledger semantics: RUNNING means "nobody closed this", which
 * is exactly what a process death leaves behind. A run that ended without a
 * clean finish must be closed on the spot, otherwise the launch-time scan
 * reports it as an interruption and the user gets a recovery hint for a request
 * that simply hit the turn cap.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class GenerationRunStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    @Test
    fun aRunNobodyClosedIsReportedAbandoned() {
        val runId = GenerationRunStore.start(context, "s-abandon", "model-x", "anchor-1")
        assertTrue(GenerationRunStore.abandoned(context).any { it.runId == runId })
    }

    @Test
    fun aClosedRunIsNotAnAbandonCandidate() {
        val runId = GenerationRunStore.start(context, "s-closed", "model-x")
        GenerationRunStore.finish(context, runId, ok = false)
        assertFalse(GenerationRunStore.abandoned(context).any { it.runId == runId })
        assertFalse(GenerationRunStore.recentlyAbandonedFor(context, "s-closed").any { it.runId == runId })
    }

    @Test
    fun markAbandonedProducesTheRecoveryHint() {
        val runId = GenerationRunStore.start(context, "s-hint", "model-x")
        GenerationRunStore.markAbandoned(context, runId)
        assertTrue(GenerationRunStore.recentlyAbandonedFor(context, "s-hint").any { it.runId == runId })
    }

    @Test
    fun turnAnchorRoundTripsThroughTheLedger() {
        val runId = GenerationRunStore.start(context, "s-anchor", "model-x", "msg-42")
        val run = GenerationRunStore.abandoned(context).first { it.runId == runId }
        assertEquals("msg-42", run.turnAnchor)
    }
}
