package com.openminis.app.ui.chat

import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes

/** Read-only compatibility with the #401 JSONL format. Never dispatch or reconcile from it. */
internal object LegacyToolReceiptStore {
    internal const val DIR = "tool-receipts"
    internal const val MAX_BYTES = 2 * 1024 * 1024
    private const val MAX_LINE_BYTES = 8 * 1024
    private val unambiguousId = Regex("[A-Za-z0-9.-]{1,128}")
    private val legacyKey = Regex("[0-9a-f]{16}")

    data class Diagnostics(val unsettled: Int)
    private data class Attempt(val key: String, val op: String)

    fun readDiagnostics(filesDir: File, sessionId: String): Diagnostics? {
        // There is no owner in the records themselves. The old writer replaced
        // unsafe characters with '_', so even an *exact* ID containing '_' can
        // share a filename with other sessions. Only unambiguous names qualify.
        if (!unambiguousId.matches(sessionId) || sessionId == "." || sessionId == "..") return null
        return try {
            val dir = filesDir.canonicalFile.toPath().resolve(DIR)
            if (!Files.isDirectory(dir, NOFOLLOW_LINKS)) return null
            val file = dir.resolve("$sessionId.jsonl")
            val attrs = Files.readAttributes(file, BasicFileAttributes::class.java, NOFOLLOW_LINKS)
            if (!attrs.isRegularFile || attrs.size() > MAX_BYTES) return null
            val real = file.toRealPath()
            if (real.parent != dir || real.fileName.toString() != file.fileName.toString()) return null
            Files.newInputStream(file, READ, NOFOLLOW_LINKS).buffered().use { input ->
                val open = HashSet<Attempt>()
                val line = ByteArray(MAX_LINE_BYTES)
                var used = 0
                var total = 0
                while (true) {
                    val byte = input.read()
                    if (byte < 0) break // An unterminated last record may be a torn write.
                    if (++total > MAX_BYTES) return null
                    if (byte == '\n'.code) {
                        if (used in 1..line.size) consume(String(line, 0, used, Charsets.UTF_8), open)
                        used = 0
                    } else if (used <= line.size) {
                        if (used < line.size) line[used] = byte.toByte()
                        used++
                    }
                }
                Diagnostics(open.size)
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun consume(line: String, open: MutableSet<Attempt>) {
        val record = try {
            JSONObject(line)
        } catch (_: Exception) {
            return
        } catch (_: StackOverflowError) {
            return
        }
        val key = (record.opt("key") as? String)?.takeIf(legacyKey::matches) ?: return
        val op = record.opt("op") as? String ?: return
        val attempt = Attempt(key, op)
        when (record.opt("state")) {
            "not_sent" -> open.add(attempt)
            "accepted" -> if (record.opt("success") is Boolean) open.remove(attempt)
            "abandoned" -> open.remove(attempt)
        }
    }

    /**
     * [T-session-sidecar-cleanup] Deleted session: drop the legacy JSONL.
     *
     * Same disambiguation as [readDiagnostics]: the old writer replaced unsafe
     * characters with '_', so names containing them may SHARE a file with
     * other sessions — those are deliberately left alone rather than deleted
     * from under a live session. Idempotent.
     */
    fun deleteStored(filesDir: File, sessionId: String) {
        if (!unambiguousId.matches(sessionId) || sessionId == "." || sessionId == "..") return
        runCatching { File(File(filesDir, DIR), "$sessionId.jsonl").delete() }
    }
}
