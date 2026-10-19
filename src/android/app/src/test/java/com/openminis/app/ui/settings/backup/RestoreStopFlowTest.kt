package com.openminis.app.ui.settings.backup

import com.openminis.app.ProductionSources
import com.openminis.app.backup.BackupImporter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-restore-ui] Stopping a restore: the tap target, the confirmation,
 * and the state handoff that makes a Stop look like a Stop.
 *
 * Reported: during a restore the button bar went inert and nothing on screen
 * could cancel it — the only way out was to leave the settings screen (the
 * work kept running) or force-quit the app.
 *
 * The ring is tested as data ([restoreFraction]); the wiring is pinned by
 * source guards, because the screen and the view model need a Context, an
 * Activity and a database to instantiate.
 */
class RestoreStopFlowTest {

    // ── the fraction that drives the ring (pure) ─────────────────────────

    @Test
    fun `no total means no fraction`() {
        assertNull(restoreFraction(null))
        assertNull(restoreFraction(BackupImporter.Progress("chats", done = 5)))
        assertNull(restoreFraction(BackupImporter.Progress("chats", done = 0, total = 0)))
    }

    @Test
    fun `a reported total yields the plain ratio`() {
        assertEquals(0.3f, restoreFraction(BackupImporter.Progress("chats", 3, 10))!!, 1e-6f)
        assertEquals(0f, restoreFraction(BackupImporter.Progress("chats", 0, 10))!!, 1e-6f)
    }

    @Test
    fun `done past total clamps to full, never wraps`() {
        // The manifest's count and the JSONL's record count can disagree by a
        // few rows. An arc past 100% wraps around and reads as "just started"
        // at the exact moment the restore is finishing.
        assertEquals(1f, restoreFraction(BackupImporter.Progress("chats", 105, 100))!!, 1e-6f)
    }

    // ── the screen's wiring ──────────────────────────────────────────────

    private val screen by lazy {
        ProductionSources.read("ui/settings/backup/BackupAndRestoreScreen.kt")
    }

    private val viewModelSource by lazy {
        ProductionSources.read("ui/settings/backup/BackupViewModel.kt")
    }

    @Test
    fun `stop is offered only while a restore runs`() {
        assertTrue(
            "the stop target must appear only while running",
            screen.contains("onStopClick = { confirmStop = true }.takeIf { running }"),
        )
        // The bar itself stays disabled while running: a stray tap must be
        // unable to start a second restore or abandon the current one.
        assertTrue(
            "the action button must stay inert while running",
            screen.contains("enabled = !running && restoreSelected.isNotEmpty()"),
        )
    }

    @Test
    fun `stopping abandons work in progress, so it asks first`() {
        val dialog = screen.substringAfter("if (confirmStop)")
        assertTrue(
            "the confirmation must say what happens to the restored data",
            dialog.contains("R.string.restore_stop_message"),
        )
        // Exactly one call site: the dialog's confirm button. A second, silent
        // stop path would defeat the question.
        assertEquals(
            1,
            Regex("vm\\.stopRunningRestore\\(\\)").findAll(screen).count(),
        )
    }

    @Test
    fun `the busy ring is determinate`() {
        // The indeterminate spinner animates forever, repainting the whole
        // surface every frame for the length of an I/O-bound restore. It must
        // not come back.
        assertTrue(
            "the busy indicator must be determinate",
            !screen.contains(
                "CircularProgressIndicator(\n" +
                    "            modifier = Modifier.size(18.dp),",
            ),
        )
        assertTrue(
            "the ring must be fed a fraction",
            screen.contains("progress = { fraction ?: 0f }"),
        )
    }

    // ── the view model's handoff ─────────────────────────────────────────

    @Test
    fun `stop cancels the job and clears the copy itself`() {
        val fn = viewModelSource
            .substringAfter("fun stopRunningRestore()")
            .substringBefore("// -- Persistence helpers")
        // The copy is set here, not by the cancelled coroutine: its handlers
        // are not guaranteed to run before the user sees the screen.
        assertTrue("the job must be cancelled", fn.contains("job.cancel()"))
        assertTrue(fn.contains("_isRunning.value = false"))
        assertTrue(fn.contains("_restoreProgress.value = null"))
        assertTrue(fn.contains("_statusText.value = null"))
        // A second run must start with a fresh estimate, not the old average.
        assertTrue(fn.contains("restoreEta.reset()"))
    }

    @Test
    fun `a stopped restore is not reported as a failure`() {
        val branch = viewModelSource
            .substringAfter("[T-android-restore-ui] Stop is not a failure")
            .substringBefore("catch (e: Exception)")
        assertTrue("the cancellation must be rethrown", branch.contains("throw e"))
        assertTrue("a stop must not set an error", !branch.contains("_errorText.value"))
    }

    @Test
    fun `a stale finally must not clear the newer run`() {
        // Stop frees the slot at once; the cancelled job's finally then runs
        // and would otherwise wipe the state of a restore started meanwhile.
        assertTrue(
            viewModelSource.contains(
                "if (restoreJob === coroutineContext[kotlinx.coroutines.Job]) {",
            ),
        )
    }
}
