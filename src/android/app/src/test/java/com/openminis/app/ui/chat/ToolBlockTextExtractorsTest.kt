package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-shell-detail-dedupe GH#339] The detail sheet renders the command itself;
 * a legacy/persisted `$ <command>` echo at the head of the stored output must
 * be removed so the command is not shown twice.
 */
class ToolBlockTextExtractorsTest {

    @Test
    fun `echoed shell command line is stripped`() {
        assertEquals(
            "file1\nfile2",
            stripShellCommandEcho("$ ls -la\nfile1\nfile2", "ls -la"),
        )
    }

    @Test
    fun `command-only echo becomes empty output`() {
        assertEquals("", stripShellCommandEcho("$ pwd", "pwd"))
    }

    @Test
    fun `genuine output is left alone`() {
        // Bare first line equal to the command is real output, not an echo.
        assertEquals("ls\nmore", stripShellCommandEcho("ls\nmore", "ls"))
        // Echo of a DIFFERENT command (same prefix) must not lose its line.
        assertEquals(
            "$ ls -la /tmp2\nx",
            stripShellCommandEcho("$ ls -la /tmp2\nx", "ls -la /tmp"),
        )
    }
}
