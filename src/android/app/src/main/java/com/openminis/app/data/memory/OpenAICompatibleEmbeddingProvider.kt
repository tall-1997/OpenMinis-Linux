package com.openminis.app.data.memory

import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-memory-embedding-recall] OpenAI-compatible `/v1/embeddings` client (#27).
 *
 * Works with any vendor that implements the well-known request/response
 * shape — OpenAI, Azure-compatible proxies, ollama (`/v1/embeddings`), LM
 * Studio, xinference — because the payload is minimal:
 *
 * ```
 * POST {baseURL}/v1/embeddings
 * Authorization: Bearer <key>
 * {"model": "<model>", "input": ["...", "..."]}
 * -> {"data": [{"index": 0, "embedding": [0.1, ...]}, ...]}
 * ```
 *
 * Batching: inputs are chunked into groups of [BATCH_SIZE] — one HTTP call
 * per chunk, results re-joined in input order. Every failure mode (missing
 * config, network error, non-2xx, malformed body, ragged vectors) collapses
 * to the interface's single "unavailable" signal: a null return. Nothing here
 * throws past [embed]'s boundary.
 *
 * The pure request/response helpers ([endpoint], [buildRequestBody],
 * [parseEmbeddingsResponse]) are internal so the JVM tests can exercise the
 * wire format without a server; [embed] itself is covered end-to-end by a
 * MockWebServer test.
 */
class OpenAICompatibleEmbeddingProvider(
    private val baseUrl: String,
    private val apiKey: String,
    private val model: String,
    timeoutMillis: Long = 20_000L,
) : EmbeddingProvider {

    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .writeTimeout(timeoutMillis, TimeUnit.MILLISECONDS)
        .build()

    override suspend fun embed(texts: List<String>): List<FloatArray>? {
        if (texts.isEmpty()) return emptyList()
        return withContext(Dispatchers.IO) {
            val out = ArrayList<FloatArray>(texts.size)
            for (batch in texts.chunked(BATCH_SIZE)) {
                val parsed = embedBatch(batch) ?: return@withContext null
                if (parsed.size != batch.size) return@withContext null
                out += parsed
            }
            out
        }
    }

    /** One HTTP round trip; null on any failure (never throws). */
    private fun embedBatch(batch: List<String>): List<FloatArray>? = runCatching {
        val body = buildRequestBody(batch, model).toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url(endpoint())
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val text = response.body?.string() ?: return null
            parseEmbeddingsResponse(text) ?: return null
        }
    }.getOrNull()

    // ── pure wire-format helpers (unit-test seams) ─────────────────────────

    /**
     * `{baseURL}/v1/embeddings`, tolerant of user input: trailing slashes are
     * stripped, and a base URL that already carries `/v1/embeddings` (or just
     * `/v1/embeddings`) is used verbatim so self-hosted setups exposing the
     * full endpoint also work.
     */
    internal fun endpoint(): String {
        val trimmed = baseUrl.trim().trimEnd('/')
        return when {
            trimmed.endsWith("/v1/embeddings") -> trimmed
            trimmed.endsWith("/embeddings") -> trimmed.removeSuffix("/embeddings") + "/v1/embeddings"
            trimmed.endsWith("/v1") -> "$trimmed/embeddings"
            else -> "$trimmed/v1/embeddings"
        }
    }

    internal fun buildRequestBody(texts: List<String>, model: String): String {
        val arr = JSONArray()
        for (t in texts) arr.put(t)
        return JSONObject().put("model", model).put("input", arr).toString()
    }

    /**
     * Parse the `data[]` reply. Vectors are re-ordered by `index` (the API
     * does not guarantee order), must all share one dimension, and any
     * non-finite entry rejects the whole response.
     */
    internal fun parseEmbeddingsResponse(json: String): List<FloatArray>? {
        return try {
            val data = JSONObject(json).optJSONArray("data") ?: return null
            if (data.length() == 0) return null
            val byIndex = HashMap<Int, FloatArray>(data.length())
            for (i in 0 until data.length()) {
                val entry = data.optJSONObject(i) ?: return null
                val embedding = entry.optJSONArray("embedding") ?: return null
                val vector = FloatArray(embedding.length())
                for (j in vector.indices) {
                    val v = embedding.optDouble(j, Double.NaN)
                    if (!v.isFinite()) return null
                    vector[j] = v.toFloat()
                }
                if (vector.isEmpty()) return null
                byIndex[entry.optInt("index", i)] = vector
            }
            if (byIndex.size != data.length()) return null
            val dim = byIndex.values.first().size
            if (byIndex.values.any { it.size != dim }) return null
            (0 until data.length()).map { byIndex.getValue(it) }
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** Input strings per HTTP call — keeps request bodies comfortably small. */
        const val BATCH_SIZE = 16
    }
}
