package com.openminis.app.data.body

import java.util.concurrent.atomic.AtomicLong

/**
 * One absolute budget shared by chat, export, backup, fork, search,
 * evolution, logs, network responses, and native entry points.
 * [onTrimMemory] may release reservations and refuse new work. It is not
 * the size ceiling.
 */
object Admission {
    private val remaining = AtomicLong(ResourceLimits.ADMIT_BUDGET_BYTES)

    class Refused(val bytes: Long) : IllegalStateException("admission refused: $bytes")

    inline fun <T> nativeCall(text: String, block: () -> T): T {
        val bytes = text.length.toLong() * 2
        if (!tryAdmit(bytes)) throw Refused(bytes)
        try {
            return block()
        } finally {
            release(bytes)
        }
    }

    inline fun <T> occupy(bytes: Long, block: () -> T): T {
        if (!tryAdmit(bytes)) throw Refused(bytes)
        try {
            return block()
        } finally {
            release(bytes)
        }
    }

    fun tryAdmit(bytes: Long): Boolean {
        if (bytes < 0) return false
        if (bytes == 0L) return true
        while (true) {
            val now = remaining.get()
            if (now < bytes) return false
            if (remaining.compareAndSet(now, now - bytes)) return true
        }
    }

    fun release(bytes: Long) {
        if (bytes <= 0) return
        while (true) {
            val now = remaining.get()
            val next = (now + bytes).coerceAtMost(ResourceLimits.ADMIT_BUDGET_BYTES)
            if (remaining.compareAndSet(now, next)) return
        }
    }

    fun remaining(): Long = remaining.get()

    /** Drop cached reservations. Does not raise the absolute ceiling. */
    fun onTrim() {
        remaining.set(ResourceLimits.ADMIT_BUDGET_BYTES)
    }

    internal fun resetForTests() {
        remaining.set(ResourceLimits.ADMIT_BUDGET_BYTES)
    }
}
