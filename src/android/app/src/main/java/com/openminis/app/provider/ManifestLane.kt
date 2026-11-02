package com.openminis.app.provider

import com.openminis.app.logging.AppLogger
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * The generic manifest-driven free lane: an OpenAI-compatible upstream whose
 * instance layer came from [FreeLaneManifest] rather than from a compiled
 * [FreeLane] implementation.
 *
 * This is what makes "a new free lane without a release" possible: any
 * upstream that speaks OpenAI JSON and declares its own free slice is one
 * manifest entry. The trade-off that keeps it safe is the same one Kilo
 * teaches — the listing's declaration is the authority
 * ([declaredFree]), probing collects liveness only, and a bare status code
 * is never read as a statement about a model.
 *
 * Error-dialect policy (aligned with [KiloFreeLane], deliberately not
 * looser): a structured `error.code` gets one of the words this lane knows;
 * an unknown word is logged and stays UNKNOWN; a response with no structured
 * code falls back to transport rules where 401/403 alone mean nothing.
 */
internal class ManifestLane(private val entry: ManifestLaneEntry) : FreeLane {

    private val host = runCatching { entry.baseURL.toHttpUrlOrNull()?.host }.getOrNull()

    override val id = entry.id
    override val providerLabel = entry.label
    override val defaultBaseURL: String = entry.baseURL

    override val offlineSeedIds: List<String> = entry.offlineSeedIds

    /**
     * The listing declares free-ness outright, so a probe has nothing left to
     * discover — exactly Kilo's contract, and for the same reason: letting a
     * probe add an id here would keep a model visible after upstream stopped
     * advertising it.
     */
    override val discoversUndeclaredIds = false

    /**
     * Host recognition. The credential does not participate: a user-supplied
     * account token at this host IS the lane (that is the accountToken
     * multi-account contract), and an anonymous call to it is the lane too.
     * A user-built provider at the same host is therefore also "ours" —
     * which is correct for a lane whose whole point is pooled accounts, and
     * is what lets a manifest lane claim the user's pooled keys on refresh.
     */
    override fun owns(baseURL: String?, apiKey: String?): Boolean =
        hostOf(baseURL?.takeIf { it.isNotBlank() } ?: defaultBaseURL) == host

    override fun probeHeaders(): Map<String, String> =
        if (entry.credentialConvention == CredentialConvention.FIXED) {
            mapOf("Authorization" to "Bearer ${entry.credentialValue}")
        } else {
            // none: any token is rejected upstream. accountToken: a probe has
            // no account context and must not borrow the user's pooled keys.
            emptyMap()
        }

    override fun declaredFree(id: String, serverSaysFree: Boolean?): Boolean = serverSaysFree == true

    /** Evidence is scoped per lane: the same id on another upstream is a different fact. */
    private fun hide(modelId: String, evidence: FreeLaneEvidence) =
        FreeLaneHealth.recordFailure(id, modelId, evidence)

    private fun answered(modelId: String) = FreeLaneHealth.recordSuccess(id, modelId)

    override fun classify(id: String, code: Int, body: String): FreeLaneVerdict {
        val errorCode = FreeLaneBodies.errorCode(body)
        if (errorCode == null) return classifyByTransport(id, code, body)
        return classifyByErrorCode(id, code, errorCode, body)
    }

    private fun classifyByErrorCode(id: String, code: Int, errorCode: String, body: String): FreeLaneVerdict {
        if (errorCode == CODE_NOT_FOUND || FreeLaneBodies.errorDetail(body)
                .contains("not found for account", ignoreCase = true)
        ) {
            hide(id, FreeLaneEvidence.GONE)
            return FreeLaneVerdict.DEAD
        }
        val evidence = when (errorCode) {
            CODE_PAID_MODEL -> FreeLaneEvidence.FREE_TIER
            CODE_INVALID_TOKEN -> FreeLaneEvidence.CREDENTIAL_REJECTED
            else -> {
                AppLogger.info("ManifestLane", "probe $id: unmapped error code '$errorCode' (http $code)")
                FreeLaneEvidence.UNSPECIFIED_ERROR_TYPE
            }
        }
        hide(id, evidence)
        return if (evidence == FreeLaneEvidence.FREE_TIER) FreeLaneVerdict.PAID else FreeLaneVerdict.UNKNOWN
    }

    /**
     * No structured error in the body, so the status is all upstream said.
     * Only the shapes that name the model may hide it; a bare 401/403 with no
     * structured word is UNKNOWN — the lane's own declaration already covers
     * membership, and a status-only inference would let an auth hiccup
     * permanently drop an id from a lane whose catalogue it lives in.
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
        else -> FreeLaneVerdict.UNKNOWN
    }

    private fun hostOf(baseURL: String): String? =
        runCatching { baseURL.toHttpUrlOrNull()?.host }.getOrNull()

    private companion object {
        const val CODE_PAID_MODEL = "PAID_MODEL_AUTH_REQUIRED"
        const val CODE_INVALID_TOKEN = "INVALID_TOKEN"
        const val CODE_NOT_FOUND = "Not Found"
    }
}
