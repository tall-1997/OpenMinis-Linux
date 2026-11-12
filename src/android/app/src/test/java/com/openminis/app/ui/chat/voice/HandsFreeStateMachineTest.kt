package com.openminis.app.ui.chat.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-handsfree-loop] (#40) Pure-JVM transition-table tests for the hands-free
 * conversation state machine. Engine interaction is a recording [HandsFreePorts]
 * fake, so every command the machine issues is asserted directly. Covers the
 * happy loop, silence, interruption, error/retry paths and the anti-echo /
 * anti-loop guards.
 */
class HandsFreeStateMachineTest {

    /** Recording fake — every command lands here in order. */
    private class FakePorts : HandsFreePorts {
        val commands = mutableListOf<String>()

        override fun startListening() {
            commands.add("startListening")
        }

        override fun sendMessage(text: String) {
            commands.add("send:$text")
        }

        override fun stopSpeaking() {
            commands.add("stopSpeaking")
        }

        override fun cancelListening() {
            commands.add("cancelListening")
        }

        fun lastCommand(): String? = commands.lastOrNull()

        fun count(command: String): Int = commands.count { it == command }
    }

    private fun machine(ports: FakePorts = FakePorts()): Pair<HandsFreeStateMachine, FakePorts> =
        HandsFreeStateMachine(ports) to ports

    // ------------------------------------------------------------ happy loop

    @Test
    fun `happy loop - listen recognize send speak listen again`() {
        val (m, p) = machine()
        m.start()
        assertEquals(HandsFreeState.LISTENING, m.state.value)
        assertEquals("startListening", p.lastCommand())

        m.onCaptureStarted()
        assertEquals(HandsFreeState.LISTENING, m.state.value)

        m.onFinishing()
        assertEquals(HandsFreeState.RECOGNIZING, m.state.value)

        m.onCaptureEnded("hello there")
        assertEquals(HandsFreeState.SENDING, m.state.value)
        assertEquals("send:hello there", p.lastCommand())

        m.onSpeakingStarted()
        assertEquals(HandsFreeState.SPEAKING, m.state.value)

        m.onSpeakingFinished()
        assertEquals(HandsFreeState.LISTENING, m.state.value)
        assertEquals("startListening", p.lastCommand())
        // Exactly one send for the whole turn.
        assertEquals(1, p.count("send:hello there"))
    }

    @Test
    fun `silence re-arms listening without sending`() {
        val (m, p) = machine()
        m.start()
        m.onCaptureStarted()
        m.onCaptureEnded("")
        assertEquals(HandsFreeState.LISTENING, m.state.value)
        assertEquals(0, p.commands.count { it.startsWith("send:") })
        // Two re-arms total: initial + after silence.
        assertEquals(2, p.count("startListening"))
    }

    @Test
    fun `blank transcript in RECOGNIZING re-arms without sending`() {
        val (m, p) = machine()
        m.start()
        m.onFinishing()
        m.onCaptureEnded("   ")
        assertEquals(HandsFreeState.LISTENING, m.state.value)
        assertEquals(0, p.commands.count { it.startsWith("send:") })
    }

    // ------------------------------------------------------------ interruption

    @Test
    fun `user interrupt during speaking cuts tts and re-enters listening`() {
        val (m, p) = machine()
        m.start()
        m.onCaptureEnded("q")
        m.onSpeakingStarted()
        m.onInterrupt()
        assertEquals(HandsFreeState.LISTENING, m.state.value)
        // stopSpeaking FIRST (barge-in drops audio), then re-arm.
        assertTrue(p.commands.indexOf("stopSpeaking") < p.commands.lastIndexOf("startListening"))
    }

    @Test
    fun `repeated interrupt events are ignored outside SPEAKING`() {
        val (m, p) = machine()
        m.start()
        m.onInterrupt()
        assertEquals(HandsFreeState.LISTENING, m.state.value)
        // No stopSpeaking was issued: we were never speaking.
        assertEquals(0, p.count("stopSpeaking"))
    }

    // ------------------------------------------------------------ anti-echo guards

    @Test
    fun `tts blip while listening does not flip to speaking`() {
        val (m, p) = machine()
        m.start()
        m.onCaptureStarted()
        m.onSpeakingStarted()
        assertEquals(HandsFreeState.LISTENING, m.state.value)
        assertEquals(0, p.count("stopSpeaking"))
    }

    @Test
    fun `final result during SPEAKING is ignored`() {
        val (m, p) = machine()
        m.start()
        m.onCaptureEnded("q")
        m.onSpeakingStarted()
        m.onCaptureEnded("ghost")
        assertEquals(HandsFreeState.SPEAKING, m.state.value)
        assertEquals(1, p.commands.count { it.startsWith("send:") })
    }

    @Test
    fun `final result during SENDING is ignored - no double send`() {
        val (m, p) = machine()
        m.start()
        m.onCaptureEnded("q")
        // A late duplicate capture-end (e.g. another engine callback) must
        // not fire a second send.
        m.onCaptureEnded("q")
        assertEquals(HandsFreeState.SENDING, m.state.value)
        assertEquals(1, p.count("send:q"))
    }

    @Test
    fun `speaking finished outside SPEAKING never re-arms`() {
        val (m, p) = machine()
        m.start()
        val before = p.commands.size
        m.onSpeakingFinished()
        assertEquals(HandsFreeState.LISTENING, m.state.value)
        assertEquals(before, p.commands.size)
    }

    @Test
    fun `start while running is a no-op - no double mic arm`() {
        val (m, p) = machine()
        m.start()
        m.start()
        m.start()
        assertEquals(1, p.count("startListening"))
    }

    @Test
    fun `stop tears down both engines exactly once`() {
        val (m, p) = machine()
        m.start()
        m.onCaptureEnded("q")
        m.onSpeakingStarted()
        m.stop()
        assertEquals(HandsFreeState.IDLE, m.state.value)
        assertEquals(1, p.count("stopSpeaking"))
        assertEquals(1, p.count("cancelListening"))
        // Post-stop events are inert.
        m.onCaptureEnded("late")
        m.onSpeakingStarted()
        assertEquals(HandsFreeState.IDLE, m.state.value)
        assertEquals(1, p.commands.count { it.startsWith("send:") })
    }

    // ------------------------------------------------------------ error paths

    @Test
    fun `recognition failure retries once then exits`() {
        val (m, p) = machine()
        m.start()
        m.onRecognitionFailed()
        assertEquals(HandsFreeState.LISTENING, m.state.value)
        assertNull(m.exit.value)

        m.onRecognitionFailed()
        assertEquals(HandsFreeState.IDLE, m.state.value)
        assertEquals(HandsFreeExitReason.RECOGNITION_FAILED, m.exit.value)
        // Tear-down on exit.
        assertEquals(1, p.count("cancelListening"))
        assertEquals(1, p.count("stopSpeaking"))
    }

    @Test
    fun `a successful turn resets the recognition failure counter`() {
        val (m, p) = machine()
        m.start()
        m.onRecognitionFailed() // strike 1
        m.onCaptureStarted()
        m.onCaptureEnded("ok") // success resets
        m.onSpeakingStarted()
        m.onSpeakingFinished()
        m.onCaptureStarted()
        m.onRecognitionFailed() // would be strike 2 without the reset
        assertEquals(HandsFreeState.LISTENING, m.state.value)
        assertNull(m.exit.value)
    }

    @Test
    fun `no-speech silence never counts as a failure`() {
        val (m, p) = machine()
        m.start()
        repeat(5) {
            m.onNoSpeech()
            assertEquals(HandsFreeState.LISTENING, m.state.value)
        }
        assertNull(m.exit.value)
    }

    @Test
    fun `silent reply degrades once then exits with TTS_FAILED`() {
        val (m, p) = machine()
        m.start()
        m.onCaptureEnded("q")
        // Reply settled with no speech at all (read-aloud broken).
        m.onReplySettledWithoutSpeech()
        assertEquals(HandsFreeState.LISTENING, m.state.value)
        assertNull(m.exit.value)

        // Second consecutive silent reply → give up with the TTS reason.
        m.onCaptureStarted()
        m.onCaptureEnded("q2")
        m.onReplySettledWithoutSpeech()
        assertEquals(HandsFreeState.IDLE, m.state.value)
        assertEquals(HandsFreeExitReason.TTS_FAILED, m.exit.value)
    }

    @Test
    fun `spoken reply resets the silent-reply counter`() {
        val (m, p) = machine()
        m.start()
        m.onCaptureEnded("q1")
        m.onReplySettledWithoutSpeech() // silent reply 1
        m.onCaptureStarted()
        m.onCaptureEnded("q2")
        m.onSpeakingStarted() // this one SPOKE
        m.onSpeakingFinished()
        m.onCaptureStarted()
        m.onCaptureEnded("q3")
        m.onReplySettledWithoutSpeech() // silent again — but counter was reset
        assertEquals(HandsFreeState.LISTENING, m.state.value)
        assertNull(m.exit.value)
    }

    @Test
    fun `reply events outside SENDING are ignored`() {
        val (m, p) = machine()
        m.start()
        m.onReplySettledWithoutSpeech()
        assertEquals(HandsFreeState.LISTENING, m.state.value)
        m.onSpeakingFinished()
        assertEquals(HandsFreeState.LISTENING, m.state.value)
    }

    // ------------------------------------------------------------ notices

    @Test
    fun `exit notice is consumed by clearExit`() {
        val (m, _) = machine()
        m.start()
        m.onRecognitionFailed()
        m.onRecognitionFailed()
        assertEquals(HandsFreeExitReason.RECOGNITION_FAILED, m.exit.value)
        m.clearExit()
        assertNull(m.exit.value)
    }

    @Test
    fun `restart after self-exit works`() {
        val (m, p) = machine()
        m.start()
        m.onRecognitionFailed()
        m.onRecognitionFailed()
        assertEquals(HandsFreeState.IDLE, m.state.value)
        // The user taps Hands-free again → fresh loop.
        m.clearExit()
        m.resetFailures()
        m.start()
        assertEquals(HandsFreeState.LISTENING, m.state.value)
        assertNull(m.exit.value)
        // 3 arms total: the initial loop's first arm, its retry after the
        // first failure, and the restart (the exit itself re-arms nothing).
        assertEquals(3, p.count("startListening"))
    }
}
