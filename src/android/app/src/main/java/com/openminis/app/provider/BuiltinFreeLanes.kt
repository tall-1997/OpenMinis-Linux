package com.openminis.app.provider

import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType

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
     * False when the instance must NOT appear on first launch — when the lane
     * only works after an explicit user action (installing a local plugin).
     * Auto-seeding such a lane would show a provider row whose every refresh
     * fails until the user finds the plugin market. The explicit channels
     * (add-provider preset, recovery row, post-install restore) still seed it,
     * because those are the user asking for it.
     */
    val seedOnBootstrap: Boolean = true,
) {
    val baseURL: String get() = lane.defaultBaseURL

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
    )

    val OFM = BuiltinFreeLane(
        lane = LocalGatewayFreeLane,
        // Same no-`-free`-suffix rule as Kilo's id.
        instanceId = "builtin-ofm-gateway",
        label = LocalGatewayFreeLane.DISPLAY_NAME,
        // The local gateway binds loopback only and serves no auth challenge,
        // so no credential is written for this lane.
        apiKey = "",
        // Seeded by the plugin installer instead of first launch: the row is
        // dead weight until the gateway payload is installed and running.
        seedOnBootstrap = false,
    )

    val ALL: List<BuiltinFreeLane> = listOf(ZEN, KILO, OFM)

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
