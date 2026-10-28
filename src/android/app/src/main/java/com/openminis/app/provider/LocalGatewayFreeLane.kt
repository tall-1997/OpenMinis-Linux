package com.openminis.app.provider

import com.openminis.app.logging.AppLogger

/**
 * The local gateway free lane: an OpenAI-compatible proxy running inside the
 * PRoot sandbox (`http://127.0.0.1:18900/v1`), installed from the APK's
 * builtin-plugin payload and managed by the app (see
 * `plugins/builtin/GatewayServiceManager`).
 *
 * Everything about this lane is inverted relative to Zen and Kilo:
 *
 * - **Free-ness is a construction fact, not a discovery.** The gateway only
 *   serves upstream free channels, so [declaredFree] is true for any id the
 *   listing carries. There is nothing for a probe to learn, and
 *   [discoversUndeclaredIds] is false for the same reason as Kilo's: a
 *   `learned.free` entry must never keep a model visible after the gateway
 *   stopped serving it.
 * - **No offline seeds.** Zen and Kilo seed two verified ids so a first
 *   install without network still offers something. This lane's gateway needs
 *   network to serve ANY model (it proxies upstream free channels), and an
 *   offline seed would be a guess at upstream ids — a guess that fails on the
 *   user's first send. An empty picker until the first successful fetch is the
 *   honest state.
 * - **[classify] never returns PAID or DEAD.** The endpoint is a local proxy:
 *   an error body is an upstream channel complaint, not a verdict about the
 *   model, and a 401 from whatever process squatted port 18900 after the
 *   gateway died is garbage evidence. Only a clean 200 counts, and it counts
 *   positively — it is the one thing that can cancel death evidence.
 *
 * Connection refusals never reach [classify] at all: the directory's probe
 * wraps the call in `runCatching` and maps transport failures to UNKNOWN
 * before this lane sees them. That is what keeps a stopped gateway from
 * poisoning [FreeLaneHealth].
 */
internal object LocalGatewayFreeLane : FreeLane {
    private const val TAG = "LocalGatewayFreeLane"

    const val HOST = "127.0.0.1"
    const val PORT = 18900
    const val BASE_URL = "http://127.0.0.1:18900/v1"
    const val PROVIDER_ID = "ofm-gateway"
    const val DISPLAY_NAME = "Free Model Gateway (Local)"

    override val id = FreeLanes.OFM_ID
    override val providerLabel = "OFM"
    override val defaultBaseURL = BASE_URL

    override val offlineSeedIds: List<String> = emptyList()

    override val discoversUndeclaredIds = false

    /**
     * Host + port + empty key. [hostOf] strips the port, so the port check
     * reads it back out of the URL: localhost carries every service the
     * sandbox owns, and 18900 is what makes this row OUR gateway rather than
     * some other local endpoint. The empty key is part of the identity because
     * a user pointing a real credential at the same port owns their own
     * instance and must get the full catalogue.
     */
    override fun owns(baseURL: String?, apiKey: String?): Boolean {
        if (!apiKey.isNullOrBlank()) return false
        val raw = baseURL ?: defaultBaseURL
        if (ProviderKeyGate.hostOf(raw) != HOST) return false
        return try {
            java.net.URI(raw).port == PORT
        } catch (_: Exception) {
            false
        }
    }

    /** The gateway serves no auth challenge, so a probe carries nothing. */
    override fun probeHeaders(): Map<String, String> = emptyMap()

    override fun declaredFree(id: String, serverSaysFree: Boolean?): Boolean = true

    override fun classify(id: String, code: Int, body: String): FreeLaneVerdict {
        if (code == 200) {
            FreeLaneHealth.recordSuccess(this.id, id)
            return FreeLaneVerdict.FREE
        }
        // Any other response — squatter 401, upstream channel 502, a 410 from
        // a half-dead proxy — is an observation, never a verdict. Logged so a
        // device report can identify it; acted on never.
        AppLogger.info(
            TAG,
            "probe $id: local gateway answered http $code (body ${body.take(80)}) — not evidence",
        )
        return FreeLaneVerdict.UNKNOWN
    }
}
