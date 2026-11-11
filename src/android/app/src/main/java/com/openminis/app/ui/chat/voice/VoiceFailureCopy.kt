// [OpenMinis#267] Localized, actionable copy for failed voice input.
package com.openminis.app.ui.chat.voice

import android.content.Context
import com.openminis.app.R
import com.openminis.app.speech.RecognitionError

/**
 * [OpenMinis#267] Localized, actionable copy for a failed voice-input capture.
 *
 * The panel used to render `message ?: error.name`, i.e. an English engine token
 * or nothing at all — "语音输入失败" with no explanation. Every terminal
 * [RecognitionError] now has a user-facing sentence that names the likely cause
 * and the available remedy; the raw engine detail survives only as the
 * interpolation of the UNKNOWN case, where it is the only information there is.
 *
 * Extracted from InlineVoiceInputPanel for the architecture-policy line ceiling.
 */
internal fun voiceFailureText(context: Context, error: RecognitionError, detail: String?): String =
    when (error) {
        RecognitionError.NETWORK -> context.getString(R.string.voice_error_network)
        RecognitionError.TRANSCRIPTION_FAILED -> context.getString(R.string.voice_error_transcription)
        RecognitionError.RECOGNIZER_BUSY -> context.getString(R.string.voice_error_busy)
        RecognitionError.AUDIO_ERROR -> context.getString(R.string.voice_error_audio)
        RecognitionError.LANGUAGE_UNSUPPORTED -> context.getString(R.string.voice_error_language)
        RecognitionError.OEM_NO_SERVICE -> context.getString(R.string.voice_panel_no_engine_body)
        // Handled by the panel before this helper is reached.
        RecognitionError.NO_MATCH, RecognitionError.PERMISSION_DENIED -> ""
        RecognitionError.UNKNOWN -> context.getString(
            R.string.voice_error_unknown,
            detail?.takeIf { it.isNotBlank() } ?: error.name,
        )
    }
