package com.openminis.app.logging

/**
 * Line parsing, level ranking, filtering and ring buffering for the in-app
 * live log panel (T-logcat-panel).
 *
 * Everything here is pure Kotlin — no android.util.Log, no Context, no
 * Compose — so the whole contract is unit-testable on the JVM. The screen
 * (`ui/settings/LogcatPanelScreen.kt`) only owns the tailer lifecycle and
 * rendering; every decision about *what a line is* and *whether it shows*
 * lives in this file.
 */

/**
 * One line as the panel sees it.
 *
 * [raw] keeps the untouched original so "copy" reproduces exactly what
 * logcat emitted, while [time]/[level]/[tag]/[message] are the parsed
 * columns the UI colours and filters on.
 */
internal data class LogcatLine(
    val time: String,
    val level: Char,
    val tag: String,
    val message: String,
    val raw: String,
    /**
     * Monotonic id assigned by [LogcatLiveBuffer]. Used as the LazyColumn key
     * so rows keep identity as the ring evicts from the front — positional
     * keys make every row re-bind on each eviction, which reads as a flash.
     */
    val seq: Long = 0L,
)

/**
 * Parser for `logcat -v time` output:
 *
 * ```
 * 11-03 14:21:07.123 I/Minis.Chat( 4321): token refresh ok
 * ```
 *
 * Real-world shapes the parser must survive:
 * - pid padding — `( 4321)` and `(12345)` both occur,
 * - tags containing dots, dashes and digits (`Minis.Chat`, `RIL-Socket-2`),
 * - messages containing `(` / `)` / `/` / `:` — only the *first* `(digits)`
 *   after the tag may be treated as the pid, everything after it is message,
 * - non-logcat lines (a stray `--------- beginning of main` banner, or a
 *   process writing raw text into the pipe) — these come back with level
 *   [UNKNOWN_LEVEL] and the whole text as the message so the panel still
 *   shows them instead of silently swallowing output.
 */
internal object LogcatLineParser {

    /** Level char used for lines that do not carry a logcat level prefix. */
    const val UNKNOWN_LEVEL = '?'

    fun parse(raw: String): LogcatLine {
        val slash = raw.indexOf('/')
        if (slash <= 0 || slash + 1 >= raw.length) return passthrough(raw)
        // The level sits immediately before the slash. logcat writes it as
        // one character, but pre-formatted third-party lines sometimes pad
        // with a space ("I /Tag") — scan back over spaces first.
        var levelIdx = slash - 1
        while (levelIdx > 0 && raw[levelIdx] == ' ') levelIdx--
        val level = raw[levelIdx].uppercaseChar()
        if (level !in LogcatLevels.ORDER) return passthrough(raw)

        val time = raw.substring(0, levelIdx).trim()
        // Tag runs to the first '(' that opens a pid group. Without a pid
        // group the tag ends at the first space — a bare "D/Tag message"
        // line still has to split into a tag column and a message column.
        val paren = raw.indexOf('(', slash + 1)
        val tagEnd = when {
            paren > slash -> paren
            else -> raw.indexOf(' ', slash + 1).let { if (it > slash) it else raw.length }
        }
        val tag = raw.substring(slash + 1, tagEnd).trim().ifEmpty { "—" }

        // Message starts after "):" when a pid group is present. If the
        // group closes without a colon (rare) fall back to everything after
        // the closing paren.
        val message = if (paren > slash) {
            val close = raw.indexOf(')', paren)
            if (close in (paren + 1) until raw.length) {
                var start = close + 1
                if (start < raw.length && raw[start] == ':') start++
                raw.substring(start).trimStart()
            } else {
                raw.substring(tagEnd).trimStart()
            }
        } else {
            raw.substring(tagEnd).trimStart()
        }

        return LogcatLine(time = time, level = level, tag = tag, message = message, raw = raw)
    }

    private fun passthrough(raw: String) =
        LogcatLine(time = "", level = UNKNOWN_LEVEL, tag = "", message = raw, raw = raw)
}

/** Verbosity ranking + threshold testing. */
internal object LogcatLevels {

    /** Ascending verbosity ranking; index doubles as severity rank. */
    const val ORDER = "VDIWEFA"

    fun rank(level: Char): Int {
        val i = ORDER.indexOf(level.uppercaseChar())
        return if (i >= 0) i else ORDER.indexOf('V')
    }

    /** True when [level] is at least as severe as [minLevel]. */
    fun passes(level: Char, minLevel: Char): Boolean {
        if (level == LogcatLineParser.UNKNOWN_LEVEL) return true
        return rank(level) >= rank(minLevel)
    }
}

/**
 * Fixed-capacity FIFO of parsed lines.
 *
 * A plain ring keeps memory bounded no matter how chatty the process is
 * (dVRR alone can emit thousands of lines a minute before the tailer's
 * `-v time` filters). [dropped] counts evictions so the UI can say "older
 * lines dropped" instead of pretending it saw the whole session.
 */
internal class LogcatRing(private val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "capacity must be positive" }
    }

    private val items = ArrayDeque<LogcatLine>(minOf(capacity, 512))

    var dropped: Int = 0
        private set

    val size: Int get() = items.size

    /** Appends [line]; returns true when the oldest line was evicted. */
    fun add(line: LogcatLine): Boolean {
        var evicted = false
        while (items.size >= capacity) {
            items.removeFirst()
            dropped++
            evicted = true
        }
        items.addLast(line)
        return evicted
    }

    fun snapshot(): List<LogcatLine> = items.toList()

    fun clear() {
        items.clear()
        dropped = 0
    }

    companion object {
        /** 2000 rows ≈ a few minutes of an active session; ~1.2 MB worst case. */
        const val DEFAULT_CAPACITY = 2000
    }
}

/**
 * Level + text filter.
 *
 * The query is matched case-insensitively against tag and message (the two
 * fields a person actually searches). A leading `tag:` narrows the search to
 * the tag only — the cheap scoping people reach for first when hunting one
 * subsystem in a noisy stream.
 */
internal object LogcatFilter {

    fun visible(lines: List<LogcatLine>, minLevel: Char, query: String): List<LogcatLine> {
        val q = query.trim()
        if (q.isEmpty() && minLevel == 'V') return lines
        val tagOnly = q.startsWith("tag:")
        val needle = (if (tagOnly) q.substring(4) else q).trim().lowercase()
        if (needle.isEmpty()) return lines.filter { LogcatLevels.passes(it.level, minLevel) }
        return lines.filter { line ->
            if (!LogcatLevels.passes(line.level, minLevel)) return@filter false
            if (tagOnly) {
                line.tag.lowercase().contains(needle)
            } else {
                line.tag.lowercase().contains(needle) || line.message.lowercase().contains(needle)
            }
        }
    }

    /** Text handed to the clipboard: the original bytes, newline-joined. */
    fun toClipboard(lines: List<LogcatLine>): String =
        lines.joinToString("\n") { it.raw }
}
