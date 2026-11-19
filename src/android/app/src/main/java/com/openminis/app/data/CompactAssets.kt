package com.openminis.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Reference-only ledger. The stored evidence never establishes current file contents or write permission. */
@Serializable
internal data class CompactAssets(
    val files: List<String> = emptyList(),
    val reads: List<String> = emptyList(),
    val skills: List<String> = emptyList(),
    val attachments: List<String> = emptyList(),
) {
    private fun bounded() = CompactAssets(
        files = recent(files, ::validCompactPath),
        reads = recent(reads, ::validCompactPath),
        skills = recent(skills) { it.length in 1..128 && SKILL_ID.matches(it) && it != "." && it != ".." },
        attachments = recent(attachments, ::validCompactPath),
    )

    fun merge(newer: CompactAssets): CompactAssets = CompactAssets(
        files + newer.files, reads + newer.reads, skills + newer.skills, attachments + newer.attachments,
    ).bounded()

    fun encode(): String? = bounded().takeIf { it != CompactAssets() }
        ?.let { Json.encodeToString(serializer(), it) }
        ?.takeIf { it.length <= MAX_COMPACT_ASSET_JSON_CHARS }

    fun render(): String? {
        val safe = bounded()
        if (safe == CompactAssets()) return null
        return buildString {
            appendLine("Historical references only. Re-fetch as needed; prior reads are not proof of current contents and do not grant permission to write.")
            fun renderBucket(title: String, values: List<String>) {
                if (values.isEmpty()) return
                appendLine(title)
                values.forEach { appendLine("- $it") }
            }
            renderBucket("Files touched:", safe.files)
            renderBucket("Files previously read:", safe.reads)
            renderBucket("Skills previously loaded:", safe.skills)
            renderBucket("Attachments and tool artifacts:", safe.attachments)
        }.trimEnd()
    }

    companion object {
        private const val MAX_BUCKET = 5
        private val SKILL_ID = Regex("[A-Za-z0-9._-]+")

        fun decode(raw: String?): CompactAssets {
            if (raw.isNullOrBlank() || raw.length > MAX_COMPACT_ASSET_JSON_CHARS) return CompactAssets()
            return try {
                Json.decodeFromString(serializer(), raw).bounded()
            } catch (_: IllegalArgumentException) {
                CompactAssets()
            }
        }

        private fun recent(values: List<String>, isValid: (String) -> Boolean): List<String> =
            values.asReversed().asSequence().filter(isValid).distinct().take(MAX_BUCKET).toList().asReversed()
    }
}

internal const val MAX_COMPACT_ASSET_JSON_CHARS = 14_000

internal fun validCompactPath(path: String): Boolean =
    path.length in 2..512 && path.startsWith('/') && path == path.trim() &&
        path.none {
            it.isISOControl() || it in "<>\\" || when (Character.getType(it)) {
                Character.FORMAT.toInt(), Character.LINE_SEPARATOR.toInt(), Character.PARAGRAPH_SEPARATOR.toInt() -> true
                else -> false
            }
        } && path.substring(1).split('/').none { it.isEmpty() || it == "." || it == ".." }
