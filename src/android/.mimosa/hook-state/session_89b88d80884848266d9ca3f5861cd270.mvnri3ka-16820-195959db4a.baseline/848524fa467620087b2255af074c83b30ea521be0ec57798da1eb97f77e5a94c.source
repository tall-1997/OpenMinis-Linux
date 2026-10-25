package com.openminis.app.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ImageBudget
import com.openminis.app.util.IsoTime
import java.io.ByteArrayOutputStream
import org.json.JSONObject

/**
 * T209: resize image bytes for the LLM inference payload only — the
 * full-resolution original is preserved on disk (mediaStore + uploads
 * dir) so chat history fullscreen view, agent shell `cat`, and
 * `read_image` all see the user's original picture, matching iOS.
 *
 * Returns null when the source already fits within [maxEdge] (caller
 * should fall back to [rawBytes]) or on any decode/compress failure.
 */
private fun ChatViewModel.resizeImageBytes(
    rawBytes: ByteArray,
    mimeType: String,
    maxEdge: Int = 2000,
): ByteArray? {
    return try {
        val original = BitmapFactory.decodeByteArray(rawBytes, 0, rawBytes.size) ?: return null
        if (original.width <= maxEdge && original.height <= maxEdge) {
            original.recycle()
            return null
        }
        val scale = maxEdge.toFloat() / maxOf(original.width, original.height)
        val w = (original.width * scale).toInt()
        val h = (original.height * scale).toInt()
        val scaled = Bitmap.createScaledBitmap(original, w, h, true)
        val out = ByteArrayOutputStream()
        val format = if (mimeType.contains("png")) Bitmap.CompressFormat.PNG
        else Bitmap.CompressFormat.JPEG
        scaled.compress(format, 85, out)
        if (scaled !== original) scaled.recycle()
        original.recycle()
        out.toByteArray()
    } catch (_: Exception) {
        null
    }
}

/**
 * Bundle of everything derived from a user-message's input attachments:
 * the resized in-memory image bytes for the LLM, file:// URIs of the
 * persisted copies (for stable rendering across app restarts), the
 * filenames in original attachment order (images first, then non-image
 * files — matches the rendering convention in UserAttachmentList), and
 * the mediaRef JSON parts that need to be embedded in parts_json so the
 * attachments survive a session reload (T128).
 */
internal data class PreparedAttachments(
    val imageParts: List<LLMMessage.ImagePart>,
    val imageUris: List<Uri>,
    val attachmentNames: List<String>,
    val mediaRefPartsJson: List<String>,
    // T132: iOS-parity additions so the model sees the attachment as
    // a real file in the agent's sandbox (read_image / shell_execute can
    // open these paths).
    //   imageUploadPaths: one /var/minis/attachments/uploads/<safe> per
    //     inlined image, in the same order as `imageParts`.
    //   attachedFilesXml:  null when no attachments, otherwise the
    //     <user-attached-files> XML block iOS appends to the user turn.
    val imageUploadPaths: List<String>,
    val attachedFilesXml: String?,
    // T150: file:// URIs of persisted non-image attachments, in the same
    // order as the non-image suffix of `attachmentNames`. Carried into
    // ChatMessage so the user-bubble file chip can route a tap directly
    // to FilePreviewScreen without re-resolving by filename.
    val nonImageUris: List<Uri>,
)

/**
 * Resize each image attachment, copy the bytes into MediaStore (private
 * filesDir/media/<date>/<sessionId>/<id>.<ext>), and return both the
 * in-memory bytes (for the LLM) and a stable file:// URI + mediaRef JSON
 * part (for persistence + reload). T150: non-image attachments take the
 * same persistence + uploadsHostDir path so they survive session reload
 * and remain visible to the agent's shell tools — but their content is
 * NOT inlined into the LLM payload (parity with iOS processAttachments,
 * AIChatViewModel.swift L1552-1645).
 */
internal fun ChatViewModel.prepareUserAttachments(
    attachments: List<InputAttachment>,
    sessionId: String,
): PreparedAttachments {
    val imageParts = mutableListOf<LLMMessage.ImagePart>()
    val imageUris = mutableListOf<Uri>()
    val imageNames = mutableListOf<String>()
    val nonImageNames = mutableListOf<String>()
    val nonImageUris = mutableListOf<Uri>()
    // T150: separate buffers so the persisted mediaRefPartsJson is
    // image-first, matching the on-screen UserAttachmentList ordering
    // and `attachmentNames = imageNames + nonImageNames`. On restore,
    // `loadSessionMessages` walks parts_json in array order — keeping
    // the persisted order image-first means restoredAttachmentNames
    // and restoredAttachmentUris also come out image-first/non-image-suffix.
    val imageMediaRefPartsJson = mutableListOf<String>()
    val nonImageMediaRefPartsJson = mutableListOf<String>()
    val imageUploadPaths = mutableListOf<String>()
    // T132: also write the resized bytes into the session's iSH-bound
    // attachments dir (filesDir/minis-sessions/<sid>/attachments/uploads/),
    // which is mounted at /var/minis/attachments/ inside iSH. This makes
    // the same image accessible to the agent via shell tools (read_image
    // / cat / file) and matches the iOS uploads-directory convention.
    val uploadsHostDir = java.io.File(
        com.openminis.app.sandbox.SessionWorkspace.hostDir(context.filesDir, sessionId, "attachments"),
        "uploads",
    ).apply { mkdirs() }
    // Metadata captured per attachment for the <user-attached-files> XML.
    data class UploadMeta(val linuxPath: String, val size: Long, val modifiedIso: String)
    val metas = mutableListOf<UploadMeta>()
    val nowMs = System.currentTimeMillis()
    val nowStr = IsoTime.formatUtcSeconds(nowMs)

    for (attachment in attachments) {
        if (attachment.isImage) {
            // T209: read the original image bytes once and reuse them
            // for storage + uploads dir; only the LLM inference payload
            // gets the resized copy. Pre-T209 the resized JPEG was used
            // for all three, so chat history fullscreen view and agent
            // shell tools (read_image / cat) saw a 1024px JPEG instead
            // of the user's original picture. Matches iOS canonical
            // (AIChatViewModel.swift L1595-1617).
            val rawBytes = try {
                context.contentResolver.openInputStream(attachment.uri)?.use { it.readBytes() }
            } catch (e: Exception) {
                Log.w(ChatViewModel.TAG, "image read failed for ${attachment.fileName}: ${e.message}")
                null
            } ?: continue
            val ref = try {
                mediaStore.saveMedia(
                    data = rawBytes,
                    mimeType = attachment.mimeType,
                    sessionId = sessionId,
                    originalFileName = attachment.fileName,
                )
            } catch (e: Exception) {
                Log.e(ChatViewModel.TAG, "Failed to persist image attachment ${attachment.fileName}", e)
                continue
            }
            // Resize only for the LLM payload — token-efficient and a
            // close-enough sketch of the picture for the model. Falls
            // back to raw bytes if the source is already small or the
            // decode/compress step fails.
            val inferenceBytes = resizeImageBytes(rawBytes, attachment.mimeType, maxEdge = 2000)
                ?: rawBytes

            // Mirror ORIGINAL bytes into the iSH uploads dir under a
            // unique safe name so agent shell tools see the full-res
            // image. Don't fail the send if this write fails —
            // image_url in the request still carries (resized) bytes;
            // the model just won't be able to ask the agent to re-read
            // the same file from shell.
            //
            // Done BEFORE ImagePart construction so the linuxPath is
            // attached to the part — request-level image budgeting
            // uses it to emit a re-fetchable text placeholder when
            // the cumulative payload would exceed the per-request cap.
            val safeName = uniqueUploadFileName(uploadsHostDir, attachment.fileName)
            val dest = java.io.File(uploadsHostDir, safeName)
            val uploadOk = try { dest.writeBytes(rawBytes); true } catch (e: Exception) {
                Log.w(ChatViewModel.TAG, "uploads write failed for ${attachment.fileName}: ${e.message}")
                false
            }
            val linuxPath = if (uploadOk) "/var/minis/attachments/uploads/$safeName" else null
            if (linuxPath != null) {
                imageUploadPaths.add(linuxPath)
                metas.add(UploadMeta(linuxPath = linuxPath, size = rawBytes.size.toLong(), modifiedIso = nowStr))
            }

            imageParts.add(LLMMessage.ImagePart(inferenceBytes, attachment.mimeType, linuxPath = linuxPath))
            val savedFile = java.io.File(mediaStore.mediaBaseDir, ref.relativePath)
            imageUris.add(Uri.fromFile(savedFile))
            imageNames.add(attachment.fileName)
            imageMediaRefPartsJson.add(buildMediaRefPartJson(ref, linuxPath = linuxPath))
            continue
        }

        // T150: non-image attachment — stream-copy to disk (no
        // resize), persist a mediaRef so the chip survives session
        // reload (T151), and put a copy in the iSH uploads dir so
        // the agent can `cat` it via shell tools. iOS parity: the
        // file content is NOT inlined into the LLM payload — it
        // only appears in <user-attached-files> XML metadata, the
        // model fetches content on demand.
        //
        // CRITICAL: we deliberately do NOT `readBytes()` the
        // attachment here. A 400MB APK shared in by the user would
        // OOM on a low-RAM device (heap growth limit ~500MB on
        // Pixel 4a); the file's not even going into the LLM
        // payload, so loading the full byte array is pointless.
        // Stream-copy to the uploads dest first, then hand that
        // file to MediaStore.saveMediaStreamed so a second
        // streaming pass produces the durable mediaRef.
        nonImageNames.add(attachment.fileName)
        val safeName = uniqueUploadFileName(uploadsHostDir, attachment.fileName)
        val dest = java.io.File(uploadsHostDir, safeName)
        val uploadOk = try {
            context.contentResolver.openInputStream(attachment.uri)?.use { input ->
                dest.outputStream().use { output -> input.copyTo(output) }
            } != null
        } catch (e: Exception) {
            Log.w(ChatViewModel.TAG, "non-image upload write failed for ${attachment.fileName}: ${e.message}")
            runCatching { dest.delete() }
            false
        }
        if (!uploadOk) continue

        val ref = try {
            dest.inputStream().use { input ->
                mediaStore.saveMediaStreamed(
                    source = input,
                    mimeType = attachment.mimeType,
                    sessionId = sessionId,
                    originalFileName = attachment.fileName,
                )
            }
        } catch (e: Exception) {
            Log.e(ChatViewModel.TAG, "Failed to persist non-image attachment ${attachment.fileName}", e)
            null
        }
        if (ref != null) {
            nonImageMediaRefPartsJson.add(buildMediaRefPartJson(ref))
            nonImageUris.add(Uri.fromFile(java.io.File(mediaStore.mediaBaseDir, ref.relativePath)))
        }

        val linuxPath = "/var/minis/attachments/uploads/$safeName"
        metas.add(UploadMeta(linuxPath = linuxPath, size = dest.length(), modifiedIso = nowStr))
    }

    // T-imgsize: byte-level budget enforcement. The resizeImageBytes pass
    // above caps *resolution* at 2000px but does nothing for the JPEG byte
    // size when the source is a 12-megapixel photo — Anthropic 413s once
    // cumulative inline image payload crosses ~30MB. ImageBudget walks
    // every image part, re-encodes oversize ones via the quality ladder,
    // and drops the tail when cumulative bytes would exceed 20MB. Result
    // is surfaced to the UI through _imageBudgetEvent so the Snackbar can
    // tell the user we touched their attachments.
    if (imageParts.isNotEmpty()) {
        val budgetResult = ImageBudget.applyMessageBudget(imageParts.map { it.data })
        // budgetResult.keptBytes.size <= imageParts.size; tail-drop the
        // parallel image-only lists symmetrically. Re-encoded bytes always
        // come out as JPEG so flip the mimeType on any part whose bytes
        // changed size (cheap proxy — never a false positive that hurts
        // semantics because the byte stream itself is the JPEG header).
        val newImageParts = budgetResult.keptBytes.mapIndexed { idx, kept ->
            val orig = imageParts[idx]
            if (kept === orig.data) orig
            else LLMMessage.ImagePart(kept, "image/jpeg", linuxPath = orig.linuxPath)
        }
        val newSize = newImageParts.size
        imageParts.clear()
        imageParts.addAll(newImageParts)
        while (imageUris.size > newSize) imageUris.removeAt(imageUris.size - 1)
        while (imageNames.size > newSize) imageNames.removeAt(imageNames.size - 1)
        while (imageMediaRefPartsJson.size > newSize) imageMediaRefPartsJson.removeAt(imageMediaRefPartsJson.size - 1)
        while (imageUploadPaths.size > newSize) imageUploadPaths.removeAt(imageUploadPaths.size - 1)
        if (budgetResult.mutated) {
            AppLogger.info(
                ChatViewModel.TAG,
                "[ImageBudget] compose: in=${budgetResult.keptBytes.size + budgetResult.droppedCount} kept=${budgetResult.keptBytes.size} compressed=${budgetResult.compressedCount} dropped=${budgetResult.droppedCount} totalBytes=${budgetResult.totalBytes}",
            )
            _imageBudgetEvent.tryEmit(budgetResult)
        }
    }

    // Build the <user-attached-files> XML block (iOS parity). One <file>
    // per attachment (image and non-image) that successfully landed in
    // the iSH uploads dir — gives the model a metadata-only inventory
    // it can resolve via shell tools when content is needed.
    val xml = if (metas.isEmpty()) null else buildString {
        append("<user-attached-files>\n")
        for (m in metas) {
            val urlPath = m.linuxPath.removePrefix("/var/minis/")
            append("  <file path=\"")
            append(m.linuxPath)
            append("\" url=\"minis://")
            append(urlPath)
            append("\" size=\"")
            append(m.size)
            append("\" modified=\"")
            append(m.modifiedIso)
            append("\" />\n")
        }
        append("</user-attached-files>")
    }

    // Order matches UserAttachmentList convention: images first, then files.
    return PreparedAttachments(
        imageParts = imageParts,
        imageUris = imageUris,
        attachmentNames = imageNames + nonImageNames,
        mediaRefPartsJson = imageMediaRefPartsJson + nonImageMediaRefPartsJson,
        imageUploadPaths = imageUploadPaths,
        attachedFilesXml = xml,
        nonImageUris = nonImageUris,
    )
}

/**
 * Compute a unique-on-disk filename inside [dir] for [original]. Strips
 * path separators, falls back to "image.jpg" if the input is empty, and
 * appends `_N` before the extension when the target already exists.
 */
private fun ChatViewModel.uniqueUploadFileName(dir: java.io.File, original: String): String {
    val raw = original.substringAfterLast('/').substringAfterLast('\\').ifBlank { "image.jpg" }
    // Sanitize control / path-hostile chars without going overboard;
    // safe POSIX path chars are kept.
    val sanitized = raw.replace(Regex("[^A-Za-z0-9._-]"), "_")
    if (!java.io.File(dir, sanitized).exists()) return sanitized
    val dot = sanitized.lastIndexOf('.')
    val base = if (dot > 0) sanitized.substring(0, dot) else sanitized
    val ext = if (dot > 0) sanitized.substring(dot) else ""
    var n = 1
    while (true) {
        val candidate = "${base}_$n$ext"
        if (!java.io.File(dir, candidate).exists()) return candidate
        n++
    }
}

private fun ChatViewModel.buildMediaRefPartJson(
    ref: com.openminis.app.data.model.MediaRef,
    linuxPath: String? = null,
): String {
    val value = JSONObject()
        .put("id", ref.id)
        .put("relativePath", ref.relativePath)
        .put("mimeType", ref.mimeType)
    if (ref.originalFileName != null) value.put("originalFileName", ref.originalFileName)
    // Carry the iSH-visible uploads path through persistence so that
    // restored history can reconstruct AgentContentPart.ImageData with
    // its original linuxPath. Restored images that miss this field
    // (older rows written before this column existed) get linuxPath=null
    // and fall back to spillover at budget-elide time.
    if (linuxPath != null) value.put("linuxPath", linuxPath)
    return JSONObject().put("type", "mediaRef").put("value", value).toString()
}

/**
 * Build the parts_json array for a user message: a `text` part (omitted
 * when the user only sent attachments with no caption) followed by one
 * `mediaRef` part per persisted image. Mirrors the existing single-part
 * shape when there are no attachments.
 */
/**
 * [T-android-paste-mediaref] What a message's `[Pasted#N]` markers turned
 * into once each was written to disk.
 *
 * @param partsJson the message's parts, in order, with each marker replaced
 *   by a `text/plain` mediaRef part and the surrounding prose kept as
 *   separate text parts.
 * @param modelText the same body with every marker expanded back to its full
 *   text — what the model must see on THIS turn. (Later turns rebuild it
 *   from disk via toLLMMessage.)
 * @param uiNames / [uiUris] the pasted blocks as attachment-style entries so
 *   the sent bubble shows a file card, exactly like a picked document.
 * @param consumedIds buffer entries actually referenced, for the caller to
 *   clear once the send is committed.
 */
internal data class PastedParts(
    val partsJson: List<String>,
    val modelText: String,
    val uiNames: List<String>,
    val uiUris: List<Uri>,
    val consumedIds: Set<Int>,
)

/**
 * [T-android-paste-mediaref] Write each `[Pasted#N]` in [text] to its own
 * `text/plain` media file and return the pieces the send path needs.
 *
 * This is the crux of the change. Previously the marker was substituted
 * inline and the whole block was persisted as one `text` part; now the block
 * becomes a mediaRef — the same mechanism images and documents already use —
 * so the stored message and its bubble stay small while the file on disk
 * holds the content.
 *
 * Returns null when [text] contains no live marker, letting every caller
 * keep its existing straight-line path untouched.
 */
internal fun ChatViewModel.buildPastedParts(text: String, sessionId: String): PastedParts? {
    val (chunks, consumed) = splitPastePlaceholders(text, _pastedTexts.value)
    if (consumed.isEmpty()) return null
    val byId = _pastedTexts.value.associateBy { it.id }

    val parts = mutableListOf<String>()
    val model = StringBuilder()
    val names = mutableListOf<String>()
    val uris = mutableListOf<Uri>()
    for (chunk in chunks) {
        when (chunk) {
            is PasteChunk.Text -> {
                parts.add("""{"type":"text","value":${escapeJson(chunk.value)}}""")
                model.append(chunk.value)
            }
            is PasteChunk.Pasted -> {
                val entry = byId[chunk.id] ?: continue
                val ref = try {
                    mediaStore.saveMedia(
                        data = entry.text.toByteArray(Charsets.UTF_8),
                        mimeType = PastedMedia.MIME,
                        sessionId = sessionId,
                        originalFileName = PastedMedia.fileNameFor(chunk.id),
                    )
                } catch (e: Exception) {
                    // Disk full / unwritable: fall back to inlining this one
                    // block as text. The message is then shaped like the old
                    // behaviour — big, but complete. Losing the paste
                    // silently would be far worse than a heavy bubble.
                    AppLogger.warning(
                        ChatViewModel.TAG,
                        "[Paste] saveMedia failed for #${chunk.id}, inlining: ${e.message}",
                    )
                    parts.add("""{"type":"text","value":${escapeJson(entry.text)}}""")
                    model.append(entry.text)
                    continue
                }
                parts.add(buildMediaRefPartJson(ref))
                model.append(entry.text)
                names.add(ref.originalFileName ?: PastedMedia.fileNameFor(chunk.id))
                uris.add(Uri.fromFile(java.io.File(mediaStore.mediaBaseDir, ref.relativePath)))
            }
        }
    }
    AppLogger.info(
        ChatViewModel.TAG,
        "[Paste] ${consumed.size} placeholder(s) -> mediaRef: " +
            "${text.length} chars in bubble, ${model.length} chars to model",
    )
    return PastedParts(parts, model.toString(), names, uris, consumed)
}

internal fun ChatViewModel.buildUserPartsJson(
    text: String,
    mediaRefPartsJson: List<String>,
    // [T-android-retry-attachment-loss] The <user-attached-files> XML
    // inventory (non-image file paths/sizes the model uses to `cat` the
    // file). iOS persists this same XML as a trailing text part so it
    // round-trips through retry / rerun / session-reload unchanged — the
    // model keeps seeing the /var/minis/attachments/uploads/... paths.
    // Android previously only added it to the in-memory agentHistory and
    // never persisted it, so a retry silently dropped the file inventory.
    // Persist it here as a text part (iOS parity); toLLMMessage restores
    // it via the plain "text" case with zero special-casing.
    attachedFilesXml: String? = null,
    /**
     * [T-android-paste-mediaref] Pre-split body parts from
     * [buildPastedParts], used INSTEAD of the single `text` part when the
     * message contained `[Pasted#N]` markers. Already an interleaved
     * text/mediaRef sequence, so it is spliced in at the position the plain
     * text part would have occupied — order is what keeps the pasted block
     * where the user put it, between the words around it.
     */
    bodyPartsJson: List<String>? = null,
): String {
    val parts = mutableListOf<String>()
    if (bodyPartsJson != null) {
        parts.addAll(bodyPartsJson)
    } else if (text.isNotEmpty() || mediaRefPartsJson.isEmpty()) {
        parts.add("""{"type":"text","value":${escapeJson(text)}}""")
    }
    parts.addAll(mediaRefPartsJson)
    attachedFilesXml?.let { parts.add("""{"type":"text","value":${escapeJson(it)}}""") }
    return parts.joinToString(prefix = "[", postfix = "]", separator = ",")
}
