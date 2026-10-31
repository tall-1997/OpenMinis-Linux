package com.openminis.app.vscreen

import com.openminis.app.offload.ShizukuManager
import com.openminis.app.vscreen.VirtualScreenManager.CommandOutcome
import com.openminis.app.vscreen.VirtualScreenManager.CreateOutcome
import com.openminis.app.vscreen.VirtualScreenManager.DisplayHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-android-vscreen2] JVM tests for the V2 manager behaviours wired onto
 * the original seams: the silent ledger around launch/destroy, the
 * not-muting-what-the-user-uses rule, leftover reconcile, idle release and
 * the handoff controller plumbing (controller override).
 */
class VirtualScreenManagerV2Test {

    private class FakeShell : VirtualScreenManager.ShellRunner {
        val calls = mutableListOf<Array<String>>()
        var respond: (Array<String>) -> ShizukuManager.ProcessResult = { ShizukuManager.ProcessResult(1, "", "refused") }

        override fun run(argv: Array<String>, timeoutMs: Long): ShizukuManager.ProcessResult {
            calls.add(argv)
            return respond(argv)
        }

        fun joined() = calls.map { it.joinToString(" ") }
        fun of(bin: String) = calls.filter { it.firstOrNull() == bin }.map { it.toList() }
    }

    private class FakeDisplay(override val displayId: Int) : DisplayHandle {
        var released = false
        override fun attach(surface: android.view.Surface): Boolean = true
        override fun detach() {}
        override fun release() {
            released = true
        }
    }

    private lateinit var shell: FakeShell

    private val scheduler = object : VirtualScreenManager.Scheduler {
        val queued = mutableListOf<Runnable>()
        override fun post(r: Runnable, delayMs: Long) {
            queued.add(r)
        }
    }

    @Before
    fun reset() {
        shell = FakeShell()
        VirtualScreenManager.resetForTest(
            factory = countingFactory(),
            shell = shell,
            guard = { null },
            scheduler = scheduler,
        )
    }

    /** Fresh ids per create so several screens can coexist in one test. */
    private fun countingFactory(): VirtualScreenManager.DisplayHandleFactory {
        var next = 2
        return VirtualScreenManager.DisplayHandleFactory { _, _, _, _ -> FakeDisplay(next++) }
    }

    private fun seedDisplay() {
        VirtualScreenManager.create(0, 0, 0, "seed", null)
    }

    /** Blocks until the post-launch spillback watcher exited (deterministic assertions). */
    private fun awaitWatcher(displayId: Int) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val s = VirtualScreenManager.screen(displayId) ?: return
            if (!s.spillbackWatching) return
            Thread.sleep(10)
        }
    }

    /** Launch with mute working (appops get reports "allow", everything set exits 0). */
    private fun launchMuted(): CommandOutcome.Ok {
        seedDisplay()
        shell.respond = { argv ->
            val joined = argv.joinToString(" ")
            when {
                joined.startsWith("appops get") -> ShizukuManager.ProcessResult(0, "PLAY_AUDIO: allow; time=...", "")
                joined.startsWith("appops set") -> ShizukuManager.ProcessResult(0, "", "")
                joined.startsWith("am start") -> ShizukuManager.ProcessResult(0, "Status: ok", "")
                // Listing unavailable → the spillback watcher exits immediately
                // (deterministic: no background polling during the test).
                else -> ShizukuManager.ProcessResult(1, "", "no listing")
            }
        }
        val r = VirtualScreenManager.launch(2, "com.target.app", "com.target.app/.Main") as CommandOutcome.Ok
        awaitWatcher(2)
        return r
    }

    // ── silent ledger ─────────────────────────────────────────────────────

    @Test
    fun `launch mutes via appops BEFORE am start and says so in the detail`() {
        val r = launchMuted()
        val relevant = shell.joined().filter { it.startsWith("appops") || it.startsWith("am start") }
        assertEquals(
            listOf(
                "appops get --user 0 com.target.app PLAY_AUDIO",
                "appops set --user 0 com.target.app PLAY_AUDIO ignore",
                "am start -W --display 2 -n com.target.app/.Main",
            ),
            relevant,
        )
        assertTrue(r.detail.contains("audio muted via appops"))
        assertTrue(r.detail.contains("PLAY_AUDIO was: allow"))
    }

    @Test
    fun `launch refuses to mute a package the user has open on the main display`() {
        seedDisplay()
        var listingCalls = 0
        shell.respond = { argv ->
            val joined = argv.joinToString(" ")
            when {
                joined.startsWith("am stack list") || joined.startsWith("cmd activity stack list") ||
                    joined.startsWith("dumpsys") ->
                    // First listing: the target app already runs on the user's
                    // display; later ones fail so the watcher exits quickly.
                    if (listingCalls++ == 0) {
                        ShizukuManager.ProcessResult(0, "displayId=0\n*TaskRecord{def #7 A=com.target.app U=0 sz=1}", "")
                    } else {
                        ShizukuManager.ProcessResult(1, "", "no")
                    }
                joined.startsWith("appops") -> ShizukuManager.ProcessResult(0, "", "")
                joined.startsWith("am start") -> ShizukuManager.ProcessResult(0, "Status: ok", "")
                else -> ShizukuManager.ProcessResult(1, "", "no")
            }
        }
        val r = VirtualScreenManager.launch(2, "com.target.app", "com.target.app/.Main") as CommandOutcome.Ok
        awaitWatcher(2)
        assertTrue(r.detail.contains("not muting com.target.app"))
        assertTrue(shell.of("appops").isEmpty())
    }

    @Test
    fun `a mute failure is reported but does not block the launch`() {
        seedDisplay()
        shell.respond = { argv ->
            val joined = argv.joinToString(" ")
            when {
                joined.startsWith("appops get") -> ShizukuManager.ProcessResult(1, "", "appops refused")
                joined.startsWith("am start") -> ShizukuManager.ProcessResult(0, "Status: ok", "")
                else -> ShizukuManager.ProcessResult(1, "", "no listing")
            }
        }
        val r = VirtualScreenManager.launch(2, "com.target.app", "com.target.app/.Main") as CommandOutcome.Ok
        awaitWatcher(2)
        assertTrue(r.detail.contains("audio mute failed"))
        assertEquals(1, shell.of("appops").size) // get failed → no set attempted
    }

    @Test
    fun `destroy restores the original PLAY_AUDIO mode of everything the screen muted`() {
        launchMuted()
        shell.calls.clear()
        shell.respond = { argv ->
            if (argv.joinToString(" ").startsWith("appops set")) ShizukuManager.ProcessResult(0, "", "")
            else ShizukuManager.ProcessResult(1, "", "no")
        }
        val report = VirtualScreenManager.destroyDetailed(2)!!
        assertEquals(listOf("com.target.app"), report.soundRestored)
        assertTrue(report.soundRestored.isNotEmpty())
        assertTrue(shell.joined().single().endsWith("com.target.app PLAY_AUDIO allow"))
        // And the ledger is empty afterwards.
        assertEquals(0, VirtualScreenManager.muter().mutedPackages().size)
    }

    @Test
    fun `reconcileState restores leftover ledger entries of dead displays`() {
        seedDisplay()
        shell.respond = { ShizukuManager.ProcessResult(0, "", "") }
        VirtualScreenManager.muter().mute(99, "com.ghost.app") // display 99 never existed
        shell.calls.clear()
        val notes = VirtualScreenManager.reconcileState()
        assertTrue(notes.any { it.contains("leftover") && it.contains("com.ghost.app") })
        assertTrue(shell.joined().single().endsWith("com.ghost.app PLAY_AUDIO allow"))
        assertEquals(0, VirtualScreenManager.muter().mutedPackages().size)
    }

    // ── handoff plumbing ──────────────────────────────────────────────────

    private fun controllerWithWaiting(displayId: Int): HandoffController {
        val prefs = MemoryPrefs()
        HandoffStore(prefs).put(
            HandoffRecord(
                displayId = displayId,
                taskId = 42,
                packageName = "com.agent.app",
                topActivity = "",
                kind = "login",
                reason = "sign in",
                state = HandoffStateMachine.State.WAITING,
                startedAtMs = 1L,
                lastChangedAtMs = 1L,
            ),
        )
        return HandoffController(
            store = HandoffStore(prefs),
            mover = TaskMover({ a, t -> shell.run(a, t) }, service = { null }),
            muter = AudioMuter({ a, t -> shell.run(a, t) }, MemoryPrefs()),
            notifier = { true },
        )
    }

    @Test
    fun `handoffNote surfaces the overridden controller's waiting state`() {
        VirtualScreenManager.resetForTest(
            factory = countingFactory(),
            shell = shell,
            guard = { null },
            controller = controllerWithWaiting(2),
            scheduler = scheduler,
        )
        seedDisplay()
        val note = VirtualScreenManager.handoffNote(2)
        assertNotNull(note)
        assertTrue(note!!.contains("WAITING"))
        assertNull(VirtualScreenManager.handoffNote(999))
    }

    @Test
    fun `reapIdle keeps a screen with a WAITING handoff and releases the rest`() {
        VirtualScreenManager.resetForTest(
            factory = countingFactory(),
            shell = shell,
            guard = { null },
            controller = controllerWithWaiting(2),
            scheduler = scheduler,
        )
        val a = (VirtualScreenManager.create(0, 0, 0, null, null) as CreateOutcome.Created).screen
        val b = (VirtualScreenManager.create(0, 0, 0, null, null) as CreateOutcome.Created).screen
        val old = System.currentTimeMillis() - VirtualScreenManager.IDLE_RELEASE_MS - 1
        a.lastUsedAtMs = old
        b.lastUsedAtMs = old
        val released = VirtualScreenManager.reapIdle()
        assertEquals(listOf(b.displayId), released)
        assertNotNull(VirtualScreenManager.screen(a.displayId)) // waiting handoff pins it
        assertNull(VirtualScreenManager.screen(b.displayId))
        // Releasing the pinned screen once the handoff resolves works.
        VirtualScreenManager.resolveHandoff(a.displayId, HandoffStateMachine.Action.KEEP)
        a.lastUsedAtMs = old
        assertEquals(listOf(a.displayId), VirtualScreenManager.reapIdle())
    }

    @Test
    fun `reapIdle goes through the full destroy path and restores sound`() {
        launchMuted()
        shell.calls.clear()
        val screen = VirtualScreenManager.screen(2)!!
        screen.lastUsedAtMs = System.currentTimeMillis() - VirtualScreenManager.IDLE_RELEASE_MS - 1
        shell.respond = { argv ->
            if (argv.joinToString(" ").startsWith("appops set")) ShizukuManager.ProcessResult(0, "", "")
            else ShizukuManager.ProcessResult(1, "", "no")
        }
        val released = VirtualScreenManager.reapIdle()
        assertEquals(listOf(2), released)
        assertTrue(shell.joined().single().endsWith("com.target.app PLAY_AUDIO allow"))
    }

    // ── spillback note plumbing ───────────────────────────────────────────

    @Test
    fun `consumeSpillbackNote returns the note once and clears it`() {
        seedDisplay()
        VirtualScreenManager.screen(2)!!.spillbackNote = "com.pay.app was moved back"
        assertEquals("com.pay.app was moved back", VirtualScreenManager.consumeSpillbackNote(2))
        assertNull(VirtualScreenManager.consumeSpillbackNote(2))
    }

    @Test
    fun `the spillback watcher reports an honest skip when listing is unavailable`() {
        launchMuted() // listing unavailable → watcher exits with the skip note
        val note = VirtualScreenManager.consumeSpillbackNote(2)
        assertTrue(note!!.contains("spillover recovery skipped"))
        assertTrue(note.contains("task listing unavailable"))
    }
}
