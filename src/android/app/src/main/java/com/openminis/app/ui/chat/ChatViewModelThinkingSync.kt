package com.openminis.app.ui.chat

import androidx.lifecycle.viewModelScope
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
 */
/** [T-thinking-dormant-restore] User's explicit level parked while the
 *  active model's ceiling was too low; restored (once) when the ceiling
 *  widens past it. Session-lifetime only — a process death clears it. */
private var dormantThinkingLevel: ThinkingLevel? = null

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
                val clamped = clampThinkingToCeiling(_thinkingLevel.value, ceiling)
                val dormant = dormantThinkingLevel
                if (clamped != _thinkingLevel.value) {
                    // [T-thinking-dormant-restore] The user's explicit level is
                    // DEMOTED to a dormant slot, not destroyed: when the ceiling
                    // widens again (Thinking switch re-enabled / override
                    // raised / better model selected) the original choice is
                    // restored — the old one-way demotion permanently erased it.
                    dormantThinkingLevel = _thinkingLevel.value.takeIf { it != ThinkingLevel.OFF }
                    _thinkingLevel.value = clamped
                    persistThinkingOverride(clamped)
                } else if (dormant != null && dormant.rank <= ceiling.rank) {
                    // Ceiling widened past the dormant level: restore it.
                    _thinkingLevel.value = dormant
                    persistThinkingOverride(dormant)
                    dormantThinkingLevel = null
                }
            }
        }.collect()
    }


/**
 * [T-thinking-ceiling-sync] Persist the (possibly clamped) level to the
 * session row. Ceiling-sync triggers this on every config emission — an
 * unguarded DB failure (disk full) would crash the process from an
 * automatic path; the in-memory level is already applied and persistence
 * self-heals on the next emission.
 */
internal fun ChatViewModel.persistThinkingOverride(level: ThinkingLevel) {
    viewModelScope.launch {
        runCatching {
            val sid = ensureSession()
            chatRepository.dao.updateThinkingOverride(sid, level.name)
        }.onFailure { e ->
            AppLogger.warning(ChatViewModel.TAG, "thinking override persist failed: ${e.message}")
        }
    }
}

    internal fun ChatViewModel.applyGroupSessionDefaults(groupId: String) {
        val group = providerRepository.group(groupId) ?: return
        val level = group.defaultThinkingLevel ?: return
        if (_thinkingLevel.value == level) return
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
        viewModelScope.launch {
            val sid = ensureSession()
            chatRepository.dao.updateThinkingOverride(sid, clampedLevel.name)
        }
    }
