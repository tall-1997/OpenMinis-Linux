package com.openminis.app.ui.components

import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the pure logic behind the code editor: search, position
 * maths, language sniffing and the undo/redo stack.
 */
class CodeEditorLogicTest {

    // ------------------------------------------------------------------
    // findMatches
    // ------------------------------------------------------------------

    @Test
    fun `empty query yields no matches`() {
        assertTrue(CodeEditorLogic.findMatches("hello world", "").isEmpty())
    }

    @Test
    fun `case-insensitive by default`() {
        val m = CodeEditorLogic.findMatches("Foo foo FOO bar", "foo")
        assertEquals(3, m.size)
        assertEquals(0..2, m[0])
        assertEquals(4..6, m[1])
        assertEquals(8..10, m[2])
    }

    @Test
    fun `case-sensitive mode`() {
        val m = CodeEditorLogic.findMatches("Foo foo FOO", "foo", ignoreCase = false)
        assertEquals(listOf(4..6), m)
    }

    @Test
    fun `matches are non-overlapping`() {
        // "aaa" with query "aa": greedy non-overlap gives one match at 0..1,
        // then scanning resumes at 2 where only one 'a' remains.
        val m = CodeEditorLogic.findMatches("aaa", "aa")
        assertEquals(listOf(0..1), m)
    }

    @Test
    fun `maxMatches caps result`() {
        val text = "ab ".repeat(100)
        val m = CodeEditorLogic.findMatches(text, "ab", maxMatches = 5)
        assertEquals(5, m.size)
    }

    @Test
    fun `unicode length drift bails out instead of returning bad ranges`() {
        // 'İ'.lowercase() expands to two chars — indices would not map back.
        val text = "İstanbul İ"
        val m = CodeEditorLogic.findMatches(text, "i")
        assertTrue(m.isEmpty())
    }

    @Test
    fun `newline spanning query works`() {
        val m = CodeEditorLogic.findMatches("line one\nline two", "one\nline")
        assertEquals(listOf(5..12), m)
    }

    // ------------------------------------------------------------------
    // wrapIndex / selectRange
    // ------------------------------------------------------------------

    @Test
    fun `wrapIndex cycles forward and backward`() {
        assertEquals(1, CodeEditorLogic.wrapIndex(0, 3, forward = true))
        assertEquals(2, CodeEditorLogic.wrapIndex(1, 3, forward = true))
        assertEquals(0, CodeEditorLogic.wrapIndex(2, 3, forward = true)) // wrap
        assertEquals(2, CodeEditorLogic.wrapIndex(0, 3, forward = false)) // wrap back
        assertEquals(1, CodeEditorLogic.wrapIndex(2, 3, forward = false))
    }

    @Test
    fun `wrapIndex handles empty and invalid current`() {
        assertEquals(-1, CodeEditorLogic.wrapIndex(0, 0, forward = true))
        assertEquals(-1, CodeEditorLogic.wrapIndex(0, -1, forward = false))
        assertEquals(1, CodeEditorLogic.wrapIndex(-1, 3, forward = true))
        assertEquals(1, CodeEditorLogic.wrapIndex(99, 3, forward = true))
    }

    @Test
    fun `selectRange clamps to text bounds`() {
        val value = TextFieldValue("short", TextRange(0))
        val selected = CodeEditorLogic.selectRange(value, 3..99)
        assertEquals(TextRange(3, 5), selected.selection)
        assertEquals("short", selected.text)
    }

    // ------------------------------------------------------------------
    // lineAndColumn / lineCount / lineOf
    // ------------------------------------------------------------------

    @Test
    fun `line and column are 1-based`() {
        assertEquals(1 to 1, CodeEditorLogic.lineAndColumn("", 0))
        assertEquals(1 to 4, CodeEditorLogic.lineAndColumn("abcdef", 3))
        assertEquals(2 to 1, CodeEditorLogic.lineAndColumn("abc\ndef", 4))
        assertEquals(2 to 3, CodeEditorLogic.lineAndColumn("abc\ndef", 6))
    }

    @Test
    fun `cursor position is clamped`() {
        assertEquals(2 to 4, CodeEditorLogic.lineAndColumn("abc\ndef", 999))
        assertEquals(1 to 1, CodeEditorLogic.lineAndColumn("abc", -5))
    }

    @Test
    fun `line count treats trailing newline as an extra empty line`() {
        assertEquals(1, CodeEditorLogic.lineCount(""))
        assertEquals(1, CodeEditorLogic.lineCount("no newline"))
        assertEquals(2, CodeEditorLogic.lineCount("a\n"))
        assertEquals(3, CodeEditorLogic.lineCount("a\nb\nc"))
    }

    @Test
    fun `lineOf is zero-based line index`() {
        assertEquals(0, CodeEditorLogic.lineOf("first\nsecond", 0))
        assertEquals(1, CodeEditorLogic.lineOf("first\nsecond", 6))
    }

    // ------------------------------------------------------------------
    // detectLanguage
    // ------------------------------------------------------------------

    @Test
    fun `detects shell shebang`() {
        assertEquals("sh", CodeEditorLogic.detectLanguage("#!/usr/bin/env bash\nset -e"))
        assertEquals("sh", CodeEditorLogic.detectLanguage("#!/bin/sh\necho hi"))
        assertEquals("text", CodeEditorLogic.detectLanguage("#!/usr/bin/python3\nprint(1)"))
    }

    @Test
    fun `detects markup and data formats`() {
        assertEquals("xml", CodeEditorLogic.detectLanguage("<?xml version=\"1.0\"?>\n<a/>"))
        assertEquals("yaml", CodeEditorLogic.detectLanguage("---\nkey: value\n"))
        assertEquals("json", CodeEditorLogic.detectLanguage("{\n  \"name\": \"x\",\n  \"n\": 1\n}"))
        assertEquals("text", CodeEditorLogic.detectLanguage("{ not json at all"))
    }

    @Test
    fun `detects programming languages`() {
        assertEquals(
            "kotlin",
            CodeEditorLogic.detectLanguage("package com.example\n\nfun main() {\n    println(1)\n}"),
        )
        assertEquals("python", CodeEditorLogic.detectLanguage("def solve(n):\n    return n * 2"))
        assertEquals("cpp", CodeEditorLogic.detectLanguage("#include <stdio.h>\nint main() {}"))
        assertEquals("rust", CodeEditorLogic.detectLanguage("use std::io;\nfn main() {}"))
        assertEquals("go", CodeEditorLogic.detectLanguage("package main\n\nfunc main() {}"))
    }

    @Test
    fun `plain prose stays text`() {
        assertEquals("text", CodeEditorLogic.detectLanguage("Just a normal sentence about nothing."))
    }

    // ------------------------------------------------------------------
    // UndoableEditorState
    // ------------------------------------------------------------------

    private fun tfv(text: String) = TextFieldValue(text, TextRange(text.length))

    @Test
    fun `undo restores previous snapshot and redo replays`() {
        val undo = UndoableEditorState()
        val v1 = tfv("hello")
        undo.record(v1, nowMs = 1_000)
        val v2 = tfv("hello world") // edit happened after window expired
        undo.record(v2, nowMs = 5_000)

        val current = tfv("hello world!!")
        val restored = undo.undo(current)
        assertEquals(v2, restored)
        assertEquals(current, undo.redo(tfv("anything")))
        assertTrue(undo.canUndo)
        assertFalseCanRedo(undo)
    }

    private fun assertFalseCanRedo(state: UndoableEditorState) {
        assertEquals(false, state.canRedo)
    }

    @Test
    fun `rapid single-char edits merge into one undo step`() {
        val undo = UndoableEditorState()
        undo.record(tfv("abc"), nowMs = 0)       // snapshot before typing 'd'
        undo.record(tfv("abcd"), nowMs = 200)    // before 'e' — merges
        undo.record(tfv("abcde"), nowMs = 400)   // before 'f' — merges
        assertEquals(1, undo.undoDepth)
        assertEquals(tfv("abc"), undo.undo(tfv("abcdef")))
    }

    @Test
    fun `newline edit breaks the merge chain`() {
        val undo = UndoableEditorState()
        undo.record(tfv("abc"), nowMs = 0)
        undo.record(tfv("abc\n"), nowMs = 100) // line count changed → new step
        assertEquals(2, undo.undoDepth)
    }

    @Test
    fun `multi-char paste breaks the merge chain`() {
        val undo = UndoableEditorState()
        undo.record(tfv("abc"), nowMs = 0)
        undo.record(tfv("abcXYZ"), nowMs = 100) // +3 chars → new step
        assertEquals(2, undo.undoDepth)
    }

    @Test
    fun `merge window expiry starts a new step`() {
        val undo = UndoableEditorState()
        undo.record(tfv("abc"), nowMs = 0)
        undo.record(tfv("abcd"), nowMs = CodeEditorLogic.MERGE_WINDOW_MS + 1)
        assertEquals(2, undo.undoDepth)
    }

    @Test
    fun `new edit clears redo stack`() {
        val undo = UndoableEditorState()
        undo.record(tfv("a"), nowMs = 0)
        undo.record(tfv("ab"), nowMs = 5_000)
        undo.undo(tfv("abc"))
        assertTrue(undo.canRedo)
        undo.record(tfv("abc"), nowMs = 10_000)
        assertFalseCanRedo(undo)
    }

    @Test
    fun `undo on empty stack returns null`() {
        val undo = UndoableEditorState()
        assertNull(undo.undo(tfv("x")))
        assertNull(undo.redo(tfv("x")))
        assertEquals(false, undo.canUndo)
    }

    @Test
    fun `undo depth is capped`() {
        val undo = UndoableEditorState()
        for (i in 0..(CodeEditorLogic.MAX_UNDO + 10)) {
            undo.record(tfv("x".repeat(i % 50)), nowMs = i * 10_000L) // outside merge window
        }
        assertEquals(CodeEditorLogic.MAX_UNDO, undo.undoDepth)
    }

    @Test
    fun `clear empties both stacks`() {
        val undo = UndoableEditorState()
        undo.record(tfv("a"), nowMs = 0)
        undo.clear()
        assertEquals(false, undo.canUndo)
        assertFalseCanRedo(undo)
    }
}
