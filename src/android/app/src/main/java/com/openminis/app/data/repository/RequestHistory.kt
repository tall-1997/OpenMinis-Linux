package com.openminis.app.data.repository

import androidx.room.withTransaction
import com.openminis.app.data.ContextTokenMeter
import com.openminis.app.data.body.ResourceLimits
import com.openminis.app.data.compactPairKey
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.display.DisplayParts
import com.openminis.app.data.isLegacyStopCarrier
import com.openminis.app.provider.ToolPairing
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONTokener
import java.nio.charset.CharacterCodingException

// Serialized UTF-8 bytes, including reasoning. Two maximum-size bodies can
// fit; the VM still applies its own decoded-context budget afterwards.
internal const val REQUEST_HISTORY_MAX_BYTES = 2L * ResourceLimits.MAX_DECLARED_UNCOMPRESSED
private const val REQUEST_HISTORY_PAGE_SIZE = 32
private val REQUEST_BODY_SIZES = 1L..ResourceLimits.MAX_DECLARED_UNCOMPRESSED.toLong()

internal class RequestHistoryUnavailable(message: String) : IllegalStateException(message)

internal suspend fun ChatRepository.readRequestHistoryTail(
    sessionId: String,
    limit: Int,
    maxTokens: Int? = null,
): ChatRepository.SessionTail {
    require(limit >=0)
    require(maxTokens == null || maxTokens >=0)
    // The count, keyset pages, and chunks of an editable inline body must
    // agree on one snapshot. Production repositories always own a Room DB.
    return db?.withTransaction { readRequestSuffix(sessionId, limit, maxTokens) }
        ?: readRequestSuffix(sessionId, limit, maxTokens)
}

private suspend fun ChatRepository.readRequestSuffix(
    sessionId: String,
    limit: Int,
    maxTokens: Int?,
): ChatRepository.SessionTail {
    val total = dao.messageCountForSession(sessionId)
    val target = minOf(total, limit)
    val newest = ArrayList<RequestEntry>()
    val store = bodyStore()
    var before = Long.MAX_VALUE
    var remaining = REQUEST_HISTORY_MAX_BYTES
    var remainingTokens = maxTokens ?: Int.MAX_VALUE
    scan@ while (newest.size < target) {
        val page = dao.loadRequestHistoryRows(sessionId, before, minOf(REQUEST_HISTORY_PAGE_SIZE, target - newest.size))
        if (page.isEmpty()) throw RequestHistoryUnavailable("Persisted request history changed while loading.")
        for (meta in page) {
            currentCoroutineContext().ensureActive()
            val row = meta.row
            val ref = row.bodyRef
            val size = (if (ref == null) meta.inlineSize else row.bodyBytes)
                ?.takeIf { it in REQUEST_BODY_SIZES } ?: row.unavailable("invalid body size")
            val reasoningSize = meta.reasoningSize ?:0L
            if (reasoningSize > ResourceLimits.MAX_DECLARED_UNCOMPRESSED) row.unavailable("invalid reasoning size")
            val bytes = size + reasoningSize
            if (bytes > remaining) break@scan

            val raw = if (ref != null) {
                if (row.bodySha != null && row.bodySha != ref) row.unavailable("body digest does not match its reference")
                store?.readExact(ref, size.toInt())?.let { row.decode(it) }
                    ?: row.unavailable("offloaded body could not be verified")
            } else {
                val inline = meta.inlineParts ?: row.readChunks(size.toInt()) { offset, count ->
                    dao.compactInlineBodyChunk(sessionId, row.id, offset, count)
                }
                row.decode(inline)
            }
            val reasoning = if (reasoningSize > ResourceLimits.INLINE_BODY_BYTES) {
                row.decode(row.readChunks(reasoningSize.toInt()) { offset, count ->
                    dao.loadRequestReasoningChunk(sessionId, row.id, offset, count)
                })
            } else row.reasoningContent
            val rowTokens = if (maxTokens == null)0 else ContextTokenMeter.rawTextTokens(raw) +
                ContextTokenMeter.rawTextTokens(reasoning.orEmpty())
            if (maxTokens != null && newest.isNotEmpty() && rowTokens > remainingTokens) break@scan
            newest += row.copy(partsJson = raw, reasoningContent = reasoning).requestEntry()
            remaining -= bytes
            if (maxTokens != null) {
                remainingTokens = (remainingTokens - rowTokens).coerceAtLeast(0)
                if (remainingTokens ==0) break@scan
            }
            before = row.sortOrder.toLong()
        }
    }
    val chronological = newest.asReversed()
    // The omitted rows are a prefix, not covered history. The request gate
    // independently checks coverage up to the first returned durable ID.
    val first = if (chronological.isNotEmpty() && newest.size < total) {
        val head = chronological.first()
        val legacyHead = head.legacyStop && dao.requestLegacyKeepId(sessionId) == head.row.id
        requestPairBoundary(chronological, legacyHead)
    } else 0
    if (target > 0 && first == chronological.size) {
        throw RequestHistoryUnavailable("No complete request history fits the limit. A tool round crosses the retained boundary.")
    }
    val rows = chronological.subList(first, chronological.size).map { it.row }
    return ChatRepository.SessionTail(rows, total, total - rows.size)
}

private suspend fun MessageEntity.readChunks(size: Int, chunk: suspend (Int, Int) -> ByteArray?): ByteArray {
    val bytes = ByteArray(size)
    var offset = 0
    while (offset < size) {
        currentCoroutineContext().ensureActive()
        val count = minOf(ResourceLimits.SQL_CELL_BYTES, size - offset)
        val block = chunk(offset + 1, count)?.takeIf { it.size == count } ?: unavailable("incomplete inline cell")
        block.copyInto(bytes, offset)
        offset += count
    }
    return bytes
}

private fun MessageEntity.decode(bytes: ByteArray): String = try {
    bytes.decodeToString(throwOnInvalidSequence = true)
} catch (_: CharacterCodingException) {
    unavailable("invalid UTF-8")
}

private fun MessageEntity.unavailable(reason: String): Nothing =
    throw RequestHistoryUnavailable("Cannot restore persisted request message $id: $reason.")

private data class RequestToolPart(val result: Boolean, val id: String, val name: String)
private data class RequestEntry(val row: MessageEntity, val tools: List<RequestToolPart>, val legacyStop: Boolean)

private fun MessageEntity.requestEntry(): RequestEntry = try {
    val input = JSONTokener(partsJson)
    val parts = input.nextValue() as? JSONArray ?: unavailable("expected a parts array")
    if (input.nextClean() != '\u0000') unavailable("unexpected data after the parts array")
    val tools = ArrayList<RequestToolPart>()
    for (index in 0 until parts.length()) {
        val part = parts.getJSONObject(index)
        when (part.opt("type") as? String ?: unavailable("missing part type")) {
            "text" -> {
                val text = (part.opt("value") ?: part.opt("text")) as? String ?: unavailable("missing text")
                if (parts.length() == 1 && (text.startsWith(DisplayParts.STUB_PREFIX) ||
                        text.startsWith(DisplayParts.OMITTED_PREFIX) || text == "[body kept on disk]")) {
                    unavailable("stored body placeholder")
                }
            }
            "toolUse", "toolResult" -> {
                val value = part.getJSONObject("value")
                tools += RequestToolPart(part.optString("type") == "toolResult",
                    value.optString("toolUseId", ""), value.optString("name", ""))
            }
        }
    }
    RequestEntry(this, tools, role == "user" && parts.isLegacyStopCarrier())
} catch (_: JSONException) {
    unavailable("malformed parts JSON")
} catch (_: StackOverflowError) {
    unavailable("parts JSON is too deeply nested")
}

/** If a result has no preceding call in the candidate suffix, its call may be
 * in the omitted prefix. Move the boundary past that result and past any
 * other local pairs it crosses (including parallel calls in the same row).
 */
private fun requestPairBoundary(rows: List<RequestEntry>, legacyHead: Boolean): Int {
    val crossing = IntArray(rows.size + 1)
    var earliest = 0
    // Run both the wire match and the durable compaction match (canonical aliases).
    for (canonical in listOf(false, true)) {
        val pending = HashMap<Pair<String, String>, ArrayDeque<Int>>()
        rows.forEachIndexed { index, entry ->
            for (tool in entry.tools) {
                val key = if (canonical) compactPairKey(tool.id, tool.name) else ToolPairing.key(tool.id) to tool.name
                if (!tool.result) {
                    if (key != null) pending.getOrPut(key, ::ArrayDeque).addLast(index)
                } else {
                    val uses = key?.let { pending[it] }
                    if (uses == null) {
                        // Only a pre-existing v1 first-kept Stop is already split
                        // by a durable marker; the wire renders its orphans as a note.
                        if (index != 0 || !legacyHead) earliest = maxOf(earliest, index + 1)
                    } else {
                        val use = uses.removeFirst()
                        if (uses.isEmpty()) pending.remove(key)
                        crossing[use + 1]++
                        crossing[index + 1]--
                    }
                }
            }
        }
    }
    var open = 0
    for (index in rows.indices) {
        open += crossing[index]
        if (index >= earliest && open == 0) return index
    }
    return rows.size
}
