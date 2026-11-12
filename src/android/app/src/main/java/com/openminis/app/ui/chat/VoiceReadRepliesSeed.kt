// [OpenMinis#267] Voice-mode read-replies default.
package com.openminis.app.ui.chat

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.app.speech.VoiceOutputState

/**
 * [OpenMinis#267] A voice-first session should SPEAK the reply.
 *
 * The "Read replies" switch exists but defaulted off, so a user asking by voice
 * got silent answers ("不自动朗读"). Entering voice mode now seeds it ON exactly
 * once: after the user flips the switch either way
 * ([VoiceOutputState.wasEverConfigured]) their choice is persisted and this
 * helper is a no-op. Extracted from ChatScreen for the architecture-policy line
 * ceiling.
 */
internal fun seedReadRepliesOnVoiceEntry(context: Context) {
    runCatching {
        VoiceOutputState.init(context)
        if (!VoiceOutputState.wasEverConfigured) {
            VoiceOutputState.setEnabled(true)
            VoiceOutputState.setMuted(false)
        }
    }.onFailure {
        AppLogger.warning(
            "ReadReplies",
            "[OpenMinis#267] could not seed read-replies on voice entry: ${it.message}",
        )
    }
}
