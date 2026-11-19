package com.openminis.app.data.repository

/**
 * [T-memory-frontmatter] Optional structured metadata for a memory file.
 *
 * WHY this exists: daily logs were plain markdown with no way to say "this
 * conclusion has been superseded", "this note expires in 7 days" or "this
 * entry is disabled". Every recalled fact therefore stayed eligible forever,
 * and an old wrong conclusion kept competing with the correction that replaced
 * it. OpenHarness solves this with a frontmatter schema
 * (type/scope/importance/ttl_days/disabled/supersedes); this is the subset that
 * changes a DECISION rather than a display.
 *
 * Backward compatibility is the hard requirement: a file with no frontmatter is
 * exactly as eligible as before. Unknown keys are carried, never rejected, so a
 * file written by a newer build still loads here.
 *
 * Parsing is deliberately tiny and total:
 *  - a block must be the FIRST thing in the file (`---` line, then `key: value`
 *    lines, then a closing `---`), otherwise the file is treated as having no
 *    metadata at all;
 *  - a malformed line is skipped, not fatal;
 *  - only the keys below are interpreted; the rest is ignored.
 */
internal data class MemoryMetadata(
    /** `user` | `feedback` | `project` | `reference`; display only today. */
    val type: String? = null,
    /** `private` | `project` | `team`; display only today. */
    val scope: String? = null,
    /** A disabled file is excluded from prompt injection AND from the index. */
    val disabled: Boolean = false,
    /** Days after [updatedAtMs] at which the file stops being eligible. */
    val ttlDays: Int? = null,
    /** Loose identity of what this file replaces (informational). */
    val supersedes: String? = null,
) {
    /**
     * True when this file must not reach the model at [nowMs].
     *
     * TTL is measured from the file's own mtime, not from a frontmatter
     * timestamp: the mtime is the one value every writer already maintains, so
     * a hand-edited file and a `memory_write` entry expire on the same rule.
     */
    fun isExpired(nowMs: Long, fileModifiedAtMs: Long): Boolean {
        val ttl = ttlDays ?: return false
        if (ttl <= 0) return false
        val ageMs = nowMs - fileModifiedAtMs
        return ageMs > ttl.toLong() * 86_400_000L
    }

    fun isEligible(nowMs: Long, fileModifiedAtMs: Long): Boolean =
        !disabled && !isExpired(nowMs, fileModifiedAtMs)
}

/** [T-memory-frontmatter] Parser + the one eligibility entry point. */
internal object MemoryMetadataParser {

    private const val FENCE = "---"

    /** Parse the leading frontmatter block; empty metadata when absent. */
    fun parse(text: String): MemoryMetadata {
        if (text.isEmpty()) return MemoryMetadata()
        val lines = text.lineSequence().iterator()
        if (!lines.hasNext() || lines.next().trim() != FENCE) return MemoryMetadata()
        var disabled = false
        var ttlDays: Int? = null
        var type: String? = null
        var scope: String? = null
        var supersedes: String? = null
        var closed = false
        while (lines.hasNext()) {
            val raw = lines.next().trim()
            if (raw == FENCE) {
                closed = true
                break
            }
            if (raw.isEmpty() || raw.startsWith("#")) continue
            val sep = raw.indexOf(':')
            if (sep <= 0) continue
            val key = raw.substring(0, sep).trim().lowercase()
            val value = raw.substring(sep + 1).trim().trim('"', '\'')
            when (key) {
                "disabled" -> disabled = value.equals("true", ignoreCase = true)
                "ttl_days" -> ttlDays = value.toIntOrNull()
                "type" -> type = value.ifBlank { null }
                "scope" -> scope = value.ifBlank { null }
                "supersedes" -> supersedes = value.ifBlank { null }
                else -> Unit
            }
        }
        // An unterminated block is not frontmatter: the file may legitimately
        // start with a `---` horizontal rule, and treating the whole body as
        // metadata would silently hide it.
        if (!closed) return MemoryMetadata()
        return MemoryMetadata(type = type, scope = scope, disabled = disabled, ttlDays = ttlDays, supersedes = supersedes)
    }

    /** Eligibility of a file's text at a point in time. */
    fun isEligible(text: String, nowMs: Long, fileModifiedAtMs: Long): Boolean =
        parse(text).isEligible(nowMs, fileModifiedAtMs)

    /**
     * [T-memory-supersedes] Identities replaced by some file's `supersedes:`
     * value.
     *
     * WHY a shared helper: "a superseded memory must not reach the prompt AND
     * must not be recalled" is one rule applied in three readers (the daily
     * prompt window, the index and the recall engine). Three inline copies is
     * exactly how the token meter and the tool pairing drifted in this branch,
     * so the rule lives once and each reader passes its own (id, text) pairs.
     *
     * Matching is deliberately loose and forgiving, because the value is
     * human-written: case-insensitive, with or without a `.md` suffix, and it
     * matches an id by exact equality. A near-miss supersedes nothing rather
     * than hiding an unrelated memory.
     */
    fun supersededIds(files: List<Pair<String, String>>): Set<String> {
        if (files.isEmpty()) return emptySet()
        val claimed = HashSet<String>()
        for ((_, text) in files) {
            val target = parse(text).supersedes ?: continue
            claimed += normalizeId(target)
        }
        if (claimed.isEmpty()) return emptySet()
        return files.asSequence()
            .map { it.first }
            .filter { normalizeId(it) in claimed }
            .toHashSet()
    }

    /**
     * [T-memory-frontmatter-strip] The file body WITHOUT the frontmatter block.
     *
     * WHY: the block is machine metadata. Rendering it into the TOC preview or
     * the injected daily fragment showed the model `---` / `type: daily` as if
     * it were memory content, and the TOC preview of a NEW day became literally
     * "---". Readers strip; the writer keeps the raw file intact.
     */
    fun strip(text: String): String {
        if (text.isEmpty()) return text
        val reader = text.lineSequence().iterator()
        if (!reader.hasNext() || reader.next().trim() != FENCE) return text
        val rest = StringBuilder()
        var closed = false
        while (reader.hasNext()) {
            val raw = reader.next()
            if (!closed) {
                if (raw.trim() == FENCE) closed = true
                continue
            }
            rest.append(raw).append('\n')
        }
        // An unterminated block is not frontmatter: keep the original text.
        return if (closed) rest.toString() else text
    }

    private fun normalizeId(id: String): String =
        id.trim().lowercase().removeSuffix(".md")

    /**
     * [T-memory-frontmatter-writer] Render the block this parser reads back.
     *
     * WHY the writer lives beside the parser: the two halves of one format
     * drifting apart is the failure this whole file exists to prevent — the
     * reader already had `supersedes`/`ttl_days` support while nothing emitted
     * them, so `4.1` was "reader-only". Emitting from the same object means a
     * key added here is a key the reader already understands.
     *
     * Only non-null fields are written, so a plain daily log stays a plain
     * daily log instead of gaining five `null` lines. Returns the empty string
     * when there is nothing to declare — callers prepend it unconditionally.
     */
    fun frontmatterFor(
        type: String? = null,
        scope: String? = null,
        ttlDays: Int? = null,
        disabled: Boolean = false,
        supersedes: String? = null,
    ): String {
        val lines = ArrayList<String>(5)
        type?.takeIf { it.isNotBlank() }?.let { lines += "type: $it" }
        scope?.takeIf { it.isNotBlank() }?.let { lines += "scope: $it" }
        if (disabled) lines += "disabled: true"
        ttlDays?.let { lines += "ttl_days: $it" }
        supersedes?.takeIf { it.isNotBlank() }?.let { lines += "supersedes: $it" }
        if (lines.isEmpty()) return ""
        return buildString {
            append(FENCE).append('\n')
            for (line in lines) append(line).append('\n')
            append(FENCE).append('\n')
        }
    }
}
