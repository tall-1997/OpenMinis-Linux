package com.openminis.app.speech.correction

import android.content.Context

/**
 * [T-android-voice-correction] Opt-in consent for storing correction learning
 * data. Port of iOS `VoiceCorrectionCollectionConsent`, same preference keys so
 * a config moved between platforms keeps the user's choice.
 *
 * ## What this gates
 * WRITES ONLY. Reading existing data is never gated: with collection off,
 * correction keeps working exactly as before — it simply stops learning. The
 * guards live in the three writers (recorder, engine, vocabulary builder)
 * rather than at each call site, so a future caller is covered automatically.
 *
 * Both flags default to FALSE. Nothing is collected until the user says yes.
 */
object VoiceCorrectionConsent {

    private const val PREFS = "voice_correction_prefs"

    /** Key names match iOS UserDefaults so the semantics stay recognisable. */
    private const val KEY_ENABLED = "voiceCorrectionCollectionEnabled"
    private const val KEY_PROMPTED = "voiceCorrectionCollectionPrompted"

    /** True when the user has agreed to store learning data. Default false. */
    fun isEnabled(context: Context): Boolean =
        prefs().bool(KEY_ENABLED, false)

    fun setEnabled(context: Context, enabled: Boolean) {
        prefs().set(KEY_ENABLED, enabled)
    }

    /**
     * True once the one-time prompt has been shown AND answered — whatever the
     * answer was. It never fires again after that.
     */
    fun hasPrompted(context: Context): Boolean =
        prefs().bool(KEY_PROMPTED, false)

    fun setPrompted(context: Context, prompted: Boolean) {
        prefs().set(KEY_PROMPTED, prompted)
    }

    private fun prefs() = com.openminis.app.config.Prefs.file(PREFS)
}
