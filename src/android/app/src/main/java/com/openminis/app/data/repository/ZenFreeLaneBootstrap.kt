package com.openminis.app.data.repository

import com.openminis.app.data.model.InstanceRemoval
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.provider.ZenEndpoint

/**
 * Seeds, recognises, restores and forgets the built-in OpenCode Zen free lane.
 *
 * Everything here is a pure [ProviderConfig] mutation — no Context, no
 * repository, no I/O — so the six device states are testable on the JVM. The
 * caller owns persistence (`saveConfig`) and the credential store
 * (`saveApiKey(instanceId, API_KEY)`), because the key does not live in the
 * config.
 *
 * ## The id is the migration
 *
 * [INSTANCE_ID] is deliberately the same id the earlier build seeded and then
 * removed on launch. Reusing it is what makes old references revive on their
 * own: a default-slot pointer written as `entry:<uuid>`, a last-used entry, a
 * group membership — all of them are keyed by instance id or by row uuid under
 * that id, so a re-seed under a fresh id would leave every one of them dangling
 * while the config looked perfectly healthy.
 *
 * ## Who is ours
 *
 * [isZenFreeInstance] is the only gate that decides what this file may touch.
 * A user-built Zen provider — same host, real API key — is not the free lane
 * and is never renamed, re-pointed, re-keyed or pruned. Recognition needs both
 * the host and the placeholder credential for that reason: the host alone would
 * claim the user's own provider, and the credential alone would claim any
 * local endpoint that happens to use `public`.
 */
internal object ZenFreeLaneBootstrap {

    /** Same id the earlier build seeded, so old slot and session references revive. */
    const val INSTANCE_ID = "builtin-opencode-zen"

    const val LABEL = "OpenCode Zen (Free)"
    const val ENDPOINT = "https://opencode.ai/zen/v1"

    /** The placeholder credential the keyless lane authenticates with. */
    const val API_KEY = "public"

    private const val CLASH_SUFFIX = " · Built-in"

    fun isBuiltinFreeInstanceId(instanceId: String): Boolean = instanceId == INSTANCE_ID

    fun isZenFreeInstance(instance: ProviderInstance, apiKey: String?): Boolean =
        instance.id == INSTANCE_ID ||
            (ZenEndpoint.isZenHost(instance.customBaseURL) && apiKey == API_KEY)

    /**
     * Seeds the free lane when this device has none.
     *
     * Returns false — and changes nothing — when a free-lane instance already
     * exists (its label, rows and per-row user state belong to the user and to
     * the refresh path, not to this function) or when the user deleted it on
     * purpose. An instance that an app upgrade removed carries no tombstone, so
     * it comes back here; that asymmetry is the whole point of
     * [InstanceRemoval].
     *
     * [apiKeyOf] reads the credential store for one instance id. Defaulted to
     * "nothing" so tests need no store; the repository passes its own loader.
     */
    fun ensureSeeded(
        config: ProviderConfig,
        apiKeyOf: (String) -> String? = { null },
    ): Boolean {
        if (config.instances.any { isZenFreeInstance(it, apiKeyOf(it.id)) }) return false
        if (InstanceRemoval.wasRemovedByUser(config, INSTANCE_ID)) return false
        config.instances.add(builtInInstance(config))
        return true
    }

    /**
     * The explicit recovery channel: drop the user's deletion tombstone, seed
     * the instance if it is gone, and clear the model-level tombstones recorded
     * against it so previously deleted free models reappear on the next refresh.
     *
     * Only this instance's tombstones are cleared. Deletions the user made on
     * some other provider are theirs and stay.
     */
    fun restore(
        config: ProviderConfig,
        apiKeyOf: (String) -> String? = { null },
    ): Boolean {
        var changed = InstanceRemoval.clear(config, INSTANCE_ID)
        if (config.instances.none { isZenFreeInstance(it, apiKeyOf(it.id)) }) {
            config.instances.add(builtInInstance(config))
            changed = true
        }
        if (config.deletedModelIds.remove(INSTANCE_ID) != null) changed = true
        return changed
    }

    /**
     * What [ProviderRepository.removeInstance] must do besides dropping the
     * instance: clear the model-level tombstones keyed by that id, and record an
     * instance-level tombstone when the id is our seed.
     *
     * The tombstone half of this was missing for `deletedModelIds` alone, which
     * is not a Zen bug at all — `removeInstance` cleaned up instances, rows,
     * group members, agent-loop ids, empty groups, api key, OAuth, voice shadow,
     * last-used entry and thinking rules, but left the model tombstones behind,
     * so any instance rebuilt under the same id inherited them and its models
     * vanished silently. Clearing it here fixes that for every provider.
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
        val deleted = config.deletedModelIds[INSTANCE_ID].orEmpty()
        val row = config.modelEntries.firstOrNull { entry ->
            entry.providerInstanceId == INSTANCE_ID &&
                !entry.isHidden &&
                entry.baseModel.id !in deleted
        } ?: return false
        config.defaultPrimaryGroupId = "entry:${row.uuid}"
        return true
    }

    private fun builtInInstance(config: ProviderConfig): ProviderInstance {
        val taken = config.instances.any { it.label == LABEL }
        return ProviderInstance(
            id = INSTANCE_ID,
            label = if (taken) LABEL + CLASH_SUFFIX else LABEL,
            providerType = ProviderType.openAI,
            credentialType = ProviderCredential.apiKey,
            customBaseURL = ENDPOINT,
            // The endpoint already carries /v1; forcing it again yields
            // .../zen/v1/v1/models and a 404.
            appendV1Suffix = false,
        )
    }
}
