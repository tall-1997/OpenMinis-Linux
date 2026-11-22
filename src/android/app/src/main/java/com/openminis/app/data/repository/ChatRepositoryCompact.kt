package com.openminis.app.data.repository

import androidx.room.withTransaction
import com.openminis.app.data.CompactSliceBoundaries
import com.openminis.app.data.LEGACY_ORPHAN_STOP_NOTE
import com.openminis.app.data.body.ResourceLimits
import com.openminis.app.data.compactPairKey
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.db.CompactSourceRow
import com.openminis.app.data.isAttestedLegacyStopCarrier
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.pruneStaleCompactMarkers
import com.openminis.app.util.Digest
import com.openminis.app.util.SecretMasking
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.nio.charset.CharacterCodingException
import java.util.UUID

private const val MAX_COMPACT_ROWS = 192
private const val COMPACT_LOOKAHEAD_ROWS = 192
// Several maximum-size rows (e.g. call and result) must fit in one pass.
private const val MAX_COMPACT_READ_BYTES = 4 * ResourceLimits.MAX_DECLARED_UNCOMPRESSED
private val START_OF_SESSION = Int.MIN_VALUE.toLong()

internal class CompactSourceChanged(message: String) : IllegalStateException(message)

/** [T-compact-degenerate] Raised when a fold would mint a marker whose summary is byte-identical
 *  to the latest marker's — either an echo over a different range (refused), or a same-anchor
 *  journal replay (handled idempotently by the caller gate in commitCompactPrefix). */
internal class CompactDegenerateSummary(message: String) : IllegalStateException(message)

internal data class CompactDecodedRow(
    val message: LLMMessage,
    val realUser: Boolean = false,
    val completedAssistant: Boolean = false,
    val uploads: List<String> = emptyList(),
)

internal data class CompactScannedRow(
    val row: CompactSourceRow,
    val bodyBytes: Long,
    val inlineDigest: String?,
)

internal data class CompactSource(
    val sessionId: String,
    val previous: CompactMarkerEntity?,
    val start: Long,
    val previousBoundary: CompactBoundarySnapshot?,
    val scanned: List<CompactScannedRow>,
    val tailOrder: Int?,
    val firstRetiredCallRow: Int?,
    val messages: List<LLMMessage>,
    val uploadsByRow: List<List<String>>,
    val limited: Boolean,
) {
    val rows get() = scanned.take(messages.size).map { it.row }
    val previousSummary get() = previous?.summary?.takeIf(String::isNotBlank)
}

internal data class CompactBoundarySnapshot(
    val row: CompactSourceRow,
    val inlineSize: Long?,
    val inlineDigest: String?,
)

private data class PreviousBoundary(val id: String, val inclusive: Boolean)

private fun CompactMarkerEntity?.coveredBoundary(): PreviousBoundary? {
    if (this == null || summary.isBlank()) return null
    if (version < 2) {
        val firstKept = firstKeptMessageId?.takeIf(String::isNotBlank)
            ?: boundaryMessageId?.takeIf(String::isNotBlank)
        if (firstKept != null) return PreviousBoundary(firstKept, inclusive = true)
    }
    return lastCompactedMessageId?.takeIf(String::isNotBlank)?.let { PreviousBoundary(it, inclusive = false) }
}

internal data class RequestCompactState(val marker: CompactMarkerView?, val hasCoverageGap: Boolean)

/** Coverage may only be credited to the same marker the request will actually carry. */
internal suspend fun ChatRepository.requestCompactState(sessionId: String, firstResidentDbId: String?): RequestCompactState =
    checkNotNull(db).withTransaction {
        val marker = dao.latestCompactMarker(sessionId)
        val boundary = marker.coveredBoundary()
        val anchorOrder = boundary?.let { dao.compactOrderForId(sessionId, it.id) }
        val start = if (boundary != null && anchorOrder != null) {
            anchorOrder.toLong() + if (boundary.inclusive) 0L else 1L
        } else START_OF_SESSION
        val end = firstResidentDbId?.let { dao.compactOrderForId(sessionId, it)?.toLong() } ?: Long.MAX_VALUE
        RequestCompactState(marker?.toView()?.takeIf { anchorOrder != null }, dao.hasCompactSourceRows(sessionId, start, end))
    }

internal suspend fun ChatRepository.hasUncoveredCompactPrefix(sessionId: String, firstResidentDbId: String?): Boolean =
    requestCompactState(sessionId, firstResidentDbId).hasCoverageGap

internal suspend fun ChatRepository.compactOrderForId(sessionId: String, id: String): Int? =
    dao.compactOrderForId(sessionId, id)

/** Remove stale markers on cold load without confusing an evicted anchor for a deleted one. */
internal suspend fun ChatRepository.latestUsableCompactMarker(sessionId: String): CompactMarkerEntity? =
    checkNotNull(db).withTransaction {
        pruneStaleCompactMarkers(dao, sessionId)
        val marker = dao.latestCompactMarker(sessionId) ?: return@withTransaction null
        if (marker.version >= 2) {
            val id = marker.lastCompactedMessageId?.takeIf(String::isNotBlank) ?: return@withTransaction null
            if (dao.compactOrderForId(sessionId, id) == null) return@withTransaction null
        }
        marker
    }

/** Compare the previous marker's boundary row as well as the marker itself on publish. */
private suspend fun ChatRepository.resolveCompactStart(
    sessionId: String,
    previous: CompactMarkerEntity?,
): Pair<Long, CompactBoundarySnapshot?> {
    val boundary = previous.coveredBoundary()
    if (boundary == null) {
        if (previous != null && previous.version >= 2 && previous.summary.isNotBlank()) {
            throw CompactSourceChanged("The previous summary has no persisted anchor.")
        }
        // A legacy sort-order-only marker does not prove coverage. Start at
        // the oldest actual row, while still carrying its summary forward.
        return START_OF_SESSION to null
    }
    val order = dao.compactOrderForId(sessionId, boundary.id)?.toLong()
        ?: throw CompactSourceChanged("The previous summary's anchor no longer exists.")
    val row = dao.compactSourceRows(sessionId, order, order, 1).singleOrNull()
        ?.takeIf { it.id == boundary.id }
        ?: throw CompactSourceChanged("The previous summary's anchor changed.")
    val probe = if (row.bodyRef == null) dao.compactInlineProbe(sessionId, row.id) else null
    val hash = if (probe != null && probe.size in 1L..ResourceLimits.MAX_DECLARED_UNCOMPRESSED.toLong()) {
        compactInlineDigest(sessionId, row.id, probe.size.toInt(), probe.parts)
            ?: throw CompactSourceChanged("The previous summary's anchor could not be verified.")
    } else null
    return (order + if (boundary.inclusive) 0L else 1L) to CompactBoundarySnapshot(row, probe?.size, hash)
}

/**
 * Read an oldest uncovered prefix by durable order, even if none of that
 * prefix is resident. Hydrate only bounded, complete JSON bodies; do not
 * treat a broken/missing/oversized body as a preview the summary covered.
 */
internal suspend fun ChatRepository.loadCompactSource(
    sessionId: String,
    throughId: String?,
    allowTerminatedTail: Boolean = true,
    decode: (CompactSourceRow, String) -> CompactDecodedRow?,
): CompactSource = checkNotNull(db).withTransaction {
    val previous = dao.latestCompactMarker(sessionId)
    val (start, previousBoundary) = resolveCompactStart(sessionId, previous)
    val end = if (throughId == null) Long.MAX_VALUE else {
        dao.compactOrderForId(sessionId, throughId)?.toLong()
            ?: throw CompactSourceChanged("The requested compaction boundary is no longer persisted.")
    }
    val tailOrder = dao.compactTailOrder(sessionId)
    val scanLimit = MAX_COMPACT_ROWS + COMPACT_LOOKAHEAD_ROWS
    val page = dao.compactSourceRows(sessionId, start, Long.MAX_VALUE, scanLimit + 1)
    val inputLimit = page.takeWhile { it.sortOrder.toLong() <= end }.size.coerceAtMost(MAX_COMPACT_ROWS)
    val scanned = ArrayList<CompactScannedRow>()
    val decoded = ArrayList<CompactDecodedRow>()
    val store = bodyStore()
    var remainingBytes = MAX_COMPACT_READ_BYTES
    if (inputLimit > 0) for (row in page.take(scanLimit)) {
        currentCoroutineContext().ensureActive()
        val ref = row.bodyRef
        val probe = if (ref == null) dao.compactInlineProbe(sessionId, row.id) ?: break else null
        if (ref != null && row.bodySha != null && row.bodySha != ref) break
        // Inline restore can re-encode JSON and change its size (or omit body_bytes).
        // Only the actual byte length is authoritative for an inline cell.
        val size = (probe?.size ?: row.bodyBytes).takeIf {
            it in 1L..minOf(ResourceLimits.MAX_DECLARED_UNCOMPRESSED, remainingBytes).toLong()
        }?.toInt() ?: break
        val bytes = probe?.parts ?: (
            if (ref == null) readCompactInlineBody(sessionId, row.id, size) else store?.readExact(ref, size)
        ) ?: break
        if (bytes.size != size) break
        val raw = try {
            bytes.decodeToString(throwOnInvalidSequence = true)
        } catch (_: CharacterCodingException) {
            break
        }
        val message = decode(row, raw)?.takeIf { it.message.dbMessageId == row.id } ?: break
        remainingBytes -= size
        scanned += CompactScannedRow(row, size.toLong(), if (ref == null) Digest.sha256Hex(bytes) else null)
        decoded += message
    }
    val projected = projectHistoricalToolCalls(
        decoded,
        terminatedRows = scanned.map { it.row.terminalAssistant },
        allowTerminatedTail = allowTerminatedTail && scanned.lastOrNull()?.row?.sortOrder == tailOrder,
    )
    val limited = (page.getOrNull(inputLimit)?.sortOrder?.toLong()?.let { it <= end } == true) || decoded.size < inputLimit
    CompactSource(sessionId, previous, start, previousBoundary, scanned, tailOrder, projected.firstRetiredCallRow,
        projected.messages.take(inputLimit), decoded.take(inputLimit).map { it.uploads }, limited)
}

private suspend fun ChatRepository.readCompactInlineBody(sessionId: String, id: String, size: Int): ByteArray? {
    val out = ByteArray(size)
    var position = 0
    while (position < size) {
        currentCoroutineContext().ensureActive()
        val count = minOf(ResourceLimits.SQL_CELL_BYTES, size - position)
        val chunk = dao.compactInlineBodyChunk(sessionId, id, position + 1, count) ?: return null
        if (chunk.size != count) return null
        chunk.copyInto(out, position)
        position += count
    }
    return out
}

private suspend fun ChatRepository.compactInlineDigest(sessionId: String, id: String, size: Int, small: ByteArray?): String? {
    val bytes = small ?: readCompactInlineBody(sessionId, id, size)
    return bytes?.takeIf { it.size == size }?.let(Digest::sha256Hex)
}

private suspend fun ChatRepository.verifyCompactRows(
    sessionId: String,
    start: Long,
    expected: List<CompactScannedRow>,
): Boolean {
    val end = expected.last().row.sortOrder.toLong()
    if (dao.compactSourceRows(sessionId, start, end, expected.size) != expected.map { it.row }) return false
    val store = bodyStore()
    for ((row, size, inlineDigest) in expected) {
        if (row.bodyRef != null) {
            if (store?.verifyExact(row.bodyRef, size.toInt()) != true) return false
        } else {
            val probe = dao.compactInlineProbe(sessionId, row.id) ?: return false
            if (probe.size != size || inlineDigest == null ||
                compactInlineDigest(sessionId, row.id, size.toInt(), probe.parts) != inlineDigest
            ) return false
        }
    }
    return true
}

private data class CompactCall(val row: Int, val part: Int, var matched: Boolean = false) {
    val localId get() = "compact-call-$row-$part"
}

private data class HistoricalToolProjection(val messages: List<LLMMessage>, val firstRetiredCallRow: Int?)

/** Call IDs can repeat in different turns. Prefer the current turn, but keep a late result paired if no current call owns it. */
private fun projectHistoricalToolCalls(
    decoded: List<CompactDecodedRow>,
    terminatedRows: List<Boolean>,
    allowTerminatedTail: Boolean,
): HistoricalToolProjection {
    val parts = decoded.map { it.message.contentParts.toMutableList() }
    val calls = ArrayList<CompactCall>()
    val pending = HashMap<Pair<String, String>, ArrayDeque<CompactCall>>()
    val inTurn = HashMap<Pair<String, String>, ArrayDeque<CompactCall>>()
    val lastUser = decoded.indexOfLast { it.realUser }
    val terminal = if (allowTerminatedTail) decoded.indices.lastOrNull {
        terminatedRows[it] || decoded[it].completedAssistant
    } ?: -1 else -1

    fun next(queue: ArrayDeque<CompactCall>?): CompactCall? {
        if (queue == null) return null
        while (queue.firstOrNull()?.matched == true) queue.removeFirst()
        return queue.removeFirstOrNull()
    }

    decoded.forEachIndexed { rowIndex, row ->
        if (row.realUser) inTurn.clear()
        val legacyStop = row.message.isAttestedLegacyStopCarrier()
        row.message.contentParts.forEachIndexed partLoop@{ partIndex, part ->
            when {
                row.message.role == LLMMessage.Role.ASSISTANT && part is AgentContentPart.ToolUse -> {
                    val key = compactPairKey(part.id, part.name) ?: return@partLoop
                    val call = CompactCall(rowIndex, partIndex)
                    calls += call
                    pending.getOrPut(key, ::ArrayDeque).addLast(call)
                    inTurn.getOrPut(key, ::ArrayDeque).addLast(call)
                    parts[rowIndex][partIndex] = part.copy(id = call.localId)
                }
                row.message.role == LLMMessage.Role.USER && part is AgentContentPart.ToolResult -> {
                    val key = compactPairKey(part.id, part.name) ?: return@partLoop
                    val call = next(inTurn[key]) ?: next(pending[key])
                    call?.matched = true
                    if (call == null && legacyStop) {
                        parts[rowIndex][partIndex] = AgentContentPart.Text(LEGACY_ORPHAN_STOP_NOTE)
                    } else {
                        parts[rowIndex][partIndex] = part.copy(id = call?.localId ?: "compact-unmatched-$rowIndex-$partIndex")
                    }
                }
            }
        }
    }
    var firstRetired: Int? = null
    for (call in calls) {
        if (call.matched || (call.row >= lastUser && call.row > terminal)) continue
        val original = parts[call.row][call.part] as AgentContentPart.ToolUse
        // A textual observation, not a result or a source of file/read evidence.
        parts[call.row][call.part] = AgentContentPart.Text(
            "[Tool ${original.name} was requested; outcome unknown (no persisted result).]",
        )
        if (firstRetired == null) firstRetired = call.row
    }
    val messages = decoded.mapIndexed { index, row -> row.message.copy(contentParts = parts[index]) }
    return HistoricalToolProjection(messages, firstRetired)
}

/** Validate the exact covered prefix and any later retirement evidence before publishing a marker. */
internal suspend fun ChatRepository.commitCompactPrefix(
    source: CompactSource,
    coveredCount: Int,
    summary: String,
    appendChunks: (String?, String) -> String?,
    mergeAssets: (String?, List<LLMMessage>, List<List<String>>) -> String?,
): CompactMarkerEntity {
    require(coveredCount in 1..source.messages.size && summary.isNotBlank())
    // Use one redacted derivative for the marker and its new chunk. The caller
    // also uses the committed summary for sediment, not the raw model answer.
    val redactedSummary = SecretMasking.mask(summary)
    require(redactedSummary.isNotBlank())
    require(CompactSliceBoundaries(source.messages, protectPending = true).isClosed(coveredCount)) {
        "Compaction cannot cover a partial tool round."
    }
    // Retirement can depend on rows beyond the submitted prefix. Recheck
    // that evidence, and fail if a new tail could supply a late result.
    val usedRetirement = source.firstRetiredCallRow?.let { it < coveredCount } == true
    val expected = if (usedRetirement) source.scanned else source.scanned.take(coveredCount)
    // [P3-4] ALL digest hashing runs BEFORE the write transaction: the body
    // re-verification below reads up to MAX_COMPACT_READ_BYTES, and the
    // previous anchor's inline digest is re-checked here too. Hashing must
    // never hold the DB write lock. The transaction re-checks only row
    // identity/set/ordering; anything that changed after this pre-check
    // still fails the commit with the same rejection semantics.
    val boundary = resolveCompactStart(source.sessionId, source.previous)
    if (boundary != (source.start to source.previousBoundary)) {
        throw CompactSourceChanged("The previous compact marker or its boundary changed. Retry compaction.")
    }
    if (!verifyCompactRows(source.sessionId, source.start, expected)) {
        throw CompactSourceChanged("Conversation rows changed while summarizing. Retry compaction.")
    }
    return checkNotNull(db).withTransaction {
        val previous = dao.latestCompactMarker(source.sessionId)
        val snapshot = source.previousBoundary
        val boundaryRowUnchanged = snapshot == null ||
            (dao.compactOrderForId(source.sessionId, snapshot.row.id)?.toLong() == snapshot.row.sortOrder.toLong() &&
                dao.compactSourceRows(source.sessionId, snapshot.row.sortOrder.toLong(), snapshot.row.sortOrder.toLong(), 1)
                    .singleOrNull()?.takeIf { it.id == snapshot.row.id } == snapshot.row)
        if (previous != source.previous || !boundaryRowUnchanged) {
            throw CompactSourceChanged("The previous compact marker or its boundary changed. Retry compaction.")
        }
        val anchor = expected.last().row
        // [T-compact-degenerate] Idempotency / degeneracy gate. A redacted summary
        // byte-identical to the latest marker's is either a journal replay (same
        // anchor — return the existing row instead of minting a duplicate marker
        // with a fresh uuid/timestamp) or an echo over DIFFERENT rows (new anchor
        // — those rows would be hidden under text that never represented them;
        // DB-proven failure mode). Same-anchor replays are idempotent; anything
        // else refuses to mint.
        if (previous != null && previous.summary == redactedSummary) {
            if (previous.lastCompactedMessageId == anchor.id) return@withTransaction previous
            throw CompactDegenerateSummary(
                "Compaction echoed the previous marker's summary over a different range",
            )
        }
        if ((usedRetirement && dao.compactTailOrder(source.sessionId) != source.tailOrder) ||
            dao.compactSourceRows(source.sessionId, source.start, anchor.sortOrder.toLong(), expected.size) !=
            expected.map { it.row }
        ) {
            throw CompactSourceChanged("Conversation rows changed while summarizing. Retry compaction.")
        }
        reduceCompactMarker(source, coveredCount, redactedSummary, appendChunks, mergeAssets).also {
            dao.insertCompactMarker(it)
        }
    }
}

private fun reduceCompactMarker(
    source: CompactSource,
    coveredCount: Int,
    summary: String,
    appendChunks: (String?, String) -> String?,
    mergeAssets: (String?, List<LLMMessage>, List<List<String>>) -> String?,
): CompactMarkerEntity {
    val previous = source.previous?.takeIf { source.previousSummary != null }
    val anchor = source.rows[coveredCount - 1]
    val now = System.currentTimeMillis()
    val priorTime = source.previous?.createdAt ?: 0L
    return CompactMarkerEntity(
        id = UUID.randomUUID().toString(),
        sessionId = source.sessionId,
        summary = summary,
        firstKeptSortOrder = Int.MAX_VALUE,
        compactedCount = coveredCount,
        createdAt = maxOf(now, Math.addExact(priorTime, 1L)),
        lastCompactedMessageId = anchor.id,
        version = 2,
        summaryChunks = appendChunks(previous?.summaryChunks, summary),
        assetsJson = mergeAssets(previous?.assetsJson, source.messages.take(coveredCount), source.uploadsByRow.take(coveredCount)),
    )
}
