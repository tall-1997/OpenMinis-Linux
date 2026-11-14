package com.openminis.app.ui.chat

import androidx.lifecycle.viewModelScope
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
internal fun ChatViewModel.launchThinkingCeilingSync(): kotlinx.coroutines.Job =
    viewModelScope.launch {
        combine(
            _activeEntryId,
            providerRepository.config,
        ) { entryId, config ->
            val entry = entryId?.let { id -> config.modelEntries.find { it.id == id } }
            if (entry != null) {
                val ceiling = forcedThinkingCeiling(entry)
                val clamped = clampThinkingToCeiling(_thinkingLevel.value, ceiling)
                if (clamped != _thinkingLevel.value) {
                    _thinkingLevel.value = clamped
                    persistThinkingOverride(clamped)
                }
            }
        }.collect()
    }
