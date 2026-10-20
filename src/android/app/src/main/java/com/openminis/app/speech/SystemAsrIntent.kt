package com.openminis.app.speech

import android.content.Intent
import android.media.AudioFormat
import android.os.ParcelFileDescriptor
import android.speech.RecognizerIntent
import java.util.Locale

/**
 * [T-android-asr-script-subtag] Pure intent construction for the System
 * recognition engine, extracted from [SystemSpeechRecognitionEngine] so the
 * engine file stays under the architecture ceiling. No behaviour change.
 *
 * `Locale.getDefault()` on a Simplified-Chinese device is `zh-Hans-CN`, and
 * `toLanguageTag()` faithfully returns "zh-Hans-CN". Google's recognizer
 * matches its language packs on plain `language-REGION` tags, so the script
 * subtag makes it report "Locale not supported" / "Failed to get language
 * pack of required locale" and fail the session with ERROR_LANGUAGE_
 * UNAVAILABLE (12) — the mic opens and immediately closes. Observed on a
 * Pixel 4a whose system locale is zh-Hans-CN.
 *
 * Reducing to language(+region) keeps the meaningful part ("zh-CN") and is
 * safe for every other locale: tags without a script subtag are unchanged.
 */
internal fun recognizerLanguageTag(locale: Locale): String {
    val lang = locale.language
    var region = locale.country
    // A script-only locale carries no region ("zh-Hans"), and a bare "zh"
    // is just as unresolvable to the recognizer as "zh-Hans" — it needs a
    // concrete language pack. Derive the region from the script, which is
    // exactly the information the script subtag encodes.
    if (region.isNullOrEmpty() && lang == "zh") {
        region = if (locale.script == "Hant") "TW" else "CN"
    }
    // Last resort for any other script-only/region-less locale: ask ICU to
    // fill in the likely region (zh-Hans -> zh-Hans-CN, etc.).
    if (region.isNullOrEmpty()) {
        region = runCatching { Locale.Builder().setLocale(locale).build() }
            .getOrNull()?.country.orEmpty()
    }
    return if (region.isEmpty()) lang else "$lang-$region"
}

internal fun buildAsrIntent(
    locale: Locale,
    preferOffline: Boolean,
    audioPipeRead: ParcelFileDescriptor?,
    callingPackage: String,
): Intent {
    return Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, recognizerLanguageTag(locale))
        // [T-android-vad] Partials are still REQUESTED but never surfaced —
        // see onPartialResults. iOS does exactly this: its
        // SFSpeechURLRecognitionRequest sets shouldReportPartialResults =
        // true purely as a salvage path when the recognizer never emits a
        // final (VoiceProvider+System.swift:139-143), and buffers the text
        // in a local rather than showing it.
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, callingPackage)
        if (preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        // [T-android-vad] Endpointing now belongs to our Silero VAD, so ask
        // the platform to sit still and let it decide. These extras are
        // advisory (Google's recognizer has largely ignored them since
        // Android 4.x), which is precisely why we can no longer rely on
        // them: the same app segmented differently per OEM. Padding them
        // well past the VAD's ~5 s window keeps the platform from cutting
        // a segment before the VAD does on ROMs that DO honour them.
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, PLATFORM_SILENCE_GUARD_MS)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, PLATFORM_SILENCE_GUARD_MS)
        putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1500)

        // [T-android-vad] Feed OUR capture instead of letting the
        // recogniser open the mic. This is what makes a VAD possible at
        // all: two AudioRecords on one device leaves one silent, and it
        // was the recogniser that starved — it reported onStartOfSpeech
        // and then `withSpeech: false`, having been given no audio.
        //
        // With a single source the VAD judges the same samples the
        // recogniser transcribes, and closing the pipe ends the session:
        // "The recognition session will end when and only when the audio
        // is closed" (RecognizerIntent.EXTRA_AUDIO_SOURCE).
        audioPipeRead?.let { readEnd ->
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, readEnd)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, VoiceActivityDetector.SAMPLE_RATE)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            // [T-android-asr-silent-failure] EXTRA_SEGMENTED_SESSION is
            // back ON — and this time the segment callbacks below are
            // implemented, which is the piece the first attempt missed.
            //
            // History: the extra was originally set, results went to
            // onSegmentResults (unimplemented) and the panel spun forever,
            // so the "fix" was to drop the extra. That made things WORSE,
            // not better: Google's recognizer classifies an injected-audio
            // session as AMBIENT_CONTINUOUS with multi-utterance
            // endpointing REGARDLESS (its own logcat: `Initialize Soda
            // [applicationDomain: AMBIENT_CONTINUOUS]`, `multi = true`).
            // Per-utterance finals then had no compliant delivery path:
            // every terminal onResults arrived with an EMPTY
            // RESULTS_RECOGNITION (finalLen=0 on 100% of sessions in
            // minis-2026-08-16.log) while Google's own process logged
            // `#onResults withSpeech: true` two ms earlier — the text
            // existed and went nowhere. The engine then salvaged only the
            // LAST buffered partial, which resets at every internal
            // utterance boundary: a 30.72 s dictation committed 3 chars
            // (13:58:42), and when the tail utterance held no clean speech
            // the whole session collapsed to ERROR_NO_MATCH (the 13:58 /
            // 14:29 error-7 bursts) even though the VAD heard speech.
            //
            // The documented contract (RecognizerIntent.EXTRA_SEGMENTED_
            // SESSION): with the extra set to EXTRA_AUDIO_SOURCE, results
            // arrive per segment via onSegmentResults and the session
            // terminates with onEndOfSegmentedSession when the audio
            // closes. Both are implemented below; segment finals accumulate
            // in segmentTranscript, so nothing rides on the partial-salvage
            // path anymore.
            putExtra(
                RecognizerIntent.EXTRA_SEGMENTED_SESSION,
                RecognizerIntent.EXTRA_AUDIO_SOURCE,
            )
        }
    }
}
