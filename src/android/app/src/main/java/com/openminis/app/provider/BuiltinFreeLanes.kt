package com.openminis.app.provider

import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.logging.AppLogger

/**
 * Everything the config layer needs to seed one built-in keyless lane.
 *
 * Kept apart from [FreeLane] on purpose: the lane describes the *upstream*
 * (host, credential shape, error dialect, what counts as free), while this
 * describes *our instance of it* (the id we seeded, the label we show, whether a
 * credential has to be written to the store). Mixing them is how a lane ends up
 * knowing about SharedPreferences.
 */
internal data class BuiltinFreeLane(
    val lane: FreeLane,
    /** Stable forever. Old slot, group and session references are keyed by it. */
    val instanceId: String,
    val label: String,
    /**
     * The credential to write for this instance, or empty when the upstream
     * wants none. Empty is not "unset": [ProviderInstance.allowsEmptyAPIKey]
     * already models a deliberately keyless OpenAI-compatible instance, and
     * `usableApiKey` returns "" rather than null for it.
     */
    val apiKey: String,
    /**
     * How this lane's credential is meant to be used. Compile-time lanes state
     * theirs explicitly ([FIXED] for Zen's placeholder, [NONE] for Kilo's
     * token-rejecting upstream); manifest lanes carry the parsed value over.
     */
    val credentialConvention: CredentialConvention = CredentialConvention.FIXED,
) {
    val baseURL: String get() = lane.defaultBaseURL

    /**
     * True when this lane rotates user-supplied account tokens (M2 pool).
     * [FIXED] lanes write one placeholder for everyone and pooling is
     * meaningless; [NONE] lanes reject any token, so offering the pool would
     * only produce a stream of 401s the user caused themselves.
     */
    val poolEligible: Boolean get() = credentialConvention == CredentialConvention.ACCOUNT_TOKEN

    fun instance(labelToUse: String = label): ProviderInstance = ProviderInstance(
        id = instanceId,
        label = labelToUse,
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
        customBaseURL = baseURL,
        // Both endpoints already carry their full path; forcing /v1 again yields
        // .../v1/v1/models and a 404.
        appendV1Suffix = false,
    )
}

/**
 * The built-in keyless lanes this build seeds. Adding a lane here is the whole
 * registration: the bootstrap, the recovery channel, the providers-list footer,
 * the add-provider preset and the model-list footer all iterate this list.
 *
 * The Zen instance id is the one the earlier build seeded and then removed on
 * launch. Reusing it is what makes old references revive on their own: a
 * default-slot pointer written as `entry:<uuid>`, a last-used entry, a group
 * membership — all keyed by instance id or by row uuid under it, so a re-seed
 * under a fresh id would leave every one of them dangling while the config
 * looked perfectly healthy.
 */
internal object BuiltinFreeLanes {

    val ZEN = BuiltinFreeLane(
        lane = ZenFreeLane,
        instanceId = "builtin-opencode-zen",
        label = "OpenCode Zen (Free)",
        apiKey = ZenFreeLane.API_KEY,
        credentialConvention = CredentialConvention.FIXED,
    )

    val KILO = BuiltinFreeLane(
        lane = KiloFreeLane,
        // No `-free` suffix: the membership guard in FreeLaneMembershipTest walks
        // production sources for hardcoded free MODEL ids, and an instance id that
        // happens to end in `-free` reads as one.
        instanceId = "builtin-kilo",
        label = KiloFreeLane.DISPLAY_NAME,
        // Kilo rejects any token it cannot validate, including an empty one, so
        // nothing is written to the credential store for this lane at all.
        apiKey = "",
        credentialConvention = CredentialConvention.NONE,
    )

    val ALL: List<BuiltinFreeLane> = listOf(ZEN, KILO)

    /** Instance ids this build seeds, used to dedupe manifest-synthesised lanes. */
    val ALL_INSTANCE_IDS: Set<String> = ALL.map { it.instanceId }.toSet()

    /**
     * The full lane list for this device: the compiled-in lanes plus every
     * manifest entry that synthesises cleanly. Manifest entries whose
     * `instanceId` collides with a compiled-in lane are dropped (the
     * compiled-in descriptor is the one historical references are keyed by),
     * and an entry naming a compiled behaviour under a different instance id
     * is refused — a lane id like "zen" is bound to its instance id forever,
     * because the recovery channel resurrects it by that id alone.
     */
    fun fromManifest(entries: List<ManifestLaneEntry>): List<BuiltinFreeLane> {
        if (entries.isEmpty()) return ALL
        val seen = ALL_INSTANCE_IDS.toMutableSet()
        val extra = mutableListOf<BuiltinFreeLane>()
        for (entry in entries) {
            val builtin = entry.toBuiltin() ?: continue
            if (!seen.add(builtin.instanceId)) continue
            extra.add(builtin)
        }
        return ALL + extra
    }

    /** Behaviour + descriptor synthesis for one manifest entry, or null when refused. */
    internal fun ManifestLaneEntry.toBuiltin(): BuiltinFreeLane? {
        val lane: FreeLane = when (behavior) {
            FreeLaneManifest.BEHAVIOR_ZEN -> {
                if (instanceId != ZEN.instanceId) {
                    AppLogger.warning("BuiltinFreeLanes", "manifest 'zen' entry bound to '$instanceId', compiled-in id is '${ZEN.instanceId}' — refused")
                    return null
                }
                ZenFreeLane
            }
            FreeLaneManifest.BEHAVIOR_KILO -> {
                if (instanceId != KILO.instanceId) {
                    AppLogger.warning("BuiltinFreeLanes", "manifest 'kilo' entry bound to '$instanceId', compiled-in id is '${KILO.instanceId}' — refused")
                    return null
                }
                KiloFreeLane
            }
            FreeLaneManifest.BEHAVIOR_OPENAI_COMPAT -> ManifestLane(this)
            else -> return null
        }
        return BuiltinFreeLane(
            lane = lane,
            instanceId = instanceId,
            label = label,
            apiKey = when (credentialConvention) {
                CredentialConvention.FIXED -> credentialValue
                CredentialConvention.NONE, CredentialConvention.ACCOUNT_TOKEN -> ""
            },
            credentialConvention = credentialConvention,
        )
    }

    /**
     * Instance ids of built-in lanes earlier builds seeded and this build no
     * longer ships — the local a2a gateway, whose process, payload and lane all
     * left the APK.
     *
     * These ids are load-bearing precisely because the descriptor is gone: an
     * install that ever enabled that lane still carries a provider row pointed at
     * a loopback port nothing binds any more, plus the models the row listed. The
     * boot purge ([com.openminis.app.data.repository.BuiltinLaneRetirement])
     * reads this list to remove exactly those rows, and nothing else: an id that
     * is not listed here is a lane we still ship or a provider the user built.
     */
    val RETIRED_IDS: Set<String> = setOf("builtin-a2a-gateway")

    fun byInstanceId(instanceId: String): BuiltinFreeLane? =
        ALL.firstOrNull { it.instanceId == instanceId }

    fun isBuiltinFreeInstanceId(instanceId: String): Boolean = byInstanceId(instanceId) != null

    /**
     * Which of our seeds this config row is, or null when it is the user's own
     * provider. Recognition needs the host AND the credential shape: the host
     * alone would claim the user's own provider at the same upstream, and the
     * credential alone would claim any local endpoint that happens to match.
     */
    fun forInstance(instance: ProviderInstance, apiKey: String?): BuiltinFreeLane? = ALL.firstOrNull {
        instance.id == it.instanceId || it.lane.owns(instance.customBaseURL, apiKey)
    }
}
