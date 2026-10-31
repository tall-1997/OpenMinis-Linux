package com.openminis.app.vscreen

import com.openminis.app.offload.ShizukuManager
import com.openminis.app.vscreen.HandoffStateMachine.Action
import com.openminis.app.vscreen.HandoffStateMachine.State
import com.openminis.app.vscreen.HandoffController.ResolveOutcome
import com.openminis.app.vscreen.HandoffController.StartOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-vscreen2] JVM tests for the hand_to_user orchestration, driven
 * through the REAL [TaskMover]/[AudioMuter] over a scripted fake shell (so
 * the exact command sequences are asserted end to end): start (move to main
 * + restore sound + notify), the three user choices (处理好了 / 我自己做完了 /
 * 不做了), the move-back-failure hold, and the dead-screen cleanup.
 */
class HandoffControllerTest {

    /** Shell that behaves like a ROM where the `am stack` verbs work. */
    private class ScriptedShell {
        val calls = mutableListOf<Array<String>>()
        var task42Display: Int = 2
        var taskOnVscreen: Boolean = true
        var listWorks: Boolean = true
        var moveWorks: Boolean = true

        fun run(argv: Array<String>, timeoutMs: Long): ShizukuManager.ProcessResult {
            calls.add(argv)
            return respond(argv)
        }

        private fun respond(argv: Array<String>): ShizukuManager.ProcessResult {
            val joined = argv.joinToString(" ")
            return when {
                !listWorks -> ShizukuManager.ProcessResult(1, "", "list refused")
                joined.startsWith("appops get") -> ShizukuManager.ProcessResult(0, "PLAY_AUDIO: allow; time=...", "")
                joined.startsWith("appops set") -> ShizukuManager.ProcessResult(0, "", "")
                joined.startsWith("am stack list") || joined.startsWith("cmd activity stack list") ||
                    joined.startsWith("dumpsys") -> ShizukuManager.ProcessResult(0, listing(), "")
                joined.startsWith("am stack move-task") || joined.startsWith("cmd activity stack move-task") ||
                    joined.startsWith("am display move-stack") ->
                    if (moveWorks) {
                        task42Display = destinationOf(joined)
                        ShizukuManager.ProcessResult(0, "", "")
                    } else {
                        ShizukuManager.ProcessResult(1, "", "move refused")
                    }
                else -> ShizukuManager.ProcessResult(1, "", "unexpected: $joined")
            }
        }

        /** The move target is always the LAST int of the command. */
        private fun destinationOf(joined: String): Int =
            joined.split(" ").last().toIntOrNull() ?: 0

        private fun listing(): String = buildString {
            if (taskOnVscreen) {
                append("displayId=$task42Display\n*TaskRecord{abc #42 A=com.agent.app U=0 sz=1}\n")
            }
            if (task42Display != 0) {
                append("displayId=0\n*TaskRecord{def #7 A=com.other.app U=0 sz=1}\n")
            }
        }

        fun moveCalls() = calls
            .filter { it.contains("move-task") || it.contains("move-stack") }
            .map { it.joinToString(" ") }
    }

    private class Fixtures(shell: ScriptedShell) {
        val handoffPrefs = MemoryPrefs()
        val muter = AudioMuter({ a, t -> shell.run(a, t) }, MemoryPrefs())
        val posted = mutableListOf<HandoffRecord>()
        val controller = HandoffController(
            store = HandoffStore(handoffPrefs),
            mover = TaskMover({ a, t -> shell.run(a, t) }, service = { null }),
            muter = muter,
            notifier = { posted.add(it); true },
            nowMs = { 5_000L },
        )
        val shell = shell
    }

    // ── start ─────────────────────────────────────────────────────────────

    @Test
    fun `start moves the top task to the main display and records WAITING`() {
        val shell = ScriptedShell()
        val fx = Fixtures(shell)
        val r = fx.controller.start(2, "login", "please sign in")
        assertTrue(r is StartOutcome.Started)
        val started = r as StartOutcome.Started
        assertEquals(State.WAITING, started.record.state)
        assertEquals(42, started.record.taskId)
        assertEquals("com.agent.app", started.record.packageName)
        assertTrue(started.notificationPosted)
        assertEquals(listOf("am stack move-task 42 0"), shell.moveCalls())
        assertEquals(State.WAITING, HandoffStore(fx.handoffPrefs).get(2)!!.state)
        assertEquals(1, fx.posted.size)
    }

    @Test
    fun `start restores the sound of the handed-over app`() {
        val shell = ScriptedShell()
        val fx = Fixtures(shell)
        fx.muter.mute(2, "com.agent.app") // the agent muted it when launching
        val r = fx.controller.start(2, "payment", "pay the order")
        assertTrue((r as StartOutcome.Started).soundRestored)
        assertTrue(fx.muter.mutedPackages().isEmpty())
    }

    @Test
    fun `start without task listing reports the exact manual-handling error`() {
        val shell = ScriptedShell()
        shell.listWorks = false
        val fx = Fixtures(shell)
        val r = fx.controller.start(2, "login", "sign in")
        assertTrue(r is StartOutcome.MoveUnavailable)
        assertTrue(
            (r as StartOutcome.MoveUnavailable).message
                .contains("task move unavailable on this ROM; ask the user to handle it manually"),
        )
    }

    @Test
    fun `start with an empty display asks the model to launch first`() {
        val shell = ScriptedShell()
        shell.taskOnVscreen = false
        val fx = Fixtures(shell)
        val r = fx.controller.start(2, "login", "sign in")
        assertTrue(r is StartOutcome.MoveFailed)
        assertTrue((r as StartOutcome.MoveFailed).message.contains("no task is running on display 2"))
        assertTrue(shell.moveCalls().isEmpty())
    }

    @Test
    fun `start when every move candidate fails reports unavailable with the probed list`() {
        val shell = ScriptedShell()
        shell.moveWorks = false
        val fx = Fixtures(shell)
        val r = fx.controller.start(2, "login", "sign in")
        assertTrue(r is StartOutcome.MoveUnavailable)
        assertTrue((r as StartOutcome.MoveUnavailable).message.contains("probed: user service"))
    }

    @Test
    fun `a second start while waiting is refused without moving anything`() {
        val shell = ScriptedShell()
        val fx = Fixtures(shell)
        fx.controller.start(2, "login", "sign in")
        val before = shell.moveCalls().size
        val r = fx.controller.start(2, "captcha", "solve it")
        assertTrue(r is StartOutcome.AlreadyWaiting)
        assertEquals(before, shell.moveCalls().size)
    }

    // ── resolve: the three user choices ───────────────────────────────────

    @Test
    fun `done moves the task back re-mutes it and resolves to RETURNED`() {
        val shell = ScriptedShell()
        val fx = Fixtures(shell)
        fx.controller.start(2, "login", "sign in")
        val r = fx.controller.resolve(2, Action.MOVE_BACK_DONE)
        assertTrue(r is ResolveOutcome.Resolved)
        assertEquals(State.RETURNED, (r as ResolveOutcome.Resolved).record.state)
        assertEquals(State.RETURNED, HandoffStore(fx.handoffPrefs).get(2)!!.state)
        assertEquals(
            listOf("am stack move-task 42 0", "am stack move-task 42 2"),
            shell.moveCalls(),
        )
        assertEquals(listOf("com.agent.app"), fx.muter.mutedPackages())
    }

    @Test
    fun `keep resolves to KEPT and leaves the task on the main display`() {
        val shell = ScriptedShell()
        val fx = Fixtures(shell)
        fx.controller.start(2, "login", "sign in")
        val movesAfterStart = shell.moveCalls().size
        val r = fx.controller.resolve(2, Action.KEEP)
        assertEquals(State.KEPT, (r as ResolveOutcome.Resolved).record.state)
        assertEquals(movesAfterStart, shell.moveCalls().size)
        assertEquals(State.KEPT, HandoffStore(fx.handoffPrefs).get(2)!!.state)
    }

    @Test
    fun `abort resolves to ABORTED`() {
        val shell = ScriptedShell()
        val fx = Fixtures(shell)
        fx.controller.start(2, "choose", "pick one")
        val r = fx.controller.resolve(2, Action.ABORT)
        assertEquals(State.ABORTED, (r as ResolveOutcome.Resolved).record.state)
        assertEquals(State.ABORTED, HandoffStore(fx.handoffPrefs).get(2)!!.state)
    }

    @Test
    fun `a failed move back keeps the handoff WAITING with the reason in the note`() {
        val shell = ScriptedShell()
        val fx = Fixtures(shell)
        fx.controller.start(2, "login", "sign in")
        shell.moveWorks = false
        val r = fx.controller.resolve(2, Action.MOVE_BACK_DONE)
        assertTrue(r is ResolveOutcome.MoveBackFailed)
        assertEquals(State.WAITING, (r as ResolveOutcome.MoveBackFailed).record.state)
        assertEquals(State.WAITING, HandoffStore(fx.handoffPrefs).get(2)!!.state)
        assertTrue(HandoffStore(fx.handoffPrefs).get(2)!!.note.contains("move-back to the agent screen failed"))
    }

    @Test
    fun `resolving without a record is reported not waiting`() {
        val fx = Fixtures(ScriptedShell())
        assertTrue(fx.controller.resolve(2, Action.KEEP) is ResolveOutcome.NotWaiting)
        assertTrue(fx.controller.resolve(2, Action.MOVE_BACK_DONE) is ResolveOutcome.NotWaiting)
    }

    // ── cleanup and annotations ───────────────────────────────────────────

    @Test
    fun `clearForDestroyedDisplay drops the record`() {
        val shell = ScriptedShell()
        val fx = Fixtures(shell)
        fx.controller.start(2, "login", "sign in")
        val cleared = fx.controller.clearForDestroyedDisplay(2)
        assertEquals(42, cleared!!.taskId)
        assertNull(HandoffStore(fx.handoffPrefs).get(2))
    }

    @Test
    fun `noteFor reflects the state guidance`() {
        val shell = ScriptedShell()
        val fx = Fixtures(shell)
        assertNull(fx.controller.noteFor(2))
        fx.controller.start(2, "captcha", "solve it")
        assertTrue(fx.controller.noteFor(2)!!.contains("WAITING"))
        assertTrue(fx.controller.noteFor(2)!!.contains("captcha"))
        fx.controller.resolve(2, Action.KEEP)
        assertTrue(fx.controller.noteFor(2)!!.contains("do not operate"))
        fx.controller.resolve(2, Action.START)
        assertTrue(fx.controller.noteFor(2)!!.contains("WAITING"))
    }

    @Test
    fun `a failed move-back note rides in noteFor`() {
        val shell = ScriptedShell()
        val fx = Fixtures(shell)
        fx.controller.start(2, "login", "sign in")
        shell.moveWorks = false
        fx.controller.resolve(2, Action.MOVE_BACK_DONE)
        assertTrue(fx.controller.noteFor(2)!!.contains("move-back to the agent screen failed"))
    }
}
