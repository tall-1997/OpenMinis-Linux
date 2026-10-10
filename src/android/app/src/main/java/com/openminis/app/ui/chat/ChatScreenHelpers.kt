package com.openminis.app.ui.chat

// [T-android-split-chat] Camera/file URI helpers + PendingNonTextSelection model
// extracted verbatim from ChatScreen.kt. Imports trimmed after ChatScreen.kt split.
// (unused=warnings); all internal (used by the ChatScreen composable).

import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp

/**
 * Create an empty JPEG file under filesDir/camera-photos/ and return a
 * FileProvider content URI for it, plus the underlying File. The camera
 * app writes the captured image into this URI synchronously.
 *
 * Bug 1 (MIUI): the previous version put the staging file under cacheDir,
 * which MIUI may clear while the camera Activity is in flight. Moving to
 * filesDir keeps the URI valid until we explicitly delete it.
 */
internal fun createCameraOutputUri(context: android.content.Context): Pair<Uri, java.io.File> {
    val dir = java.io.File(context.filesDir, "camera-photos").also { it.mkdirs() }
    val ts = System.currentTimeMillis()
    val file = java.io.File(dir, "photo-$ts.jpg")
    file.createNewFile()
    val authority = context.packageName + ".fileprovider"
    val uri = androidx.core.content.FileProvider.getUriForFile(context, authority, file)
    return uri to file
}

internal fun getFileName(context: android.content.Context, uri: Uri): String? {
    val cursor = context.contentResolver.query(uri, null, null, null, null) ?: return null
    return cursor.use {
        if (it.moveToFirst()) {
            val nameIndex = it.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (nameIndex >= 0) it.getString(nameIndex) else null
        } else null
    }
}

// ─── Model Picker Bottom Sheet ──────────────────────────────────────────────

/**
 * Captures a tap on a model whose output modality (image/audio/video) makes
 * it unsuitable for driving an Agent task, so the picker can show a
 * confirmation dialog before applying the binding.
 */
internal sealed class PendingNonTextSelection {
    abstract val modelDisplayName: String
    abstract val modalityLabel: String

    data class Group(
        val groupId: String,
        override val modelDisplayName: String,
        override val modalityLabel: String,
    ) : PendingNonTextSelection()

    data class GroupEntry(
        val groupId: String,
        val entryId: String,
        override val modelDisplayName: String,
        override val modalityLabel: String,
    ) : PendingNonTextSelection()

    data class Entry(
        val entryId: String,
        override val modelDisplayName: String,
        override val modalityLabel: String,
    ) : PendingNonTextSelection()
}

// ── Voice-correction capture from plain text edits ────────────────────────────

/**
 * [T-android-voice-correction] Capability #3: detect a select-and-replace edit
 * in the composer and hand the before/after pair to the learning recorder.
 *
 * The trigger is structural rather than heuristic: [previous] must have had a
 * NON-EMPTY selection, and [next] must have swapped exactly that span for
 * different text. Ordinary typing is append-only and never matches, so this
 * stays silent during normal composition.
 *
 * Whether the replacement is a genuine CORRECTION is not decided here — the
 * recorder applies the same phonetic-similarity and length-ratio test used for
 * ASR transcripts, so "I changed my mind" is discarded rather than learned.
 *
 * Nothing is captured unless the user has opted in; the call is fire-and-forget
 * so keystrokes never wait on segmentation or SQLite.
 */
internal fun captureSelectionReplacement(
    context: android.content.Context,
    previous: androidx.compose.ui.text.input.TextFieldValue,
    next: androidx.compose.ui.text.input.TextFieldValue,
) {
    val selection = previous.selection
    if (selection.collapsed) return

    val start = minOf(selection.start, selection.end)
    val end = maxOf(selection.start, selection.end)
    val oldText = previous.text
    if (start < 0 || end > oldText.length || start >= end) return

    val replaced = oldText.substring(start, end)
    if (replaced.isBlank()) return

    // The unchanged prefix/suffix around the selection must still match, which
    // confirms this edit replaced exactly that span rather than something else.
    val prefix = oldText.substring(0, start)
    val suffix = oldText.substring(end)
    val newText = next.text
    if (!newText.startsWith(prefix) || !newText.endsWith(suffix)) return

    val insertedEnd = newText.length - suffix.length
    if (insertedEnd < prefix.length) return
    val inserted = newText.substring(prefix.length, insertedEnd)
    // An empty replacement is a deletion — it carries no "should have been"
    // information, so there is nothing to learn from it.
    if (inserted.isBlank() || inserted == replaced) return

    com.openminis.app.speech.correction.VoiceCorrection.captureTextInputEdit(
        context = context,
        before = replaced,
        after = inserted,
    )
}

/**
 * [T-android-chat-customization] Conversation-column gutter (dp). Live updates
 * flow through ChatScreen's shared appearance-prefs listener, which writes
 * back into this state on KEY_CHAT_GUTTER_DP changes. Default 20, 8..32.
 */
@androidx.compose.runtime.Composable
internal fun rememberChatGutterDp(
    context: android.content.Context,
): androidx.compose.runtime.MutableState<Int> =
    androidx.compose.runtime.remember(context) {
        androidx.compose.runtime.mutableStateOf(com.openminis.app.ui.settings.chatGutterDp(context))
    }

/** [T-android-chat-customization] "Load earlier / jump to latest" edge row
 *  (moved verbatim from ChatScreen.kt during the gutter ratchet extraction). */
@androidx.compose.runtime.Composable
internal fun HistoryPageEdge(
    text: String,
    onClick: () -> Unit,
) {
    androidx.compose.material3.Text(
        text = text,
        modifier = androidx.compose.ui.Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 16.dp),
        style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
        color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
}
