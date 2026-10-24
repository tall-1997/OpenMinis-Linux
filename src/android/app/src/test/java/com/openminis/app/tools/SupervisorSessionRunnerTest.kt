package com.openminis.app.tools

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SupervisorSessionRunnerTest {

    private class FakeSeat(vararg replies: String) {
        val calls = mutableListOf<String>()
        private val queue = replies.toMutableList()
        var failWith: Throwable? = null

        suspend fun speak(system: String, user: String): String {
            calls += user
            failWith?.let { throw it }
            return queue.removeFirstOrNull() ?: ""
        }
    }

    private fun envelope(task: String, acceptance: String): String =
        "## Task\n- $task\n\n## Expected result\n- $acceptance"

    private val system = "sys"

    @Test
    fun happyPath_twoRoundsThenDone() = runTest {
        val sup = FakeSeat(
            envelope("do a", "tests pass"),
            "review text without done",
            envelope("fix b", "tests pass"),
            "DONE: tests pass and shipped",
        )
        val worker = FakeSeat("report a", "report b")
        val inbox = SupervisorChat.SupervisorInbox()
        val rounds = mutableListOf<Pair<Int, String>>()
        val outcome = SupervisorSessionRunner.run(
            objective = "obj",
            contextDigest = "ctx",
            maxWorkerTurns = 10,
            supervisorSystem = system,
            supervisor = { u, _ -> sup.speak(system, u) },
            worker = { u, _ -> worker.speak(system, u) },
            inbox = inbox,
            onRound = { n, _, report -> rounds += n to report },
        )
        assertEquals(SupervisorSessionRunner.Outcome.DONE::class, outcome::class)
        assertEquals("tests pass and shipped", (outcome as SupervisorSessionRunner.Outcome.DONE).summary)
        assertEquals(2, rounds.size)
        assertEquals(1 to "report a", rounds[0])
        assertEquals(2 to "report b", rounds[1])
        assertEquals(2, worker.calls.size)
    }

    @Test
    fun envelopeRejection_bouncesWithoutWorkerTurn() = runTest {
        val sup = FakeSeat(
            "no envelope here",
            envelope("do a", "tests pass"),
            "DONE: tests pass",
        )
        val worker = FakeSeat("report a")
        val outcome = SupervisorSessionRunner.run(
            objective = "obj",
            contextDigest = "",
            maxWorkerTurns = 10,
            supervisorSystem = system,
            supervisor = { u, _ -> sup.speak(system, u) },
            worker = { u, _ -> worker.speak(system, u) },
            inbox = SupervisorChat.SupervisorInbox(),
        )
        assertEquals(SupervisorSessionRunner.Outcome.DONE::class, outcome::class)
        assertEquals(1, worker.calls.size)
    }

    @Test
    fun rubberStampTwice_failsSession() = runTest {
        val sup = FakeSeat(
            envelope("do a", "tests pass"),
            "DONE: all fine",
            "DONE: still fine",
        )
        val worker = FakeSeat("report a")
        val outcome = SupervisorSessionRunner.run(
            objective = "obj",
            contextDigest = "",
            maxWorkerTurns = 10,
            supervisorSystem = system,
            supervisor = { u, _ -> sup.speak(system, u) },
            worker = { u, _ -> worker.speak(system, u) },
            inbox = SupervisorChat.SupervisorInbox(),
        )
        assertEquals(SupervisorSessionRunner.Outcome.FAILED::class, outcome::class)
        assertTrue(
            (outcome as SupervisorSessionRunner.Outcome.FAILED).reason.contains("rubber"),
        )
    }

    @Test
    fun workerTurnCap_forceClosesWithCapPrompt() = runTest {
        val sup = FakeSeat(
            envelope("do a", "tests pass"),
            envelope("fix b", "tests pass"),
            "DONE: tests pass partially",
        )
        val worker = FakeSeat("r1", "r2")
        val outcome = SupervisorSessionRunner.run(
            objective = "obj",
            contextDigest = "",
            maxWorkerTurns = 2,
            supervisorSystem = system,
            supervisor = { u, _ -> sup.speak(system, u) },
            worker = { u, _ -> worker.speak(system, u) },
            inbox = SupervisorChat.SupervisorInbox(),
        )
        assertEquals(SupervisorSessionRunner.Outcome.DONE::class, outcome::class)
        // Third supervisor call is the cap prompt, not a worker round.
        assertEquals(2, worker.calls.size)
        assertTrue(sup.calls[2].contains("预算已耗尽"))
    }

    @Test
    fun userHalt_marksUserHaltedAndRethrows() = runTest {
        val sup = FakeSeat(envelope("do a", "tests pass"))
        val worker = FakeSeat("report a")
        var terminal: String? = null
        var rethrown = false
        var calls = 0
        try {
            SupervisorSessionRunner.run(
                objective = "obj",
                contextDigest = "",
                maxWorkerTurns = 10,
                supervisorSystem = system,
                supervisor = { u, _ ->
                    calls++
                    if (calls > 1) throw kotlinx.coroutines.CancellationException("halt")
                    sup.speak(system, u)
                },
                worker = { u, _ -> worker.speak(system, u) },
                inbox = SupervisorChat.SupervisorInbox(),
                onTerminal = { state, _ -> terminal = state },
            )
        } catch (_: kotlinx.coroutines.CancellationException) {
            rethrown = true
        }
        assertTrue(rethrown)
        assertEquals(SupervisorRunStore.STATE_USER_HALTED, terminal)
    }

    @Test
    fun workerError_failsSession() = runTest {
        val sup = FakeSeat(envelope("do a", "tests pass"))
        val worker = FakeSeat("boom")
        worker.failWith = RuntimeException("provider 500")
        val outcome = SupervisorSessionRunner.run(
            objective = "obj",
            contextDigest = "",
            maxWorkerTurns = 10,
            supervisorSystem = system,
            supervisor = { u, _ -> sup.speak(system, u) },
            worker = { u, _ -> worker.speak(system, u) },
            inbox = SupervisorChat.SupervisorInbox(),
        )
        assertEquals(SupervisorSessionRunner.Outcome.FAILED::class, outcome::class)
        assertTrue((outcome as SupervisorSessionRunner.Outcome.FAILED).reason.contains("500"))
    }

    @Test
    fun inboxMessages_reachNextSupervisorTurn() = runTest {
        val sup = FakeSeat(
            envelope("do a", "tests pass"),
            "DONE: tests pass",
        )
        val worker = FakeSeat("report a")
        val inbox = SupervisorChat.SupervisorInbox()
        inbox.push("keep it small")
        SupervisorSessionRunner.run(
            objective = "obj",
            contextDigest = "",
            maxWorkerTurns = 10,
            supervisorSystem = system,
            supervisor = { u, _ -> sup.speak(system, u) },
            worker = { u, _ -> worker.speak(system, u) },
            inbox = inbox,
        )
        // First turn carries the inbox message.
        assertTrue(sup.calls[0].contains("keep it small"))
    }

    @Test
    fun recoveryIntro_foldsIntoFirstTurn() = runTest {
        val sup = FakeSeat(envelope("do a", "tests pass"), "DONE: tests pass")
        val worker = FakeSeat("report a")
        SupervisorSessionRunner.run(
            objective = "obj",
            contextDigest = "",
            maxWorkerTurns = 10,
            supervisorSystem = system,
            supervisor = { u, _ -> sup.speak(system, u) },
            worker = { u, _ -> worker.speak(system, u) },
            inbox = SupervisorChat.SupervisorInbox(),
            recovery = "上次中断于第 7 轮",
        )
        assertTrue(sup.calls[0].contains("上次中断于第 7 轮"))
    }
}
