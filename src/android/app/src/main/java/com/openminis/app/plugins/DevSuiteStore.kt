package com.openminis.app.plugins

import android.content.Context
import com.openminis.app.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * [T-dev-suite] Install state for dev-suite components.
 *
 * SharedPreferences rather than Room: the state is a flat per-component
 * marker (installed / installing / failed) with the latest progress line,
 * and avoiding a schema migration keeps the change low-risk. The guest itself
 * is the source of truth for "is it really ready" — [DevSuiteInstaller]
 * re-probes checkCommand on demand; this store only caches the last known
 * outcome so the UI doesn't shell out on every recompose.
 *
 * [T-devsuite-progress-write] Write amplification was the reason this file
 * looks the way it does now: a multi-GB install streams thousands of guest
 * lines and every one of them used to do `putString().apply()` PLUS a full
 * `snapshot()` re-read of every key. Progress writes are now time-throttled
 * (>= [PROGRESS_WRITE_INTERVAL_MS] between writes of the SAME component) and
 * a status transition is always persisted immediately. The in-memory
 * [states] mirror is updated on every call, so the UI stays live while the
 * disk stays quiet — and [ComponentState.progressLine] is the single store
 * of the live progress line (the installer used to keep a second, parallel
 * copy that nothing read).
 */
object DevSuiteStore {
    private const val PREFS = "dev_suite_state"

    /** Minimum gap between two progress writes of the same component. */
    private const val PROGRESS_WRITE_INTERVAL_MS = 300L

    /** Per-component last outcome. */
    enum class Status { NOT_INSTALLED, INSTALLING, INSTALLED, FAILED }

    data class ComponentState(
        val componentId: String,
        val status: Status = Status.NOT_INSTALLED,
        /** Last progress line from the installer (the only one the UI reads). */
        val progressLine: String = "",
        val updatedAtMs: Long = 0L,
    )

    private val _states = MutableStateFlow<Map<String, ComponentState>>(emptyMap())
    val states: StateFlow<Map<String, ComponentState>> = _states.asStateFlow()

    private val lock = Any()
    private var loaded = false

    /** Per-component timestamp of the last write that actually hit disk. */
    private val lastPersistedMs = mutableMapOf<String, Long>()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun readFromDisk(context: Context): Map<String, ComponentState> {
        val p = prefs(context)
        val out = mutableMapOf<String, ComponentState>()
        for (id in p.all.keys) {
            val raw = p.getString(id, null) ?: continue
            val parts = raw.split('\u0001', limit = 3)
            out[id] = ComponentState(
                componentId = id,
                status = when (parts.getOrNull(0)) {
                    "INSTALLING" -> Status.INSTALLING
                    "INSTALLED" -> Status.INSTALLED
                    "FAILED" -> Status.FAILED
                    else -> Status.NOT_INSTALLED
                },
                progressLine = parts.getOrNull(1).orEmpty(),
                updatedAtMs = parts.getOrNull(2)?.toLongOrNull() ?: 0L,
            )
        }
        return out
    }

    /** Full read from disk — the prefs stay authoritative across process death. */
    fun snapshot(context: Context): Map<String, ComponentState> = synchronized(lock) {
        val disk = readFromDisk(context)
        // Keep any in-memory change that is newer than what survived to disk
        // (throttled progress lines) so a snapshot never rewinds the UI.
        val merged = disk.toMutableMap()
        for ((id, live) in _states.value) {
            val stored = merged[id]
            if (stored == null || stored.updatedAtMs <= live.updatedAtMs) merged[id] = live
        }
        loaded = true
        _states.value = merged
        merged
    }

    /**
     * Record a progress transition. [status] changes always go to disk; a
     * progress line for a status that is already current is throttled to at
     * most one write per [PROGRESS_WRITE_INTERVAL_MS].
     */
    fun update(context: Context, componentId: String, status: Status, progressLine: String = "") {
        val now = System.currentTimeMillis()
        val write: Boolean
        synchronized(lock) {
            if (!loaded) {
                var merged = _states.value
                for ((id, st) in readFromDisk(context)) {
                    val live = merged[id]
                    if (live == null || live.updatedAtMs <= st.updatedAtMs) merged = merged + (id to st)
                }
                _states.value = merged
                loaded = true
            }
            val previous = _states.value[componentId]
            write = previous?.status != status ||
                now - (lastPersistedMs[componentId] ?: 0L) >= PROGRESS_WRITE_INTERVAL_MS
            _states.value = _states.value +
                (componentId to ComponentState(componentId, status, progressLine, now))
            if (write) lastPersistedMs[componentId] = now
        }
        if (!write) return
        prefs(context).edit()
            .putString(componentId, "$status\u0001$progressLine\u0001$now")
            .apply()
    }

    /**
     * Crash recovery: a component stuck in INSTALLING means the app process
     * died mid-install. Mark it FAILED so the UI offers a retry instead of
     * pretending an install is still running (taixu's recover() equivalent,
     * scoped to the marker rather than a full snapshot).
     */
    fun recoverInterrupted(context: Context): List<String> {
        val current = snapshot(context)
        val interrupted = current.values
            .filter { it.status == Status.INSTALLING }
            .map { it.componentId }
        val reason = context.getString(R.string.dev_suite_interrupted)
        interrupted.forEach { update(context, it, Status.FAILED, reason) }
        return interrupted
    }
}
