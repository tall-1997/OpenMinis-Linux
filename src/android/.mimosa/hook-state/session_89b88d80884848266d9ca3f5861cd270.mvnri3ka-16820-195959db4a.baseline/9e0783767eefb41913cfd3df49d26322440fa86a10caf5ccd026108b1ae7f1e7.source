package com.openminis.app.data.repository

import com.openminis.app.data.model.InstanceRemoval
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.provider.BuiltinFreeLane
import com.openminis.app.provider.BuiltinFreeLanes

/**
 * Seeds, recognises, restores and forgets the built-in keyless free lanes.
 *
 * Everything here is a pure [ProviderConfig] mutation — no Context, no
 * repository, no I/O — so the device states are testable on the JVM. The caller
 * owns persistence (`saveConfig`) and the credential store
 * (`saveApiKey(instanceId, apiKey)`, skipped for a lane that wants none),
 * because the key does not live in the config.
 *
 * Lane-agnostic by construction: every entry point iterates
 * [BuiltinFreeLanes.ALL] or takes one [BuiltinFreeLane], so seeding a second
 * upstream is a descriptor and not a second copy of this file. That is the point
 * of the split — the Zen-only version of this bootstrap is what made a second
 * lane look like a fork.
 *
 * ## Who is ours
 *
 * [belongsTo] is the only gate that decides what this file may touch. A
 * user-built provider at the same host with a real API key is not a free lane
 * and is never renamed, re-pointed, re-keyed or pruned.
 */
internal object FreeLaneBootstrap {

    private const val CLASH_SUFFIX = " · Built-in"

    fun isBuiltinFreeInstanceId(instanceId: String): Boolean =
        BuiltinFreeLanes.isBuiltinFreeInstanceId(instanceId)

    fun belongsTo(builtin: BuiltinFreeLane, instance: ProviderInstance, apiKey: String?): Boolean =
        instance.id == builtin.instanceId || builtin.lane.owns(instance.customBaseURL, apiKey)

    /**
     * Seeds every free lane this device has none of.
     *
     * Returns the lanes actually added, so the caller can write exactly their
     * credentials and persist once. A lane is skipped — and the config left
     * alone — when an instance for it already exists (its label, rows and
     * per-row user state belong to the user and to the refresh path) or when the
     * user deleted it on purpose. An instance that an app upgrade removed
     * carries no tombstone, so it comes back here; that asymmetry is the whole
     * point of [InstanceRemoval].
     *
     * [apiKeyOf] reads the credential store for one instance id. Defaulted to
     * "nothing" so tests need no store; the repository passes its own loader.
     */
    fun ensureSeeded(
        config: ProviderConfig,
        apiKeyOf: (String) -> String? = { null },
    ): List<BuiltinFreeLane> {
        val seeded = mutableListOf<BuiltinFreeLane>()
        for (builtin in BuiltinFreeLanes.ALL) {
            if (config.instances.any { belongsTo(builtin, it, apiKeyOf(it.id)) }) continue
            if (InstanceRemoval.wasRemovedByUser(config, builtin.instanceId)) continue
            config.instances.add(builtin.instance(labelFor(config, builtin)))
            seeded.add(builtin)
        }
        return seeded
    }

    /**
     * The explicit recovery channel for one lane: drop the user's deletion
     * tombstone, seed the instance if it is gone, and clear the model-level
     * tombstones recorded against it so previously deleted free models reappear
     * on the next refresh.
     *
     * Only this lane's tombstones are cleared. Deletions the user made on some
     * other provider are theirs and stay — a global "restore everything deleted"
     * would undo deletions that were deliberate.
     *
     * Returns the lane when something changed, so the caller knows a credential
     * may need writing and a refetch is worth firing; null means the lane was
     * already whole.
     */
    fun restore(
        config: ProviderConfig,
        builtin: BuiltinFreeLane,
        apiKeyOf: (String) -> String? = { null },
    ): BuiltinFreeLane? {
        var changed = InstanceRemoval.clear(config, builtin.instanceId)
        if (config.instances.none { belongsTo(builtin, it, apiKeyOf(it.id)) }) {
            config.instances.add(builtin.instance(labelFor(config, builtin)))
            changed = true
        }
        if (config.deletedModelIds.remove(builtin.instanceId) != null) changed = true
        return if (changed) builtin else null
    }

    /**
     * What [ProviderRepository.removeInstance] must do besides dropping the
     * instance: clear the model-level tombstones keyed by that id, and record an
     * instance-level tombstone when the id is one of our seeds.
     *
     * The tombstone half of this was missing for `deletedModelIds` alone, which
     * is not a free-lane bug at all — `removeInstance` cleaned up instances,
     * rows, group members, agent-loop ids, empty groups, api key, OAuth, voice
     * shadow, last-used entry and thinking rules, but left the model tombstones
     * behind, so any instance rebuilt under the same id inherited them and its
     * models vanished silently. Clearing it here fixes that for every provider.
     */
    fun forgetInstance(config: ProviderConfig, instanceId: String) {
        config.deletedModelIds.remove(instanceId)
        if (isBuiltinFreeInstanceId(instanceId)) {
            InstanceRemoval.record(config, instanceId)
        }
    }

    /**
     * Points the primary slot at a usable free-lane row, but only when this
     * migration is what emptied it.
     *
     * A slot the user left empty on purpose stays empty — `emptiedByMigration`
     * is the caller's record of having just nulled it, not a guess from the
     * config, because the config cannot tell those two apart.
     */
    fun repairDefaultSlot(config: ProviderConfig, emptiedByMigration: Boolean): Boolean {
        if (!emptiedByMigration) return false
        if (config.defaultPrimaryGroupId != null) return false
        val row = BuiltinFreeLanes.ALL.firstNotNullOfOrNull { builtin ->
            val deleted = config.deletedModelIds[builtin.instanceId].orEmpty()
            config.modelEntries.firstOrNull { entry ->
                entry.providerInstanceId == builtin.instanceId &&
                    !entry.isHidden &&
                    entry.baseModel.id !in deleted
            }
        } ?: return false
        config.defaultPrimaryGroupId = "entry:${row.uuid}"
        return true
    }

    private fun labelFor(config: ProviderConfig, builtin: BuiltinFreeLane): String {
        val taken = config.instances.any { it.label == builtin.label }
        return if (taken) builtin.label + CLASH_SUFFIX else builtin.label
    }
}
