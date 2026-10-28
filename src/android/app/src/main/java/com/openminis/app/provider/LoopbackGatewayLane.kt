package com.openminis.app.provider

import com.openminis.app.logging.AppLogger

/**
 * Shared semantics of a loopback gateway lane — an OpenAI-compatible proxy
 * bound to 127.0.0.1, shipped with the APK and managed by the app (see
 * `plugins/builtin/`). Guest plugins run inside the PRoot sandbox; native
 * plugins run as host app processes — the lane does not care which, because
 * from the app's side both are just a loopback port it owns.
 *
 * Everything about these lanes is inverted relative to Zen and Kilo, and the
 * inversion is the same for every one of them, so it lives here once:
 *
 * - **Free-ness is a construction fact, not a discovery.** The gateway only
 *   serves upstream free channels, so [declaredFree] is true for any id the
 *   listing carries. There is nothing for a probe to learn, and
 *   [discoversUndeclaredIds] is false for the same reason as Kilo's: a
 *   `learned.free` entry must never keep a model visible after the gateway
 *   stopped serving it.
 * - **No offline seeds.** The gateway needs network to serve ANY model (it
 *   proxies upstream channels), and an offline seed would be a guess at
 *   upstream ids — a guess that fails on the user's first send. An empty
 *   picker until the first successful fetch is the honest state.
 * - **[classify] never returns PAID or DEAD.** The endpoint is a local proxy:
 *   an error body is an upstream channel complaint, not a verdict about the
 *   model, and a 401 from whatever process squatted the port after the
 *   gateway died is garbage evidence. Only a clean 200 counts, and it counts
 *   positively — it is the one thing that can cancel death evidence.
 *
 * Connection refusals never reach [classify] at all: the directory's probe
 * wraps the call in `runCatching` and maps transport failures to UNKNOWN
 * before the lane sees them. That is what keeps a stopped gateway from
 * poisoning [FreeLaneHealth].
 *
 * A lane distinguishes itself only by identity: its lane id, its loopback
 * port (the port IS the identity on a host that carries every sandbox
 * service), and the labels the config layer shows.
 */
internal abstract class LoopbackGatewayLane(
    laneId: String,
    /** The port this lane's gateway binds on 127.0.0.1. */
    val port: Int,
    /** Instance id prefix the config layer seeds, e.g. "ofm-gateway". */
    val providerId: String,
    /** Label shown on the seeded provider row. */
    val displayName: String,
    /** Short label for skeleton models rebuilt from cache, e.g. "OFM". */
    providerLabel: String,
) : FreeLane {
    private val tag = javaClass.simpleName

    final override val id = laneId
    final override val providerLabel = providerLabel
    final override val defaultBaseURL = "http://$HOST:$port/v1"

    final override val offlineSeedIds: List<String> = emptyList()

    final override val discoversUndeclaredIds = false

    /**
     * Host + port + empty key. [ProviderKeyGate.hostOf] strips the port, so
     * the port check reads it back out of the URL: localhost carries every
     * service the sandbox owns, and the port is what makes this row OUR
     * gateway rather than some other local endpoint. The empty key is part of
     * the identity because a user pointing a real credential at the same
     * port owns their own instance and must get the full catalogue.
     */
    final override fun owns(baseURL: String?, apiKey: String?): Boolean {
        if (!apiKey.isNullOrBlank()) return false
        val raw = baseURL ?: defaultBaseURL
        if (ProviderKeyGate.hostOf(raw) != HOST) return false
        return try {
            java.net.URI(raw).port == port
        } catch (_: Exception) {
            false
        }
    }

    /** The gateway serves no auth challenge, so a probe carries nothing. */
    final override fun probeHeaders(): Map<String, String> = emptyMap()

    final override fun declaredFree(id: String, serverSaysFree: Boolean?): Boolean = true

    final override fun classify(id: String, code: Int, body: String): FreeLaneVerdict {
        if (code == 200) {
            FreeLaneHealth.recordSuccess(this.id, id)
            return FreeLaneVerdict.FREE
        }
        // Any other response — squatter 401, upstream channel 502, a 410 from
        // a half-dead proxy — is an observation, never a verdict. Logged so a
        // device report can identify it; acted on never.
        AppLogger.info(
            tag,
            "probe $id: local gateway answered http $code (body ${body.take(80)}) — not evidence",
        )
        return FreeLaneVerdict.UNKNOWN
    }

    companion object {
        const val HOST = "127.0.0.1"
    }
}
