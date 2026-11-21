package com.openminis.app.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import com.openminis.app.logging.AppLogger
import kotlin.math.roundToInt

/**
 * AudioManager side of read-aloud playback, split out of [ReadAloudPlayer]:
 * audio focus for the media stream ([T-android-tts-silent-blackhole]) and
 * the >100% volume boost that rides the shared STREAM_MUSIC system volume
 * ([T-readaloud-volume-200]). Owns the per-player focus request and boost
 * anchor state; playback itself stays in ReadAloudPlayer (verbatim
 * extraction — no behavior change).
 */
internal class ReadAloudAudio(appContext: Context) {

    // -- audio focus [T-android-tts-silent-blackhole] --

    private val audioManager =
        appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager

    val playbackAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private var focusRequest: AudioFocusRequest? = null

    fun requestAudioFocus() {
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(playbackAttributes)
            .build()
        focusRequest = req
        val granted = audioManager.requestAudioFocus(req)
        if (granted != AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            AppLogger.info(TAG, "audio focus not granted ($granted) — playing anyway")
        }
    }

    fun abandonAudioFocus() {
        focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        focusRequest = null
    }

    // -- read-aloud volume [T-readaloud-volume-200] --

    /**
     * Pre-boost STREAM_MUSIC index while a boosted utterance plays, or null.
     * Anchored ONCE per utterance (a second [applyVolumeBoost] while boosted
     * would compound the anchor and restore a louder-than-original volume).
     */
    private var boostedStreamFrom: Int? = null

    /**
     * Raise the music stream proportionally to the >100% part of
     * [VoiceOutputState.volume]. Android has no per-app playback gain, so
     * beyond MediaPlayer's 0..1 the only lever is the shared system stream —
     * same trade-off iOS makes with AVAudioPlayer volume > 1 + session volume.
     * Silent no-op at or below 100%, when the stream is already maxed, or when
     * the ROM rejects the write.
     */
    fun applyVolumeBoost() {
        val v = VoiceOutputState.volume.value
        if (v <= 1.0f || boostedStreamFrom != null) return
        val max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val current = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        val target = (current * v).roundToInt().coerceAtMost(max)
        if (target <= current) return
        runCatching {
            // Flag 0: no OS volume HUD for a background TTS tweak.
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, target, 0)
        }.onSuccess {
            boostedStreamFrom = current
        }.onFailure {
            AppLogger.error(TAG, "stream volume boost rejected: $it")
        }
    }

    /** Undo [applyVolumeBoost]. Idempotent; safe from a cancelled coroutine. */
    fun restoreVolumeBoost() {
        val from = boostedStreamFrom ?: return
        boostedStreamFrom = null
        runCatching {
            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, from, 0)
        }
    }

    companion object {
        // Mirror of ReadAloudPlayer.TAG so the log lines emitted here keep
        // their original "ReadAloud" tag (zero log-output change).
        private const val TAG = "ReadAloud"
    }
}
