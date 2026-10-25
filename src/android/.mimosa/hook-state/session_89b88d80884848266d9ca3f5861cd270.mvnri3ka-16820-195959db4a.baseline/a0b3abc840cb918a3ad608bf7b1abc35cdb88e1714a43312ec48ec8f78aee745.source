package com.openminis.app.provider

import com.openminis.app.logging.AppLogger
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The Kilo keyless free lane: `api.kilo.ai/api/gateway`, OpenAI-compatible,
 * no credential.
 *
 * Verified against the live endpoint on 2026-10-24:
 * - `GET /v1/models` answers anonymously with 52 entries, 17 of them carrying
 *   `"isFree": true`;
 * - `POST /chat/completions` on an `isFree` id with **no** `Authorization`
 *   header streams a real completion (200, 13 tokens on `kilo-auto/free`);
 * - the same call on an id without `isFree` returns 401
 *   `{"error":{"code":"PAID_MODEL_AUTH_REQUIRED"}}`;
 * - the same call carrying `Authorization: Bearer public` — the credential the
 *   Zen lane needs — returns 401 `{"error":{"code":"INVALID_TOKEN"}}` for every
 *   id including the free ones.
 *
 * That last line is the reason [probeHeaders] is empty and the reason
 * `INVALID_TOKEN` is not death evidence. A lane's credential convention is part
 * of its identity: reuse Zen's placeholder here and every probe comes back 401,
 * which a code-based classifier would read as "this model is paid" and empty the
 * lane on our own wiring bug.
 *
 * Membership comes from the listing's own `isFree` flag, so unlike Zen this lane
 * never discovers an id by probing — see [discoversUndeclaredIds]. Probing here
 * collects liveness evidence only, and that evidence is not optional: the
 * listing advertised `moonshotai/kimi-k2.6:free` with `isFree: true` and the
 * same context window as its healthy neighbours, while a request to it returned
 * 404 `Provider function not found for account`. The catalogue cannot tell a
 * retired model from a working one.
 */
internal object KiloFreeLane : FreeLane {
    private const val TAG = "KiloFreeLane"

    const val HOST = "api.kilo.ai"
    const val BASE_URL = "https://api.kilo.ai/api/gateway"
    const val PROVIDER_ID = "kilocode"
    const val DISPLAY_NAME = "Kilo (Free)"

    /** Kilo's dialect words. */
    private const val CODE_PAID_MODEL = "PAID_MODEL_AUTH_REQUIRED"
    private const val CODE_INVALID_TOKEN = "INVALID_TOKEN"
    private const val CODE_NOT_FOUND = "Not Found"

    override val id = FreeLanes.KILO_ID
    override val providerLabel = "Kilo"
    override val defaultBaseURL = BASE_URL

    /**
     * Both carry `isFree: true` and both streamed a real completion on
     * 2026-10-24, so a first install with no network still offers two working
     * models. The seed retires on the first successful fetch.
     */
    override val offlineSeedIds = listOf("kilo-auto/free", "nvidia/nemotron-3.5-lightning:free")

    /**
     * The listing declares free-ness outright, so a probe has nothing left to
     * discover — and must not be allowed to. Letting `learned.free` add an id
     * here would keep a model visible after upstream stopped advertising it,
     * which is the one direction of error the user cannot recover from on their
     * own.
     */
    override val discoversUndeclaredIds = false

    override fun owns(baseURL: String?, apiKey: String?): Boolean =
        apiKey.isNullOrBlank() && hostOf(baseURL ?: defaultBaseURL) == HOST

    /** Empty on purpose. See the class doc: any token at all is a 401 here. */
    override fun probeHeaders(): Map<String, String> = emptyMap()

    override fun declaredFree(id: String, serverSaysFree: Boolean?): Boolean = serverSaysFree == true

    /** Evidence is scoped per lane: the same id on another upstream is a different fact. */
    private fun hide(modelId: String, evidence: FreeLaneEvidence) =
        FreeLaneHealth.recordFailure(id, modelId, evidence)

    private fun answered(modelId: String) = FreeLaneHealth.recordSuccess(id, modelId)

    /**
     * Kilo's dialect is a `code` string, not Zen's `type`, and its codes are
     * consistent enough that both load-bearing verdicts — DEAD and PAID —
     * require a structured signal.
     *
     * A bare 401/403 with no code is therefore UNKNOWN, not PAID. The asymmetry
     * is deliberate: `learned.paid` is the only verdict with no expiry, so a
     * status-only inference removes a model permanently, while an UNKNOWN merely
     * schedules another probe after the backoff. When a code this lane has never
     * seen arrives it is logged with its status, which is enough to name it in a
     * follow-up from a device report alone.
     */
    override fun classify(id: String, code: Int, body: String): FreeLaneVerdict {
        val errorCode = FreeLaneBodies.errorCode(body)
        if (errorCode == null) return classifyByTransport(id, code, body)
        return classifyByErrorCode(id, code, errorCode, body)
    }

    private fun classifyByErrorCode(
        id: String,
        code: Int,
        errorCode: String,
        body: String,
    ): FreeLaneVerdict {
        if (errorCode == CODE_NOT_FOUND || FreeLaneBodies.errorDetail(body)
                .contains("not found for account", ignoreCase = true)
        ) {
            // Upstream removed the provider function behind an id the listing
            // still advertises. This is the only signal that it is gone.
            hide(id, FreeLaneEvidence.GONE)
            return FreeLaneVerdict.DEAD
        }
        val evidence = when (errorCode) {
            CODE_PAID_MODEL -> FreeLaneEvidence.FREE_TIER
            CODE_INVALID_TOKEN -> FreeLaneEvidence.CREDENTIAL_REJECTED
            else -> {
                AppLogger.info(TAG, "probe $id: unmapped kilo error code '$errorCode' (http $code)")
                FreeLaneEvidence.UNSPECIFIED_ERROR_TYPE
            }
        }
        hide(id, evidence)
        return if (evidence == FreeLaneEvidence.FREE_TIER) FreeLaneVerdict.PAID else FreeLaneVerdict.UNKNOWN
    }

    /**
     * No structured error in the body, so the status is all upstream said. Only
     * the shapes that name the model may hide it.
     */
    private fun classifyByTransport(id: String, code: Int, body: String): FreeLaneVerdict = when {
        code == 200 -> {
            answered(id)
            FreeLaneVerdict.FREE
        }
        code == 404 || code == 410 -> {
            hide(id, FreeLaneEvidence.GONE)
            FreeLaneVerdict.DEAD
        }
        code == 400 && body.contains("unavailable", ignoreCase = true) -> {
            hide(id, FreeLaneEvidence.MODEL_UNAVAILABLE)
            FreeLaneVerdict.DEAD
        }
        code == 429 -> {
            hide(id, FreeLaneEvidence.RATE_LIMITED)
            FreeLaneVerdict.UNKNOWN
        }
        code in 500..599 -> {
            hide(id, FreeLaneEvidence.SERVER)
            FreeLaneVerdict.UNKNOWN
        }
        // Includes a bare 401/403 with no code: our wiring or an upstream auth
        // hiccup, neither of which is a statement about the model.
        else -> FreeLaneVerdict.UNKNOWN
    }

    private fun hostOf(baseURL: String): String? =
        runCatching { baseURL.toHttpUrlOrNull()?.host }.getOrNull()
}
