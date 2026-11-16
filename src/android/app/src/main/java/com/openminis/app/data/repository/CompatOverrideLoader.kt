package com.openminis.app.data.repository

import com.openminis.app.data.model.ModelEntry
import com.openminis.app.provider.compat.CompatFlags
import com.openminis.app.provider.compat.CompatResolver
import kotlinx.serialization.json.Json

/**
 * [T-compat-flags-persist] Read side of the persisted per-model compat overlay:
 * the `compat_json` column on `provider_model_entries` (pi models.json `compat`
 * equivalent) is decoded into [CompatFlags] and fed through
 * [CompatResolver.override] so the very next request of a live provider honors
 * it (per-access resolution — see CompatResolver.forBasePath).
 *
 * This file (not the data.db mapping) owns the decode because the architecture
 * policy forbids `data.db` imports of `com.openminis.app.provider.*`; the
 * repository layer is the sanctioned seam between the store and the resolver.
 *
 * WRITE-SIDE EVOLUTION: there is no UI editor (and no models.json import) yet —
 * the column is populated only by a future writer. The round-trip is already
 * complete (ModelEntry.compatJson ↔ entity.compatJson ↔ JSON mirror), so a
 * writer lands on a working seam: serialize [CompatFlags] with the repository
 * Json instance, set [ModelEntry.compatJson], save.
 */
internal object CompatOverrideLoader {

    /**
     * Decode one `compat_json` blob. Never throws: a corrupt or unknown-key
     * blob degrades to null (= no overlay, family defaults apply) instead of
     * failing the whole provider load — same contract as every other persisted
     * JSON blob in the provider store. Null/blank input decodes to null.
     */
    internal fun decodeCompatFlags(json: Json, raw: String?): CompatFlags? {
        if (raw.isNullOrBlank()) return null
        return runCatching { json.decodeFromString(CompatFlags.serializer(), raw) }
            .onFailure {
                android.util.Log.w(
                    "ProviderRepo",
                    "[CompatFlags] compat_json decode failed (family defaults apply): ${it.message}",
                )
            }
            .getOrNull()
    }

    /**
     * Sync the resolver overlay with [entries]' persisted compat blobs.
     * [CompatResolver]'s overlay map is process-global, so the sync REPLACES
     * its model-keyed contents wholesale from the freshly loaded config:
     * entries with a blob are overridden, and a model id that previously
     * carried an overlay but now does not (entry deleted / blob cleared by a
     * future writer) falls back to its family default.
     */
    internal fun applyCompatOverrides(json: Json, entries: List<ModelEntry>) {
        val desired = HashMap<String, CompatFlags>()
        for (entry in entries) {
            val flags = decodeCompatFlags(json, entry.compatJson) ?: continue
            desired[entry.baseModel.id] = flags
        }
        CompatResolver.replaceAllOverrides(desired)
        if (desired.isNotEmpty()) {
            android.util.Log.i(
                "ProviderRepo",
                "[CompatFlags] applied ${desired.size} per-model compat overlay(s) from provider store",
            )
        }
    }
}
