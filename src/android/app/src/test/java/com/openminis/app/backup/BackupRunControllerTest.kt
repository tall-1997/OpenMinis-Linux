package com.openminis.app.backup

import com.openminis.app.ProductionSources
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-backup-run-controller] "A backup survives leaving the screen" —
 * the run belongs to a process-scoped controller instead of the screen's
 * ViewModel, Stop still reaches it from anywhere, and the byte formatter lives
 * in ONE place now that a non-UI class needs it too.
 *
 * The run itself needs a device (Context, file system, network), so what is
 * pinned here is the formatting contract (pure) plus the wiring whose absence
 * would silently undo the fix: a run launched from `viewModelScope`, a Stop
 * that only the screen's own job could satisfy, a formatter copied instead of
 * shared, and a cancellation flag that never reaches the blocking code.
 */
class BackupRunControllerTest {

    private val originalLocale = Locale.getDefault()

    @After
    fun restoreLocale() {
        Locale.setDefault(originalLocale)
    }

    // ── The byte formatter, one implementation ────────────────────────────

    @Test
    fun `byte sizes follow the divisors their labels claim`() {
        assertEquals("999 B", humanBytes(999))
        assertEquals("1 kB", humanBytes(1_000))
        assertEquals("1.5 MB", humanBytes(1_500_000))
        assertEquals("2.0 GB", humanBytes(2_000_000_000))
    }

    @Test
    fun `the decimal point does not follow the device locale`() {
        // A German device renders "%.1f" as "1,5": the same package would be
        // described differently in the UI than in the persisted log line.
        Locale.setDefault(Locale.GERMANY)
        assertEquals("1.5 MB", humanBytes(1_500_000))
    }

    @Test
    fun `there is exactly one formatter`() {
        // It used to live in the screen while the ViewModel reached for it from
        // the same package. The controller is NOT in that package, so a third
        // copy was the cheap way out — this pins the shared one instead.
        val screen = src("java/com/openminis/app/ui/settings/backup/BackupAndRestoreScreen.kt")
        assertFalse(
            "screen must delegate, not reimplement",
            screen.contains("%.1f MB"),
        )
        assertTrue(
            "screen keeps a one-line alias for its five call sites",
            screen.contains("= com.openminis.app.backup.humanBytes(bytes)"),
        )
        assertTrue(
            "the implementation lives where the controller can reach it",
            src("java/com/openminis/app/backup/BackupBytes.kt").contains("fun humanBytes(bytes: Long)"),
        )
    }

    // ── The run is not the screen's ───────────────────────────────────────

    /** Paths are relative to `src/main`, resolved where the module really is. */
    private fun src(path: String): String = ProductionSources.readMain(path)

    /** Comment lines dropped: prose that names a symbol is not a call site. */
    private fun code(path: String): String = src(path).lineSequence()
        .filterNot {
            val t = it.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }
        .joinToString("\n")

    private fun block(where: String, marker: String): String {
        val at = where.indexOf(marker)
        assertTrue("guard marker vanished: $marker", at >= 0)
        return where.substring(at).substringBefore("\n    }")
    }

    private val vm by lazy { code("java/com/openminis/app/ui/settings/backup/BackupViewModel.kt") }
    private val controller by lazy { code("java/com/openminis/app/backup/BackupRunController.kt") }

    @Test
    fun `the export run is launched by the controller, not the screen`() {
        val start = block(vm, "fun startExport(passphrase: String?)")
        assertTrue("startExport must hand the run to the controller", start.contains("BackupRunController.start("))
        assertFalse("a screen-scoped job dies with the screen", start.contains("viewModelScope.launch"))
        // And the controller really owns a scope of its own: a job on
        // viewModelScope would compile just as well.
        assertTrue("controller needs a process scope", controller.contains("CoroutineScope("))
        assertTrue("run launched on that scope", controller.contains("runJob = scope.launch"))
    }

    @Test
    fun `stop works from a screen that did not start the run`() {
        val stop = block(vm, "fun stopExport()")
        assertTrue("Stop must reach the controller's job", stop.contains("BackupRunController.stop()"))
        assertTrue("controller cancels the run it holds", block(controller, "fun stop()").contains("cancel()"))
    }

    @Test
    fun `the button never reads idle while the controller is running`() {
        // One Start button, two run kinds (export = controller, restore = this
        // VM): reporting only the VM's flag is how "idle" appeared above a
        // running backup.
        assertTrue(
            "isRunning must fold in the controller's",
            vm.contains("combine(BackupRunController.isRunning, _isRunning)"),
        )
        assertTrue(
            "history must re-read on the controller's signal",
            vm.contains("BackupRunController.historyVersion.collect"),
        )
    }

    @Test
    fun `a restore refuses while a background export runs`() {
        // The export now outlives the screen, so "busy" can be a run this VM
        // never started. Refusing only on the local flag would let a restore
        // import into the database the exporter is still reading.
        val start = block(vm, "fun startRestore(passphrase: String?)")
        assertTrue("restore must see the combined flag", start.contains("if (isRunning.value) return"))
        assertFalse("the local flag alone is blind to a background export", start.contains("if (_isRunning.value) return"))
    }

    @Test
    fun `cancellation reaches the blocking work`() {
        // job.cancel() cannot interrupt a blocking upload or a file copy: the
        // run has to hand its own state down as a flag both paths poll.
        assertTrue(
            "tree walk polls the run's job",
            code("java/com/openminis/app/backup/BackupExporter.kt")
                .contains("isCancelled = { runJob?.isActive == false }"),
        )
        assertTrue(
            "phone copy is given the flag",
            controller.contains("copyPackage(folder, packageFile, packageFile.name, isCancelled)"),
        )
        val copy = code("java/com/openminis/app/backup/PhoneBackupFolderStore.kt")
            .substringAfter("fun copyPackage(")
            .substringBefore("\n    }")
        assertTrue("a copy must stop mid-file, not after it", copy.contains("if (isCancelled())"))
    }
}
