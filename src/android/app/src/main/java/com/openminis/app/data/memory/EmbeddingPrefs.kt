package com.openminis.app.data.memory

import android.content.Context
import android.content.SharedPreferences

/**
 * [T-memory-embedding-recall] Configuration for the optional semantic memory
 * recall (#27), persisted in SharedPreferences. **Default OFF** — with
 * [Snapshot.enabled] false nothing in the app touches the embeddings table,
 * no network call is ever made, and recall stays pure keyword FTS (the
 * pre-#27 behaviour, byte-identical).
 *
 * ## Why independent prefs instead of reusing a provider profile
 *
 * The provider layer (provider package) models CHAT vendors: base URL + key + chat
 * model + streaming semantics, edited through Settings → Providers, and this
 * file must stay decoupled from that domain both for layering (data/ reading
 * provider/ state) and for the in-flight provider changes. An embeddings
 * endpoint is a different contract (POST /v1/embeddings, no chat), often a
 * different vendor or a self-hosted service (ollama, xinference, OpenAI).
 * Independent keys let the user point the feature at ANY OpenAI-compatible
 * endpoint — including the same vendor they use for chat — by pasting the
 * base URL/key once. A Settings UI and "copy from provider X" affordance are
 * follow-up work; until then the keys are settable only via prefs.
 *
 * Keys (prefs file `minis_embedding_prefs`):
 *  - `embedding.enabled`  boolean, default **false**
 *  - `embedding.base_url` string — e.g. `https://api.openai.com` (no
 *    `/v1/embeddings` suffix; it is appended, see
 *    [OpenAICompatibleEmbeddingProvider.endpoint])
 *  - `embedding.api_key`  string
 *  - `embedding.model`    string, default `text-embedding-3-small`
 *
 * The feature is ACTIVE only when enabled AND base_url AND api_key AND model
 * are all non-blank ([Snapshot] returns null otherwise).
 */
object EmbeddingPrefs {

    private const val PREFS = "minis_embedding_prefs"
    private const val KEY_ENABLED = "embedding.enabled"
    private const val KEY_BASE_URL = "embedding.base_url"
    private const val KEY_API_KEY = "embedding.api_key"
    private const val KEY_MODEL = "embedding.model"

    const val DEFAULT_MODEL = "text-embedding-3-small"

    class Snapshot(
        val baseUrl: String,
        val apiKey: String,
        val model: String,
    )

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The effective config, or null when the feature is off / incomplete. */
    fun snapshot(context: Context): Snapshot? {
        val p = prefs(context)
        if (!p.getBoolean(KEY_ENABLED, false)) return null
        val baseUrl = p.getString(KEY_BASE_URL, null)?.trim().orEmpty()
        val apiKey = p.getString(KEY_API_KEY, null)?.trim().orEmpty()
        val model = p.getString(KEY_MODEL, null)?.trim().orEmpty().ifBlank { DEFAULT_MODEL }
        if (baseUrl.isBlank() || apiKey.isBlank()) return null
        return Snapshot(baseUrl = baseUrl, apiKey = apiKey, model = model)
    }

    // Setters are package-visible seams for the future Settings UI and tests.
    fun setEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    fun setBaseUrl(context: Context, baseUrl: String) {
        prefs(context).edit().putString(KEY_BASE_URL, baseUrl).apply()
    }

    fun setApiKey(context: Context, apiKey: String) {
        prefs(context).edit().putString(KEY_API_KEY, apiKey).apply()
    }

    fun setModel(context: Context, model: String) {
        prefs(context).edit().putString(KEY_MODEL, model).apply()
    }
}
