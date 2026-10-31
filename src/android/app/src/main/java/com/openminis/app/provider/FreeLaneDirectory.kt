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
 * Narrows a live catalogue down to one keyless free lane, and keeps it honest
 * in the background. Lane-independent: the per-lane decisions live in
 * [FreeLane], and this file is the machinery that used to be welded to Zen.
 *
 * Three things happen here and nowhere else:
 *
 * 1. **Narrowing.** [visible] keeps the ids the lane's own authority declares
 *    free, plus — only on a lane that can discover them — ids a probe already
 *    proved free. Anything upstream declared gone, or that answered "bring a
 *    credential", is dropped. A paid model in the same catalogue is never shown
 *    on the free lane.
 * 2. **Offline safety.** An empty live payload is treated as a fetch failure:
 *    the last cached id set is rebuilt into model skeletons instead, so a
 *    dropped network never empties the picker. Metadata is lost in that
 *    fallback (only id / displayName / provider survive) and comes back on the
 *    next successful refresh — an incomplete row the user can still pick beats
 *    an empty list.
 * 3. **Incremental probing.** Two classes of id get one minimal request each,
 *    in the background, never blocking the refresh that triggered them:
 *    *discovery* (a lane whose listing cannot declare free-ness) and *liveness*
 *    (an id we are about to show that carries no verdict yet). Verdicts land in
 *    [FreeLaneLearned] and [FreeLaneHealth] for next time.
 *
 * Liveness probing is why a dead model does not sit in the picker forever. The
 * store that hides models is only ever written from here — live traffic cannot
 * distinguish a real answer from a 200-shaped refusal without consuming the
 * stream — so an id that is never probed is an id whose death is never
 * observed. Zen shipped exactly that gap: rule-shaped ids were excluded from
 * probing, so a `-free` model retired upstream stayed visible and failing until
 * the user hit it. Kilo's listing advertises a model whose provider function
 * was removed upstream (404 `Not found for account`) with byte-identical
 * metadata to its healthy neighbours, which makes the gap impossible to close
 * from the catalogue alone.
 *
 * Probes go through [OpenAILlmHttpClient] so lane-specific request shaping
 * ([ZenFreeLaneGate]) applies to them exactly as it does to real traffic: a
 * probe that returns 200 proves the model works on the lane as it is actually
 * used, not merely that it appears in a list.
 */
internal object FreeLaneDirectory {
    private const val TAG = "FreeLaneDirectory"
    private const val PROBE_PATH = "/chat/completions"

    /**
     * How long an id with no verdict waits before being probed again.
     *
     * An UNKNOWN verdict writes nothing on purpose — a rate-limited or
     * unreachable probe must not be mistaken for evidence — but "writes nothing"
     * also means "asks again next refresh", and a lane under a multi-hour quota
     * window would otherwise be re-probed on every single refresh for the whole
     * window. One attempt per window per id keeps that bounded without changing
     * what any verdict means.
     */
    const val PROBE_BACKOFF_MS = 6L * 60 * 60 * 1000

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val client by lazy { OpenAILlmHttpClient.build() }
    private val JSON = "application/json".toMediaType()

    /**
     * Narrow [live] to [lane]. Returns the previous catalogue when the fetch
     * produced nothing, so callers never hand an empty list to `replaceEntries`
     * — which prunes non-custom rows and would wipe the picker.
     *
     * [serverSaysFree] carries the listing's own free declaration per id, or is
     * empty for a lane whose listing makes none.
     */
    suspend fun resolve(
        context: Context?,
        lane: FreeLane,
        baseURL: String?,
        live: List<LLMModel>,
        serverSaysFree: Map<String, Boolean> = emptyMap(),
    ): List<LLMModel> = withContext(Dispatchers.IO) {
        val key = FreeLanes.cacheKey(lane, baseURL)
        val cache = FreeLaneCatalogCache(context)
        val snapshot = cache.loadOrSeed(lane, key)

        if (live.isEmpty()) {
            val fallback = snapshot.ids.map { id ->
                LLMModel(id = id, displayName = id, provider = lane.providerLabel)
            }
            AppLogger.warning(
                TAG,
                "${lane.id}: empty live catalogue (${if (snapshot.isOfflineSeed) "offline seed" else "stale cache"}), " +
                    "keeping ${fallback.size} ids instead of emptying the lane",
            )
            return@withContext fallback
        }

        val learned = snapshot.learned
        val free = visible(lane, live, serverSaysFree, learned)
        cache.save(
            key,
            snapshot.copy(
                ids = free.map { it.id },
                learned = learned,
                fetchedAtMs = System.currentTimeMillis(),
                isOfflineSeed = false,
                isStale = false,
            ),
        )

        val toProbe = needsProbe(lane, live, serverSaysFree, learned, snapshot.probedAtMs)
        if (toProbe.isNotEmpty()) {
            AppLogger.info(TAG, "${lane.id}: ${toProbe.size} ids to probe in the background")
            launchProbe(context, lane, baseURL, key, toProbe, learned, free.map { it.id }, snapshot.probedAtMs)
        }
        free
    }

    /**
     * What the user may pick: declared free, or proven free on a lane that can
     * discover ids; never paid, never dead.
     */
    internal fun visible(
        lane: FreeLane,
        models: List<LLMModel>,
        serverSaysFree: Map<String, Boolean>,
        learned: FreeLaneLearned,
    ): List<LLMModel> = models.filter { model ->
        val id = model.id
        id !in learned.paid &&
            isFree(lane, id, serverSaysFree[id], learned) &&
            !FreeLaneHealth.isDead(lane.id, id)
    }

    /** Membership including learned evidence, without the health/paid filters. */
    private fun isFree(
        lane: FreeLane,
        id: String,
        serverSaysFree: Boolean?,
        learned: FreeLaneLearned,
    ): Boolean = lane.declaredFree(id, serverSaysFree) ||
        (lane.discoversUndeclaredIds && id in learned.free)

    /**
     * Ids worth one lightweight probe, in two classes:
     * - **discovery** — the lane's authority cannot decide, and a 200 would add
     *   the id to the catalogue. Only on a lane that [FreeLane.discoversUndeclaredIds].
     * - **liveness** — the id is about to be shown and carries no verdict, so
     *   nothing has ever observed whether upstream still serves it.
     *
     * Both skip anything already decided, anything hidden, and anything probed
     * inside [PROBE_BACKOFF_MS], so a pass is incremental rather than a full
     * re-scan of a several-hundred-entry catalogue on every refresh.
     */
    internal fun needsProbe(
        lane: FreeLane,
        models: List<LLMModel>,
        serverSaysFree: Map<String, Boolean>,
        learned: FreeLaneLearned,
        probedAtMs: Map<String, Long>,
        now: Long = System.currentTimeMillis(),
    ): List<String> = models.asSequence()
        .map { it.id }
        .distinct()
        .filter { it !in learned.paid }
        .filter { id ->
            val declared = lane.declaredFree(id, serverSaysFree[id])
            val proven = id in learned.free
            // A proven id is settled: it needs neither discovery nor liveness.
            val discovery = lane.discoversUndeclaredIds && !declared
            val liveness = declared
            !proven && (discovery || liveness)
        }
        .filter { !FreeLaneHealth.isDead(lane.id, it) }
        .filter { id ->
            val at = probedAtMs[id] ?: return@filter true
            now - at >= PROBE_BACKOFF_MS
        }
        .toList()

    /**
     * Background incremental probe. Never awaited by [resolve]: the refresh
     * returns the visible catalogue immediately and the learned evidence
     * improves the next one.
     */
    private fun launchProbe(
        context: Context?,
        lane: FreeLane,
        baseURL: String?,
        key: String,
        ids: List<String>,
        learned: FreeLaneLearned,
        knownIds: List<String>,
        probedAtMs: Map<String, Long>,
    ) {
        scope.launch {
            var updated = learned
            val probed = probedAtMs.toMutableMap()
            val now = System.currentTimeMillis()
            for (id in ids) {
                probed[id] = now
                val verdict = probeOne(lane, baseURL, id)
                updated = when {
                    verdict == FreeLaneVerdict.FREE -> updated.withFree(id)
                    // A "bring a credential" answer about an id the lane's own
                    // authority declares free is contradictory evidence, and
                    // learned.paid is the one verdict with no expiry. Persisting
                    // it would let a single upstream auth hiccup remove a model
                    // permanently — the mechanism that emptied the Zen lane on
                    // 2026-10-17. Log it, leave the catalogue alone, and let a
                    // later probe or the user's own request settle it.
                    verdict == FreeLaneVerdict.PAID && lane.declaredFree(id, null) -> {
                        AppLogger.warning(
                            TAG,
                            "${lane.id}: probe $id said 'needs a credential' but the lane declares it free — " +
                                "not persisting a permanent verdict on contradictory evidence",
                        )
                        updated
                    }
                    // [P2-convergence] learned.paid / FreeLaneHealth death marks are the
                    // free-lane side of the permanence spectrum whose 429/body-marker end
                    // lives in HttpRetryAfter.classifyCapacityBody; see FreeLaneEvidence
                    // .marksDead for why the two stay separate vocabularies.
                    verdict == FreeLaneVerdict.PAID -> updated.withPaid(id)
                    // DEAD is recorded inside the lane's classify via
                    // FreeLaneHealth. UNKNOWN writes nothing at all.
                    else -> updated
                }
            }
            val merged = (knownIds + updated.free.filter { it !in updated.paid }).distinct()
            FreeLaneCatalogCache(context).save(
                key,
                FreeLaneCatalogSnapshot(
                    ids = merged,
                    learned = updated,
                    fetchedAtMs = System.currentTimeMillis(),
                    probedAtMs = probed,
                ),
            )
            AppLogger.info(
                TAG,
                "${lane.id}: probe pass done — ${updated.free.size} learned free, ${updated.paid.size} learned paid",
            )
        }
    }

    private suspend fun probeOne(lane: FreeLane, baseURL: String?, id: String): FreeLaneVerdict =
        withContext(Dispatchers.IO) {
            val url = FreeLanes.cacheKey(lane, baseURL) + PROBE_PATH
            // Minimal body on purpose: max_tokens=1 keeps it cheap, and any
            // lane-specific shaping happens in the interceptor so the probe
            // travels in the same shape as a real call.
            val payload = JSONObject()
                .put("model", id)
                .put(
                    "messages",
                    JSONArray().put(JSONObject().put("role", "user").put("content", "ping")),
                )
                .put("max_tokens", 1)
            val builder = Request.Builder()
                .url(url)
                .post(payload.toString().toRequestBody(JSON))
            // A lane that must be called with NO credential gets none. Sending a
            // placeholder here would produce a stream of 401s that read as
            // "this model is paid" and hide the whole free slice.
            lane.probeHeaders().forEach { (name, value) -> builder.header(name, value) }
            runCatching {
                client.newCall(builder.build()).execute().use { response ->
                    // Reading the body matters: a refusal can arrive as a
                    // 200-shaped SSE error or as a JSON error dialect, so the
                    // status code alone cannot classify the verdict.
                    val text = runCatching { response.body?.string().orEmpty() }.getOrDefault("")
                    lane.classify(id, response.code, text)
                }
            }.getOrElse {
                AppLogger.info(TAG, "${lane.id}: probe $id failed to reach the endpoint: ${it.message}")
                FreeLaneVerdict.UNKNOWN
            }
        }
}
