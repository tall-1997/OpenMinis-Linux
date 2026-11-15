package com.openminis.app.ui.chat

import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import com.openminis.app.data.ThinkingDormancy
import com.openminis.app.logging.AppLogger
import com.openminis.app.data.model.ThinkingLevel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * [T-thinking-ceiling-sync] Invariant: the session level never exceeds the
 * ACTIVE model's ceiling. The model-parameter page writes
 * `overrides.maxThinkingLevel` / `supportsReasoning` (the ceiling), the
 * session stores its own level (`sessions.thinking_override`) — two stores,
 * no sync, and a ceiling lowered (or the model's Thinking switch turned off)
 * left the session at a level the next request silently clamped anyway. The
 * provider clamp honours overrides (#348), so this flow makes the SESSION
 * state honour them too: re-clamp on every active-entry / config change,
 * force-thinking lift included. Lives in its own file because the VM sits at
 * its architecture ceiling — this is the one feature that pays for itself.
 *
 * [T-thinking-dormant-restore] (P2 1-8) The demotion half of that sync used
 * to destroy the user's choice: the clamped level was persisted over the
 * original and a later ceiling raise left the low value in place. The park /
 * restore decision now lives in the pure [ThinkingDormancy.sync] (JVM-tested),
 * and the park itself persists in `sessions.dormant_thinking_override` so it
 * survives a process restart, not just this VM instance.
 */

/** Toggle thinking between OFF and MEDIUM (matches iOS default toggle semantics).
 *  Moved from the VM body (architecture ceiling) — same package, single call
 *  site ([ChatViewModelSlashExec]) resolves the extension without imports. */
internal fun ChatViewModel.toggleThinking() {
    if (!currentModelSupportsReasoning) {
        appendSystemInfo(
            text = context.getString(com.openminis.app.R.string.vm_thinking_unsupported),
            iconKind = "thinking",
        )
        return
    }
    val newLevel = if (_thinkingLevel.value.isEnabled) ThinkingLevel.OFF else ThinkingLevel.MEDIUM
    // An explicit toggle drops any level parked by ceiling-sync — the newer
    // user decision must win when the ceiling later widens. [persistThinkingOverride]
    // keeps the persisted park column in lockstep with the cleared slot.
    dormantThinkingLevel = null
    _thinkingLevel.value = newLevel; persistThinkingOverride(newLevel)
    appendSystemInfo(
        text = context.getString(com.openminis.app.R.string.vm_thinking_set_to, newLevel.localizedName(context)),
        iconKind = "thinking",
    )
}

/**
 * [T-android-thinking-level-arch] Set thinking level explicitly — the member
 * override in ChatViewModel.kt delegates here (architecture ceiling; same
 * split #372 applied to toggleThinking). Used by the inline level picker in
 * the `/thinking` slash row. Mirrors iOS `setThinkingLevel(_:)` — silently
 * ignored when the current model doesn't support reasoning.
 */
internal fun ChatViewModel.setThinkingLevelInternal(level: ThinkingLevel) {
    if (!currentModelSupportsReasoning) return
    // [T-android-thinking-level-arch] Double-safety clamp: the composer UI
    // already filters to availableThinkingLevels, but never fully trust the
    // caller — cap to the current model's ceiling so a stale/over-range
    // request can't persist a level the model can't reach.
    val ceiling = currentModelMaxThinkingLevel
    val clamped = if (level.rank > ceiling.rank) ceiling else level
    // An explicit pick (even re-picking the same level) drops any level parked
    // by ceiling-sync — see [ChatViewModel.dormantThinkingLevel]. [persistThinkingOverride]
    // writes both columns, so a re-pick that changes nothing still clears the
    // persisted park; without that, a reload would resurrect a park the user
    // has already superseded.
    dormantThinkingLevel = null
    if (_thinkingLevel.value != clamped) _thinkingLevel.value = clamped
    persistThinkingOverride(clamped)
}

internal fun ChatViewModel.launchThinkingCeilingSync(): kotlinx.coroutines.Job =
    viewModelScope.launch {
        combine(
            _activeEntryId,
            providerRepository.config,
            sessionLoaded,
        ) { entryId: String?, config: com.openminis.app.data.model.ProviderConfig, loaded: Boolean ->
            // [T-thinking-ceiling-sync-gate] Wait for the session row (and its
            // persisted thinking_override) to be restored before clamping —
            // otherwise the first config emission during loadSession would
            // clamp the not-yet-restored (OFF) value. Not harmful today only
            // because OFF is the rank floor; the gate removes the ordering
            // coincidence the T306 collector had to document around.
            if (!loaded) return@combine

            val entry = entryId?.let { id -> config.modelEntries.find { it.id == id } }
            if (entry != null) {
                val ceiling = forcedThinkingCeiling(entry)
                // [T-thinking-dormant-restore] The whole demote/park/restore
                // decision is the pure [ThinkingDormancy.sync] — same outcome
                // as the old inline ladder, with the park now fed back into
                // the slot instead of being lost.
                val decision = ThinkingDormancy.sync(
                    current = _thinkingLevel.value,
                    ceiling = ceiling,
                    dormant = dormantThinkingLevel,
                )
                if (decision.changed) {
                    dormantThinkingLevel = decision.dormant
                    _thinkingLevel.value = decision.level
                    persistThinkingOverride(decision.level)
                    // [T-thinking-sync-notice] (P2-8) A silent clamp/restore looked
                    // like the user's pick had vanished. Surface both directions
                    // like [toggleThinking] does: demotion says the ceiling forced
                    // it (and the choice is parked), restore says it came back.
                    // appendSystemInfo only mutates the messages StateFlow (no
                    // view dependency), so the flow is safe to fire before any
                    // UI is mounted.
                    appendSystemInfo(
                        text = context.getString(
                            if (thinkingSyncNotice(decision) == ThinkingSyncNotice.CLAMPED) {
                                com.openminis.app.R.string.vm_thinking_clamped_to
                            } else {
                                com.openminis.app.R.string.vm_thinking_ceiling_restored
                            },
                            decision.level.localizedName(context),
                        ),
                        iconKind = "thinking",
                    )
                }
            }
        }.collect()
    }


/**
 * [T-thinking-sync-notice] (P2-8) Which ceiling-sync move a
 * [ThinkingDormancy.sync] result is, for the collector's localized notice.
 * Pure and extracted so both branches are JVM-asserted (see
 * [ThinkingSyncNoticeTest]); the two only appear inside a `changed`
 * decision: demote parks the old choice (dormant != null), restore clears
 * the park (dormant == null).
 */
internal enum class ThinkingSyncNotice { CLAMPED, RESTORED }

internal fun thinkingSyncNotice(result: ThinkingDormancy.SyncResult): ThinkingSyncNotice =
    if (result.dormant != null) ThinkingSyncNotice.CLAMPED else ThinkingSyncNotice.RESTORED

/**
 * [T-thinking-ceiling-sync] Persist the (possibly clamped) level to the
 * session row. Ceiling-sync triggers this on every config emission where the
 * decision changed — an unguarded DB failure (disk full) would crash the
 * process from an automatic path; the in-memory level is already applied and
 * persistence self-heals on the next emission.
 *
 * [T-thinking-dormant-restore] Both columns persist from the CURRENT VM
 * state in one shot: every caller that mutates the slot does so immediately
 * before calling this (user pick / toggle / group default clear it, the
 * ceiling-sync demote sets it, the restore clears it), so writing
 * `dormant_thinking_override` alongside `thinking_override` keeps the row in
 * lockstep with memory and a cold start can restore the park faithfully.
 * The slot value is captured BEFORE the coroutine launches so a racing user
 * pick cannot pair a stale park with a fresh level — the racing pick's own
 * call re-persists both columns anyway.
 */
internal fun ChatViewModel.persistThinkingOverride(level: ThinkingLevel) {
    val dormantNow = dormantThinkingLevel
    viewModelScope.launch {
        runCatching {
            val sid = ensureSession()
            val dao = chatRepository.dao
            // One Room transaction: two concurrent persists interleave their
            // two writes on Room's query executor (A1,B1,B2,A2) and the stale
            // park can land AFTER the user's clear, resurrecting a level the
            // user already vetoed on the next ceiling widen (adversarial
            // review P2-2). Same null-db degradation as
            // ChatRepository.deleteSession.
            val db = chatRepository.db
            if (db != null) {
                db.withTransaction {
                    dao.updateThinkingOverride(sid, level.name)
                    dao.updateDormantThinkingOverride(sid, ThinkingDormancy.encode(dormantNow))
                }
            } else {
                dao.updateThinkingOverride(sid, level.name)
                dao.updateDormantThinkingOverride(sid, ThinkingDormancy.encode(dormantNow))
            }
        }.onFailure { e ->
            AppLogger.warning(ChatViewModel.TAG, "thinking override persist failed: ${e.message}")
        }
    }
}

    internal fun ChatViewModel.applyGroupSessionDefaults(groupId: String) {
        val group = providerRepository.group(groupId) ?: return
        val level = group.defaultThinkingLevel ?: return
        // [T-thinking-dormant-restore] The group default is the new level
        // authority — a ceiling-sync-parked slot must not restore over it,
        // and the parked column must drop with the slot or a reload would
        // resurrect the park.
        val parkedBefore = dormantThinkingLevel
        dormantThinkingLevel = null
        if (_thinkingLevel.value == level) {
            // Same-value switch: the level write is a no-op but the park
            // clear is not — persist it (only when a park actually existed).
            if (parkedBefore != null) persistThinkingOverride(level)
            return
        }
        // [T-thinking-three-faces-unified] Clamp to the group model's ceiling
        // (force lift included).
        val activeEntry = _activeEntryId.value?.let { id ->
            providerRepository.config.value.modelEntries.find { it.id == id }
        } ?: return
        val clampedLevel = clampThinkingToCeiling(
            level,
            forcedThinkingCeiling(activeEntry),
        )
        _thinkingLevel.value = clampedLevel
        persistThinkingOverride(clampedLevel)
    }
