package com.openminis.app.ui.chat.voice

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.chat.ChatViewModel
import com.openminis.app.ui.chat.updateSlashMenuState
import kotlinx.coroutines.CoroutineScope

/**
 * [T-voice-send-waits-for-asr] One place that answers "does this send have to
 * wait, and what gets sent once it may go".
 *
 * Voice mode can owe text without the composer showing it: the mic is still
 * capturing, the tail of a take is held below the segment minimum, or a
 * transcription is in flight. Sending on top of that cleared the composer,
 * which cancelled the capture — the last sentence spoken before the tap was
 * simply gone. Every send entry (button, hardware Enter, drag-to-send) asks
 * [defer] first; when it says yes the send is replayed by [send] after
 * recognition finishes, so the tap still means "send", just later.
 *
 * Held by the composer's `remember` and wired with [send] right after the
 * ordinary send lambda exists (that lambda is what a deferred send replays).
 */
internal class VoiceSendCoordinator(private val viewModel: ChatViewModel) {

    /** The ordinary send-or-enqueue path, installed once per composition. */
    var send: ((String) -> Unit)? = null

    /**
     * @return true when the caller must stop: voice input is outstanding and
     *   [send] will run after it lands.
     */
    fun defer(scope: CoroutineScope, context: Context): Boolean =
        VoiceSendGate.deferSendIfOwed(scope, context) {
            // The panel hands its transcript over through callbacks; copy it
            // here too so a deferred send never depends on that ordering.
            val spoken = VoiceSendGate.transcript
            if (VoiceModePrefs.isVoiceActive && spoken.isNotEmpty() &&
                viewModel.inputText.value != spoken
            ) {
                viewModel.setInputText(spoken)
                viewModel.updateSlashMenuState(spoken)
            }
            val text = viewModel.inputText.value
            if (text.isNotBlank() || viewModel.attachments.value.isNotEmpty()) {
                send?.invoke(text)
            } else {
                AppLogger.info("VoiceSend", "[voice-send] nothing to send after recognition finished")
            }
        }
}
