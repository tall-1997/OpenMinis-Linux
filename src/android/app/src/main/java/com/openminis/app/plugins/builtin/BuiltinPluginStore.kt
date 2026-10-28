package com.openminis.app.plugins.builtin

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-builtin-plugins] Install state for builtin plugins, keyed by catalog id.
 *
 * Same shape and same write-amplification discipline as [com.openminis.app.plugins.DevSuiteStore]
 * (SharedPreferences, not Room — a flat per-plugin marker avoids a schema
 * migration; the guest is the source of truth for "is it really ready"):
 *
 *  - Only status transitions persist to disk. The installer streams hundreds
 *    of guest lines per install; each one used to cost a full XML rewrite
 *    (see [T-dev-suite-prefs-storm] for the main-thread stall that caused).
 *    Progress/diagnosis lines live only in the in-memory flow.
 *  - [snapshot] merges disk with newer in-memory state so a re-read can never
 *    rewind the UI.
 *  - [recoverInterrupted] flips INSTALLING rows to FAILED after process death
 *    so the market card offers a retry instead of pretending to run.
 *
 * [PluginState.diagnosis] is the human-readable failure reason surfaced on the
 * card (e.g. "node: exit 4 — nodesource unreachable"), distinct from the live
 * progress line so a retry doesn't erase why the last attempt failed.
 */
object BuiltinPluginStore {
    private const val PREFS = "builtin_plugin_state"

    enum class Status { NOT_INSTALLED, INSTALLING, INSTALLED, FAILED }

    data class PluginState(
        val pluginId: String,
        val status: Status = Status.NOT_INSTALLED,
        /** Version of the payload this state describes; empty before a success. */
        val version: String = "",
        /** Why the last attempt failed (FAILED rows); empty otherwise. */
        val diagnosis: String = "",
        /** Live progress line — UI-only, never persisted. */
        val progressLine: String = "",
        val updatedAtMs: Long = 0L,
    )

    private val _states = MutableStateFlow<Map<String, PluginState>>(emptyMap())
    val states: StateFlow<Map<String, PluginState>> = _states.asStateFlow()

    private val lock = Any()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readFromDisk(context: Context): Map<String, PluginState> {
        val p = prefs(context)
        val out = mutableMapOf<String, PluginState>()
        for (id in p.all.keys) {
            out[id] = decodeState(id, p.getString(id, null)) ?: continue
        }
        return out
    }

    /** Full read from disk — prefs stay authoritative across process death. */
    fun snapshot(context: Context): Map<String, PluginState> = synchronized(lock) {
        val merged = readFromDisk(context).toMutableMap()
        for ((id, live) in _states.value) {
            val stored = merged[id]
            if (stored == null || stored.updatedAtMs <= live.updatedAtMs) merged[id] = live
        }
        _states.value = merged
        merged
    }

    /**
     * Record a transition. Status changes persist; progress-line refreshes
     * stay in memory (the installer feeds every guest line through here).
     */
    fun update(
        context: Context,
        pluginId: String,
        status: Status,
        version: String = "",
        diagnosis: String = "",
        progressLine: String = "",
    ) {
        val previous = _states.value[pluginId]
        val next = PluginState(
            pluginId = pluginId,
            status = status,
            version = version.ifBlank { previous?.version.orEmpty() },
            diagnosis = diagnosis,
            progressLine = progressLine,
            updatedAtMs = System.currentTimeMillis(),
        )
        if (shouldPersistState(previous?.status, status)) {
            prefs(context).edit()
                .putString(pluginId, encodeState(status, next.version, diagnosis, next.updatedAtMs))
                .apply()
        }
        _states.value = _states.value + (pluginId to next)
    }

    /**
     * Crash recovery: INSTALLING at launch means the process died
     * mid-install. Mark FAILED so the card offers a retry.
     */
    fun recoverInterrupted(context: Context, reason: String): List<String> {
        val interrupted = snapshot(context).values
            .filter { it.status == Status.INSTALLING }
            .map { it.pluginId }
        interrupted.forEach { update(context, it, Status.FAILED, diagnosis = reason) }
        return interrupted
    }

    /** True when this update must hit disk: ONLY status transitions. Pure. */
    internal fun shouldPersistState(previous: Status?, next: Status): Boolean =
        previous != next

    /** Encodes one plugin marker for SharedPreferences. Pure (testable). */
    internal fun encodeState(
        status: Status,
        version: String,
        diagnosis: String,
        updatedAtMs: Long,
    ): String = "$status\u0001$version\u0001$diagnosis\u0001$updatedAtMs"

    /** Decodes one plugin marker; null for absent rows. Pure (testable). */
    internal fun decodeState(pluginId: String, raw: String?): PluginState? {
        if (raw == null) return null
        val parts = raw.split('\u0001', limit = 4)
        val status = when (parts.getOrNull(0)) {
            "INSTALLING" -> Status.INSTALLING
            "INSTALLED" -> Status.INSTALLED
            "FAILED" -> Status.FAILED
            else -> Status.NOT_INSTALLED
        }
        return PluginState(
            pluginId = pluginId,
            status = status,
            version = parts.getOrNull(1).orEmpty(),
            diagnosis = parts.getOrNull(2).orEmpty(),
            progressLine = "",
            updatedAtMs = parts.getOrNull(3)?.toLongOrNull() ?: 0L,
        )
    }
}
