package com.openminis.app.ui.chat

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import com.openminis.app.speech.TextToSpeechManager

/**
 * [T-android-tts-scene-announcements] Binds the announcement sink for as
 * long as the hosting screen is composed. The engine binding lives in
 * TextToSpeechManager (speech layer); the ViewModel only sees the
 * `(String) -> Unit` sink and never the engine itself. Cleared on dispose
 * so a popped screen stops announcing.
 *
 * Extracted from ChatScreen.kt to keep that file under its
 * architecture-policy ceiling.
 */
@Composable
internal fun rememberAnnouncementTtsWire(
    viewModel: ChatViewModel,
    context: Context,
) {
    val announcementTts = remember { TextToSpeechManager() }
    DisposableEffect(viewModel, announcementTts) {
        // [T-android-entry-tts-lazy] Engine construction is a cross-process
        // bind that can stall for seconds on a cold engine; calling init()
        // unconditionally held up the first frame of EVERY session open.
        // Bind on first actual utterance instead — speak() before the bind
        // settles is buffered in the manager's preInitQueue and replayed
        // once the engine is up, so the first announcement is never lost.
        viewModel.announcementSink = { text ->
            announcementTts.ensureInitialized(context)
            announcementTts.speak(text)
        }
        onDispose {
            viewModel.announcementSink = null
            announcementTts.shutdown()
        }
    }
}
