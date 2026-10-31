package com.openminis.app.knowledge

/**
 * [P2-convergence] Minimal shared reader for the two `---`-delimited Markdown
 * formats the app ships and edits: app knowledge packs (`APP.md`) and SOUL.md.
 * Both used to hand-roll the same scan (find the opening `---`, find the
 * closing `---`, split `key: value` lines, strip one pair of double quotes);
 * this is that scan, once.
 *
 * Pure Kotlin — no Android types, no YAML dependency, JVM-testable. What it
 * deliberately does NOT decide (the two call sites keep their own tolerance):
 *  - CRLF normalization: the reader splits on `\n` only; callers that need it
 *    normalize `"\r\n" → "\n"` first (AppKnowledge does, SOUL.md does not, so
 *    its historic round-trip behavior is byte-preserved).
 *  - Leading blank lines: SOUL.md strips them before calling; packs treat
 *    them as "no frontmatter".
 *  - Body post-processing: [body] is the raw text after the closing `---`
 *    (re-joined with `\n`); the pack trims it, SOUL.md drops only leading
 *    newlines.
 *  - Key casing and per-key emptiness rules live with the callers; keys are
 *    reported exactly as written, in file order.
 */
internal data class Frontmatter(
    /** `key to value` pairs in file order; value keeps the raw unquoted scalar. */
    val fields: List<Pair<String, String>>,
    /** Everything after the closing `---` line, joined back with `\n`. Never trimmed here. */
    val body: String,
) {
    /** Value of the LAST occurrence of [key], or null when the key never appears. */
    fun value(key: String): String? {
        var found: String? = null
        fields.forEach { if (it.first == key) found = it.second }
        return found
    }

    companion object {
        /**
         * Parse [text]. Null when the text does not open with a `---` line or
         * the frontmatter is never closed — the caller decides whether that is
         * fatal (packs throw) or a pass-through (SOUL.md falls back to its
         * default metadata).
         */
        fun parse(text: String): Frontmatter? {
            val lines = text.split("\n")
            if (lines.firstOrNull()?.trim() != "---") return null
            val closeIdx = lines.drop(1).indexOfFirst { it.trim() == "---" }
            if (closeIdx < 0) return null
            val close = closeIdx + 1

            val fields = mutableListOf<Pair<String, String>>()
            for (raw in lines.subList(1, close)) {
                val line = raw.trim()
                // Blank lines and `#` comments are skipped, not fatal; a line
                // without a colon (or starting with one) is malformed YAML and
                // is skipped too. Matches both former hand-rolled loops.
                if (line.isEmpty() || line.startsWith("#")) continue
                val colon = line.indexOf(':')
                if (colon <= 0) continue
                val key = line.substring(0, colon).trim()
                val value = line.substring(colon + 1).trim().removeSurrounding("\"")
                fields.add(key to value)
            }
            val body = lines.drop(close + 1).joinToString("\n")
            return Frontmatter(fields, body)
        }
    }
}
