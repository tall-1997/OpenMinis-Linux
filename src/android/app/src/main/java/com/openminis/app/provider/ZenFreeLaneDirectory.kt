package com.openminis.app.provider

import android.content.Context
import com.openminis.app.data.model.LLMModel
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.openai.OpenAILlmHttpClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Narrows the live Zen catalogue down to the keyless free lane, and teaches
 * itself which unnamed ids are free.
 *
 * Three things happen here and nowhere else:
 *
 * 1. **Narrowing.** [ZenFreeLaneCatalog.filterFree] keeps rule-shaped ids plus
 *    whatever probing already proved free, and drops anything upstream declared
 *    gone. A paid model in the same catalogue is never shown on the free lane.
 * 2. **Offline safety.** An empty live payload is treated as a fetch failure:
 *    the last cached id set is rebuilt into model skeletons instead, so a
 *    dropped network never empties the picker. Metadata is lost in that
 *    fallback (only id / displayName / provider survive) and comes back on the
 *    next successful refresh — an incomplete row the user can still pick beats
 *    an empty list.
 * 3. **Incremental probing.** Ids the rule cannot decide get one minimal
 *    request each, in the background, never blocking the refresh that triggered
 *    it. The verdicts land in [ZenLearnedFree] for next time.
 *
 * Probes go through [OpenAILlmHttpClient], so [ZenFreeLaneGate] rewrites them
 * exactly like real traffic: a probe that returns 200 proves the model works on
 * the lane as it is actually used, not merely that it appears in a list.
 */
internal object ZenFreeLaneDirectory {
    private const val TAG = "ZenFreeLaneDirectory"
    private const val PUBLIC_CREDENTIAL = "Bearer public"
    private const val DEFAULT_BASE = "https://opencode.ai/zen/v1"
    private const val ZEN_PROVIDER = "Zen"
    private const val PROBE_PATH = "/chat/completions"

    /** The placeholder credential the keyless lane uses; a real key means "not ours". */
    fun isKeylessLane(apiKey: String?): Boolean = apiKey == "public"

    fun cacheKey(baseURL: String?): String = (baseURL ?: DEFAULT_BASE).trimEnd('/')

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy { OpenAILlmHttpClient.build() }
    private val JSON = "application/json".toMediaType()

    /** What one probe concluded about one id. */
    internal enum class Verdict { FREE, PAID, DEAD, UNKNOWN }

    /**
     * Narrow [live] to the free lane. Returns the previous catalogue when the
     * fetch produced nothing, so callers never hand an empty list to
     * `replaceEntries` — which prunes non-custom rows and would wipe the picker.
     */
    suspend fun resolve(
        context: Context?,
        baseURL: String?,
        live: List<LLMModel>,
    ): List<LLMModel> = withContext(Dispatchers.IO) {
        val key = cacheKey(baseURL)
        val cache = ZenCatalogCache(context)
        val snapshot = cache.loadOrSeed(key)

        if (live.isEmpty()) {
            val fallback = snapshot.ids.map { id ->
                LLMModel(id = id, displayName = id, provider = ZEN_PROVIDER)
            }
            AppLogger.warning(
                TAG,
                "empty live catalogue (${if (snapshot.isOfflineSeed) "offline seed" else "stale cache"}), " +
                    "keeping ${fallback.size} ids instead of emptying the lane",
            )
            return@withContext fallback
        }

        val free = ZenFreeLaneCatalog.filterFree(live, snapshot.learned)
        cache.save(
            key,
            ZenCatalogSnapshot(
                ids = free.map { it.id },
                learned = snapshot.learned,
                fetchedAtMs = System.currentTimeMillis(),
            ),
        )

        val toProbe = ZenFreeLaneCatalog.needsProbe(live, snapshot.learned)
        if (toProbe.isNotEmpty()) {
            AppLogger.info(TAG, "${toProbe.size} undecided ids, probing in the background")
            launchProbe(context, baseURL, key, toProbe, snapshot.learned, free.map { it.id })
        }
        free
    }

    /**
     * Background incremental probe. Never awaited by [resolve]: the refresh
     * returns the rule-shaped catalogue immediately and the learned evidence
     * improves the next one.
     */
    private fun launchProbe(
        context: Context?,
        baseURL: String?,
        key: String,
        ids: List<String>,
        learned: ZenLearnedFree,
        knownIds: List<String>,
    ) {
        scope.launch {
            var updated = learned
            for (id in ids) {
                when (probeOne(baseURL, id)) {
                    Verdict.FREE -> updated = updated.withFree(id)
                    Verdict.PAID -> updated = updated.withPaid(id)
                    // DEAD is recorded inside probeOne via ZenFreeLaneHealth.
                    // UNKNOWN writes nothing at all, so a rate-limited or
                    // unreachable probe is simply retried next refresh.
                    Verdict.DEAD, Verdict.UNKNOWN -> Unit
                }
            }
            val merged = (knownIds + updated.free.filter { it !in updated.paid }).distinct()
            ZenCatalogCache(context).save(
                key,
                ZenCatalogSnapshot(
                    ids = merged,
                    learned = updated,
                    fetchedAtMs = System.currentTimeMillis(),
                ),
            )
            AppLogger.info(
                TAG,
                "probe pass done: ${updated.free.size} learned free, ${updated.paid.size} learned paid",
            )
        }
    }

    private suspend fun probeOne(baseURL: String?, id: String): Verdict =
        withContext(Dispatchers.IO) {
            val url = (baseURL ?: DEFAULT_BASE).trimEnd('/') + PROBE_PATH
            // Minimal body on purpose: ZenFreeLaneGate injects the four gate
            // tools and flips stream on, so the probe travels in the same shape
            // as a real call. max_tokens=1 keeps it cheap.
            val payload = JSONObject()
                .put("model", id)
                .put(
                    "messages",
                    JSONArray().put(JSONObject().put("role", "user").put("content", "ping")),
                )
                .put("max_tokens", 1)
            val request = Request.Builder()
                .url(url)
                .header("Authorization", PUBLIC_CREDENTIAL)
                .post(payload.toString().toRequestBody(JSON))
                .build()
            runCatching {
                client.newCall(request).execute().use { response ->
                    // Reading the body matters: Zen reports refusals as 200-shaped
                    // SSE errors and as a JSON error dialect, so the code alone
                    // cannot classify the verdict.
                    val text = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
                    classify(id, response.code, text)
                }
            }.getOrElse {
                AppLogger.info(TAG, "probe $id failed to reach the endpoint: ${it.message}")
                Verdict.UNKNOWN
            }
        }

    /**
     * Maps a probe response onto a verdict.
     *
     * The distinction that matters: `FreeTierError` after the gate was
     * satisfied is UNKNOWN, not PAID. It used to be read as "this model needs a
     * credential", which is how the whole lane got retired — but it is equally
     * what a gate regression looks like, and blaming the model for our bug hid
     * the bug. `RegionError` is UNKNOWN for the same reason in reverse: the lane
     * is reachable from a locked region through the user's own proxy, so a
     * region refusal must not remove a model that works for everyone else.
     */
    internal fun classify(id: String, code: Int, body: String): Verdict {
        val errorType = zenErrorType(body)
        when (errorType) {
            "RegionError" -> {
                ZenFreeLaneHealth.recordFailure(id, ZenFailureEvidence.REGION)
                return Verdict.UNKNOWN
            }
            "FreeTierError" -> {
                ZenFreeLaneHealth.recordFailure(id, ZenFailureEvidence.FREE_TIER)
                return Verdict.UNKNOWN
            }
            "ModelError" -> {
                if (body.contains("not supported", ignoreCase = true)) {
                    ZenFreeLaneHealth.recordFailure(id, ZenFailureEvidence.MODEL_NOT_SUPPORTED)
                    return Verdict.DEAD
                }
            }
        }
        return when {
            code == 200 -> Verdict.FREE
            code == 410 -> {
                ZenFreeLaneHealth.recordFailure(id, ZenFailureEvidence.GONE)
                Verdict.DEAD
            }
            code == 400 && body.contains("Model is unavailable", ignoreCase = true) -> {
                ZenFreeLaneHealth.recordFailure(id, ZenFailureEvidence.MODEL_UNAVAILABLE)
                Verdict.DEAD
            }
            code == 401 || code == 403 -> {
                // Credentials genuinely required for this id.
                ZenFreeLaneHealth.recordFailure(id, ZenFailureEvidence.FREE_TIER)
                Verdict.PAID
            }
            code == 429 -> {
                ZenFreeLaneHealth.recordFailure(id, ZenFailureEvidence.RATE_LIMITED)
                Verdict.UNKNOWN
            }
            code in 500..599 -> {
                ZenFreeLaneHealth.recordFailure(id, ZenFailureEvidence.SERVER)
                Verdict.UNKNOWN
            }
            else -> Verdict.UNKNOWN
        }
    }

    /** `{"type":"error","error":{"type":"ModelError"|…}}`, or null when absent. */
    private fun zenErrorType(body: String): String? {
        if (body.isEmpty() || !body.contains("error")) return null
        return runCatching {
            JSONObject(body).optJSONObject("error")?.optString("type")?.takeIf { it.isNotEmpty() }
        }.getOrNull()
    }
}
