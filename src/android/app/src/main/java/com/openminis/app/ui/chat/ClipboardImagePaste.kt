package com.openminis.app.ui.chat

import android.content.ClipboardManager
import android.content.Context
import android.net.Uri
import android.os.Build
import java.io.File
import java.util.UUID

/**
 * [T-composer-clipboard-image] Paste images straight from the system clipboard
 * into the composer's attachment row.
 *
 * Android 13+ (API 33) lets the system clipboard hold an image clip — the
 * screenshot "copy" action and other apps' "copy image" both produce one.
 * Compose 1.9's paste gating is clip-ENTRY based, so the composer's own
 * long-press menu already offers Paste for image-only clips; the toolbar
 * wrapper (see [ComposerImagePaste]) routes that tap into [paste].
 *
 * Two deliberate constraints:
 *  • Detection reads [ClipboardManager.primaryClipDescription] only — reading
 *    the clip DATA is what triggers Android 12+'s "app read your clipboard"
 *    toast; the description does not. The data is only touched when the user
 *    actually taps paste.
 *  • The clip's content URI is only granted while it stays the primary clip.
 *    The moment the user copies anything else, the URI dangles. So paste
 *    copies the bytes into our private cache immediately (via
 *    [ChatViewModel.addAttachmentFromStagedShare], which re-stages into
 *    `cache/share_inbound`), and the attachment then outlives the clipboard.
 */
object ClipboardImagePaste {

    /** Mimes the system clipboard can hold as an image clip. */
    val IMAGE_MIMES: Set<String> = setOf(
        "image/png",
        "image/jpeg",
        "image/jpg",
        "image/webp",
        "image/gif",
    )

    /**
     * True when a clip description advertises an image mime we accept.
     * Pure — unit-testable without a framework ClipboardManager.
     */
    fun isImageClip(mimes: Collection<String>): Boolean =
        mimes.any { it.lowercase() in IMAGE_MIMES }

    /** File extension for a mime, used to name the staged temp file so the
     *  share-inbound restager can map it back to a mime via MimeTypeMap. */
    fun extensionForMime(mime: String?): String = when {
        mime == null -> "jpg"
        mime.lowercase().contains("png") -> "png"
        mime.lowercase().contains("webp") -> "webp"
        mime.lowercase().contains("gif") -> "gif"
        else -> "jpg"
    }

    /**
     * The image URI currently sitting in the system clipboard, or null.
     * Description-only probe: no clipboard-access toast, no data read.
     */
    fun currentImageUri(context: Context): Uri? {
        if (Build.VERSION.SDK_INT < 33) return null
        val cm = context.getSystemService(ClipboardManager::class.java) ?: return null
        val desc = cm.primaryClipDescription ?: return null
        val mimes = (0 until desc.mimeTypeCount).map { desc.getMimeType(it) }
        if (!isImageClip(mimes)) return null
        val clip = cm.primaryClip ?: return null
        for (i in 0 until clip.itemCount) {
            val uri = clip.getItemAt(i).uri ?: continue
            if (uri.scheme == "content") return uri
        }
        return null
    }

    /**
     * Copy the clipboard image into the composer's attachments.
     * Returns false (and toasts nothing — the chip simply stays) when the
     * clipboard no longer holds a readable image; the caller decides UX.
     */
    fun paste(context: Context, viewModel: ChatViewModel): Boolean {
        val uri = currentImageUri(context) ?: return false
        val mime = runCatching { context.contentResolver.getType(uri) }
            .getOrNull()
            ?: "image/jpeg"
        val tmp = File(context.cacheDir, "clipboard_paste_${UUID.randomUUID()}.${extensionForMime(mime)}")
        return try {
            val copied = runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    tmp.outputStream().use { output -> input.copyTo(output) }
                } != null
            }.getOrDefault(false)
            if (!copied || tmp.length() == 0L) {
                tmp.delete()
                return false
            }
            // addAttachmentFromStagedShare re-copies into cache/share_inbound
            // (the durable location), so the temp file can go immediately.
            val attached = viewModel.addAttachmentFromStagedShare(tmp) != null
            tmp.delete()
            attached
        } catch (e: Exception) {
            tmp.delete()
            false
        }
    }
}
