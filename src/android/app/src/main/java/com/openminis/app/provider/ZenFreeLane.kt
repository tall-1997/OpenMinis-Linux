package com.openminis.app.provider

import com.openminis.app.logging.AppLogger

/**
 * The OpenCode Zen keyless lane.
 *
 * Recognition is the placeholder credential plus a `*.opencode.ai` host: Zen's
 * free tier requires a non-empty token (`Bearer public`), and a user who typed a
 * real Zen key at the same host is not ours to narrow.
 *
 * Probes carry that placeholder for the same reason — Zen rejects an anonymous
 * request with `FreeTierError` before it ever looks at the model.
 */
internal object ZenFreeLane : FreeLane {
    private const val TAG = "ZenFreeLane"
    private const val PUBLIC_CREDENTIAL = "Bearer public"

    /** The credential the lane must be configured with. A real key means "not ours". */
    const val API_KEY = "public"

    override val id = FreeLanes.ZEN_ID
    override val providerLabel = "Zen"
    override val defaultBaseURL: String = ZenEndpoint.ZEN_BUNDLED_ENDPOINT

    /**
     * Both satisfy [ZenFreeLaneRule] and both answered on 2026-10-24, so a first
     * install with no network still offers two working models. The seed retires
     * on the first successful fetch.
     */
    override val offlineSeedIds = listOf("deepseek-v4.1-flash-free", "big-pickle")

    override val discoversUndeclaredIds = true

    override fun owns(baseURL: String?, apiKey: String?): Boolean =
        apiKey == API_KEY && ZenEndpoint.isZenHost(baseURL ?: defaultBaseURL)

    override fun probeHeaders(): Map<String, String> = mapOf("Authorization" to PUBLIC_CREDENTIAL)

    override fun declaredFree(id: String, serverSaysFree: Boolean?): Boolean =
        ZenFreeLaneRule.isFreeLaneModelId(id)

    /** Evidence is scoped per lane: the same id on another upstream is a different fact. */
    private fun hide(modelId: String, evidence: FreeLaneEvidence) =
        FreeLaneHealth.recordFailure(id, modelId, evidence)

    private fun answered(modelId: String) = FreeLaneHealth.recordSuccess(id, modelId)

    override fun classify(id: String, code: Int, body: String): FreeLaneVerdict {
        val errorType = zenErrorType(body) ?: return classifyByTransport(id, code, body)
        return classifyByErrorType(id, code, errorType, body)
    }

    /**
     * Death is the only verdict a named type can carry, and it needs a sentence
     * about the model. Everything else is UNKNOWN, which [FreeLaneDirectory]
     * writes nothing for, so the id is simply probed again after the backoff.
     *
     * The distinctions that matter inside the dialect: `FreeTierError` after the
     * gate was satisfied is UNKNOWN, not PAID — it used to be read as "this
     * model needs a credential", but it is equally what a gate regression looks
     * like, and blaming the model for our bug hid the bug. `RegionError` is
     * UNKNOWN for the same reason in reverse: the lane is reachable from a
     * locked region through the user's own proxy, so a region refusal must not
     * remove a model that works for everyone else. A `ModelError` that never
     * says "not supported" belongs to the same class — an upstream complaint
     * with the model's name on it but no verdict attached.
     */
    private fun classifyByErrorType(id: String, code: Int, type: String, body: String): FreeLaneVerdict {
        if (type == "ModelError" && body.contains("not supported", ignoreCase = true)) {
            hide(id, FreeLaneEvidence.MODEL_NOT_SUPPORTED)
            return FreeLaneVerdict.DEAD
        }
        val evidence = when (type) {
            "RegionError" -> FreeLaneEvidence.REGION
            "FreeTierError" -> FreeLaneEvidence.FREE_TIER
            "ModelError" -> FreeLaneEvidence.MODEL_ERROR_UNSPECIFIED
            else -> {
                // Named, logged, unmapped: a new dialect word is an observation,
                // never a verdict. Keep the status in the log so the next one of
                // these can be identified from a device report alone.
                AppLogger.info(TAG, "probe $id: unmapped zen error type '$type' (http $code)")
                FreeLaneEvidence.UNSPECIFIED_ERROR_TYPE
            }
        }
        hide(id, evidence)
        return FreeLaneVerdict.UNKNOWN
    }

    /**
     * No structured error in the body, so the status is the only thing upstream
     * said. A bare 401 or 403 really does mean this id wants a credential; the
     * string-shaped `{"error":"Model is unavailable."}` really is a switched-off
     * model. Both shapes arrive without a nested error type, which is what keeps
     * them distinguishable from the dialect above.
     */
    private fun classifyByTransport(id: String, code: Int, body: String): FreeLaneVerdict = when {
        code == 200 -> {
            // A clean 200 is the only positive evidence this layer ever sees, so
            // it is also the only thing allowed to cancel a death verdict.
            // Without this the evidence is one-sided: a probe can hide a model,
            // and only the 7-day TTL can bring it back.
            answered(id)
            FreeLaneVerdict.FREE
        }
        code == 410 -> {
            hide(id, FreeLaneEvidence.GONE)
            FreeLaneVerdict.DEAD
        }
        code == 400 && body.contains("Model is unavailable", ignoreCase = true) -> {
            hide(id, FreeLaneEvidence.MODEL_UNAVAILABLE)
            FreeLaneVerdict.DEAD
        }
        code == 401 || code == 403 -> {
            // Credentials genuinely required for this id.
            hide(id, FreeLaneEvidence.FREE_TIER)
            FreeLaneVerdict.PAID
        }
        code == 429 -> {
            hide(id, FreeLaneEvidence.RATE_LIMITED)
            FreeLaneVerdict.UNKNOWN
        }
        code in 500..599 -> {
            hide(id, FreeLaneEvidence.SERVER)
            FreeLaneVerdict.UNKNOWN
        }
        else -> FreeLaneVerdict.UNKNOWN
    }

    /**
     * The nested `error.type`, read from either spelling by [FreeLaneBodies].
     * Null means the body carried no structured error, so the transport map is
     * allowed to speak.
     */
    private fun zenErrorType(body: String): String? = FreeLaneBodies.errorType(body)
}
