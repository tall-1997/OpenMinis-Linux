package com.openminis.app.speech

import android.media.AudioManager
import com.openminis.app.ProductionSources
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-voice-mic-preempted] Port of iOS 3a2e3e305 (issue #283): a microphone held
 * by another app (a call, a recorder) is reported as such, translated, instead
 * of the engine's English ("AudioRecord not initialized.") or, when Android 10+
 * silences the recording, instead of listening to nothing until the timeout.
 */
class MicInUseTest {

    @Test
    fun `another app is blamed only with evidence`() {
        assertEquals(RecognitionError.MIC_IN_USE, MicInUse.classify(callActive = true, silenced = false))
        assertEquals(RecognitionError.MIC_IN_USE, MicInUse.classify(callActive = false, silenced = true))
        // No evidence: the cause is unknown (route change, hardware), so the
        // generic failure stays - as iOS keeps its generic error.
        assertEquals(RecognitionError.AUDIO_ERROR, MicInUse.classify(callActive = false, silenced = false))
    }

    @Test
    fun `call and VoIP modes count as a call, ringing and normal do not`() {
        assertTrue(MicInUse.isCallMode(AudioManager.MODE_IN_CALL))
        assertTrue(MicInUse.isCallMode(AudioManager.MODE_IN_COMMUNICATION))
        assertTrue(MicInUse.isCallMode(4)) // MODE_CALL_SCREENING
        assertTrue(MicInUse.isCallMode(5)) // MODE_CALL_REDIRECT
        assertTrue(MicInUse.isCallMode(6)) // MODE_COMMUNICATION_REDIRECT
        assertFalse(MicInUse.isCallMode(AudioManager.MODE_NORMAL))
        assertFalse(MicInUse.isCallMode(AudioManager.MODE_RINGTONE))
    }

    @Test
    fun `a take the system silenced is not offered for retry`() {
        // The kept audio is silence; re-sending it cannot help.
        assertFalse(VoiceAsrFailurePolicy.shouldPromptRetry(RecognitionError.MIC_IN_USE, 12.0))
        // Unchanged for real failures with real audio.
        assertTrue(VoiceAsrFailurePolicy.shouldPromptRetry(RecognitionError.AUDIO_ERROR, 12.0))
    }

    // NOTE(fork, batch 7a): the two upstream tests below were deferred to the
    // panel PR (batch 7c) — InlineVoiceInputPanel.kt does not yet contain the
    // MIC_IN_USE -> voice_mic_in_use mapping, and the strings land with it:
    //   - "the panel shows translated text for capture failures, never the engine's English"
    //   - "the message exists in every language that has it on iOS, plus Traditional Chinese"
}
