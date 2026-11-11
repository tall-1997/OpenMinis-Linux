package com.openminis.app.auth

import android.content.Context
import android.util.Log
import com.openminis.app.BuildConfig
import com.openminis.app.network.DohDns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [GH#423][GH#301] The `claude-cli/<version>` fingerprint version.
 *
 * The Anthropic backend gates newer models on the CLI version in `User-Agent`
 * (below 2.1.251 a Fable 5.1 request is refused; below 2.1.280 an Opus 5.5 one
 * is), and the floor only rises. The version used to be hardcoded twice — once
 * in [ClaudeCliMimicryHeaders] (2.1.280) and once in
 * `AnthropicProvider.buildRequest` (2.1.195) — so the OAuth auth path and the
 * chat path advertised different client identities, and the next model gate
 * needed a code change in two places.
 *
 * Resolution order:
 *  1. [BuildConfig.CLAUDE_CLI_VERSION] when the build sets one (pin it with
 *     `-Pminis.claudeCliVersion=…` or `MINIS_CLAUDE_CLI_VERSION=…`). A pinned
 *     build never consults the network.
 *  2. The last version fetched from npm's dist-tags, cached for 24 h.
 *  3. [FALLBACK], the version this source release is known to work with.
 *
 * Refresh is best-effort and MUST stay safe on failure: any network, parse or
 * clock error keeps the previous value. A version lower than [FALLBACK] is
 * never used, so a stale cache cannot move the fingerprint backwards.
 */
object ClaudeCliVersion {

    private const val TAG = "ClaudeCliVersion"

    /** Version this build falls back to; keep in sync with the release notes. */
    const val FALLBACK = "2.1.280"

    private const val PREFS = "claude_cli_version"
    private const val KEY_VERSION = "version"
    private const val KEY_FETCHED_AT = "fetched_at"
    private const val REFRESH_INTERVAL_MS = 24L * 60L * 60L * 1000L

    /**
     * Scoped-package metadata. `%2F` is the canonical escaping; the registry
     * also accepts a slash, but the encoded form avoids URL-normalisation
     * differences in proxies.
     */
    private const val REGISTRY_URL = "https://registry.npmjs.org/@anthropic-ai%2Fclaude-code"

    /** Accept the stable dotted forms the CLI actually publishes. */
    private val VERSION_RE = Regex("""^\d+(\.\d+){1,3}$""")

    private val pinned: String? =
        BuildConfig.CLAUDE_CLI_VERSION.trim().takeIf { it.isNotEmpty() && isValidVersion(it) }

    @Volatile
    private var cachedVersion: String? = null

    @Volatile
    private var loaded = false

    private val refreshing = AtomicBoolean(false)

    private val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            // [T-doh-resolver-fallback] npmjs.org is often the first host a
            // poisoned resolver breaks; DoH keeps the version check alive.
            .dns(DohDns)
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .callTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    /** The version every Claude CLI fingerprint header must carry. */
    val current: String
        get() {
            val active = pinned ?: cachedVersion ?: return FALLBACK
            return if (compareVersions(active, FALLBACK) < 0) FALLBACK else active
        }

    /** Load the cached version (no network); called once from `MinisApp`. */
    fun init(context: Context) {
        if (pinned != null || loaded) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val saved = prefs.getString(KEY_VERSION, null)
        if (saved != null && isValidVersion(saved)) cachedVersion = saved
        loaded = true
    }

    /**
     * Refresh from npm's dist-tags when the cache is older than 24 h. Safe to
     * call on every start: concurrent calls collapse and every failure path
     * returns without touching [current].
     */
    suspend fun refreshIfStale(context: Context) {
        if (pinned != null) return
        if (!refreshing.compareAndSet(false, true)) return
        try {
            init(context)
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val now = System.currentTimeMillis()
            val fetchedAt = prefs.getLong(KEY_FETCHED_AT, 0L)
            if (cachedVersion != null && now >= fetchedAt && now - fetchedAt < REFRESH_INTERVAL_MS) {
                return
            }
            val latest = fetchLatest() ?: run {
                Log.i(TAG, "npm dist-tags unavailable; keeping ${current}")
                return
            }
            if (!isValidVersion(latest)) {
                Log.w(TAG, "npm dist-tags returned a non-version '$latest'; keeping ${current}")
                return
            }
            val before = current
            if (compareVersions(latest, before) > 0) {
                cachedVersion = latest
                prefs.edit()
                    .putString(KEY_VERSION, latest)
                    .putLong(KEY_FETCHED_AT, now)
                    .apply()
                Log.i(TAG, "Claude CLI version $before -> $latest (npm dist-tags)")
            } else {
                // Record the successful check even when npm is not ahead, so
                // the 24 h gate does not hit the network on every launch.
                prefs.edit().putLong(KEY_FETCHED_AT, now).apply()
                Log.i(TAG, "npm dist-tags latest=$latest not newer than $before; keeping $before")
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Exception) {
            // A fingerprint refresh must never take the app down.
            Log.w(TAG, "version refresh failed: ${t.javaClass.simpleName}: ${t.message}")
        } finally {
            refreshing.set(false)
        }
    }

    /** GET the package document and take `dist-tags.latest`, or null. */
    private suspend fun fetchLatest(): String? = withContext(Dispatchers.IO) {
        try {
            val request = Request.Builder()
                .url(REGISTRY_URL)
                .get()
                .header("Accept", "application/json")
                .build()
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.i(TAG, "npm dist-tags HTTP ${response.code}")
                    null
                } else {
                    val body = response.body?.string()
                    // The full package document is a few MB; anything wildly
                    // larger is not a registry response we should parse.
                    if (body == null || body.length > 8_000_000) {
                        null
                    } else {
                        JSONObject(body)
                            .optJSONObject("dist-tags")
                            ?.optString("latest")
                            ?.trim()
                            ?.takeIf { it.isNotEmpty() }
                    }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (t: Exception) {
            Log.i(TAG, "npm dist-tags fetch failed: ${t.javaClass.simpleName}: ${t.message}")
            null
        }
    }

    internal fun isValidVersion(version: String): Boolean = VERSION_RE.matches(version)

    /** Numeric dotted comparison; missing components count as 0. */
    internal fun compareVersions(a: String, b: String): Int {
        val left = a.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        val right = b.substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(left.size, right.size, 4)) {
            val l = left.getOrElse(i) { 0 }
            val r = right.getOrElse(i) { 0 }
            if (l != r) return if (l > r) 1 else -1
        }
        return 0
    }
}
