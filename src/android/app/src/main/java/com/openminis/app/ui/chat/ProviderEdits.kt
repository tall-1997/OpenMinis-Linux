// [port-upstream-1.16] Ported from OpenMinis/OpenMinis upstream 1a71df0f
// (ui/chat/ProviderEdits.kt).
package com.openminis.app.ui.chat

import com.openminis.app.data.model.ProviderInstance

/**
 * Ids of provider instances present in both snapshots whose settings differ:
 * what was edited between [before] and [now]. Added and removed instances are
 * not edits - a removed one cannot be rebuilt, and an added one has no open
 * chat built from it yet.
 */
internal fun editedProviderInstanceIds(
    before: Map<String, ProviderInstance>,
    now: Map<String, ProviderInstance>,
): Set<String> = now.filter { (id, inst) -> before[id] != null && before[id] != inst }.keys
