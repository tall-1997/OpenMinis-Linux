package com.openminis.app.data.repository

import com.openminis.app.data.body.BodyStore
import com.openminis.app.data.body.ResourceLimits
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.display.DisplayParts
import java.io.File

/**
 * [T-body-spill-stub] Hydration of display projections, and the full-body read
 * that copy/export needs.
 *
 * Split out of `ChatRepository` for the architecture ratchet, and because both
 * entry points answer the same question: *what does this row actually say?*
 * The projection's `parts_json` cannot answer it for an offloaded row — it IS
 * the `body stored (N bytes)` placeholder — so every path here resolves through
 * the content-addressed blob in `files/bodies`, never through the placeholder.
 */

/**
 * Replace projected stubs with complete, display-sized JSON. One body is read
 * at a time and discarded after shrinking. The stored row is unchanged.
 *
 * A body that cannot be read falls back to the `preview` column (up to
 * [DisplayParts.PREVIEW_NOTE_CHARS] of the real text) and only then to a
 * generic note. Falling back to `parts_json` was the bug: for an offloaded row
 * that column is the placeholder, which is valid JSON, so it parsed cleanly and
 * the bubble rendered "body stored (N bytes)" as if the model had said it.
 */
/**
 * [T-body-spill-copy] The complete parts JSON for a row, for callers that copy
 * or export it (session duplicate, backup).
 *
 * Those callers read messages through the display projection, whose
 * `parts_json` is the offload placeholder for any oversized body. Writing that
 * placeholder into a new row looked harmless — it is small, so it was stored
 * inline with `body_ref = NULL` — and the copy silently lost every offloaded
 * message it contained. Resolve through the blob instead so the new row
 * re-references the same content-addressed body.
 */
internal suspend fun ChatRepository.fullBodyForCopy(row: MessageEntity): String =
    readDisplayBody(row, bodyStore()) ?: row.partsJson

internal fun ChatRepository.bodyStore(): BodyStore? =
    filesDir?.let { BodyStore(File(it, "bodies")) }

internal suspend fun ChatRepository.readDisplayBody(row: MessageEntity, store: BodyStore?): String? {
    val ref = row.bodyRef
    if (!ref.isNullOrBlank() && store != null) {
        val bytes = runCatching {
            store.read(ref, maxBytes = ResourceLimits.MAX_DECLARED_UNCOMPRESSED)
        }.getOrNull()
        if (bytes != null && bytes.isNotEmpty()) {
            val text = bytes.toString(Charsets.UTF_8)
            if (!DisplayParts.isStub(text)) return text
        }
    }
    // The stored column holds the placeholder for offloaded rows, so it is only
    // a source of truth for rows that were never offloaded.
    return runCatching { dao.loadRawPartsJson(row.id) }.getOrNull()
        ?.takeUnless { DisplayParts.isStub(it) }
}
