package com.openminis.app.data.repository

import com.openminis.app.logging.AppLogger

/**
 * [T-m6-pair-removal] extension slice of [ChatRepository]: pair-repair
 * truncation logic that outgrew ChatRepository.kt (architecture ceiling).
 *
 * A tool step spans two rows — the assistant tool_use row and the user
 * toolResult carrier.  A truncation landing between them orphans the
 * surviving half; replaying a dangling tool_use feeds the model a tool
 * call without its result and some providers 400 on that.  The doomed
 * side must be snapshotted BEFORE [ChatRepository.deleteMessagesAfter]
 * removes it, so [deleteAfterWithPairRepair] owns the whole sequence:
 * snapshot → plan ([PairRemoval.plan], pure) → delete → apply plan.
 */
internal const val PAIR_REMOVAL_TAIL_SIZE = 100

/**
 * [T-m6-pair-removal] Delete one row by id, archiving it first — the
 * single-row counterpart of the truncation path's snapshot pass.  Only
 * pair removal empties rows AFTER a truncation ran, so the row does not
 * need a re-cut: archive + delete, aborting when archiving fails.
 */
suspend fun ChatRepository.deleteMessageById(id: String) {
    val vdao = versionDao ?: run {
        dao.deleteMessageById(id)
        return
    }
    val row = runCatching { dao.messageById(id) }.getOrNull() ?: return
    runCatching {
        vdao.insertAll(listOf(row.toVersionEntity(vdao, System.currentTimeMillis())))
    }.onFailure {
        AppLogger.error(
            "ChatRepository",
            "[T-m6-pair-removal] archiving the emptied row failed; delete ABORTED: ${it.message}",
        )
        throw it
    }
    dao.deleteMessageById(id)
}

/**
 * [T-m6-pair-removal] Truncate everything from [cutoffSortOrder] on,
 * repairing orphaned tool_use/tool_result pairs in the same pass.
 *
 * The plan is only applied when the delete reports success — an archive
 * failure aborts the whole operation and no kept row is touched, so a
 * failed truncation still leaves the session intact.
 *
 * [keptOverrides] rows the caller has already rewritten in memory and
 * will not rewrite again — e.g. `rerunFromToolBlock` trims an assistant
 * row in place.  They replace the DB snapshot for their row id: the
 * repository persists their final parts itself (with the plan's trims
 * applied), so the caller never double-writes stale parts back over a
 * repaired row.
 *
 * Both windows are bounded to [PAIR_REMOVAL_TAIL_SIZE]: a surviving
 * part's dead counterpart is always the immediately adjacent row, so
 * only rows within one tail window of the cut can participate.
 *
 * @return number of orphaned parts dropped + emptied rows removed,
 * for logging; 0 also when the delete was aborted.
 */
suspend fun ChatRepository.deleteAfterWithPairRepair(
    sessionId: String,
    cutoffSortOrder: Int,
    keptOverrides: List<Pair<String, String>> = emptyList(),
): Int {
    val doomedTail = dao.firstRowsFrom(sessionId, cutoffSortOrder, PAIR_REMOVAL_TAIL_SIZE)
    val overrideIds = keptOverrides.mapTo(mutableSetOf()) { it.first }
    val keptTail = dao.loadMessagesFromSortOrder(
        sessionId = sessionId,
        startInclusive = (cutoffSortOrder - PAIR_REMOVAL_TAIL_SIZE).coerceAtLeast(0),
        endExclusive = cutoffSortOrder,
        limit = PAIR_REMOVAL_TAIL_SIZE,
    ).filter { it.id !in overrideIds }
    val keptPairs: MutableList<Pair<String, String>> = mutableListOf()
    keptTail.forEach { keptPairs += it.id to it.partsJson }
    keptPairs += keptOverrides
    val plan = PairRemoval.plan(
        kept = keptPairs,
        deleted = doomedTail.map { it.id to it.partsJson },
    )
    if (!deleteMessagesAfter(sessionId, cutoffSortOrder)) return 0
    if (plan.isEmpty) {
        for ((rowId, json) in keptOverrides) {
            updateMessageParts(rowId, json)
        }
        return 0
    }

    for (trim in plan.updateParts) {
        if (trim.rowId in overrideIds) continue
        updateMessageParts(trim.rowId, trim.partsJson)
    }
    for ((rowId, json) in keptOverrides) {
        if (rowId in plan.removeIds) continue
        val repaired = plan.updateParts.firstOrNull { it.rowId == rowId }
        updateMessageParts(rowId, repaired?.partsJson ?: json)
    }
    for (rowId in plan.removeIds) {
        runCatching { deleteMessageById(rowId) }
            .onFailure {
                AppLogger.error(
                    "ChatRepository",
                    "[T-m6-pair-removal] deleting emptied row $rowId failed: ${it.message}",
                )
            }
    }
    return plan.updateParts.size + plan.removeIds.size
}