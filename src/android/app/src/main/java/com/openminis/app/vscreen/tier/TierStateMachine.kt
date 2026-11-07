package com.openminis.app.vscreen.tier

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.HostSuFallback
import com.openminis.app.offload.ShizukuManager
import com.openminis.app.vscreen.VscreenPrefs
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

/**
 * [T-android-vscreen-tier] Probe bookkeeping for the tier ladder: a cached
 * probe sheet (persisted so a restart does not re-pay every failed probe),
 * lazy re-probing of degraded tiers after a backoff window, and invalidation
 * on privilege-fingerprint changes (Shizuku state flips, su binary appears
 * or vanishes).
 *
 * Upgrade semantics (user decision): a privilege change NEVER migrates live
 * screens — [upgradeAvailable] only reports that recreation would move a
 * screen to a better tier, and the orchestrator applies the new tier to NEW
 * creates. The fingerprint is deliberately cheap: Shizuku's snapshot is a
 * StateFlow read, and `HostSuFallback.isAvailable()` is already called on
 * every guard decision today, so re-reading it here adds no new order of
 * magnitude.
 *
 * Thread model: synchronized like the screen registry; the sheet is also a
 * StateFlow so settings UI can observe tier states without polling.
 */
class TierStateMachine(
    private val providers: Map<VscreenTier, DisplayProvider>,
    private val prefs: VscreenPrefs,
    private val nowMs: () -> Long = System::currentTimeMillis,
    /** Cheap privilege fingerprint; a change invalidates probed (non-structural) entries. */
    private val fingerprint: () -> String = {
        ShizukuManager.snapshot.value.state.name + ":" + HostSuFallback.isAvailable()
    },
) {
    private val lock = Any()

    private val _sheet = MutableStateFlow<Map<VscreenTier, ProbeResult>>(emptyMap())
    val sheet: StateFlow<Map<VscreenTier, ProbeResult>> = _sheet.asStateFlow()

    /**
     * Pure cache read — NO fingerprint check, NO probing, safe on the MAIN
     * thread and inside the registry lock. The maintenance work (fingerprint
     * invalidation, backoff re-probes) only ever runs inside [sheet], which
     * the create path calls on a background thread. Consumers that must not
     * block (settings UI, the list tool's upgrade hint) read this snapshot;
     * a stale entry is honest "last known state", not a wrong one.
     */
    val sheetSnapshot: Map<VscreenTier, ProbeResult>
        get() = _sheet.value

    @Volatile private var lastFingerprint: String? = null

    @Volatile private var fpCache: String? = null
    @Volatile private var fpCacheAtMs: Long = 0L

    init {
        synchronized(lock) { restoreFromPrefs() }
    }

    /**
     * Current sheet with lazy maintenance: fingerprint change invalidates
     * transient probes, and Degraded entries older than [BACKOFF_MS] get one
     * fresh [DisplayProvider.probe] right here (no background thread — the
     * next create pays it, which is exactly when the answer matters).
     */
    fun sheet(context: Context?): Map<VscreenTier, ProbeResult> = synchronized(lock) {
        val fp = cachedFingerprint()
        if (lastFingerprint != null && fp != lastFingerprint) {
            AppLogger.info(TAG, "privilege fingerprint changed ($lastFingerprint -> $fp); invalidating probes")
            invalidateTransientLocked()
        }
        lastFingerprint = fp
        val now = nowMs()
        for (tier in VscreenTier.PRIORITY) {
            val cached = _sheet.value[tier]
            when {
                // First contact: probe now (once — the result persists).
                cached == null -> {
                    val fresh = runCatching { providers[tier]?.probe(context) }.getOrNull()
                    putLocked(tier, fresh ?: ProbeResult.Unavailable("tier not installed in this build"))
                }
                // A degraded tier gets one fresh probe after the backoff window.
                cached is ProbeResult.Degraded && now - cached.atMs >= BACKOFF_MS -> {
                    val fresh = runCatching { providers[tier]?.probe(context) }.getOrNull()
                    if (fresh != null) putLocked(tier, fresh)
                }
            }
        }
        _sheet.value
    }

    /**
     * The privilege fingerprint can spawn a `command -v su` (~3s timeout on
     * devices without root) — never call it per sheet() read. A 60s TTL
     * cache bounds the cost to one spawn per minute while still noticing a
     * newly granted root / Shizuku within a create's decision window.
     */
    private fun cachedFingerprint(): String {
        val now = nowMs()
        fpCache?.let { if (now - fpCacheAtMs < FINGERPRINT_TTL_MS) return it }
        val fresh = runCatching { fingerprint() }.getOrDefault("unknown")
        fpCache = fresh
        fpCacheAtMs = now
        return fresh
    }

    /** Records a probe result (capped reason, persisted). */
    fun put(tier: VscreenTier, result: ProbeResult) = synchronized(lock) { putLocked(tier, result) }

    /** Marks a tier degraded after a failed create attempt. */
    fun markDegraded(tier: VscreenTier, reason: String) =
        put(tier, ProbeResult.Degraded(reason.take(MAX_REASON), nowMs()))

    /**
     * Upgrade hint from the CACHED sheet only — no probing, no fingerprint.
     * The list tool's best-effort hint must never stall; a privilege change
     * is picked up by the next create's full [sheet] run.
     */
    fun upgradeHintFromSnapshot(liveTiers: Set<VscreenTier>): String? =
        TierSelector.upgradeHint(liveTiers, _sheet.value)

    /**
     * Upgrade hint for the list result, or null when live screens already
     * run on the best available tier (or nothing better is available).
     */
    fun upgradeAvailable(liveTiers: Set<VscreenTier>, context: Context?): String? =
        TierSelector.upgradeHint(liveTiers, sheet(context))

    /** Test/reset hook: forget everything (next sheet() re-probes). */
    fun resetForTest() = synchronized(lock) {
        lastFingerprint = null
        fpCache = null
        fpCacheAtMs = 0L
        prefs.remove(KEY)
        _sheet.value = emptyMap()
    }

    // ── internals ─────────────────────────────────────────────────────────

    private fun putLocked(tier: VscreenTier, result: ProbeResult) {
        val next = _sheet.value.toMutableMap()
        next[tier] = result
        _sheet.value = next
        persistLocked()
    }

    /**
     * Fingerprint invalidation is conservative: Unavailable entries that are
     * STRUCTURAL (not installed / no binary) may become available exactly
     * when the fingerprint changes, so everything except a live Degraded
     * backoff is dropped; the next sheet() re-probes from scratch.
     */
    private fun invalidateTransientLocked() {
        val next = _sheet.value.toMutableMap()
        next.entries.removeIf { it.value !is ProbeResult.Degraded }
        _sheet.value = next
        persistLocked()
    }

    private fun persistLocked() {
        val root = JSONObject()
        for ((tier, result) in _sheet.value) {
            val o = JSONObject()
            when (result) {
                is ProbeResult.Available -> {
                    o.put(KIND, K_AVAILABLE)
                    o.put(CAPS, org.json.JSONArray(result.capabilities.map { it.name }))
                }
                is ProbeResult.Degraded -> {
                    o.put(KIND, K_DEGRADED)
                    o.put(REASON, result.reason)
                    o.put(AT, result.atMs)
                }
                is ProbeResult.Unavailable -> {
                    o.put(KIND, K_UNAVAILABLE)
                    o.put(REASON, result.reason)
                    o.put(AT, 0L)
                }
            }
            root.put(tier.name, o)
        }
        prefs.putString(KEY, root.toString())
    }

    private fun restoreFromPrefs() {
        val raw = prefs.getString(KEY) ?: return
        runCatching {
            val root = JSONObject(raw)
            val restored = mutableMapOf<VscreenTier, ProbeResult>()
            for (tier in VscreenTier.entries) {
                val o = root.optJSONObject(tier.name) ?: continue
                when (o.optString(KIND)) {
                    K_AVAILABLE -> {
                        val caps = o.optJSONArray(CAPS)?.let { arr ->
                            (0 until arr.length()).mapNotNull { idx ->
                                runCatching { VscreenCapability.valueOf(arr.getString(idx)) }.getOrNull()
                            }
                        }.orEmpty().toSet()
                        restored[tier] = ProbeResult.Available(caps)
                    }
                    K_DEGRADED -> restored[tier] = ProbeResult.Degraded(
                        o.optString(REASON), o.optLong(AT, 0L),
                    )
                    K_UNAVAILABLE -> restored[tier] = ProbeResult.Unavailable(o.optString(REASON))
                }
            }
            _sheet.value = restored
        }.onFailure {
            AppLogger.warning(TAG, "tier probe cache unreadable; re-probing from scratch: ${it.message}")
            prefs.remove(KEY)
        }
    }

    companion object {
        private const val TAG = "VscreenTier"
        private const val KEY = "tier_probe_v1"
        private const val KIND = "kind"
        private const val CAPS = "caps"
        private const val REASON = "reason"
        private const val AT = "at"
        private const val K_AVAILABLE = "available"
        private const val K_DEGRADED = "degraded"
        private const val K_UNAVAILABLE = "unavailable"
        private const val MAX_REASON = 300

        /** A degraded tier waits this long before the next create re-probes it. */
        const val BACKOFF_MS = 10 * 60_000L

        /** Privilege-fingerprint cache window (bounds su-probe spawns). */
        const val FINGERPRINT_TTL_MS = 60_000L
    }
}
