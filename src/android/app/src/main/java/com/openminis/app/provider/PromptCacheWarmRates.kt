package com.openminis.app.provider

import android.content.Context
import android.util.Log
import java.io.File
import org.json.JSONObject

/**
 * [T-prompt-cache-warming] Per-model prompt-cache pricing for
 * [PromptCacheWarmer.evaluateWarming]. pi reads `Model.cost` (input / output /
 * cacheRead / cacheWrite) off its bundled catalog; this codebase's
 * ModelsDevApi parser extracts only `cost.output`, so this side-car reads the
 * SAME models.dev registry — the disk cache ModelsDevApi already maintains,
 * falling back to the bundled gzip asset — and extracts the full cost object
 * WITHOUT touching ModelsDevApi (that file sits at the architecture line cap
 * and its consumers only need output pricing).
 *
 * models.dev publishes USD per MILLION tokens; [Rates] carries USD per token,
 * the unit [PromptCacheWarmer.evaluateWarming] takes. When the catalog has no
 * entry for a model id (private relays, renamed deployments) `forModel`
 * returns null and the warming planner treats economics as unavailable —
 * it never invents a rate, matching pi's "economics unknown → do not warm"
 * posture.
 */
object PromptCacheWarmRates {

    /** USD per token, resolved from the models.dev registry cost object. */
    data class Rates(
        val inputPerTokenUsd: Double,
        val outputPerTokenUsd: Double,
        val cacheReadPerTokenUsd: Double,
        val cacheWritePerTokenUsd: Double,
    )

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var cachedRegistryJson: String? = null

    /** Per-model memo: the registry parse is expensive, foreground checks are not. */
    private val memo = java.util.concurrent.ConcurrentHashMap<String, Rates?>()

    /** Prime with the app context (MinisApp.onCreate, ToolLimitPrefs pattern). */
    fun prime(context: Context) {
        appContext = context.applicationContext
    }

    /**
     * Resolve rates for [modelId]; null when unprimed or the registry lacks the
     * model. Negative results are memoized too (containsKey, not a null-tolerant
     * getOrDefault) so an unknown relay id does not re-parse the registry text
     * on every foreground check.
     */
    fun forModel(modelId: String): Rates? {
        if (memo.containsKey(modelId)) return memo[modelId]
        val resolved = registryJson()?.let { parseRates(it, modelId) }
        memo[modelId] = resolved
        return resolved
    }

    /**
     * Extract the cost object for [modelId] from a models.dev registry
     * document. Pure JVM (testable): matches the id exactly across every
     * provider block first, then falls back to the final path segment of an
     * "org/model"-shaped id (relays persist prefixed ids while the registry
     * keys bare ids).
     */
    internal fun parseRates(registryJson: String, modelId: String): Rates? {
        val root = runCatching { JSONObject(registryJson) }.getOrNull() ?: return null
        val names = root.names() ?: return null
        val candidates = buildList {
            add(modelId)
            modelId.substringAfterLast('/').takeIf { it != modelId }?.let { add(it) }
        }
        for (i in 0 until names.length()) {
            val providerBlock = root.optJSONObject(names.optString(i)) ?: continue
            val models = providerBlock.optJSONObject("models") ?: continue
            for (candidate in candidates) {
                val model = models.optJSONObject(candidate) ?: continue
                parseCostObject(model.optJSONObject("cost"))?.let { return it }
            }
        }
        return null
    }

    /**
     * Convert one models.dev `cost` object (USD per million tokens) into
     * per-token rates. cache_write is frequently absent (OpenAI publishes
     * only input/output/cache_read); a missing side stays 0.0 and
     * [PromptCacheWarmer.evaluateWarming] already treats a non-positive cache
     * write as "price the miss at input" — pi's documented fallback.
     */
    internal fun parseCostObject(cost: JSONObject?): Rates? {
        if (cost == null) return null
        fun perToken(key: String): Double? =
            cost.optDouble(key, Double.NaN)
                .takeIf { !it.isNaN() && it >= 0.0 }
                ?.div(1_000_000.0)
        val input = perToken("input") ?: return null
        val output = perToken("output") ?: return null
        return Rates(
            inputPerTokenUsd = input,
            outputPerTokenUsd = output,
            cacheReadPerTokenUsd = perToken("cache_read") ?: 0.0,
            cacheWritePerTokenUsd = perToken("cache_write") ?: 0.0,
        )
    }

    // ── Registry source (same tiers as ModelsDevApi, read-only) ─────────────

    /**
     * Load the registry text once per process: the disk cache ModelsDevApi
     * wrote (authoritative, network-refreshed) → the bundled gzip asset. The
     * loaded copy is cached; a failed refresh simply keeps the last good one.
     */
    private fun registryJson(): String? {
        cachedRegistryJson?.let { return it }
        return try {
            val loaded = readDiskCache() ?: readBundledAsset() ?: return null
            cachedRegistryJson = loaded
            loaded
        } catch (e: Exception) {
            Log.w("PromptCacheWarm", "registry read failed: ${e.message}")
            null
        }
    }

    private fun readDiskCache(): String? {
        val ctx = appContext ?: return null
        // Mirrors ModelsDevApi.getCacheFile's path — keep in step if that moves.
        val file = File(File(ctx.cacheDir, "models-dev-cache"), "api.json")
        if (!file.exists()) return null
        return runCatching { file.readText() }.getOrNull()
    }

    private fun readBundledAsset(): String? {
        val ctx = appContext ?: return null
        return runCatching {
            ctx.assets.open("models-dev-api.json.gzip").use { raw ->
                java.util.zip.GZIPInputStream(raw).bufferedReader().readText()
            }
        }.getOrNull()
    }
}
