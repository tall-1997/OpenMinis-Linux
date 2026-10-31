package com.openminis.app.vscreen

import com.openminis.app.offload.ShizukuManager
import com.openminis.app.vscreen.TaskMover.ListOutcome
import com.openminis.app.vscreen.TaskMover.MoveOutcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-vscreen2] JVM tests for the shell probe table behind task
 * moves/listing (the user-service binder path needs an Android runtime and
 * is exercised on-device): candidates probed in order, exit-0 verified
 * through listing, contradictions rejected, and the exact honest error when
 * every candidate fails.
 */
class TaskMoverTest {

    private class FakeShell {
        val calls = mutableListOf<Array<String>>()
        var respond: (Array<String>) -> ShizukuManager.ProcessResult = { ShizukuManager.ProcessResult(1, "", "not found") }
        fun run(argv: Array<String>, timeoutMs: Long): ShizukuManager.ProcessResult {
            calls.add(argv)
            return respond(argv)
        }

        fun joined() = calls.map { it.joinToString(" ") }
    }

    private fun mover(shell: FakeShell) = TaskMover({ argv, _ -> shell.run(argv, 5_000L) }, service = { null })

    /** `am stack list`-style output with one task per display. */
    private val stackTwoRows =
        "Display #2\n" +
            "  Stack #2: type=standard mode=fullscreen\n" +
            "    *TaskRecord{6f0d16c #42 A=com.agent.app U=0 sz=1}\n" +
            "Display #0\n" +
            "  Stack #0: type=standard mode=fullscreen\n" +
            "    *TaskRecord{ab12cd #7 A=com.user.app U=0 sz=1}\n"

    @Test
    fun `every candidate failing gives the exact manual-handling error`() {
        val shell = FakeShell() // everything exits 1
        val m = mover(shell)
        val r = m.move(42, 0)
        assertTrue(r is MoveOutcome.Unavailable)
        assertEquals(
            "task move unavailable on this ROM; ask the user to handle it manually " +
                "(probed: user service, am stack move-task, cmd activity stack move-task, " +
                "am display move-stack)",
            (r as MoveOutcome.Unavailable).message,
        )
        // All three shell candidates were probed, in table order.
        assertEquals(
            listOf(
                "am stack move-task 42 0",
                "cmd activity stack move-task 42 0",
                "am display move-stack 0 42",
            ),
            shell.joined(),
        )
    }

    @Test
    fun `a verified move through the first candidate wins and is cached`() {
        val shell = FakeShell()
        var row42 = 2
        var row7 = 0
        shell.respond = { argv ->
            val joined = argv.joinToString(" ")
            when {
                joined.startsWith("am stack list") || joined.startsWith("cmd activity stack list") ||
                    joined.startsWith("dumpsys") ->
                    ShizukuManager.ProcessResult(
                        0,
                        "displayId=$row42\n*TaskRecord{abc #42 A=com.agent.app U=0}\n" +
                            "displayId=$row7\n*TaskRecord{def #7 A=com.user.app U=0}",
                        "",
                    )
                joined == "am stack move-task 42 0" -> { row42 = 0; ShizukuManager.ProcessResult(0, "", "") }
                joined == "am stack move-task 7 2" -> { row7 = 2; ShizukuManager.ProcessResult(0, "", "") }
                else -> ShizukuManager.ProcessResult(1, "", "unexpected")
            }
        }
        val m = mover(shell)
        val r = m.move(42, 0)
        assertTrue(r is MoveOutcome.Moved)
        assertEquals("am stack move-task", (r as MoveOutcome.Moved).via)
        assertTrue(r.verified)
        // Cached: the next move tries the winner first and no other candidate.
        shell.calls.clear()
        val r2 = m.move(7, 2)
        assertTrue(r2 is MoveOutcome.Moved)
        assertEquals(
            listOf("am stack move-task 7 2"),
            shell.joined().filter { it.startsWith("am stack move-task") },
        )
    }

    @Test
    fun `an exit-0 candidate that did not move the task is rejected and the next is probed`() {
        val shell = FakeShell()
        var moved = false
        shell.respond = { argv ->
            val joined = argv.joinToString(" ")
            when {
                joined.startsWith("am stack list") || joined.startsWith("dumpsys") ->
                    ShizukuManager.ProcessResult(0, if (moved) stackMovedRows() else stackTwoRows, "")
                joined.startsWith("cmd activity stack list") ->
                    ShizukuManager.ProcessResult(0, stackTwoRows, "")
                joined == "am stack move-task 42 0" ->
                    ShizukuManager.ProcessResult(0, "", "silently useless") // exit 0, no effect
                joined == "cmd activity stack move-task 42 0" -> { moved = true; ShizukuManager.ProcessResult(0, "", "") }
                else -> ShizukuManager.ProcessResult(1, "", "unexpected")
            }
        }
        val r = mover(shell).move(42, 0)
        assertTrue(r is MoveOutcome.Moved)
        assertEquals("cmd activity stack move-task", (r as MoveOutcome.Moved).via)
        assertTrue((r as MoveOutcome.Moved).verified)
    }

    @Test
    fun `an exit-0 move without listing is reported as unverified`() {
        val shell = FakeShell()
        shell.respond = { argv ->
            if (argv.first() == "am" && argv[1] == "stack" && argv[2] == "move-task") {
                ShizukuManager.ProcessResult(0, "", "")
            } else {
                // am stack list exits 0? No — everything else fails, so
                // listing is unavailable and the move stays unverified.
                ShizukuManager.ProcessResult(1, "", "no")
            }
        }
        val r = mover(shell).move(42, 0)
        assertTrue(r is MoveOutcome.Moved)
        assertFalse((r as MoveOutcome.Moved).verified)
        assertEquals("am stack move-task", r.via)
    }

    @Test
    fun `listTasks parses am stack list output with display tracking`() {
        val shell = FakeShell()
        shell.respond = { argv ->
            if (argv.first() == "am" && argv.contentEquals(arrayOf("am", "stack", "list"))) {
                ShizukuManager.ProcessResult(0, stackTwoRows, "")
            } else {
                ShizukuManager.ProcessResult(1, "", "no")
            }
        }
        val o = mover(shell).listTasks()
        assertTrue(o is ListOutcome.Rows)
        val rows = (o as ListOutcome.Rows).rows
        assertEquals(listOf(42, 7), rows.map { it.taskId })
        assertEquals(listOf(2, 0), rows.map { it.displayId })
        assertEquals("com.agent.app", rows[0].packageName)
        assertEquals("am stack list", o.via)
        assertEquals(42, mover(shell).listTop(2)?.taskId)
    }

    @Test
    fun `listTasks falls through empty parses to the next candidate and reports unavailable when all fail`() {
        val shell = FakeShell()
        // am stack list exits 0 with unusable output; cmd exits 1; dumpsys exits 1.
        shell.respond = { argv ->
            if (argv.first() == "am" && argv[1] == "stack" && argv[2] == "list") {
                ShizukuManager.ProcessResult(0, "nothing useful here", "")
            } else {
                ShizukuManager.ProcessResult(1, "", "no")
            }
        }
        val o = mover(shell).listTasks()
        assertTrue(o is ListOutcome.Unavailable)
        assertTrue((o as ListOutcome.Unavailable).message.contains("task listing unavailable"))
        assertTrue(o.message.contains("am stack list"))
    }

    @Test
    fun `parseStackDump handles both display header shapes`() {
        val rows = TaskMover.parseStackDump(
            "Tasks in Display #0\n" +
                "  *TaskRecord{abc #9 A=com.one U=0}\n" +
                "displayId=3\n" +
                "  *TaskRecord{def #10 A=com.two U=0}\n",
        )
        assertEquals(listOf(9 to 0, 10 to 3), rows.map { it.taskId to it.displayId })
        // No TaskRecord → empty list (caller treats as unusable output).
        assertTrue(TaskMover.parseStackDump("Tasks in Display #0\n(none)\n").isEmpty())
    }

    @Test
    fun `parseTaskLines reads the user-service wire format`() {
        val rows = TaskMover.parseTaskLines("42\t2\tcom.agent.app\tcom.agent.app/.Main\n7\t0\tcom.user.app\t\n")
        assertEquals(2, rows.size)
        assertEquals(TaskMover.TaskRow(42, 2, "com.agent.app", "com.agent.app/.Main"), rows[0])
        assertTrue(TaskMover.parseTaskLines("").isEmpty())
        assertTrue(TaskMover.parseTaskLines("garbage line\n").isEmpty())
    }

    /** Listing after task 42 has been moved to the main display (0). */
    private fun stackMovedRows(): String =
        "Display #2\n" +
            "  Stack #2: type=standard mode=fullscreen\n" +
            "Display #0\n" +
            "  Stack #0: type=standard mode=fullscreen\n" +
            "    *TaskRecord{6f0d16c #42 A=com.agent.app U=0 sz=1}\n"

    @Test
    fun `ime policy without a bound service is honest`() {
        val shell = FakeShell()
        assertEquals(TaskMover.ImePolicyOutcome.NoService, mover(shell).setImePolicy(2, 1))
        assertTrue(shell.calls.isEmpty()) // no shell guessing for IME policy
    }
}
