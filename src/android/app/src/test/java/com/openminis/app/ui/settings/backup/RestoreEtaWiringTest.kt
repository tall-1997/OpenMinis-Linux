package com.openminis.app.ui.settings.backup

import com.openminis.app.ProductionSources
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-restore-ui] Wiring guard for the restore ETA.
 *
 * [RestoreEtaTest] covers the arithmetic; nothing there notices if the
 * estimator is never fed, or if the screen keeps rendering the bare step line
 * it replaced. Both failures look identical on a device — no estimate — and
 * both are invisible to a compile.
 *
 * Pins the CONTRACT, not the spelling: the ViewModel must feed the estimator
 * from the importer's per-category counts and clear it on every terminal path;
 * the screen must render status and estimate as one running-gated line.
 */
class RestoreEtaWiringTest {

    private val vm = ProductionSources.read("ui/settings/backup/BackupViewModel.kt")
    private val screen = ProductionSources.read("ui/settings/backup/BackupAndRestoreScreen.kt")
    private val label = ProductionSources.read("ui/settings/backup/RestoreEtaLabel.kt")

    /** The startRestore body, so an unrelated `reset()` elsewhere cannot pass. */
    private fun startRestore(): String =
        vm.substringAfter("fun startRestore(").substringBefore("// -- Persistence helpers")

    @Test
    fun `the estimator is fed from the importer's per-category counts`() {
        val body = startRestore()
        assertTrue(
            "startRestore must feed RestoreEta with the importer's category counts",
            body.contains("restoreEta.update(") &&
                body.contains("p.categoryKey") &&
                body.contains("p.done") &&
                body.contains("p.total"),
        )
        // Fed from the same callback the button's counter comes from: two
        // subscriptions to the importer would drift apart.
        val onCount = body.substringAfter("onCount = {").substringBefore("},")
        assertTrue("the ETA must ride onCount, not its own callback", onCount.contains("restoreEta.update("))
        assertTrue("the ETA update must accompany the counter update", onCount.contains("_restoreProgress.value"))
    }

    @Test
    fun `every terminal path clears the estimate`() {
        // A stale estimate left in the flow after a restore ends would render
        // in the NEXT run's first seconds, when no rate exists yet.
        val resets = Regex("restoreEta\\.reset\\(\\)").findAll(vm).count()
        val clears = Regex("_restoreEtaSeconds\\.value = null").findAll(vm).count()
        assertTrue("reset must be called at least twice (start + end), saw $resets", resets >= 2)
        assertTrue("the flow must be cleared at least twice, saw $clears", clears >= 2)
        assertTrue(
            "the end-of-run cleanup belongs in finally so a throw cannot skip it",
            startRestore().substringAfter("finally {").contains("restoreEta.reset()"),
        )
    }

    @Test
    fun `the screen renders the estimate inside the running branch`() {
        val tab = screen.substringAfter("private fun RestoreTab(").substringBefore("\n@Composable")
        val call = tab.indexOf("restoreEtaLabel(etaSeconds)")
        assertTrue("the restore tab must render the estimate", call >= 0)
        // Still inside the `if (running)` block that opens last before the call:
        // a closer at the gate's own indentation would have ended it, so its
        // absence is what "inside" means here — no pinned surrounding lines.
        val gate = tab.substring(0, call).lastIndexOf("if (running) {")
        assertTrue("the estimate must sit behind the running gate", gate >= 0)
        val gateIndent = tab.substring(0, gate).substringAfterLast("\n").length
        val endsEarly = tab.substring(gate, call).lines()
            .any { it.trim() == "}" && it.indexOf('}') == gateIndent }
        assertTrue("the estimate must render only while a restore runs", !endsEarly)
        // Same row as the live step: two stacked rows would reflow the layout
        // every time an estimate appears or drops.
        assertTrue(
            "status and estimate must be joined into one line",
            tab.contains("listOfNotNull(status, restoreEtaLabel(etaSeconds))") &&
                tab.contains("joinToString("),
        )
    }

    @Test
    fun `the label maps every bucket to a string resource and nothing else`() {
        // Every bucket has a destination: a new bucket that maps nowhere shows
        // up as a silently missing estimate.
        for (bucket in listOf("AlmostDone", "Seconds", "MinutesSeconds")) {
            assertTrue("RestoreEtaLabel must handle $bucket", label.contains(bucket))
        }
        for (key in listOf("restore_eta_almost_done", "restore_eta_seconds", "restore_eta_minutes")) {
            assertTrue("RestoreEtaLabel must use $key", label.contains("R.string.$key"))
        }
        // No hard-coded display text: every string comes from resources, which
        // is what keeps the line translated.
        assertTrue(
            "the label must not embed user-facing literals",
            !Regex("R\\.string\\.[a-z_]+\\s*,\\s*\"").containsMatchIn(label),
        )
    }
}
