package com.openminis.app.provider

/** What one probe concluded about one id on one lane. */
internal enum class FreeLaneVerdict { FREE, PAID, DEAD, UNKNOWN }

/**
 * What probing and real usage taught a lane about ids its own rule cannot
 * decide. [free] means the lane answered without a credential; [paid] means it
 * demanded one, so the id is never shown and never re-probed.
 *
 * Shared by every lane and persisted inside [FreeLaneCatalogSnapshot], whose
 * cache key is the endpoint — so two lanes never read each other's evidence.
 */
internal data class FreeLaneLearned(
    val free: Set<String> = emptySet(),
    val paid: Set<String> = emptySet(),
) {
    fun withFree(id: String) = copy(free = free + id, paid = paid - id)
    fun withPaid(id: String) = copy(paid = paid + id, free = free - id)
}

/**
 * One keyless upstream we ship as a built-in provider.
 *
 * A lane owns three decisions and nothing else — recognition, free-slice
 * membership, and how its error dialect maps onto evidence. Everything that
 * surrounds those decisions (narrowing, offline fallback, background probing,
 * backoff, persistence) is shared by [FreeLaneDirectory], because it was
 * already lane-independent the day a second lane showed up and the only thing
 * stopping reuse was a hardcoded `isZenHost` at the call site.
 *
 * Adding a lane is therefore: implement this interface, add it to [FreeLanes],
 * seed the built-in instance. No transport work — every lane so far is an
 * OpenAI-compatible endpoint reached with an empty or placeholder credential,
 * which `ProviderInstance.allowsEmptyAPIKey` already treats as configured.
 */
internal interface FreeLane {
    /** Stable scope for [FreeLaneHealth] and for logs. Never renamed. */
    val id: String

    /** Shown as the provider name on skeleton models rebuilt from cache. */
    val providerLabel: String

    /** Used when the instance carries no explicit base URL. */
    val defaultBaseURL: String

    /**
     * The only hardcoded ids anywhere in the feature, and they exist purely so
     * a first install with no network still offers something. Retired for good
     * by the first successful fetch.
     */
    val offlineSeedIds: List<String>

    /**
     * True when the listing does not declare free-ness, so a probe is the only
     * way to discover a free id the membership rule misses — and so a proven
     * free id may be ADDED to the lane.
     *
     * Zen has no `isFree` field and infers membership from the id shape, so a
     * probe that answers without a credential genuinely teaches the catalogue.
     * Kilo's listing states free-ness outright, so Kilo probes only to collect
     * liveness evidence: a `learned.free` entry there suppresses re-probing and
     * must never keep a model the upstream stopped advertising.
     */
    val discoversUndeclaredIds: Boolean

    /**
     * Whether a provider instance is OURS — the built-in keyless lane — rather
     * than one the user created with a real credential. Anything false here is
     * never narrowed, never probed, never touched.
     *
     * The credential test is part of the identity, not an afterthought: a user
     * who points their own paid key at the same host must get the full
     * catalogue, and a lane that only matched on host would silently hide
     * everything they paid for.
     */
    fun owns(baseURL: String?, apiKey: String?): Boolean

    /**
     * Headers a probe must carry. Empty for a lane that rejects any non-empty
     * token: sending a placeholder there turns our own wiring into a stream of
     * 401s that read as "this model is paid".
     */
    fun probeHeaders(): Map<String, String>

    /**
     * Membership by the lane's OWN authority, before any learned evidence is
     * consulted: Zen the id shape, Kilo the listing's `isFree` flag.
     *
     * [serverSaysFree] is the listing's declaration, or null when the listing
     * carries no such field. A lane whose upstream declares free-ness trusts
     * the declaration over any local rule; a lane whose upstream does not uses
     * its rule.
     *
     * Kept separate from learned evidence on purpose — see
     * [FreeLaneDirectory.contradictsDeclaration], which refuses to persist a
     * "needs a credential" verdict against an id this method just declared free.
     */
    fun declaredFree(id: String, serverSaysFree: Boolean?): Boolean

    /**
     * Maps one probe response onto a verdict, recording evidence in
     * [FreeLaneHealth] as it goes.
     *
     * The invariant every lane must keep: when upstream speaks a structured
     * error dialect, the DIALECT decides the verdict and the transport code
     * decides nothing. Only a body carrying no structured error may be read by
     * status. A status code is an inference about the model — "needs a
     * credential" — and the learned set persists that inference across restarts,
     * so any dialect word that falls through to the status map turns a
     * transient account-scoped refusal into the permanent removal of a working
     * model. That is how the 2026-10-17 Zen retirement happened.
     *
     * Corollary: an error word this lane has never seen maps to
     * [FreeLaneEvidence.UNSPECIFIED_ERROR_TYPE] and [FreeLaneVerdict.UNKNOWN],
     * which writes nothing. Logged loudly, acted on never.
     */
    fun classify(id: String, code: Int, body: String): FreeLaneVerdict
}

/**
 * The lanes we ship. Recognition order matters only if two lanes ever claim the
 * same host with the same credential shape, which they must not.
 */
internal object FreeLanes {
    const val ZEN_ID = "zen"
    const val KILO_ID = "kilo"
    const val OFM_ID = "ofm"
    const val A2A_ID = "a2a"

    /** Known lane scopes, used to tell a scoped health key from a legacy flat one. */
    val LANE_IDS: Set<String> = setOf(ZEN_ID, KILO_ID, OFM_ID, A2A_ID)

    val all: List<FreeLane> = listOf(ZenFreeLane, KiloFreeLane, LocalGatewayFreeLane, Agent2ApiFreeLane)

    /** The built-in lane for this instance, or null when the instance is the user's. */
    fun forEndpoint(baseURL: String?, apiKey: String?): FreeLane? =
        all.firstOrNull { it.owns(baseURL, apiKey) }

    fun byId(laneId: String): FreeLane? = all.firstOrNull { it.id == laneId }

    /**
     * An endpoint with no explicit lane still needs a cache key and a probe URL,
     * and both must agree with whatever the instance was seeded with. Trailing
     * slashes are stripped so `…/gateway` and `…/gateway/` are one lane.
     */
    fun cacheKey(lane: FreeLane, baseURL: String?): String =
        (baseURL?.takeIf { it.isNotBlank() } ?: lane.defaultBaseURL).trimEnd('/')
}
