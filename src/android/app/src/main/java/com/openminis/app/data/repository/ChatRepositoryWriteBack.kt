package com.openminis.app.data.repository

import androidx.room.withTransaction
import com.openminis.app.data.db.MessageEntity
import org.json.JSONObject
import java.util.UUID

/**
 * [T-pairing-writeback] One interrupted call's persisted result: the DB row id
 * of the assistant message owning the use, the use id (idempotence key), and
 * the pre-built neutral result partsJson (built on the ui side — data never
 * imports ui).
 */
data class PairingWriteBackRow(
    val assistantRowId: String,
    val toolUseId: String,
    val partsJson: String,
)

/**
 * [T-pairing-writeback] A tool-use id already present in a persisted result
 * row — matched as an EXACT serialized `"id":<json-string>` token, never a
 * bare substring (an id embedded in another row's input/output text must not
 * count; an id that contains another as a prefix must not hide it).
 */
private val RESULT_ID_TOKEN = Regex("\"toolUseId\"\\s*:\\s*\"")

internal fun partsJsonHasUseId(partsJson: String, toolUseId: String): Boolean =
    RESULT_ID_TOKEN.findAll(partsJson).any { match ->
        // match.range.last is the OPENING quote (the regex ends at it) —
        // scanning starts right after it, at the token's first character.
        var end = match.range.last + 1
        while (end < partsJson.length) {
            when (partsJson[end]) {
                '\\' -> end += 1
                '"' -> return@any partsJson.substring(match.range.last + 1, end) == toolUseId
            }
            end += 1
        }
        false
    }

/**
 * [T-pairing-writeback] Persist one neutral result row per interrupted tool
 * call, immediately after its assistant row (local renumber via
 * [ChatPairingDao.shiftSortOrdersAfter]). After this the request projection's
 * pairing repair finds the call already matched and stops injecting a
 * synthetic user turn on every request.
 *
 * Idempotence is an EXACT-token scan ([partsJsonHasUseId]) over the session's
 * persisted result rows — re-run before every shift so a row persisted since
 * the initial snapshot (a concurrent write-back, or the turn-persist path
 * landing its own result mid-loop) is never duplicated and never shifted past.
 * The whole loop is per-row guarded: a single-row failure skips only that row.
 *
 * Extracted from [ChatRepository] to keep that file under its line ceiling;
 * a member method would read the same at every call site.
 */
suspend fun ChatRepository.writeBackInterruptedResults(sessionId: String, rows: List<PairingWriteBackRow>): Int {
    if (rows.isEmpty()) return 0
    val dbRef = db ?: return 0
    var written = 0
    for (row in rows) {
        runCatching {
            dbRef.withTransaction {
                val pairingDao = dbRef.pairingDao()
                // Re-scan inside the transaction: the initial snapshot can be
                // stale (turn-persist path or a sibling write-back landed its
                // own result row since) — never duplicate, never shift past.
                val alreadyPersisted = pairingDao.resultRowsJson(sessionId).any { json ->
                    partsJsonHasUseId(json, row.toolUseId)
                }
                if (alreadyPersisted) return@withTransaction
                val useSort = pairingDao.sortOrderOf(row.assistantRowId) ?: return@withTransaction
                pairingDao.shiftSortOrdersAfter(sessionId, useSort)
                dao.insertMessage(
                    MessageEntity(
                        id = UUID.randomUUID().toString(),
                        sessionId = sessionId,
                        role = "user",
                        partsJson = row.partsJson,
                        createdAt = System.currentTimeMillis(),
                        sortOrder = useSort + 1,
                        preview = PreviewText.extractTextPreview(row.partsJson),
                    ),
                )
                written += 1
            }
        }
    }
    return written
}