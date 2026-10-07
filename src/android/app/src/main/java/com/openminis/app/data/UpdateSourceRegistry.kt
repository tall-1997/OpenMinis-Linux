package com.openminis.app.data

import android.content.Context
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * [T-update-source-choice] Registry of download sources for app updates.
 *
 * The old flow raced every mirror and streamed from whichever answered TTFB
 * first — fast, but opaque: the user never knew which host served the APK,
 * and a mirror that started serving junk (ghproxy.com's parked lander, see
 * [T-about-update-mirrors]) would silently win. This registry replaces the
 * race with an explicit, user-visible choice:
 *
 *  - [SOURCES] is the ordered list of download sources (origin first).
 *  - [probeSources] measures reachability + TTFB for each, concurrently, so
 *    the update dialog can annotate every row.
 *  - [preferredSourceId]/[setPreferredSourceId] persist the user's pick.
 *  - [fallbackOrder] builds the try-order for a download: the user's pick
 *    first, then the remaining sources in registry order. Fallback is a
 *    safety net, not a selection mechanism — the active source is reported
 *    in the download state.
 *
 * Probing requires 206 (not 200) for the same reasons the old race did: it
 * proves Range capability (the download resumes via Range) and rejects
 * parked-domain landers that answer 200 with an HTML page.
 */
object UpdateSourceRegistry {

    private const val TAG = "UpdateSourceRegistry"
    private const val PREFS = "update_sources"
    private const val KEY_PREFERRED = "preferred_source_id"
    private const val KEY_INCLUDE_ROLLING = "include_rolling"
    private const val PROBE_TIMEOUT_MS = 6_000L

    /** A download source: stable id + URL resolver. */
    data class UpdateSource(
        /** Stable identifier persisted as the user's preference. */
        val id: String,
        /** Human-readable host, for logs and fallback reporting. */
        val host: String,
        /**
         * Resolve the GitHub asset URL into this source's URL. The origin
         * passes it through; mirrors re-host it as a path suffix.
         */
        val resolve: (String) -> String,
    )

    /**
     * Ordered download sources. The origin is first so the fallback chain
     * ends at the canonical host, and so a fresh install (no preference
     * saved) defaults to GitHub direct.
     */
    val SOURCES = listOf(
        UpdateSource(
            id = "github-direct",
            host = "github.com",
            resolve = { it },
        ),
        UpdateSource(
            id = "gh-proxy",
            host = "gh-proxy.com",
            resolve = { url -> "https://gh-proxy.com/$url" },
        ),
    )

    /** One probe outcome for [probeSources]. */
    data class ProbeResult(
        val sourceId: String,
        val reachable: Boolean,
        /** TTFB in ms when reachable, else -1. */
        val latencyMs: Int,
    )

    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .readTimeout(PROBE_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        .followRedirects(true)
        .build()

    /**
     * Probe every source concurrently against the real asset URL.
     * A source is reachable when a `Range: bytes=0-0` request returns 206 —
     * proving both liveness and Range support (needed for resume), and
     * rejecting HTML landers that answer 200.
     */
    suspend fun probeSources(assetUrl: String): List<ProbeResult> = withContext(Dispatchers.IO) {
        coroutineScope {
            SOURCES.map { src ->
                async {
                    val url = src.resolve(assetUrl)
                    val t0 = System.nanoTime()
                    val ok = runCatching {
                        val req = Request.Builder()
                            .url(url)
                            .header("Range", "bytes=0-0")
                            .build()
                        probeClient.newCall(req).execute().use { it.code == 206 }
                    }.getOrDefault(false)
                    val ms = ((System.nanoTime() - t0) / 1_000_000L).toInt()
                    if (!ok) AppLogger.info(TAG, "probe ${src.id} unreachable (${ms}ms)")
                    ProbeResult(sourceId = src.id, reachable = ok, latencyMs = if (ok) ms else -1)
                }
            }.map { it.await() }
        }
    }

    /**
     * Try-order for a download: the user's preferred source first (when it
     * still exists in [SOURCES]), then the rest in registry order.
     */
    fun fallbackOrder(preferredSourceId: String?): List<UpdateSource> {
        val pref = preferredSourceId?.let { id -> SOURCES.firstOrNull { it.id == id } }
        return if (pref != null) listOf(pref) + SOURCES.filter { it.id != pref.id } else SOURCES
    }

    /** The user's saved source pick, or null (no preference yet). */
    fun preferredSourceId(context: Context): String? =
        requirePrefs(context).getString(KEY_PREFERRED, null)

    /** Persist the user's source pick (validated against [SOURCES]). */
    fun setPreferredSourceId(context: Context, id: String) {
        if (SOURCES.none { it.id == id }) return
        requirePrefs(context).edit().putString(KEY_PREFERRED, id).apply()
        AppLogger.info(TAG, "preferred source set to $id")
    }

    /**
     * [T-update-rolling-channel] Whether release checks should also consider
     * the rolling prerelease channel (the android-latest tag, CI builds).
     * Default false: stable users never see a CI build unless they opt in.
     */
    fun includeRolling(context: Context): Boolean =
        requirePrefs(context).getBoolean(KEY_INCLUDE_ROLLING, false)

    fun setIncludeRolling(context: Context, value: Boolean) {
        requirePrefs(context).edit().putBoolean(KEY_INCLUDE_ROLLING, value).apply()
        AppLogger.info(TAG, "include rolling channel = $value")
    }

    private fun requirePrefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
