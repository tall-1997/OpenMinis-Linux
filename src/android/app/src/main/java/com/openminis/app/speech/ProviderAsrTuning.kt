package com.openminis.app.speech

import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.repository.ProviderRepository

/**
 * [T-android-voice-asr-stall-skip] Tuning constants for the Provider
 * recognition engine, extracted from
 * [ProviderSpeechRecognitionEngine] so the engine file stays under the
 * architecture ceiling. No behaviour change.
 */
/**
 * How long an attempt may go without a result before "Switch Model" is
 * offered. iOS 4a1f44220: 5 s (the user found 10 s too long). Only an
 * offer: an attempt the user does not skip keeps running and lands
 * normally.
 */
internal const val ASR_STALL_THRESHOLD_MS = 5_000L

/** nextEntryId meaning "System recognition" (Android candidates never include System). */
internal const val SYSTEM_NEXT = "__system_asr__"

internal const val SAMPLE_RATE = 16_000

/** Hard cap on a single take (60 s at 16 kHz PCM16 mono ≈ 1.9 MB). */
internal const val MAX_RECORD_SECONDS = 60

/**
 * Cloud ASR has no per-request length ceiling the way Apple's system
 * recogniser does, so a segment may run to the full session cap
 * (iOS VoiceInputPanel.swift:488-489).
 */
internal const val CLOUD_MAX_SEGMENT_SECONDS = 300

/**
 * [T-android-vad-manual-stop] Everything [stop] needs to finalise a take
 * the user ended by hand.
 *
 * On the VAD path audio reaches transcription ONLY through the detector's
 * `onVoiceEnd` callback, and that callback closes over the locale, repo,
 * candidate chain and listener for the session. `stop()` takes no
 * arguments, so without holding them here it could tear the detector down
 * but had no way to transcribe what had already been captured.
 */
internal class VadSession(
    val locale: java.util.Locale,
    val repo: ProviderRepository,
    val candidates: List<Pair<ProviderInstance, ModelEntry>>,
    val listener: SpeechRecognitionEngine.Listener,
)

/**
 * [T-android-voice-asr-stall-skip] Pure decision for a user "switch model"
 * tap: which entry the NEXT segments should start on. The engine applies
 * the side effects (sticky pin / System pin); this type only carries them.
 */
internal class AsrSkipDecision(
    val displayName: String,
    val stickyEntryId: String?,
    val pinSystem: Boolean,
)

internal fun resolveAsrSkipDecision(
    targets: List<String>,
    candidates: List<Pair<ProviderInstance, ModelEntry>>,
    systemName: String,
    genericName: String,
): AsrSkipDecision {
    val nextCandidate = targets.firstOrNull { it != SYSTEM_NEXT }
        ?.let { id -> candidates.firstOrNull { it.second.id == id } }
    return when {
        nextCandidate != null -> AsrSkipDecision(
            displayName = nextCandidate.second.model.displayName,
            stickyEntryId = nextCandidate.second.id,
            pinSystem = false,
        )
        targets.contains(SYSTEM_NEXT) -> AsrSkipDecision(
            displayName = systemName,
            stickyEntryId = null,
            pinSystem = true,
        )
        // The candidate set changed under the skip: the abandoned attempts
        // still move on inside their own loops.
        else -> AsrSkipDecision(
            displayName = genericName,
            stickyEntryId = null,
            pinSystem = false,
        )
    }
}
