package com.openminis.app.ui.chat

import android.util.Log
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.db.CompactMarkerEntity

/**
 * Phase 2.5 marker restore (Android port of iOS
 * AIChatViewModel+Persistence.swift:236+).
 *
 * Resolution order (mirrors iOS exactly):
 *   1. v2 marker (`version >= 2`) — use `lastCompactedMessageId`
 *      via sourceDbIds range → divider AFTER that UI row
 *   2. v1 compactAll-shape (firstKept/boundary both null,
 *      lcmId set) — same as 1
 *   3. v1 compactBefore (firstKeptMessageId / boundaryMessageId
 *      set) — divider BEFORE that boundary row
 *   4. **createdAt self-heal** — find the last raw with
 *      `createdAt < marker.createdAt` whose id is still in
 *      agentHistory, use it as the new anchor, REWRITE the
 *      marker as v2 + write back to DB. Next load takes the
 *      v2 fast path (no heal needed).
 *   5. Final fallback — insert divider at idx=0, gray NOTHING.
 *      This deliberately differs from the pre-T-compact-v2
 *      behaviour of "divider at bottom, gray everything" which
 *      grayed newly-sent messages on every reload (the
 *      user-reported "divider at top, new messages keep
 *      turning gray" symptom).
 *
 * Suspending because the self-heal path writes back through
 * the DAO. Caller (loadSession) is already on a coroutine.
 */
internal suspend fun ChatViewModel.applyCompactMarkerGraying(
    messages: List<ChatMessage>,
    marker: com.openminis.app.data.db.CompactMarkerEntity,
    rawMessages: List<com.openminis.app.data.db.MessageEntity>,
    historyDbIds: Set<String>,
): List<ChatMessage> {
    // Some legacy rows have empty-string boundaries instead of NULL —
    // treat both as "no boundary" so the compactAll path below kicks in.
    val firstKeptId = (marker.firstKeptMessageId?.takeIf { it.isNotEmpty() })
        ?: (marker.boundaryMessageId?.takeIf { it.isNotEmpty() })
    val lcmId = marker.lastCompactedMessageId?.takeIf { it.isNotEmpty() }

    // ─── Resolve insertIdx ────────────────────────────────────────
    //
    // insertIdx semantics: messages[0 until insertIdx] become grayed
    // (isCompactedHistory=true); the divider sits at insertIdx;
    // messages[insertIdx..] stay active.
    //
    // Special value -1 → "unresolved": skip the rewrite below and
    // return the messages untouched with no divider (the marker is
    // effectively invisible until the user reverts or self-heals).
    // Used when even createdAt fallback fails — better to show no
    // divider than to incorrectly gray live messages.
    var insertIdx = -1
    var healedMarker: com.openminis.app.data.db.CompactMarkerEntity? = null

    // Helper: locate the UI message whose sourceDbIds (or id) contains
    // the given dbId. Matches iOS uiIndexForAnchorRaw, which scans by
    // sourceSortOrder range; Android's equivalent is sourceDbIds.
    fun uiIdxForDbId(dbId: String): Int =
        messages.indexOfLast { msg -> dbId in msg.sourceDbIds || msg.id == dbId }

    if (firstKeptId == null) {
        // v2 OR v1 compactAll-shape — anchored by lcmId.
        val lcmIdx = lcmId?.let { uiIdxForDbId(it) } ?: -1
        if (lcmIdx >= 0) {
            // Happy path: lcmId resolves directly. Divider AFTER anchor.
            insertIdx = lcmIdx + 1
        } else {
            // lcmId missing or orphaned. Try createdAt self-heal.
            val heal = anchorByCreatedAt(rawMessages, marker.createdAt, historyDbIds)
            val healUiIdx = heal?.let { uiIdxForDbId(it.id) } ?: -1
            if (heal != null && healUiIdx >= 0) {
                insertIdx = healUiIdx + 1
                healedMarker = rewriteMarkerForHeal(marker, heal, rawMessages.lastOrNull())
                AppLogger.warning(
                    ChatViewModel.TAG,
                    "[Compact] Phase2.5 self-heal: orphaned lcmId=${lcmId?.take(8) ?: "nil"} " +
                        "→ newAnchor=${heal.id.take(8)} (createdAt=${heal.createdAt}) " +
                        "→ uiIdx=$healUiIdx insertIdx=$insertIdx",
                )
            } else {
                // Even createdAt heal failed. Place divider at top
                // with NO graying — this is iOS's "insertIdx=0, no
                // gray" branch (Persistence.swift:350-351). The
                // pre-T-compact-v2 behaviour of "cutoff = lastIndex,
                // gray everything" produced the user-reported bug:
                // every new message also fell within [0..cutoff]
                // and was repeatedly grayed on each reload.
                insertIdx = 0
                AppLogger.warning(
                    ChatViewModel.TAG,
                    "[Compact] Phase2.5 unresolved (heal failed): marker.id=${marker.id.take(8)} " +
                        "lcmId=${lcmId?.take(8) ?: "nil"} — divider at top, no graying",
                )
            }
        }
    } else {
        // v1 compactBefore — anchored by firstKeptId. Divider BEFORE
        // the boundary; boundary is the first active message.
        val bIdx = messages.indexOfFirst { msg ->
            firstKeptId in msg.sourceDbIds || msg.id == firstKeptId
        }
        if (bIdx >= 0) {
            insertIdx = bIdx
        } else {
            // Boundary deleted / orphaned. Try createdAt self-heal —
            // same path as compactAll, then divider AFTER the healed
            // anchor (treating this as an upgrade to v2 compactAll
            // semantics).
            val heal = anchorByCreatedAt(rawMessages, marker.createdAt, historyDbIds)
            val healUiIdx = heal?.let { uiIdxForDbId(it.id) } ?: -1
            if (heal != null && healUiIdx >= 0) {
                insertIdx = healUiIdx + 1
                healedMarker = rewriteMarkerForHeal(marker, heal, rawMessages.lastOrNull())
                AppLogger.warning(
                    ChatViewModel.TAG,
                    "[Compact] Phase2.5 v1→v2 heal: firstKeptId=${firstKeptId.take(8)} orphaned " +
                        "→ newAnchor=${heal.id.take(8)} → uiIdx=$healUiIdx",
                )
            } else {
                insertIdx = 0
                AppLogger.warning(
                    ChatViewModel.TAG,
                    "[Compact] Phase2.5 v1 unresolved (heal failed): firstKeptId=${firstKeptId.take(8)} — " +
                        "divider at top, no graying",
                )
            }
        }
    }

    // ─── Persist healed marker (if any) ───────────────────────────
    //
    // Run BEFORE building the UI list so a future loadSession() picks
    // up the v2 fast path. Failure here is non-fatal — UI still
    // renders against the in-memory healed pointer.
    if (healedMarker != null) {
        runCatching { chatRepository.dao.updateCompactMarker(healedMarker) }
            .onFailure { Log.w(ChatViewModel.TAG, "updateCompactMarker (self-heal) failed: ${it.message}") }
        // Refresh in-memory cache so effectiveAgentHistory and the
        // next compact pass see the upgraded marker. The caller
        // (loadSession) sets _cachedLatestMarker = marker BEFORE
        // calling us, so overwrite with the healed one now.
        _cachedLatestMarker = healedMarker
        _compactSummary.value = healedMarker.summary
    }

    // ─── Apply graying ────────────────────────────────────────────
    val grayed: List<ChatMessage> = if (insertIdx <= 0) {
        // No graying — either explicit no-gray branch or boundary at
        // index 0 (nothing to gray).
        messages
    } else {
        messages.mapIndexed { idx, msg ->
            if (idx >= insertIdx) msg
            else if (msg.role == "system") msg
            else if (msg.isCompactedHistory) msg
            else msg.copy(isCompactedHistory = true)
        }
    }

    // ─── Insert divider row ───────────────────────────────────────
    // T126-marker: match iOS `"\(insertIdx) messages compacted"`
    // (AIChatViewModel.swift:3432). Count = number of UI bubbles
    // above the divider, not marker.compactedCount (which counts raw
    // agentHistory entries — tool_use/tool_result pairs that never
    // appear as their own UI bubble).
    val compactedUICount = (0 until insertIdx.coerceIn(0, grayed.size))
        .count { grayed[it].role != "system" }
    val dividerLabel = "$compactedUICount messages compacted"
    val markerForDivider = healedMarker ?: marker
    val dividerBlock = AssistantBlock(
        id = "compact-divider-${markerForDivider.id}",
        kind = "info",
        content = dividerLabel,
        toolName = "compact",
        toolArgs = markerForDivider.summary,
    )
    val dividerMsg = ChatMessage(
        id = "compact-divider-msg-${markerForDivider.id}",
        role = "system",
        content = "",
        toolBlocks = listOf(dividerBlock),
    )
    val withDivider = grayed.toMutableList()
    withDivider.add(insertIdx.coerceIn(0, withDivider.size), dividerMsg)
    return withDivider
}
