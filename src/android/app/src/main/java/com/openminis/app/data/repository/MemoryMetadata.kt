package com.openminis.app.data.repository

import java.io.BufferedReader

/** Optional file-level policy. Entry-level lifecycle remains independent. */
internal data class MemoryMetadata(
    val type: String? = null,
    val scope: String? = null,
    val disabled: Boolean = false,
    val ttlDays: Int? = null,
    val supersedes: String? = null,
) {
    fun isExpired(nowMs: Long, fileModifiedAtMs: Long): Boolean {
        val ttl = ttlDays?.takeIf { it > 0 } ?: return false
        val lifetime = ttl * MemoryLifecycle.DAY_MS
        return fileModifiedAtMs <= Long.MAX_VALUE - lifetime && nowMs > fileModifiedAtMs + lifetime
    }

    fun isEligible(nowMs: Long, fileModifiedAtMs: Long): Boolean = !disabled && !isExpired(nowMs, fileModifiedAtMs)
}

internal object MemoryMetadataParser {
    private const val FENCE = "---"
    private const val MAX_HEADER_CHARS = 16 * 1024

    /** UTF-16 offset after the original closing line, including BOM and line endings. */
    data class Header(val metadata: MemoryMetadata, val bodyStart: Int)

    fun header(text: String): Header? = text.reader().buffered().use(::header)

    /** The same bounded parser is used for the full body and for header-only scope scans. */
    fun header(reader: BufferedReader): Header? {
        var offset = 0
        fun readLine(): String? {
            val line = StringBuilder()
            while (offset < MAX_HEADER_CHARS) {
                val c = reader.read()
                if (c < 0) return line.toString().takeIf { it.isNotEmpty() }
                offset++
                if (c == '\n'.code) return line.toString()
                if (c == '\r'.code) {
                    reader.mark(1)
                    if (reader.read() == '\n'.code) offset++ else reader.reset()
                    return line.toString()
                }
                line.append(c.toChar())
            }
            return null
        }

        if (readLine()?.removePrefix("\uFEFF")?.trim() != FENCE) return null
        var metadata = MemoryMetadata()
        var hasField = false
        while (true) {
            val line = readLine()?.trim() ?: return null
            if (line == FENCE) return if (hasField) Header(metadata, offset) else null
            // A legacy diary can open with a horizontal rule, then a real entry.
            if (MemoryDiaryFormat.marker.matches(line)) return null
            if (line.startsWith('#')) continue
            val sep = line.indexOf(':')
            val key = if (sep > 0) line.substring(0, sep).trim().lowercase() else continue
            if (key.isEmpty() || key.any { !it.isLetterOrDigit() && it != '_' && it != '-' }) continue
            hasField = true // Unknown fields are still part of a real header.
            val value = line.substring(sep + 1).trim().trim('"', '\'')
            metadata = when (key) {
                "disabled" -> metadata.copy(disabled = value.equals("true", ignoreCase = true))
                "ttl_days" -> metadata.copy(ttlDays = value.toIntOrNull())
                "type" -> metadata.copy(type = value.ifBlank { null })
                "scope" -> metadata.copy(scope = value.ifBlank { null })
                "supersedes" -> metadata.copy(supersedes = value.ifBlank { null })
                else -> metadata
            }
        }
    }

    fun parse(text: String): MemoryMetadata = header(text)?.metadata ?: MemoryMetadata()

    fun isEligible(text: String, nowMs: Long, fileModifiedAtMs: Long): Boolean =
        parse(text).isEligible(nowMs, fileModifiedAtMs)

    /** One directory only. Retirement survives expiry/disable of the replacement; self-claims do not hide it. */
    fun supersededIds(fileMetadata: Map<String, MemoryMetadata>): Set<String> {
        val claimed = fileMetadata.mapNotNullTo(HashSet()) { (name, metadata) ->
            metadata.supersedes?.let(::normalizeId)?.takeUnless { it == normalizeId(name) }
        }
        return fileMetadata.keys.filterTo(HashSet()) { normalizeId(it) in claimed }
    }

    fun strip(text: String): String = header(text)?.let { text.substring(it.bodyStart) } ?: text

    private fun normalizeId(id: String): String = id.trim().lowercase().removeSuffix(".md")

    fun frontmatterFor(
        type: String? = null,
        scope: String? = null,
        ttlDays: Int? = null,
        disabled: Boolean = false,
        supersedes: String? = null,
    ): String {
        val lines = ArrayList<String>(5)
        type?.takeIf(::isHeaderValue)?.let { lines += "type: $it" }
        scope?.takeIf(::isHeaderValue)?.let { lines += "scope: $it" }
        ttlDays?.let { lines += "ttl_days: $it" }
        if (disabled) lines += "disabled: true"
        supersedes?.takeIf(::isHeaderValue)?.let { lines += "supersedes: $it" }
        return if (lines.isEmpty()) "" else lines.joinToString("\n", prefix = "$FENCE\n", postfix = "\n$FENCE\n")
    }

    private fun isHeaderValue(value: String): Boolean = value.isNotBlank() && '\n' !in value && '\r' !in value
}
