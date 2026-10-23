package com.openminis.app.provider

import com.openminis.app.logging.AppLogger
import com.openminis.app.network.SharedHttpClients
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The client version string the keyless Zen lane is impersonated as.
 *
 * [T-zen-client-version] The measured working User-Agent is
 * `opencode/prod/<version>/cli`. The older shape this project used before
 * (`opencode/1.18.31 (Android arm64; native)`) is not accepted any more, so the
 * version half is not free-form.
 *
 * The published npm package is queried once in the background, but only a
 * **2.0.0-or-newer** release is adopted: as of 2026-10-23 `registry.npmjs.org`
 * reports `1.19.4` as latest while the working client string says `2.0.29`, so
 * the npm channel is behind the build the endpoint expects. Adopting 1.x would
 * regress a working header, hence [FALLBACK] stays in place until a 2.x is
 * actually published. A failed probe is not an error — the fallback is the
 * measured-good value.
 */
internal object ZenClientVersion {
    private const val TAG = "ZenClientVersion"

    /** Measured-good version; used unless npm publishes a 2.x. */
    const val FALLBACK = "2.0.29"

    private const val MIN_MAJOR = 2
    private const val REGISTRY_URL = "https://registry.npmjs.org/opencode-ai/latest"

    @Volatile
    private var resolved: String? = null

    private val probeStarted = AtomicBoolean(false)

    fun version(): String = resolved ?: FALLBACK

    fun userAgent(): String = "opencode/prod/${version()}/cli"

    /** Fire-and-forget, at most once per process. Never blocks a request. */
    fun refreshOnceInBackground() {
        if (!probeStarted.compareAndSet(false, true)) return
        val thread = Thread({
            runCatching {
                val request = Request.Builder()
                    .url(REGISTRY_URL)
                    .header("Accept", "application/json")
                    .build()
                SharedHttpClients.default.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        AppLogger.warning(TAG, "npm probe returned HTTP ${response.code}, keeping $FALLBACK")
                        return@runCatching
                    }
                    val raw = response.body?.string() ?: return@runCatching
                    acceptIfUsable(JSONObject(raw).optString("version"))
                }
            }.onFailure {
                AppLogger.warning(TAG, "npm probe failed (${it.javaClass.simpleName}), keeping $FALLBACK")
            }
        }, "zen-version-probe")
        thread.isDaemon = true
        thread.start()
    }

    /** Adopts the version only when it is at least [MIN_MAJOR].0.0. Visible for tests. */
    internal fun acceptIfUsable(raw: String?) {
        val candidate = raw?.trim().orEmpty()
        if (candidate.isEmpty()) return
        val major = candidate.substringBefore('.').toIntOrNull()
        if (major == null || major < MIN_MAJOR) {
            AppLogger.info(TAG, "npm latest is $candidate (< $MIN_MAJOR.0), keeping fallback $FALLBACK")
            return
        }
        resolved = candidate
        AppLogger.info(TAG, "adopted npm version $candidate")
    }

    /** Test seam: drop any adopted version so the fallback path can be re-checked. */
    internal fun resetForTest() {
        resolved = null
        probeStarted.set(false)
    }
}
