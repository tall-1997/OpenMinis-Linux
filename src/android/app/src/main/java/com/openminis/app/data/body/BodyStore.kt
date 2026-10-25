package com.openminis.app.data.body

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import java.util.UUID

/**
 * Durable body files. A killed write leaves only a temp file, which the next
 * open deletes. The SQLite row is updated only after the rename succeeds.
 *
 * File layout: magic OMB1, uncompressed size (int), compressed size (int),
 * codec byte, payload. Declared uncompressed size above the cap is refused
 * before inflate.
 */
open class BodyStore(private val root: File) {
    data class Put(val ok: Boolean, val ref: String? = null, val sha: String? = null, val error: String? = null)

    fun put(bytes: ByteArray): Put {
        if (bytes.size > ResourceLimits.MAX_DECLARED_UNCOMPRESSED) {
            return Put(ok = false, error = "declared size ${bytes.size} exceeds cap")
        }
        if (!root.mkdirs() && !root.isDirectory) {
            return Put(ok = false, error = "body directory unavailable")
        }
        // Compression is staged to disk, so the only body-sized allocation is
        // the caller-owned input; reserve that input for cross-operation limits.
        val reservation = bytes.size.toLong()
        if (!Admission.tryAdmit(reservation)) {
            return Put(ok = false, error = "admission refused")
        }
        try {
            val sha = sha256(bytes)
            val dest = File(root, sha)
            if (dest.isFile && dest.length() > HEADER) return Put(ok = true, ref = sha, sha = sha)
            // A unique temp per invocation; a concurrent put for identical bytes
            // can never truncate or unlink another writer's in-flight payload.
            val tmp = File(root, "$sha.${UUID.randomUUID()}.tmp")
            return try {
                writeFramed(tmp, bytes)

                synchronized(finalizeLock) {
                    // Another writer of the same content may have won while we
                    // compressed. Never replace its complete object with an
                    // in-progress copy.
                    if (dest.isFile && dest.length() > HEADER) {
                        tmp.delete()
                    } else {
                        if (!tmp.renameTo(dest)) {
                            tmp.copyTo(dest, overwrite = true)
                            tmp.delete()
                        }
                    }
                }
                Put(ok = true, ref = sha, sha = sha)
            } catch (e: Exception) {
                tmp.delete()
                Put(ok = false, error = e.javaClass.simpleName)
            }
        } finally {
            Admission.release(reservation)
        }
    }

    fun read(ref: String, maxBytes: Int = ResourceLimits.SQL_CELL_BYTES): ByteArray? {
        if (ref.length != 64 || ref.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        val file = File(root, ref)
        if (!file.isFile) return null
        val length = file.length()
        if (length < HEADER || length > HEADER + ResourceLimits.MAX_DECLARED_UNCOMPRESSED.toLong()) return null
        // Read framing without copying the stored payload. Reserve output plus a
        // bounded scratch chunk; the caller receives at most maxBytes.
        val inputLength = length - HEADER
        val header = ByteArray(HEADER)
        val stream = try { file.inputStream() } catch (_: Exception) { return null }
        val parsed = try {
            DataInputStream(stream).use { input ->
                input.readFully(header)
                val magicOk = header[0] == 'O'.code.toByte() && header[1] == 'M'.code.toByte() &&
                    header[2] == 'B'.code.toByte() && header[3] == '1'.code.toByte()
                if (!magicOk) return null
                Triple(intAt(header, 4), intAt(header, 8), header[12].toInt() and 0xff)
            }
        } catch (_: Exception) { return null }
        val (uncompressed, stored, codec) = parsed
        if (uncompressed < 0 || uncompressed > ResourceLimits.MAX_DECLARED_UNCOMPRESSED ||
            stored < 0 || stored.toLong() != inputLength) return null
        if (codec == CODEC_RAW && uncompressed != stored) return null
        if (codec == CODEC_DEFLATE && (stored == 0 ||
                uncompressed > stored.toLong() * ResourceLimits.MAX_EXPANSION_RATIO)) return null
        if (codec != CODEC_RAW && codec != CODEC_DEFLATE) return null
        val bounded = maxBytes.coerceIn(0, uncompressed)
        val reservation = bounded.toLong() + IO_CHUNK_BYTES
        if (!Admission.tryAdmit(reservation)) return null
        try {
            val bodyPrefix = ByteArray(bounded)
            val digest = MessageDigest.getInstance("SHA-256")
            val payloadStream = try {
                FileInputStream(file).also { input ->
                    var skipped = 0L
                    while (skipped < HEADER) {
                        val n = input.skip(HEADER - skipped)
                        if (n <= 0) { input.close(); return null }
                        skipped += n
                    }
                }
            } catch (_: Exception) { return null }
            val decoded = if (codec == CODEC_RAW) payloadStream else InflaterInputStream(payloadStream)
            decoded.use { input ->
                val chunk = ByteArray(IO_CHUNK_BYTES)
                var total = 0
                var prefixWritten = 0
                while (true) {
                    val n = input.read(chunk)
                    if (n < 0) break
                    total += n
                    if (total > uncompressed) return null
                    digest.update(chunk, 0, n)
                    val keep = minOf(n, bounded - prefixWritten)
                    if (keep > 0) {
                        System.arraycopy(chunk, 0, bodyPrefix, prefixWritten, keep)
                        prefixWritten += keep
                    }
                }
                if (total != uncompressed || prefixWritten != bounded) return null
            }
            if (!digest.digest().joinToString("") { "%02x".format(it) }.equals(ref, ignoreCase = false)) return null
            return bodyPrefix
        } catch (_: Exception) {
            return null
        } finally {
            Admission.release(reservation)
        }
    }

    private fun writeFramed(tmp: File, bytes: ByteArray) {
        // Keep the overridable atomic writer as the failure-injection seam used
        // by storage tests and production disk-full handling.
        writeAtomic(tmp, MAGIC + intBytes(bytes.size) + intBytes(bytes.size) +
            byteArrayOf(CODEC_RAW.toByte()) + bytes)
    }

    internal open fun writeAtomic(tmp: File, bytes: ByteArray) {
        tmp.outputStream().use { out ->
            out.write(bytes)
            out.fd.sync()
        }
    }

    /** Remove only abandoned temps old enough that no current put can own them. */
    fun discardTemps(maxAgeMillis: Long = TEMP_MAX_AGE_MILLIS, nowMillis: Long = System.currentTimeMillis()) {
        root.listFiles()?.filter { it.name.endsWith(".tmp") }?.forEach { file ->
            val modified = file.lastModified()
            if (modified > 0 && nowMillis - modified >= maxAgeMillis) file.delete()
        }
    }

    private fun deflate(bytes: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        DeflaterOutputStream(out, Deflater(Deflater.BEST_SPEED)).use { it.write(bytes) }
        return out.toByteArray()
    }

    private fun inflateCapped(payload: ByteArray, declared: Int): ByteArray? {
        val out = ByteArray(declared)
        return try {
            InflaterInputStream(ByteArrayInputStream(payload)).use { input ->
                var off = 0
                while (off < declared) {
                    val n = input.read(out, off, declared - off)
                    if (n < 0) return null
                    off += n
                }
                if (input.read() != -1) return null
            }
            out
        } catch (_: java.io.IOException) {
            null
        }
    }

    private fun readBounded(file: File): ByteArray? {
        val len = file.length()
        if (len < HEADER || len > HEADER + ResourceLimits.MAX_DECLARED_UNCOMPRESSED.toLong()) return null
        val out = ByteArray(len.toInt())
        file.inputStream().use { input ->
            var off = 0
            while (off < out.size) {
                val n = input.read(out, off, out.size - off)
                if (n < 0) return null
                off += n
            }
            if (input.read() != -1) return null
        }
        return out
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun intBytes(value: Int): ByteArray = byteArrayOf(
        (value ushr 24).toByte(),
        (value ushr 16).toByte(),
        (value ushr 8).toByte(),
        value.toByte(),
    )

    private fun intAt(raw: ByteArray, offset: Int): Int =
        ((raw[offset].toInt() and 0xff) shl 24) or
            ((raw[offset + 1].toInt() and 0xff) shl 16) or
            ((raw[offset + 2].toInt() and 0xff) shl 8) or
            (raw[offset + 3].toInt() and 0xff)

    companion object {
        private val MAGIC = byteArrayOf('O'.code.toByte(), 'M'.code.toByte(), 'B'.code.toByte(), '1'.code.toByte())
        private const val CODEC_RAW = 1
        private const val CODEC_DEFLATE = 2
        private const val HEADER = 13
        private const val IO_CHUNK_BYTES = 64 * 1024
        private const val TEMP_MAX_AGE_MILLIS = 15L * 60L * 1000L
        private val finalizeLock = Any()
    }
}
