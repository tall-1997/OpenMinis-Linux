package com.openminis.app.sandbox

import android.util.Log
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.charset.Charset
import java.nio.file.Files
import java.nio.file.Paths

/** Log tag preserved from RootfsManager so tar-extraction logging is unchanged. */
private const val TAG = "RootfsManager"

// --- POSIX tar extraction ---

/**
 * Extract a POSIX tar stream into the target directory.
 * Handles regular files, directories, and symlinks.
 * Does not depend on any external library.
 */
internal fun extractTar(input: InputStream, targetDir: File) {
    val header = ByteArray(512)

    while (true) {
        val bytesRead = readFully(input, header)
        if (bytesRead == 0) break
        if (bytesRead < 512) throw java.io.IOException("short tar header: $bytesRead")

        // Check for end-of-archive (two consecutive zero blocks)
        if (header.all { it == 0.toByte() }) break

        val name = extractString(header, 0, 100)
        val modeOctal = extractString(header, 100, 8)
        val sizeOctal = extractString(header, 124, 12)
        val typeFlag = header[156].toInt().toChar()
        val linkName = extractString(header, 157, 100)
        val mode = modeOctal.trim().toIntOrNull(8) ?: 0

        // Handle GNU/POSIX long names via prefix field (bytes 345-500)
        val prefix = extractString(header, 345, 155)
        val fullName = if (prefix.isNotEmpty()) "$prefix/$name" else name

        if (fullName.isEmpty()) break

        val size = sizeOctal.trim().toLongOrNull(8) ?: 0L
        val outFile = File(targetDir, fullName)

        when (typeFlag) {
            '5', 'D' -> {
                // Directory
                outFile.mkdirs()
            }
            '2' -> {
                // Symbolic link. Android app-private storage often rejects
                // createSymbolicLink; fall back to copying the referent so
                // /etc/ssl/certs hash names still resolve (otherwise apt/curl
                // report "no valid CA chain").
                outFile.parentFile?.mkdirs()
                if (outFile.exists()) outFile.delete()
                val linkPath = Paths.get(linkName)
                try {
                    Files.createSymbolicLink(outFile.toPath(), linkPath)
                } catch (_: Exception) {
                    val dest = if (linkPath.isAbsolute) {
                        File(targetDir, linkPath.toString().trimStart('/'))
                    } else {
                        File(outFile.parentFile, linkName)
                    }
                    try {
                        if (dest.exists() && dest.isFile) {
                            dest.copyTo(outFile, overwrite = true)
                        } else {
                            Log.w(TAG, "Failed to create symlink: $fullName -> $linkName")
                        }
                    } catch (t: Exception) {
                        Log.w(TAG, "Failed to materialize symlink $fullName: ${t.message}")
                    }
                }
            }
            '0', '\u0000' -> {
                // Regular file (type '0' or null/legacy)
                outFile.parentFile?.mkdirs()
                outFile.outputStream().use { output ->
                    var remaining = size
                    val buf = ByteArray(8192)
                    while (remaining > 0) {
                        val toRead = minOf(buf.size.toLong(), remaining).toInt()
                        val n = input.read(buf, 0, toRead)
                        if (n < 0) {
                            throw java.io.IOException("short read of $name, $remaining bytes missing")
                        }
                        output.write(buf, 0, n)
                        remaining -= n
                    }
                }
                // Preserve executable permission from tar header
                if (mode and 0b001_001_001 != 0) {
                    outFile.setExecutable(true, false)
                }
                // Skip padding to next 512-byte boundary
                val remainder = (size % 512).toInt()
                if (remainder != 0) {
                    skipFully(input, (512 - remainder).toLong())
                }
                continue // Already consumed data + padding
            }
            '1' -> {
                // Hard link — create a copy
                outFile.parentFile?.mkdirs()
                val linkTarget = File(targetDir, linkName)
                if (linkTarget.exists()) {
                    linkTarget.copyTo(outFile, overwrite = true)
                }
            }
            else -> {
                // Unknown type, skip data
            }
        }

        // Skip data blocks for non-file entries that we didn't consume above
        if (typeFlag != '0' && typeFlag != '\u0000' && size > 0) {
            val blocks = (size + 511) / 512 * 512
            skipFully(input, blocks)
        }
    }
}

internal fun extractString(header: ByteArray, offset: Int, length: Int): String {
    val end = minOf(offset + length, header.size)
    var actualEnd = offset
    for (i in offset until end) {
        if (header[i] == 0.toByte()) break
        actualEnd = i + 1
    }
    return String(header, offset, actualEnd - offset, Charset.forName("UTF-8"))
}

internal fun readFully(input: InputStream, buf: ByteArray): Int {
    var offset = 0
    while (offset < buf.size) {
        val n = input.read(buf, offset, buf.size - offset)
        if (n < 0) return offset
        offset += n
    }
    return offset
}

internal fun skipFully(input: InputStream, count: Long) {
    var remaining = count
    val buf = ByteArray(8192)
    while (remaining > 0) {
        val toRead = minOf(buf.size.toLong(), remaining).toInt()
        val n = input.read(buf, 0, toRead)
        if (n < 0) throw java.io.IOException("short tar skip: $remaining")
        remaining -= n
    }
}

/**
 * InputStream wrapper that reports cumulative bytes read as a 0..1 fraction.
 * Throttles updates so each +1% bump emits at most once. Designed to wrap
 * the asset stream (not the gzip stream) so progress is monotonic against
 * a size we can cheaply know up front.
 */
internal class ProgressInputStream(
    inner: InputStream,
    private val total: Long,
    private val onProgress: (Float) -> Unit,
) : FilterInputStream(inner) {
    private var read: Long = 0
    private var lastReportedPercent: Int = -1

    override fun read(): Int {
        val b = super.read()
        if (b >= 0) { read += 1; publish() }
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = super.read(b, off, len)
        if (n > 0) { read += n; publish() }
        return n
    }

    override fun skip(n: Long): Long {
        val skipped = super.skip(n)
        if (skipped > 0) { read += skipped; publish() }
        return skipped
    }

    private fun publish() {
        if (total <= 0) return
        val pct = ((read * 100) / total).toInt().coerceIn(0, 99)
        if (pct != lastReportedPercent) {
            lastReportedPercent = pct
            onProgress(pct / 100f)
        }
    }
}
