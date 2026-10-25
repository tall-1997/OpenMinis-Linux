package com.openminis.app.sandbox.kernel

/**
 * The producer brake. When the ring is full the caller must stop reading so
 * the pipe fills and the guest blocks in write. Delivery to a UI callback is
 * a separate, slower permit — stopping the read is what stops the disk walk,
 * not a nice value.
 */
class StreamSink(
    val capBytes: Long,
    val rateBytesPerSec: Long,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val highWater = minOf(8 * 1024 * 1024L, maxOf(64 * 1024L, capBytes / 4)).toInt()
    private val memory = StringBuilder()
    private var accepted = 0L
    private var dropped = 0L
    private var stop = false
    private var windowStart = 0L
    private var windowUsed = 0L

    /**
     * How long the reader should wait before the next read so a fast guest
     * blocks in write instead of filling the UI pipe. Zero means read now.
     */
    @Synchronized
    fun writeWaitMs(bytes: Int, nowMs: Long = clock()): Long {
        if (rateBytesPerSec <= 0L || bytes <= 0) return 0L
        if (windowStart == 0L || nowMs - windowStart >= 1000L) {
            windowStart = nowMs
            windowUsed = 0L
        }
        windowUsed += bytes
        if (windowUsed <= rateBytesPerSec) return 0L
        val over = windowUsed - rateBytesPerSec
        return (over * 1000L / rateBytesPerSec).coerceIn(1L, 1000L)
    }

    @Synchronized
    fun canRead(): Boolean = !stop && memory.length < highWater

    /** @return false when the cap is hit and the caller must stop reading. */
    @Synchronized
    fun append(chars: CharArray, off: Int, len: Int): Boolean {
        if (stop || len <= 0) return !stop
        var i = off
        val end = (off + len).coerceAtMost(chars.size)
        while (i < end) {
            if (accepted >= capBytes) {
                dropped += (end - i)
                stop = true
                return false
            }
            if (memory.length >= highWater) {
                val drop = memory.length / 2
                memory.delete(0, drop)
            }
            val room = minOf(highWater - memory.length, (capBytes - accepted).toInt(), end - i)
            if (room <= 0) {
                stop = true
                dropped += (end - i)
                return false
            }
            memory.append(chars, i, room)
            accepted += room
            i += room
        }
        return !stop
    }

    // [T-streamsink-dead-delivery] pollLine/takeDelivery had zero callers —
    // the delivery-side "permit" they metered never existed, so keeping
    // them suggested a double brake (byte brake + line brake) that was not
    // there. The only rate brake is writeWaitMs, which the reader consults
    // between reads; it owns the window bookkeeping takeDelivery used to
    // split with it.

    @Synchronized
    fun snapshot(): String = buildString {
        append(memory)
        if (dropped > 0L || stop) {
            append("\n[sink-capped dropped=")
            append(dropped)
            append(" accepted=")
            append(accepted)
            append("]")
        }
    }
}
