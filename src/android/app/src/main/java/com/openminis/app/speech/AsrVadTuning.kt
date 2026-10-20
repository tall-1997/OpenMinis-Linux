package com.openminis.app.speech

/**
 * [T-android-vad] Endpointing tuning shared by BOTH recognition engines
 * (the values are the iOS-parity numbers; the engines must segment
 * identically), extracted from [SystemSpeechRecognitionEngine] and
 * [ProviderSpeechRecognitionEngine] so those files stay under the
 * architecture ceiling. No behaviour change.
 */
// ── [T-android-vad] ──
/**
 * Silence-close minimum, matching iOS `minSegmentSeconds`
 * (VoiceInputPanel.swift:607). Shorter utterances are discarded.
 */
internal const val MIN_SEGMENT_SECONDS = 2.0f

/** Below this we stay silent; above it the user is told why (iOS :661). */
internal const val TOO_SHORT_FLOOR_SECONDS = 0.3f

/**
 * [T-android-vad-merge-segments] How long the session stays open
 * waiting for the user to top up a sub-2s utterance. Matches the iOS
 * force-flush timer (VoiceInputPanel.swift:609).
 */
internal const val HOLD_FLUSH_MS = 5_000L

/**
 * What we ask the platform to wait before endpointing itself. Our VAD
 * closes at ~5 s, so this is padded well past that: on a ROM that DOES
 * honour the extra, the platform must not cut first.
 */
internal const val PLATFORM_SILENCE_GUARD_MS = 15_000
