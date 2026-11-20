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
import java.io.File

/**
 * [T-generation-run] The safety net that closes the gap the loop body cannot:
 * a terminal provider error (or cancellation) is thrown straight out of the
 * body, so its own `failed(...)` call never runs. Left alone the row stays
 * RUNNING, and the launch scan reads RUNNING as "the process died mid-run" —
 * which is the hint this whole ledger exists to keep honest.
 *
 * The key property is that [GenerationRunLifecycle.closeOpen] only touches rows
 * still marked RUNNING, so it is a no-op on the two clean paths by construction
 * rather than by bookkeeping.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class GenerationRunLifecycleTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun statusOf(runId: String): String? {
        val file = File(GenerationRunStore.dir(context), "$runId.json")
        if (!file.exists()) return null
        return Regex("\"status\"\\s*:\\s*\"([A-Z]+)\"").find(file.readText())?.groupValues?.get(1)
    }

    @Test
    fun anEscapedRunIsRecordedAsFailedNotLeftRunning() {
        val runId = GenerationRunLifecycle.start(context, "s-escape", "model-x", "anchor-1")
        assertEquals("RUNNING", statusOf(runId))

        val closed = GenerationRunLifecycle.closeOpen(context, runId)

        assertTrue("the escaped row is closed", closed)
        assertEquals("FAILED", statusOf(runId))
        assertFalse(
            "a FAILED run must not surface as an interruption hint",
            GenerationRunStore.recentlyAbandonedFor(context, "s-escape").any { it.runId == runId },
        )
    }

    @Test
    fun theSafetyNetNeverRewritesASuccessfulRun() {
        val runId = GenerationRunLifecycle.start(context, "s-done", "model-x")
        GenerationRunLifecycle.succeeded(context, runId)

        assertFalse(GenerationRunLifecycle.closeOpen(context, runId))
        assertEquals("DONE", statusOf(runId))
    }

    @Test
    fun theSafetyNetNeverRewritesATurnCapFailure() {
        val runId = GenerationRunLifecycle.start(context, "s-cap", "model-x")
        GenerationRunLifecycle.failed(context, runId)

        assertFalse(GenerationRunLifecycle.closeOpen(context, runId))
        assertEquals("FAILED", statusOf(runId))
    }

    @Test
    fun onlyTheFinishedRunIsClosedEvenWithAReplacementInTheSameChat() {
        val mine = GenerationRunLifecycle.start(context, "s-mine", "model-x")
        val other = GenerationRunLifecycle.start(context, "s-other", "model-x")
        val replacement = GenerationRunLifecycle.start(context, "s-mine", "model-y")

        assertTrue(GenerationRunLifecycle.closeOpen(context, mine))
        assertEquals("FAILED", statusOf(mine))
        assertEquals("RUNNING", statusOf(other))
        assertEquals("RUNNING", statusOf(replacement))
    }

    @Test
    fun closingTwiceIsIdempotent() {
        val runId = GenerationRunLifecycle.start(context, "s-twice", "model-x")
        assertTrue(GenerationRunLifecycle.closeOpen(context, runId))
        assertFalse(GenerationRunLifecycle.closeOpen(context, runId))
    }

    @Test
    fun aMissingRunIdTouchesNothing() {
        val runId = GenerationRunLifecycle.start(context, "s-empty", "model-x")
        assertFalse(GenerationRunLifecycle.closeOpen(context, "missing-id"))
        assertEquals("RUNNING", statusOf(runId))
    }

    @Test
    fun aStillRunningRunRemainsACrashCandidate() {
        val runId = GenerationRunLifecycle.start(context, "s-crash", "model-x")
        assertTrue(
            "RUNNING is still what a dead process leaves behind",
            GenerationRunStore.abandoned(context).any { it.runId == runId },
        )
    }
}
