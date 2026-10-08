package com.openminis.app.provider

import android.content.Context

/**
 * [T-zen-free-lane-follow] Self-healing health record for the OpenCode Zen
 * free lane.
 *
 * The free-lane picker now FOLLOWS the live catalogue (every advertised
 * `-free` id / `big-pickle`), which is what the user asked for — but the
 * catalogue advertises rows the upstream no longer serves (measured
 * 2026-10-08: 5 of 13 advertised free ids fail on every call). The old
 * protection was a hard-coded measured table shipped with the app, which
 * meant every upstream drift needed an app release. This record replaces
 * that gate with in-production evidence: when a real chat attempt answers
 * a retirement dialect — 401 ModelError "not supported", RegionError, or
 * "Endpoint is unavailable" on any status — the id is recorded here and
 * the refresh/sweep paths drop it from the picker until the mark expires.
 *
 * TTL is the safety valve: a mark lives [DEAD_TTL_MS] (7 days), then the
 * id re-appears and gets to re-prove itself. A wrongly-recorded id (say a
 * transient 503 that happened to carry the phrase) heals itself within a
 * week; a genuinely retired one re-records on its next failure. No
 * permanent state, no manual unburying, no app release.
 *
 * Pure-JVM safe: without [initialize] (unit tests) the record lives in
 * memory only and persistence is a no-op, so provider-level tests can
 * record and assert without Android.
 */
object ZenFreeLaneHealth {

    private const val PREFS_NAME = "zen_free_lane_health"
    private const val KEY_DEAD = "dead_ids"

    /** How long a retirement mark keeps an id out of the picker. */
    internal const val DEAD_TTL_MS: Long = 7L * 24 * 60 * 60 * 1000

    @Volatile
    private var appContext: Context? = null

    @Volatile
    private var deadAt: Map<String, Long> = emptyMap()

    /** Wire persistence; call once from Application.onCreate. */
    fun initialize(context: Context) {
        appContext = context.applicationContext
        reload()
    }

    @Synchronized
    private fun reload() {
        val raw = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            ?.getString(KEY_DEAD, null) ?: return
        deadAt = deserializeDeadRecord(raw)
    }

    /**
     * Record [modelId] as retired-at-[now]. Idempotent; refreshes the
     * timestamp so a repeatedly-failing id keeps its mark young.
     */
    @Synchronized
    fun recordDead(modelId: String, now: Long = System.currentTimeMillis()) {
        if (modelId.isBlank()) return
        if (deadAt[modelId] == now) return
        deadAt = deadAt + (modelId to now)
        persist()
    }

    /** Live (unexpired) retirement marks. */
    @Synchronized
    fun deadIds(now: Long = System.currentTimeMillis()): Set<String> =
        deadAt.filterValues { now - it in 0 until DEAD_TTL_MS }.keys

    fun isDead(modelId: String, now: Long = System.currentTimeMillis()): Boolean =
        modelId in deadIds(now)

    /** Clear a mark — the id re-enters the picker at the next refresh. */
    @Synchronized
    fun revive(modelId: String) {
        if (deadAt.containsKey(modelId)) {
            deadAt = deadAt - modelId
            persist()
        }
    }

    private fun persist() {
        val prefs = appContext?.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE) ?: return
        prefs.edit().putString(KEY_DEAD, serializeDeadRecord(deadAt)).apply()
    }

    // ---- serialization: `id=timestamp,id=timestamp` — stable, debuggable ----

    internal fun serializeDeadRecord(map: Map<String, Long>): String =
        map.entries.joinToString(",") { "${it.key}=${it.value}" }

    internal fun deserializeDeadRecord(raw: String): Map<String, Long> {
        if (raw.isBlank()) return emptyMap()
        val out = mutableMapOf<String, Long>()
        for (pair in raw.split(',')) {
            val idx = pair.indexOf('=')
            if (idx <= 0) continue
            val ts = pair.substring(idx + 1).toLongOrNull() ?: continue
            out[pair.substring(0, idx)] = ts
        }
        return out
    }
}
