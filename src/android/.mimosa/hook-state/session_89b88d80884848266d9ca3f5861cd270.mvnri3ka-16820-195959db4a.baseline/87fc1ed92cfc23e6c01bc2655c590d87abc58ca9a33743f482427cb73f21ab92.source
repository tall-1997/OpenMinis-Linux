package com.openminis.app.ui.chat

import com.openminis.app.logging.AppLogger

/**
 * T187: enter edit mode for [messageId]. Returns the cleaned text the
 * caller should drop into the composer (with any
 * `<user-attached-files>` XML stripped), or null when the message
 * cannot be edited (streaming in progress, message missing, or not
 * a user turn). Setting `_editingMessageId` is what flips the
 * composer into edit-mode UI; the next sendMessage call sees the
 * non-null id and truncates the conversation from that point.
 * Mirrors iOS AIChatViewModel.editMessage(_:) (L2468).
 */
fun ChatViewModel.editMessage(messageId: String): String? {
    if (_isStreaming.value) return null
    val msg = _messages.value.firstOrNull { it.id == messageId } ?: return null
    if (msg.role != "user") return null
    var text = msg.content
    val startIdx = text.indexOf("<user-attached-files>")
    if (startIdx >= 0) {
        val endTag = "</user-attached-files>"
        val endIdx = text.indexOf(endTag, startIdx)
        text = if (endIdx >= 0) {
            (text.substring(0, startIdx) + text.substring(endIdx + endTag.length)).trim()
        } else {
            text.substring(0, startIdx).trim()
        }
    }
    // [T-android-edit-loses-attachments] Restore the message's attachments
    // into the composer alongside its text.
    //
    // Without this, editing a message that carried an image silently
    // dropped it: the composer showed only the text, and re-sending
    // produced a turn the model could no longer see the picture in. The
    // XML strip above is what makes the loss invisible — the
    // `<user-attached-files>` block naming the files is removed from the
    // text, so nothing on screen hints that anything was attached.
    //
    // iOS has done this since AIChatViewModel.editMessage (L3891-3920);
    // this side only ever returned the text. Android needs no copy step,
    // unlike iOS: `imageUris`/`attachmentUris` on a restored message
    // already point at files inside the app's own media store (see the
    // `mediaRef` branch of loadSessionMessages, which resolves them
    // against mediaStore.mediaBaseDir and skips any that no longer
    // exist), and that is exactly what the send path re-reads.
    //
    // Ordering matches ChatMessage's own convention — images first, then
    // files — so the composer's preview row shows them the same way the
    // sent bubble did. Names come from `attachmentNames`, which is built
    // image-first to align with these two lists; it is indexed
    // defensively anyway, since a row persisted by an older build could
    // be short.
    val restored = mutableListOf<InputAttachment>()
    msg.imageUris.forEachIndexed { i, uri ->
        val name = msg.attachmentNames.getOrNull(i) ?: uri.lastPathSegment ?: "image"
        restored.add(
            InputAttachment(
                fileName = name,
                uri = uri,
                mimeType = guessMimeType(name, fallback = "image/*"),
                kind = com.openminis.app.session.InputAttachment.Kind.IMAGE,
            ),
        )
    }
    msg.attachmentUris.forEachIndexed { i, uri ->
        // The non-image names occupy the suffix of attachmentNames, after
        // the imageUris-many image entries.
        val name = msg.attachmentNames.getOrNull(msg.imageUris.size + i)
            ?: uri.lastPathSegment ?: "file"
        restored.add(
            InputAttachment(
                fileName = name,
                uri = uri,
                mimeType = guessMimeType(name, fallback = "application/octet-stream"),
                kind = com.openminis.app.session.InputAttachment.Kind.DOCUMENT,
            ),
        )
    }
    // Replace rather than append: edit mode reloads a specific message, so
    // whatever the composer held was a different draft. Assigning even when
    // empty keeps that true for a message that genuinely had no files.
    _attachments.value = restored

    _editingMessageId.value = messageId
    AppLogger.info(
        ChatViewModel.TAG_STREAM,
        "✏️ editMessage id=${messageId.take(8)} text=${text.length}ch " +
            "attachments=${restored.size}",
    )
    return text
}

/**
 * [T-android-edit-attachments] Best-effort MIME for a restored attachment,
 * derived from its file extension.
 *
 * The exact type only has to be good enough for the composer chip and the
 * send path's image/non-image split; the kind is already decided by which
 * list the URI came out of, so a miss here cannot misroute an attachment.
 */
private fun guessMimeType(fileName: String, fallback: String): String {
    val ext = fileName.substringAfterLast('.', "").lowercase()
    if (ext.isEmpty()) return fallback
    return android.webkit.MimeTypeMap.getSingleton()
        .getMimeTypeFromExtension(ext) ?: fallback
}
