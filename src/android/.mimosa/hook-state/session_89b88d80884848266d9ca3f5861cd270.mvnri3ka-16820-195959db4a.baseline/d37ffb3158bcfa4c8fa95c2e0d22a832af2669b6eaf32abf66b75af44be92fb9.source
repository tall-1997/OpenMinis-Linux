package com.openminis.app.speech

import android.content.Context

/**
 * [T-android-tts-scene-announcements] Routes an app EVENT to speech.
 *
 * Isolation is the whole point: callers (tool loop, error handler, scheduler,
 * agent lifecycle) hand over a [AnnouncementScene] and a human detail string;
 * everything downstream — the preference gate, the phrase, the TTS engine, its
 * not-yet-initialized window, a device with no engine at all — is this class's
 * problem. A tool result must never crash because TTS was unavailable.
 *
 * ## Why a speak LAMBDA
 * The production constructor takes a [TextToSpeechManager] and forwards to
 * `speak(String)`. The other constructor takes `(String) -> Unit`. On the JVM
 * the manager-taking constructor cannot be driven at all
 * ([TextToSpeechManager.init] needs a real Context and the framework's
 * TextToSpeech service), so tests inject a recording lambda through the
 * primary one and assert on what WAS spoken — plus that a throwing lambda is
 * swallowed. Same arrangement iOS gets from `VoiceAnnouncing`, minus a protocol.
 *
 * ## Gating
 * Announcement scenes are INDEPENDENT of [VoiceOutputState]'s read-replies
 * switch: a reminder can be spoken with read-replies off, and vice versa. The
 * scene preference ([AnnouncementPrefs]) is the only gate consulted here.
 *
 * @param speak sink for the finished phrase; production passes `tts::speak`.
 */
class VoiceAnnouncementCoordinator(
    private val speak: (String) -> Unit,
) {

    /**
     * Production wiring: forward to the shared engine's `speak(text)`.
     *
     * Note this does NOT call [TextToSpeechManager.init] — the engine is owned
     * and initialized by its owner. If the engine is still binding, the manager
     * buffers the text for replay after `onInit` (or drops it when init already
     * reported failure), so an announcement arriving during the binding window
     * is not silently lost. See `TextToSpeechManager.preInitQueue`.
     */
    constructor(tts: TextToSpeechManager) : this(tts::speak)

    /**
     * Announce [scene] if its preference is on.
     *
     * Pure decision + sink call: when [VoiceAnnouncementPrefs.shouldAnnounce]
     * says no, nothing is spoken and nothing is logged — an off scene must not
     * leave a trace in the audio path.
     *
     * The speak call is wrapped in [runCatching] so an uninitialized engine, a
     * vendor ROM rejecting the utterance, or a test's throwing lambda all
     * degrade to silence rather than propagating into the caller's event loop.
     *
     * @return true when a phrase was handed to the engine. A rejected or
     *   throwing engine still returns true (the DECISION was "announce"); the
     *   return value reports the gate, which is what callers and tests assert.
     */
    fun onScene(
        scene: AnnouncementScene,
        detail: String? = null,
        prefs: AnnouncementPrefs,
    ): Boolean {
        if (!VoiceAnnouncementPrefs.shouldAnnounce(scene, prefs)) return false
        val text = VoiceAnnouncementPrefs.announceText(scene, detail)
        runCatching { speak(text) }
        return true
    }

    /**
     * Same as the snapshot overload, reading the gate from persisted
     * preferences.
     *
     * Convenience for call sites that hold a Context but no snapshot; the
     * lookup is a single `getBoolean`, not a full map rebuild. Prefer the
     * snapshot overload where one is already in hand (e.g. a batch of events),
     * so the read happens once instead of per event.
     */
    fun onScene(
        context: Context,
        scene: AnnouncementScene,
        detail: String? = null,
    ): Boolean = onScene(scene, detail, VoiceAnnouncementPrefs.load(context))
}
