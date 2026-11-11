package com.openminis.app.ui.chat.voice

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openminis.app.R
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.speech.RecognitionError
import com.openminis.app.speech.RecognitionState
import com.openminis.app.speech.SpeechRecognitionManager
import com.openminis.app.speech.VoiceOutputState
import com.openminis.app.ui.chat.ChatViewModel
import com.openminis.app.ui.theme.ChatColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * [T-handsfree-loop] (#40) Hands-free continuous conversation: the existing
 * STT → send → TTS → re-listen cycle with no keypress between turns.
 *
 * Layers:
 *  - [HandsFreeStateMachine] (pure, separate file) owns the transition table.
 *  - [HandsFreeController] binds it to the real engines — everything it
 *    observes is a main-confined StateFlow, and every command it issues is
 *    idempotent, so duplicated engine callbacks cannot double-fire a turn.
 *  - [HandsFreeVoicePanel] (this file's composable) wraps the existing
 *    InlineVoiceInputPanel: adds the toggle pill + three-state banner
 *    (listening / thinking / speaking) above the unchanged panel. The panel
 *    file is pinned at its architecture ceiling, which is why the wrap
 *    happens OUTSIDE it.
 *
 * Engine interactions already reused (not reimplemented):
 *  - echo prevention: ReadAloudPlayer drops new utterances while the mic is
 *    capturing and VoiceOutputState.suspendAllForCapture pauses playback
 *    when one starts (speech/, pre-existing);
 *  - VAD auto-stop on silence: the active STT engine ends the capture
 *    itself, which is what triggers onCaptureEnded;
 *  - interruption: tapping the panel's mic during SPEAKING starts a capture;
 *    the controller reads that as barge-in, cuts TTS and continues
 *    listening — the state machine's onInterrupt path.
 */
object HandsFreePrefs {

    private const val PREFS = "voice_prefs"
    private const val KEY_ENABLED = "handsFree.enabled"

    /** Set once from [init]; without it [enabled] can't reach SharedPreferences. */
    private var appContext: android.content.Context? = null

    /** Live toggle state; persisted, default OFF (per the task brief). */
    var enabled by mutableStateOf(false)
        private set

    fun init(context: android.content.Context) {
        if (appContext != null) return
        val ctx = context.applicationContext
        appContext = ctx
        enabled = ctx.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            .getBoolean(KEY_ENABLED, false)
    }

    /**
     * Set + persist. Named `updateEnabled` (not `setEnabled`) because the
     * delegated property's private setter already occupies the JVM signature
     * `setEnabled(boolean)`.
     */
    fun updateEnabled(value: Boolean) {
        enabled = value
        appContext?.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE)
            ?.edit()?.putBoolean(KEY_ENABLED, value)?.apply()
    }
}

/**
 * Binds the pure state machine to the real engines. One instance per panel
 * entry (remember); [start]/[stop] may alternate freely (toggle / exit
 * notices) — each start builds a fresh observation scope.
 */
internal class HandsFreeController(
    /** Reply-streaming flag of the hosting chat. */
    private val isStreaming: StateFlow<Boolean>,
    /** Sends the recognized turn and clears the composer. */
    private val sendTurn: (String) -> Unit,
) {

    private val ports = object : HandsFreePorts {

        override fun startListening() {
            val s = scope ?: return
            s.launch {
                // Re-arm gap: the turn we just sent cleared the composer, and
                // InlineVoiceInputPanel's empty-input effect stops live
                // captures when it lands. Waiting one beat here means the
                // capture we open can never be killed by that effect.
                delay(REARM_DELAY_MS)
                if (scope == null) return@launch
                // Interrupt path: the barge-in capture is already live.
                if (SpeechRecognitionManager.state.value != RecognitionState.IDLE) return@launch
                // Callbacks are intentionally empty: the controller is
                // flow-driven (state / lastError / recognizedText), so both
                // controller- and panel-initiated captures funnel through
                // the same observations.
                SpeechRecognitionManager.startRecording({ _, _ -> }, { _, _ -> })
            }
        }

        override fun sendMessage(text: String) = sendTurn(text)

        override fun stopSpeaking() {
            // Drop (not hold) — barge-in means the unspoken tail is unwanted;
            // resume-after-capture would otherwise interleave it with the
            // reply to the user's interruption.
            VoiceOutputState.activePlayer?.stop()
        }

        override fun cancelListening() {
            if (SpeechRecognitionManager.state.value != RecognitionState.IDLE) {
                SpeechRecognitionManager.cancelRecording()
            }
        }
    }

    val machine = HandsFreeStateMachine(ports)

    private var scope: CoroutineScope? = null

    val isRunning: Boolean get() = scope != null

    /** Turn the loop on. No-op while already running. */
    fun start() {
        if (scope != null) return
        machine.resetFailures()
        machine.clearExit()
        // Dispatchers.Main (NOT .immediate): the collectors must resume AFTER
        // the current main-thread call stack finishes. With .immediate a
        // setState(IDLE) emission would resume the collector synchronously in
        // the middle of SpeechRecognitionManager.cancelRecording — before it
        // clears recognizedText — and a cancelled capture would be read as a
        // final transcript. Posted resumption observes the settled values.
        val s = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        scope = s
        observeRecognitionState(s)
        observeRecognitionErrors(s)
        observeReplyBusy(s)
        machine.start()
    }

    /** Turn the loop off and tear the engines down (mic + TTS). */
    fun stop() {
        machine.stop()
        scope?.cancel()
        scope = null
    }

    // ------------------------------------------------------------ observers

    private fun observeRecognitionState(s: CoroutineScope) {
        s.launch {
            var previous: RecognitionState? = null
            SpeechRecognitionManager.state.collect { current ->
                val prev = previous
                previous = current
                when {
                    current == RecognitionState.RECORDING || current == RecognitionState.STARTING -> {
                        // A capture opening while SPEAKING is the user's
                        // barge-in (panel mic tap): cut the audio, keep
                        // listening. Everything else is our own (re)arm.
                        if (machine.state.value == HandsFreeState.SPEAKING) {
                            machine.onInterrupt()
                        } else {
                            machine.onCaptureStarted()
                        }
                    }
                    current == RecognitionState.FINISHING -> machine.onFinishing()
                    current == RecognitionState.IDLE &&
                        prev != null && prev != RecognitionState.IDLE ->
                        // Capture over: the manager's recognizedText now holds
                        // the final transcript ("" when cancelled/silent).
                        machine.onCaptureEnded(SpeechRecognitionManager.recognizedText.value)
                }
            }
        }
    }

    private fun observeRecognitionErrors(s: CoroutineScope) {
        s.launch {
            var lastSeen: RecognitionError? = null
            SpeechRecognitionManager.lastError.collect { error ->
                if (error == null || error == lastSeen) {
                    lastSeen = error
                    return@collect
                }
                lastSeen = error
                // NO_MATCH = silence, not failure. PERMISSION_DENIED and the
                // rest retry once (machine) and exit on the second strike.
                if (error == RecognitionError.NO_MATCH) machine.onNoSpeech()
                else machine.onRecognitionFailed()
            }
        }
    }

    private fun observeReplyBusy(s: CoroutineScope) {
        s.launch {
            combine(VoiceOutputState.isSpeaking, isStreaming) { speaking, streaming ->
                speaking to streaming
            }.collect { (speaking, streaming) ->
                when {
                    speaking -> machine.onSpeakingStarted()
                    // Reply settled: drained TTS AND finished streaming. Which
                    // machine state we were in tells whether speech happened.
                    !speaking && !streaming -> when (machine.state.value) {
                        HandsFreeState.SPEAKING -> machine.onSpeakingFinished()
                        HandsFreeState.SENDING -> machine.onReplySettledWithoutSpeech()
                        else -> Unit
                    }
                }
            }
        }
    }

    companion object {
        /** Gap before re-arming the mic after a send (see [ports]). */
        const val REARM_DELAY_MS = 300L
    }
}

/**
 * Voice panel with the hands-free loop wrapped around it. Forwards every
 * parameter verbatim to [InlineVoiceInputPanel]; the loop above it is fully
 * optional (toggle off → identical behavior to the bare panel).
 */
@Composable
fun HandsFreeVoicePanel(
    viewModel: ChatViewModel,
    providerRepository: ProviderRepository,
    inputText: String,
    onInputTextChange: (String) -> Unit,
    ensureMicPermission: suspend () -> Boolean,
    conversationContextProvider: () -> com.openminis.app.speech.correction.ConversationContext,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    HandsFreePrefs.init(context)
    var enabled by rememberSaveable { mutableStateOf(HandsFreePrefs.enabled) }

    val controller = remember {
        HandsFreeController(
            isStreaming = viewModel.isStreaming,
            sendTurn = { text ->
                viewModel.sendMessage(text)
                // Clear through the composer so the panel's transcript sync
                // and slash-menu state follow the same path as a typed send.
                onInputTextChange("")
            },
        )
    }
    DisposableEffect(Unit) {
        onDispose { controller.stop() }
    }

    // Persisted ON → resume the loop on re-entry (the pref is the mode, not
    // a one-shot). Permission denied at this point degrades to toggle-off.
    LaunchedEffect(Unit) {
        if (enabled && !controller.isRunning) {
            if (ensureMicPermission()) {
                VoiceOutputState.init(context)
                // Cut leftover playback so the first capture starts clean.
                VoiceOutputState.activePlayer?.stop()
                controller.start()
            } else {
                enabled = false
                HandsFreePrefs.updateEnabled(false)
            }
        }
    }

    val machineState by controller.machine.state.collectAsStateWithLifecycle()
    val exitReason by controller.machine.exit.collectAsStateWithLifecycle()

    // Self-exit after repeated failures: surface the reason, flip the
    // toggle off. USER-initiated stops never enter this flow.
    LaunchedEffect(exitReason) {
        val reason = exitReason ?: return@LaunchedEffect
        android.widget.Toast.makeText(
            context,
            context.getString(
                when (reason) {
                    HandsFreeExitReason.RECOGNITION_FAILED -> R.string.handsfree_exit_recognition
                    HandsFreeExitReason.TTS_FAILED -> R.string.handsfree_exit_tts
                },
            ),
            android.widget.Toast.LENGTH_LONG,
        ).show()
        enabled = false
        HandsFreePrefs.updateEnabled(false)
        controller.machine.clearExit()
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        HandsFreeBar(
            enabled = enabled,
            state = if (enabled) machineState else HandsFreeState.IDLE,
            onToggle = { turnOn ->
                if (turnOn) {
                    scope.launch {
                        if (!ensureMicPermission()) return@launch
                        VoiceOutputState.init(context)
                        // Hands-free implies spoken replies: a muted/off TTS
                        // would push every turn straight back to listening
                        // with nothing said, so the loop turns it on.
                        if (!VoiceOutputState.canPlay) {
                            VoiceOutputState.setMuted(false)
                            VoiceOutputState.setEnabled(true)
                        }
                        VoiceOutputState.activePlayer?.stop()
                        HandsFreePrefs.updateEnabled(true)
                        enabled = true
                        controller.start()
                    }
                } else {
                    HandsFreePrefs.updateEnabled(false)
                    enabled = false
                    controller.stop()
                }
            },
        )
        InlineVoiceInputPanel(
            providerRepository = providerRepository,
            inputText = inputText,
            onInputTextChange = onInputTextChange,
            ensureMicPermission = ensureMicPermission,
            conversationContextProvider = conversationContextProvider,
        )
    }
}

@Composable
private fun HandsFreeBar(
    enabled: Boolean,
    state: HandsFreeState,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .clip(RoundedCornerShape(50))
                .background(
                    if (enabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                    else ChatColors.secondaryText.copy(alpha = 0.10f),
                )
                .clickable { onToggle(!enabled) }
                .padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Icon(
                Icons.Default.GraphicEq,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (enabled) MaterialTheme.colorScheme.primary else ChatColors.secondaryText,
            )
            Spacer(Modifier.width(5.dp))
            Text(
                text = stringResource(R.string.handsfree_toggle),
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (enabled) MaterialTheme.colorScheme.primary else ChatColors.secondaryText,
            )
        }
        Spacer(Modifier.width(8.dp))
        if (enabled) {
            val statusText = when (state) {
                HandsFreeState.LISTENING -> stringResource(R.string.handsfree_state_listening)
                HandsFreeState.RECOGNIZING, HandsFreeState.SENDING ->
                    stringResource(R.string.handsfree_state_thinking)
                HandsFreeState.SPEAKING -> stringResource(R.string.handsfree_state_speaking)
                HandsFreeState.IDLE -> stringResource(R.string.handsfree_state_starting)
            }
            Text(
                text = statusText,
                fontSize = 12.sp,
                color = ChatColors.secondaryText,
            )
        }
    }
}
