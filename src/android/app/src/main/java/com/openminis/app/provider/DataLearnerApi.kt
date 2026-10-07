package com.openminis.app.provider

import android.content.Context
import android.util.Log
import com.openminis.app.data.model.LLMModel
import com.openminis.app.network.withDohDns
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * DataLearner is the fallback catalog when models.dev has no (or incomplete)
 * context / max-output for a model. Search JSON is id-only; the detail HTML
 * holds `#basic-info` plus RSC `thinkingModes`. Cached overlays never overwrite
 * a field models.dev already filled.
 */
object DataLearnerApi {
    private const val TAG = "DataLearnerApi"
    private const val SEARCH_URL =
        "https://www.datalearner.com/api/v4/ai-resources/pretrained-models/search"
    private const val DETAIL_URL =
        "https://www.datalearner.com/ai-models/pretrained-models/"
    private const val HIT_TTL_MS = 48 * 3600 * 1000L
    private const val MISS_TTL_MS = 12 * 3600 * 1000L
    private const val MAX_BACKGROUND_FILLS = 6

    private val httpClient = OkHttpClient.Builder()
        // [T-doh-resolver-fallback] DoH when the system resolver cannot name
        // the host at all — an UnknownHostException here is unrecoverable by
        // the retry layer, since every retry re-runs the same failed lookup.
        .withDohDns()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    private var appContext: Context? = null
    private val cacheLock = Any()
    private var memoryEntries: MutableMap<String, Overlay> = HashMap()
    private var memoryMisses: MutableMap<String, Long> = HashMap()
    private var cacheLoaded = false
    private val backgroundRunning = AtomicBoolean(false)
    private val pendingFills = ConcurrentLinkedQueue<LLMModel>()
    private val inFlight = HashSet<String>()

    data class Overlay(
        val modelCode: String,
        val contextWindow: Int? = null,
        val maxOutputTokens: Int? = null,
        val supportsReasoning: Boolean? = null,
        val reasoningEffortValues: List<String>? = null,
        val fetchedAt: Long = 0L,
    )

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun supplement(model: LLMModel, fetchIfMissing: Boolean): LLMModel {
        if (!DataLearnerParser.shouldLookup(model.id)) return model
        if (!DataLearnerParser.needsSupplement(model.contextWindow, model.maxOutputTokens)) {
            return model
        }
        val key = DataLearnerParser.normalizeKey(model.id)
        if (key.isEmpty()) return model
        val cached = cachedOverlay(key)
        if (cached != null) return applyOverlay(model, cached)
        if (fetchIfMissing) {
            val fetched = fetchOverlay(model, key) ?: return model
            return applyOverlay(model, fetched)
        }
        return model
    }

    fun supplementAll(models: List<LLMModel>): List<LLMModel> {
        val applied = models.map { supplement(it, fetchIfMissing = false) }
        val pending = applied.filter {
            DataLearnerParser.shouldLookup(it.id) &&
                DataLearnerParser.needsSupplement(it.contextWindow, it.maxOutputTokens)
        }.take(MAX_BACKGROUND_FILLS)
        if (pending.isNotEmpty()) scheduleFill(pending)
        return applied
    }

    fun scheduleSupplement(model: LLMModel) {
        if (!DataLearnerParser.shouldLookup(model.id)) return
        if (!DataLearnerParser.needsSupplement(model.contextWindow, model.maxOutputTokens)) return
        scheduleFill(listOf(model))
    }

    private fun applyOverlay(model: LLMModel, overlay: Overlay): LLMModel {
        return model.copy(
            contextWindow = model.contextWindow?.takeIf { it > 0 } ?: overlay.contextWindow,
            maxOutputTokens = model.maxOutputTokens?.takeIf { it > 0 } ?: overlay.maxOutputTokens,
            supportsReasoning = model.supportsReasoning ?: overlay.supportsReasoning,
            reasoningEffortValues = model.reasoningEffortValues
                ?: overlay.reasoningEffortValues,
        )
    }

    private fun cachedOverlay(key: String): Overlay? {
        val now = System.currentTimeMillis()
        synchronized(cacheLock) {
            loadDiskLocked()
            val missAt = memoryMisses[key]
            if (missAt != null && now - missAt < MISS_TTL_MS) return null
            val hit = memoryEntries[key] ?: return null
            if (now - hit.fetchedAt > HIT_TTL_MS) {
                memoryEntries.remove(key)
                return null
            }
            return hit
        }
    }

    private fun fetchOverlay(model: LLMModel, key: String): Overlay? {
        synchronized(cacheLock) {
            loadDiskLocked()
            val missAt = memoryMisses[key]
            if (missAt != null && System.currentTimeMillis() - missAt < MISS_TTL_MS) {
                return null
            }
            if (!inFlight.add(key)) return memoryEntries[key]
        }
        try {
            val queries = DataLearnerParser.searchQueries(model.id, model.displayName)
            var picked: DataLearnerParser.SearchHit? = null
            for (query in queries) {
                val hits = search(query)
                picked = DataLearnerParser.pickSearchHit(key, model.displayName, hits)
                if (picked != null) break
            }
            if (picked == null) {
                rememberMiss(key)
                return null
            }
            val html = fetchDetail(picked.modelCode) ?: run {
                rememberMiss(key)
                return null
            }
            val detail = DataLearnerParser.parseDetail(html)
            if (detail == null ||
                (detail.contextWindow == null && detail.maxOutputTokens == null)
            ) {
                rememberMiss(key)
                return null
            }
            val overlay = Overlay(
                modelCode = picked.modelCode,
                contextWindow = detail.contextWindow,
                maxOutputTokens = detail.maxOutputTokens,
                supportsReasoning = detail.supportsReasoning
                    ?: if (picked.reasoningModel == 1) true else null,
                reasoningEffortValues = detail.reasoningEffortValues,
                fetchedAt = System.currentTimeMillis(),
            )
            rememberHit(key, overlay)
            // Also index under DataLearner's own code so `gpt-5.5` and `gpt-5-5` share.
            val codeKey = DataLearnerParser.normalizeKey(picked.modelCode)
            if (codeKey != key) rememberHit(codeKey, overlay)
            Log.d(
                TAG,
                "[DataLearner] $key <- ${picked.modelCode} ctx=${overlay.contextWindow} out=${overlay.maxOutputTokens} effort=${overlay.reasoningEffortValues}",
            )
            return overlay
        } catch (e: Exception) {
            Log.w(TAG, "[DataLearner] fetch failed for $key: ${e.message}")
            return null
        } finally {
            synchronized(cacheLock) { inFlight.remove(key) }
        }
    }

    private fun search(query: String): List<DataLearnerParser.SearchHit> {
        val url = SEARCH_URL.toHttpUrl().newBuilder()
            .addQueryParameter("query", query)
            .addQueryParameter("releaseStatus", "0")
            .addQueryParameter("locale", "zh-CN")
            .build()
        val body = httpGet(url.toString(), "application/json") ?: return emptyList()
        return DataLearnerParser.parseSearchHits(body)
    }

    private fun fetchDetail(modelCode: String): String? {
        val url = DETAIL_URL + modelCode
        return httpGet(url, "text/html")
    }

    private fun httpGet(url: String, accept: String): String? {
        val request = Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8")
            .header(
                "User-Agent",
                "MinisUltra/1.0 (DataLearner catalog fallback; +https://github.com/tall-1997/OpenMinis-Linux)",
            )
            .get()
            .build()
        httpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                Log.w(TAG, "[DataLearner] HTTP ${resp.code} $url")
                return null
            }
            return resp.body?.string()
        }
    }

    private fun rememberHit(key: String, overlay: Overlay) {
        synchronized(cacheLock) {
            loadDiskLocked()
            memoryEntries[key] = overlay
            memoryMisses.remove(key)
            persistLocked()
        }
    }

    private fun rememberMiss(key: String) {
        synchronized(cacheLock) {
            loadDiskLocked()
            memoryMisses[key] = System.currentTimeMillis()
            persistLocked()
        }
    }

    private fun scheduleFill(models: List<LLMModel>) {
        if (appContext == null) return
        for (model in models) {
            if (pendingFills.size >= MAX_BACKGROUND_FILLS) break
            pendingFills.offer(model)
        }
        if (!backgroundRunning.compareAndSet(false, true)) return
        Thread({
            try {
                var n = 0
                while (n < MAX_BACKGROUND_FILLS) {
                    val model = pendingFills.poll() ?: break
                    n++
                    val key = DataLearnerParser.normalizeKey(model.id)
                    if (key.isEmpty()) continue
                    if (cachedOverlay(key) != null) continue
                    fetchOverlay(model, key)
                }
            } finally {
                backgroundRunning.set(false)
                if (!pendingFills.isEmpty()) scheduleFill(emptyList())
            }
        }, "datalearner-fill").apply {
            isDaemon = true
            start()
        }
    }

    private fun loadDiskLocked() {
        if (cacheLoaded) return
        cacheLoaded = true
        val file = cacheFile() ?: return
        if (!file.exists()) return
        try {
            val root = JSONObject(file.readText())
            val entries = root.optJSONObject("entries")
            if (entries != null) {
                for (key in entries.keys()) {
                    val obj = entries.optJSONObject(key) ?: continue
                    memoryEntries[key] = Overlay(
                        modelCode = obj.optString("modelCode"),
                        contextWindow = obj.optIntOrNull("contextWindow"),
                        maxOutputTokens = obj.optIntOrNull("maxOutputTokens"),
                        supportsReasoning = if (obj.has("supportsReasoning")) obj.optBoolean("supportsReasoning") else null,
                        reasoningEffortValues = obj.optJSONArray("reasoningEffortValues")?.toStringList(),
                        fetchedAt = obj.optLong("fetchedAt"),
                    )
                }
            }
            val misses = root.optJSONObject("misses")
            if (misses != null) {
                for (key in misses.keys()) {
                    memoryMisses[key] = misses.optLong(key)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "[DataLearner] cache load failed: ${e.message}")
        }
    }

    private fun persistLocked() {
        val file = cacheFile() ?: return
        try {
            val entries = JSONObject()
            for ((key, overlay) in memoryEntries) {
                val obj = JSONObject()
                    .put("modelCode", overlay.modelCode)
                    .put("fetchedAt", overlay.fetchedAt)
                overlay.contextWindow?.let { obj.put("contextWindow", it) }
                overlay.maxOutputTokens?.let { obj.put("maxOutputTokens", it) }
                overlay.supportsReasoning?.let { obj.put("supportsReasoning", it) }
                overlay.reasoningEffortValues?.let { values ->
                    val arr = JSONArray()
                    values.forEach { arr.put(it) }
                    obj.put("reasoningEffortValues", arr)
                }
                entries.put(key, obj)
            }
            val misses = JSONObject()
            for ((key, at) in memoryMisses) misses.put(key, at)
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(
                JSONObject()
                    .put("entries", entries)
                    .put("misses", misses)
                    .toString(),
            )
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "[DataLearner] cache write failed: ${e.message}")
        }
    }

    private fun cacheFile(): File? {
        val ctx = appContext ?: return null
        val dir = File(ctx.cacheDir, "datalearner")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "overlay.json")
    }

    private fun JSONObject.optIntOrNull(name: String): Int? {
        if (!has(name) || isNull(name)) return null
        val v = optInt(name, Int.MIN_VALUE)
        return v.takeIf { it != Int.MIN_VALUE && it > 0 }
    }

    private fun JSONArray.toStringList(): List<String> {
        val out = ArrayList<String>(length())
        for (i in 0 until length()) {
            optString(i).trim().takeIf { it.isNotEmpty() }?.let { out.add(it) }
        }
        return out
    }
}
