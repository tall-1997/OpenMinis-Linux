package com.openminis.app.speech

import android.speech.SpeechRecognizer

/**
 * Error mapping for the System recognition engine, extracted from
 * [SystemSpeechRecognitionEngine]'s callback object so the engine file stays
 * under the architecture ceiling. No behaviour change.
 */
internal fun mapError(code: Int): RecognitionError = when (code) {
    SpeechRecognizer.ERROR_NO_MATCH,
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> RecognitionError.NO_MATCH
    SpeechRecognizer.ERROR_NETWORK,
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> RecognitionError.NETWORK
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> RecognitionError.PERMISSION_DENIED
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> RecognitionError.RECOGNIZER_BUSY
    SpeechRecognizer.ERROR_AUDIO -> RecognitionError.AUDIO_ERROR
    SpeechRecognizer.ERROR_CLIENT -> RecognitionError.OEM_NO_SERVICE
    SpeechRecognizer.ERROR_SERVER -> RecognitionError.NETWORK
    // Codes added in API 33; referenced by literal for compatibility.
    11 /* ERROR_LANGUAGE_NOT_SUPPORTED */,
    12 /* ERROR_LANGUAGE_UNAVAILABLE   */ -> RecognitionError.LANGUAGE_UNSUPPORTED
    else -> RecognitionError.UNKNOWN
}

internal fun errorMessage(code: Int): String = when (code) {
    SpeechRecognizer.ERROR_AUDIO -> "Audio recording error"
    SpeechRecognizer.ERROR_CLIENT -> "Client error (no recognition service?)"
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "RECORD_AUDIO required"
    SpeechRecognizer.ERROR_NETWORK -> "Network error"
    SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network timeout"
    SpeechRecognizer.ERROR_NO_MATCH -> "No speech recognized"
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Recognizer busy"
    SpeechRecognizer.ERROR_SERVER -> "Server error"
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "Speech timeout"
    // [T-android-asr-script-subtag] mapError already classifies 11/12 as
    // LANGUAGE_UNSUPPORTED, but this function built the user-visible
    // string and had no case for them — so a language-pack problem
    // surfaced as the useless "Unknown error (12)". Name the actual
    // cause, and point at the fix the user can act on.
    11 /* ERROR_LANGUAGE_NOT_SUPPORTED */ ->
        "Language not supported by the recognizer"
    12 /* ERROR_LANGUAGE_UNAVAILABLE */ ->
        "Language pack unavailable — download it in the system speech settings"
    else -> "Unknown error ($code)"
}

/**
 * [T-android-continuous-dictation] Join the accumulated segment transcript
 * with a new final segment. Extracted from [SystemSpeechRecognitionEngine]
 * so the engine file stays under the architecture ceiling.
 */
internal fun joinedTranscript(accumulated: StringBuilder, lastSegment: String): String {
    if (accumulated.isEmpty()) return lastSegment
    if (lastSegment.isEmpty()) return accumulated.toString()
    return "$accumulated $lastSegment"
}
