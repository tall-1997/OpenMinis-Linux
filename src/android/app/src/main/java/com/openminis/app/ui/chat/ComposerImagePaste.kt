package com.openminis.app.ui.chat

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.TextToolbar
import androidx.compose.ui.platform.TextToolbarStatus

/**
 * [T-composer-clipboard-image] Native clipboard-image paste for the composer.
 *
 * Compose 1.9's TextField paste gating is clip-ENTRY based —
 * `canPaste = isEditable && clipEntry != null` — so the system long-press
 * menu already offers Paste while the clipboard holds an image-only clip
 * (screenshot copy, "copy image" in browsers). No chip, no polling, no
 * description probing: the affordance is the field's own paste button.
 *
 * The only missing piece is the paste ACTION: the default toolbar callback
 * pastes text, and an image clip has none. [toolbar] wraps the default
 * [TextToolbar] and rewrites the paste callback — image in clipboard →
 * [pasteImage] (attach as a durable private copy); otherwise the field's
 * own text paste runs. Copy/cut/select-all, positioning and hide delegate
 * untouched.
 *
 * Privacy posture is unchanged from the old chip design: the clipboard
 * DATA is only read when the user actually taps Paste (the description
 * probe inside [ClipboardImagePaste.currentImageUri] rejects text clips
 * before any data access, so ordinary text pastes never trigger the
 * Android 12+ clipboard-access toast).
 */
object ComposerImagePaste {

    /** Wrap [delegate] so its Paste action tries [pasteImage] first. */
    fun toolbar(delegate: TextToolbar, pasteImage: () -> Boolean): TextToolbar =
        PasteInterceptingTextToolbar(delegate, pasteImage)

    private class PasteInterceptingTextToolbar(
        private val delegate: TextToolbar,
        private val pasteImage: () -> Boolean,
    ) : TextToolbar {

        override val status: TextToolbarStatus
            get() = delegate.status

        override fun showMenu(
            rect: Rect,
            onCopyRequested: (() -> Unit)?,
            onPasteRequested: (() -> Unit)?,
            onCutRequested: (() -> Unit)?,
            onSelectAllRequested: (() -> Unit)?,
        ) {
            val interceptedPaste = onPasteRequested?.let { fallback ->
                {
                    // Image wins: a clip carrying an image is a copy-image
                    // gesture. Text riding in the same clip is deliberately
                    // not co-pasted. When no image is present (or the copy
                    // failed because the clip was overwritten), the normal
                    // text paste runs.
                    if (!pasteImage()) fallback()
                }
            }
            delegate.showMenu(rect, onCopyRequested, interceptedPaste, onCutRequested, onSelectAllRequested)
        }

        override fun hide() {
            delegate.hide()
        }
    }
}
