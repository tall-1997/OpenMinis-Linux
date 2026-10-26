package com.openminis.app.provider

import java.util.concurrent.atomic.AtomicLong
import kotlin.random.Random

/**
 * Session ids for the keyless Zen lane.
 *
 * [T-zen-sid-shape] The measured shape is `ses_` + 12 lowercase-hex characters +
 * 14 Base62 characters (30 characters total). The hex half is an inverted
 * 48-bit counter built from the wall clock, which is what makes the id look
 * like an upstream-issued one rather than a random string; the Base62 half
 * carries the entropy that keeps concurrent ids apart.
 *
 * [T-zen-sid-counter-window] Re-measured 2026-10-17 (and mirrored by the
 * reference gateways this lane is impersonated against): the upstream v2 gate
 * **decodes** the twelve hex digits and refuses ids whose embedded counter
 * falls outside the 1–63 window — an ascending `ts << 12` value, a sha256
 * digest, or a counter ≥ 100 is a `403 FreeTierError` even when the `ses_`
 * shape is byte-perfect. The inversion `~(ts shl 12 + counter)` puts the low
 * 12 bits at `0xfff - counter`, so a counter of 1–63 lands the last three hex
 * digits in `fc0…ffe`, inside the accepted window. [MAX_COUNTER] is therefore
 * 63, not the 0xFFF the arithmetic would tolerate.
 *
 * ## Stable id per process
 *
 * Free-tier quota is accounted per session, and a burst of freshly minted ids
 * from one client is exactly what the upstream accounting flags as abuse
 * (measured: a fresh id per request surfaces as `429 FreeUsageLimitError`
 * with a growing retry-after). [stableId] therefore pins one id for the whole
 * process and reuses it across requests; [ZenFreeLaneGate] rotates it only on
 * a 401, which is the one signal that says the id itself was refused.
 *
 * A malformed id is never reused: [sanitizeOrNull] is the only way a persisted
 * or remembered id re-enters a request, so an id from an older client build
 * (or a truncated one) is replaced instead of being replayed into a 401 loop.
 */
internal object ZenSession {
    private const val PREFIX = "ses_"
    private const val TIME_HEX_LEN = 12
    private const val RANDOM_LEN = 14
    private const val MASK_48 = 0xFFFFFFFFFFFFL
    private const val COUNTER_BITS = 12

    /**
     * The v2 gate accepts counters 1–63 only (low 12 bits `0xfc0–0xfff`);
     * anything larger is a 403 even though the shape is right.
     */
    private const val MAX_COUNTER = 63L

    private val SHAPE = Regex("^ses_[0-9a-f]{12}[0-9A-Za-z]{14}$")
    private val ALPHABET: CharArray = (('0'..'9') + ('A'..'Z') + ('a'..'z')).toCharArray()

    private val lastMs = AtomicLong(0L)
    private var counter = 0L

    @Volatile
    private var stable: String? = null

    /** The one id this process speaks to the lane with. Minted once, reused. */
    fun stableId(): String =
        stable ?: synchronized(this) {
            stable ?: newId().also { stable = it }
        }

    /**
     * Drop the pinned id and mint a fresh one. Called by [ZenFreeLaneGate]
     * after a 401 — the only refusal that means the id itself was rejected.
     */
    fun rotate(): String = synchronized(this) {
        stable = newId()
        stable!!
    }

    /** Test seam: forget the pinned id so a fresh one is minted next. */
    internal fun resetForTest() {
        synchronized(this) { stable = null }
    }

    fun newId(): String {
        val v = nextInvertedTimestamp()
        val out = StringBuilder(PREFIX.length + TIME_HEX_LEN + RANDOM_LEN)
        out.append(PREFIX)
        out.append(v.toString(16).padStart(TIME_HEX_LEN, '0'))
        repeat(RANDOM_LEN) { out.append(ALPHABET[Random.nextInt(ALPHABET.size)]) }
        return out.toString()
    }

    /** `((ms shl 12) + counter)` masked to 48 bits, then inverted and re-masked. */
    private fun nextInvertedTimestamp(): Long {
        val ms = System.currentTimeMillis()
        val count = synchronized(this) {
            if (lastMs.get() == ms && counter < MAX_COUNTER) {
                counter++
            } else {
                lastMs.set(ms)
                counter = 1
            }
            counter
        }
        val packed = ((ms shl COUNTER_BITS) + count) and MASK_48
        return packed.inv() and MASK_48
    }

    fun isWellFormed(id: String?): Boolean = id != null && SHAPE.matches(id)

    /** The only path a remembered id may take back into a request. */
    fun sanitizeOrNull(id: String?): String? = if (isWellFormed(id)) id else null
}
