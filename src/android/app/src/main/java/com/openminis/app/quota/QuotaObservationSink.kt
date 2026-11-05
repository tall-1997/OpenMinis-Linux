package com.openminis.app.quota

import com.openminis.app.logging.AppLogger

// [T-provider-quota] Entry point for the providers: passive quota observation
// off chat responses. Port of iOS QuotaStore's observation sink.
// [port-upstream-1.16] Ported from OpenMinis/OpenMinis upstream 1a71df0f
// (quota/QuotaStore.kt), split into its own file. Chat-side callers
// (AnthropicProvider / OpenAIProvider streaming paths) are a follow-up wave —
// the sink is idempotent and cheap, so wiring it there is additive.

private const val TAG = "Quota"

/**
 * [T-quota-claude-subscription] Called on the network thread for every
 * response of an OAuth Claude / Codex instance. Filters on the calling thread
 * and allocates nothing unless the response carries usage headers; only then
 * hands them to the store.
 */
object QuotaObservationSink {
    fun recordAnthropicResponse(instanceId: String?, headers: okhttp3.Headers) {
        instanceId ?: return
        var found: HashMap<String, String>? = null
        for (i in 0 until headers.size) {
            val key = headers.name(i).lowercase()
            if (!key.startsWith("anthropic-ratelimit-")) continue
            (found ?: HashMap<String, String>().also { found = it })[key] = headers.value(i)
        }
        val h = found ?: return
        if (h.keys.none { it.startsWith(ClaudeSubscriptionQuotaAdapter.HEADER_PREFIX) }) return
        observe(instanceId, QuotaVendor.claudeSubscription, h)
    }

    /** [T-quota-codex-subscription] Codex replies (OpenAIProvider streaming path, errors included). */
    fun recordCodexResponse(instanceId: String?, headers: okhttp3.Headers) {
        instanceId ?: return
        var found: HashMap<String, String>? = null
        for (i in 0 until headers.size) {
            val key = headers.name(i).lowercase()
            if (!key.startsWith(CodexSubscriptionQuotaAdapter.HEADER_PREFIX)) continue
            (found ?: HashMap<String, String>().also { found = it })[key] = headers.value(i)
        }
        val h = found ?: return
        if (h.keys.none { it.endsWith("-used-percent") }) return
        observe(instanceId, QuotaVendor.codexSubscription, h)
    }

    /**
     * [T-quota-subscription-crossreview] The sink runs inside the chat
     * request, before the reply body is read (iOS hands off to the main actor
     * instead, so its bookkeeping can never touch the request). Quota
     * bookkeeping must never fail a chat: anything it throws is logged and
     * dropped here.
     */
    private fun observe(instanceId: String, vendor: QuotaVendor, headers: Map<String, String>) {
        try {
            QuotaStore.observeSubscriptionHeaders(instanceId, vendor, headers)
        } catch (e: Exception) {
            AppLogger.warning(TAG, "[Quota] header observation dropped vendor=${vendor.name}: ${e.javaClass.simpleName} ${e.message}")
        }
    }
}
