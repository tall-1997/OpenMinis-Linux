package com.openminis.app.data.memory

/**
 * [T-memory-embedding-recall] Minimal embedding seam (#27): anything that can
 * turn text into a fixed-length float vector.
 *
 * The contract is deliberately tiny so implementations can be added without
 * touching the recall path:
 *  - `null` means "unavailable" — no configuration, network failure, bad
 *    response, disabled. Callers must degrade to the keyword-only path when
 *    they see it; it is NOT an error condition.
 *  - The returned list MUST have one vector per input, in input order, all
 *    with the same dimensionality.
 *  - Implementations must be safe to call from any coroutine; blocking work
 *    belongs on Dispatchers.IO inside the implementation.
 */
interface EmbeddingProvider {

    /**
     * Embed [texts]. Returns one vector per input, or null when the provider
     * cannot serve this call. An empty input returns an empty list.
     */
    suspend fun embed(texts: List<String>): List<FloatArray>?
}

/**
 * Vector arithmetic + the on-disk/blob serialization used by
 * [MemoryEmbeddingIndexer]. Pure JVM so the recall fusion is testable
 * without Android or Room.
 */
object VectorMath {

    /**
     * Cosine similarity in [-1, 1]. Zero vectors, dimension mismatches and
     * null inputs all yield 0f (never NaN, never an exception) — a vector
     * written by a different model must simply score as "no signal".
     */
    fun cosine(a: FloatArray?, b: FloatArray?): Float {
        if (a == null || b == null) return 0f
        if (a.size != b.size || a.isEmpty()) return 0f
        var dot = 0.0
        var normA = 0.0
        var normB = 0.0
        for (i in a.indices) {
            val x = a[i].toDouble()
            val y = b[i].toDouble()
            dot += x * y
            normA += x * x
            normB += y * y
        }
        if (normA <= 0.0 || normB <= 0.0) return 0f
        return (dot / kotlin.math.sqrt(normA * normB)).toFloat()
    }
}

/**
 * Fixed-layout little-endian serialization of a float vector into the Room
 * BLOB column (`memory_embeddings.vector`). Layout: 4 bytes per float, in
 * order, no header — the dimensionality is stored alongside in the `dim`
 * column, so decoding trusts [ByteArray.size] to be a multiple of 4 and
 * rejects anything else.
 */
object FloatVectorBlob {

    fun encode(vector: FloatArray): ByteArray {
        val out = ByteArray(vector.size * 4)
        for (i in vector.indices) {
            val bits = java.lang.Float.floatToIntBits(vector[i])
            out[i * 4] = (bits and 0xFF).toByte()
            out[i * 4 + 1] = ((bits shr 8) and 0xFF).toByte()
            out[i * 4 + 2] = ((bits shr 16) and 0xFF).toByte()
            out[i * 4 + 3] = ((bits shr 24) and 0xFF).toByte()
        }
        return out
    }

    /** Null when [bytes] is empty or not a whole number of floats. */
    fun decode(bytes: ByteArray): FloatArray? {
        if (bytes.isEmpty() || bytes.size % 4 != 0) return null
        val out = FloatArray(bytes.size / 4)
        for (i in out.indices) {
            var bits = 0
            bits = bits or (bytes[i * 4].toInt() and 0xFF)
            bits = bits or ((bytes[i * 4 + 1].toInt() and 0xFF) shl 8)
            bits = bits or ((bytes[i * 4 + 2].toInt() and 0xFF) shl 16)
            bits = bits or ((bytes[i * 4 + 3].toInt() and 0xFF) shl 24)
            out[i] = java.lang.Float.intBitsToFloat(bits)
        }
        return out
    }
}
