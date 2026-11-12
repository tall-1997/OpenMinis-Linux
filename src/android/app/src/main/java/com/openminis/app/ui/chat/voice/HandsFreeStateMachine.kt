package com.openminis.app.ui.chat.voice

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-handsfree-loop] (#40) State machine for the hands-free conversation
 * loop: user speaks → auto-recognized → auto-sent → reply read aloud →
 * listen again. No keypresses in between.
 *
 * PURE — no Android, no Compose, no engine calls. Engine interaction is
 * inverted through [HandsFreePorts] (implemented by the Compose wiring in
 * HandsFreeVoicePanel.kt), so [HandsFreeStateMachineTest] drives the whole
 * transition table on the JVM, including interruption, error and anti-echo
 * paths.
 *
 * State ↔ real-world mapping (wiring's responsibility):
 *  - LISTENING   mic capture live (SpeechRecognitionManager RECORDING/STARTING).
 *  - RECOGNIZING capture stopped, final result pending (manager FINISHING).
 *  - SENDING     recognized text handed to the model; waiting for the reply.
 *  - SPEAKING    reply TTS is playing (VoiceOutputState.isSpeaking).
 *
 * ## Anti-echo / anti-loop guards (each asserted by a test)
 *  - The machine never emits [HandsFreePorts.startListening] while in
 *    SPEAKING: LISTENING is only re-entered from SPEAKING via
 *    [onSpeakingFinished] or [onInterrupt] — the TTS audio can therefore
 *    never be captured by a mic this loop opened.
 *  - [onSpeakingStarted] is ignored outside SENDING, so a stray TTS blip
 *    while the mic is live cannot flip the machine into SPEAKING under the
 *    user's voice.
 *  - [onCaptureEnded] outside LISTENING/RECOGNIZING is ignored — a final
 *    result arriving during SENDING/SPEAKING cannot hijack the loop.
 *  - Every event outside its valid states is a no-op (explicit table), so a
 *    duplicate engine callback can never double-send.
 *
 * ## Error surface
 *  - Recognition errors retry re-listening up to [MAX_CONSECUTIVE_ERRORS]
 *    consecutive times, then exit hands-free with [HandsFreeExitReason
 *    .RECOGNITION_FAILED] (wiring shows the notice).
 *  - A reply that settles WITHOUT any TTS (read-aloud engine dead, speech
 *    synth failure) counts toward the same cap; exceeding it exits with
 *    [HandsFreeExitReason.TTS_FAILED] instead of looping silently forever.
 *  - A spoken reply resets both counters.
 *
 * Threading: events are expected on the main thread (all observed flows are
 * main-confined); no internal locking.
 */
class HandsFreeStateMachine(private val ports: HandsFreePorts) {

    private val _state = MutableStateFlow(HandsFreeState.IDLE)
    val state: StateFlow<HandsFreeState> = _state.asStateFlow()

    /**
     * Set when the loop exits itself (never on [stop] — that is the user's
     * own action and needs no notice). The wiring consumes it to flip the
     * toggle off and show a localized message, then calls [clearExit].
     */
    private val _exit = MutableStateFlow<HandsFreeExitReason?>(null)
    val exit: StateFlow<HandsFreeExitReason?> = _exit.asStateFlow()

    private var recognitionFailures = 0
    private var silentReplies = 0

    // ------------------------------------------------------------ events

    /** Toggle on / panel re-entered. IDLE → LISTENING. */
    fun start() {
        if (_state.value != HandsFreeState.IDLE) return
        _state.value = HandsFreeState.LISTENING
        ports.startListening()
    }

    /** Toggle off / panel left. Any → IDLE, engine activity torn down. */
    fun stop() {
        if (_state.value == HandsFreeState.IDLE) return
        _state.value = HandsFreeState.IDLE
        ports.cancelListening()
        ports.stopSpeaking()
    }

    /** Observed: mic actually opened. Confirms (re)entry into LISTENING. */
    fun onCaptureStarted() {
        when (_state.value) {
            HandsFreeState.LISTENING -> Unit
            // Re-arm raced the capture already being live (interrupt path):
            // the capture exists, LISTENING is the right state.
            HandsFreeState.RECOGNIZING -> _state.value = HandsFreeState.LISTENING
            else -> Unit
        }
    }

    /** Observed: capture stopped, final result pending (manager FINISHING). */
    fun onFinishing() {
        if (_state.value != HandsFreeState.LISTENING) return
        _state.value = HandsFreeState.RECOGNIZING
    }

    /**
     * Observed: a capture session ended. [text] is the final transcript
     * (blank = silence / cancelled / nothing recognized). Blank re-arms
     * listening without counting an error; a real transcript is sent.
     */
    fun onCaptureEnded(text: String) {
        when (_state.value) {
            HandsFreeState.LISTENING, HandsFreeState.RECOGNIZING -> Unit
            else -> return
        }
        if (text.isBlank()) {
            _state.value = HandsFreeState.LISTENING
            ports.startListening()
            return
        }
        recognitionFailures = 0
        _state.value = HandsFreeState.SENDING
        ports.sendMessage(text)
    }

    /**
     * Observed: non-recoverable recognition error (NO_MATCH is routed to
     * [onNoSpeech] instead). Retries up to the cap, then exits.
     */
    fun onRecognitionFailed() {
        when (_state.value) {
            HandsFreeState.LISTENING, HandsFreeState.RECOGNIZING -> Unit
            else -> return
        }
        recognitionFailures++
        if (recognitionFailures >= MAX_CONSECUTIVE_ERRORS) {
            exitWith(HandsFreeExitReason.RECOGNITION_FAILED)
        } else {
            _state.value = HandsFreeState.LISTENING
            ports.startListening()
        }
    }

    /** Observed: NO_MATCH / silence — not a failure, just re-arm. */
    fun onNoSpeech() {
        when (_state.value) {
            HandsFreeState.LISTENING, HandsFreeState.RECOGNIZING -> Unit
            else -> return
        }
        _state.value = HandsFreeState.LISTENING
        ports.startListening()
    }

    /** Observed: reply TTS started playing. SENDING → SPEAKING. */
    fun onSpeakingStarted() {
        // Guarded to SENDING: a TTS blip while the mic is live (LISTENING)
        // must not flip the machine under the user's voice, and a duplicate
        // event inside SPEAKING must not re-run anything.
        if (_state.value != HandsFreeState.SENDING) return
        silentReplies = 0
        _state.value = HandsFreeState.SPEAKING
    }

    /**
     * Observed: reply finished — TTS drained AND streaming done. Back to
     * LISTENING for the next turn.
     */
    fun onSpeakingFinished() {
        if (_state.value != HandsFreeState.SPEAKING) return
        _state.value = HandsFreeState.LISTENING
        ports.startListening()
    }

    /**
     * Observed: reply settled without any speech (TTS failed / was muted
     * mid-turn). Degrade: re-arm listening; two consecutive silent replies
     * exit hands-free instead of looping muted.
     */
    fun onReplySettledWithoutSpeech() {
        if (_state.value != HandsFreeState.SENDING) return
        silentReplies++
        if (silentReplies >= MAX_CONSECUTIVE_ERRORS) {
            exitWith(HandsFreeExitReason.TTS_FAILED)
        } else {
            _state.value = HandsFreeState.LISTENING
            ports.startListening()
        }
    }

    /**
     * User barged in during SPEAKING (tapped the mic / started a capture).
     * TTS is cut (dropped, not held) and the loop returns to LISTENING; the
     * wiring's startListening is a no-op while the mic is already live.
     */
    fun onInterrupt() {
        if (_state.value != HandsFreeState.SPEAKING) return
        _state.value = HandsFreeState.LISTENING
        ports.stopSpeaking()
        ports.startListening()
    }

    // ------------------------------------------------------------ helpers

    private fun exitWith(reason: HandsFreeExitReason) {
        _state.value = HandsFreeState.IDLE
        ports.cancelListening()
        ports.stopSpeaking()
        _exit.value = reason
    }

    /** Wiring calls after surfacing [exit]. */
    fun clearExit() {
        _exit.value = null
    }

    /** Reset failure counters — the wiring re-arms after a manual start. */
    fun resetFailures() {
        recognitionFailures = 0
        silentReplies = 0
    }

    companion object {
        /**
         * Consecutive failures of one kind tolerated before the loop gives
         * up. Two = the documented "retry once, then exit" UX.
         */
        const val MAX_CONSECUTIVE_ERRORS = 2
    }
}

enum class HandsFreeState {
    /** Loop off (toggle off, or exited after repeated failures). */
    IDLE,

    /** Mic live, waiting for the user to speak. */
    LISTENING,

    /** Capture stopped, final transcript pending. */
    RECOGNIZING,

    /** Transcript sent; waiting for the reply to start speaking. */
    SENDING,

    /** Reply being read aloud. */
    SPEAKING,
}

enum class HandsFreeExitReason {
    /** Recognition failed too many times in a row. */
    RECOGNITION_FAILED,

    /** Replies settled without speech too many times in a row. */
    TTS_FAILED,
}

/**
 * Engine seam for the wiring. Implementations run on the main thread and
 * must be cheap — the machine may call them from any event.
 */
interface HandsFreePorts {
    /** Open the mic for the next turn (no-op when a capture is already live). */
    fun startListening()

    /** Send the recognized [text] as a chat message and clear the composer. */
    fun sendMessage(text: String)

    /** Drop any playing / queued reply TTS (barge-in, stop). */
    fun stopSpeaking()

    /** Cancel a live capture without delivering a result. */
    fun cancelListening()
}
