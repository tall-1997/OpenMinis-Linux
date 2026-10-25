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
 * A malformed id is never reused: [sanitizeOrNull] is the only way a persisted
 * or remembered id re-enters a request, so an id from an older client build
 * (or a truncated one) is replaced instead of being replayed into a 401 loop.
 *
 * The same id may be reused across requests — measured, the lane accepts it —
 * but [ZenFreeLaneGate] mints a fresh one per call and rotates it on 401.
 */
internal object ZenSession {
    private const val PREFIX = "ses_"
    private const val TIME_HEX_LEN = 12
    private const val RANDOM_LEN = 14
    private const val MASK_48 = 0xFFFFFFFFFFFFL
    private const val COUNTER_BITS = 12
    private const val MAX_COUNTER = 0xFFF

    private val SHAPE = Regex("^ses_[0-9a-f]{12}[0-9A-Za-z]{14}$")
    private val ALPHABET: CharArray = (('0'..'9') + ('A'..'Z') + ('a'..'z')).toCharArray()

    private val lastMs = AtomicLong(0L)
    private var counter = 0

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
