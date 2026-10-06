package com.openminis.app.ui.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue

/**
 * Pure, unit-testable logic behind [CodeEditorField] and the expanded
 * composer editor. No Compose runtime state here — everything takes
 * explicit inputs so behaviour can be pinned in JVM tests.
 */
object CodeEditorLogic {

    /** Hard cap so a pathological query ("a" in a novel) can't OOM the UI. */
    const val MAX_MATCHES = 1500

    /** Consecutive single-character edits within this window merge into one undo step. */
    const val MERGE_WINDOW_MS = 600L

    /** Undo snapshots kept; beyond that the oldest drops. */
    const val MAX_UNDO = 100

    // ------------------------------------------------------------------
    // Search
    // ------------------------------------------------------------------

    /**
     * Non-overlapping match ranges of [query] in [text], in document order.
     * Case-insensitive by default. Returns empty for a blank query.
     *
     * The lowercase comparison can change string length for exotic Unicode
     * (e.g. 'İ'); when that happens we bail out rather than return ranges
     * that don't map back onto the original text.
     */
    fun findMatches(
        text: String,
        query: String,
        ignoreCase: Boolean = true,
        maxMatches: Int = MAX_MATCHES,
    ): List<IntRange> {
        if (query.isEmpty()) return emptyList()
        val hay: String
        val needle: String
        if (ignoreCase) {
            hay = text.lowercase()
            needle = query.lowercase()
            if (hay.length != text.length) return emptyList()
        } else {
            hay = text
            needle = query
        }
        val out = ArrayList<IntRange>()
        var idx = hay.indexOf(needle)
        while (idx >= 0 && out.size < maxMatches) {
            out += idx..(idx + needle.length - 1)
            idx = hay.indexOf(needle, idx + needle.length)
        }
        return out
    }

    /** Wrap-around step through [total] matches. [total] <= 0 returns -1. */
    fun wrapIndex(current: Int, total: Int, forward: Boolean): Int {
        if (total <= 0) return -1
        val base = if (current < 0 || current >= total) 0 else current
        val next = if (forward) base + 1 else base - 1
        return ((next % total) + total) % total
    }

    /** TextFieldValue selecting [match] so the caret auto-scrolls into view. */
    fun selectRange(value: TextFieldValue, match: IntRange): TextFieldValue {
        val end = (match.last + 1).coerceIn(0, value.text.length)
        val start = match.first.coerceIn(0, end)
        return value.copy(selection = TextRange(start, end))
    }

    // ------------------------------------------------------------------
    // Position / stats
    // ------------------------------------------------------------------

    /** 1-based (line, column) of [cursor] within [text]. Cursor clamped. */
    fun lineAndColumn(text: String, cursor: Int): Pair<Int, Int> {
        val pos = cursor.coerceIn(0, text.length)
        var line = 1
        var lineStart = 0
        for (i in 0 until pos) {
            if (text[i] == '\n') {
                line++
                lineStart = i + 1
            }
        }
        return line to (pos - lineStart + 1)
    }

    /** Logical line count (a trailing '\n' implies one more empty line). */
    fun lineCount(text: String): Int {
        if (text.isEmpty()) return 1
        var count = 1
        for (ch in text) if (ch == '\n') count++
        return count
    }

    /** 0-based index of the line containing [offset]. */
    fun lineOf(text: String, offset: Int): Int = lineAndColumn(text, offset).first - 1

    // ------------------------------------------------------------------
    // Language detection (best-effort, for the composer's default)
    // ------------------------------------------------------------------

    private val kotlinMarker = Regex("""(^|\n)(package|import)\s+[\w.]+\s*($|\n)|(^|\n)(fun|val|var)\s+\w""")
    private val pythonMarker = Regex("""(^|\n)\s*def\s+\w+\s*\(|(^|\n)\s*import\s+\w+\s*($|\n)""")
    private val goMarker = Regex("""(^|\n)func\s+\w*\s*\(""")
    private val rustMarker = Regex("""(^|\n)fn\s+\w+\s*\(|(^|\n)use\s+\w+::""")
    private val cppMarker = Regex("""(^|\n)#include\s*[<"]""")

    /**
     * Cheap content sniffing. Returns a language id understood by
     * [com.openminis.app.ui.markdown.SyntaxHighlighter] ("text" = plain).
     */
    fun detectLanguage(text: String): String {
        val head = if (text.length > 4000) text.substring(0, 4000) else text
        val trimmed = head.trimStart()
        if (trimmed.startsWith("#!")) {
            val firstLine = trimmed.lineSequence().first()
            return if (firstLine.contains("bash") || firstLine.contains("/sh") ||
                firstLine.contains("zsh")
            ) "sh" else "text"
        }
        if (trimmed.startsWith("<?xml") || trimmed.startsWith("<!DOCTYPE")) return "xml"
        if (trimmed.startsWith("---") && head.contains("\n")) return "yaml"
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            // JSON-ish: keys in quotes followed by colon.
            if (Regex(""""[^"]*"\s*:""").containsMatchIn(trimmed)) return "json"
        }
        if (cppMarker.containsMatchIn(head)) return "cpp"
        if (rustMarker.containsMatchIn(head)) return "rust"
        // Go before Kotlin/Python: `func` is unambiguous, while a bare
        // `package main` or `import re` line would otherwise look Kotlin.
        if (goMarker.containsMatchIn(head)) return "go"
        if (pythonMarker.containsMatchIn(head)) return "python"
        if (kotlinMarker.containsMatchIn(head)) return "kotlin"
        return "text"
    }

    /** Languages offered in the editor's picker, "text" first. */
    val LANGUAGE_OPTIONS: List<String> = listOf(
        "text", "kotlin", "java", "python", "javascript", "typescript",
        "json", "sh", "yaml", "xml", "markdown", "cpp", "rust", "go",
    )
}

/**
 * Snapshot-based undo/redo for a [TextFieldValue]. The editor pushes the
 * PRE-edit value before each change; consecutive single-character edits
 * within [CodeEditorLogic.MERGE_WINDOW_MS] collapse into one step so undo
 * removes a word-ish burst instead of one keystroke at a time.
 *
 * Not thread-safe; intended for single-thread Compose state holders (and
 * direct JVM tests with a fake clock).
 */
class UndoableEditorState {

    private val undoStack = ArrayDeque<TextFieldValue>()
    private val redoStack = ArrayDeque<TextFieldValue>()
    private var lastEditAt = -1L
    private var lastPre: TextFieldValue? = null

    val canUndo: Boolean get() = undoStack.isNotEmpty()
    val canRedo: Boolean get() = redoStack.isNotEmpty()
    val undoDepth: Int get() = undoStack.size

    /**
     * Record [pre] (value BEFORE the edit) at time [nowMs].
     * [pre] is merged into the previous snapshot when the edit looks like
     * continued typing: within the merge window, same line count, and at
     * most one character longer/shorter than the previous pre-edit value.
     */
    fun record(pre: TextFieldValue, nowMs: Long) {
        val prevPre = lastPre
        val mergeable = prevPre != null &&
            lastEditAt >= 0 &&
            nowMs - lastEditAt <= CodeEditorLogic.MERGE_WINDOW_MS &&
            isIncrementalEdit(prevPre, pre)
        if (!mergeable) {
            undoStack.addLast(pre)
            if (undoStack.size > CodeEditorLogic.MAX_UNDO) undoStack.removeFirst()
        }
        redoStack.clear()
        lastEditAt = nowMs
        lastPre = pre
    }

    private fun isIncrementalEdit(older: TextFieldValue, newer: TextFieldValue): Boolean {
        if (older.text.count { it == '\n' } != newer.text.count { it == '\n' }) return false
        return kotlin.math.abs(older.text.length - newer.text.length) <= 1
    }

    /** Pop the snapshot to restore, pushing [current] onto redo. Null when empty. */
    fun undo(current: TextFieldValue): TextFieldValue? {
        val prev = undoStack.removeLastOrNull() ?: return null
        redoStack.addLast(current)
        lastEditAt = -1L // never merge across an undo boundary
        lastPre = null
        return prev
    }

    /** Inverse of [undo]. */
    fun redo(current: TextFieldValue): TextFieldValue? {
        val next = redoStack.removeLastOrNull() ?: return null
        undoStack.addLast(current)
        lastEditAt = -1L
        lastPre = null
        return next
    }

    fun clear() {
        undoStack.clear()
        redoStack.clear()
        lastEditAt = -1L
        lastPre = null
    }
}
