package com.openminis.app.mcp.server.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-mcp-fileread-oom] file_read's bounded text read: the stream stops at the
 * char cap (memory stays at the OUTPUT cap, never the file size — the old
 * readText() allocated the whole file first) and reports whether more
 * followed, so the caller can append an honest truncation note.
 *
 * Pure JVM: temp files only, no Android types.
 */
class FileToolsReadTest {

    private fun tempFile(content: String, charset: java.nio.charset.Charset = Charsets.UTF_8): File =
        File.createTempFile("mcp-filetools-read", ".txt").apply {
            writeBytes(content.toByteArray(charset))
            deleteOnExit()
        }

    @Test
    fun `short file is returned whole without a truncation flag`() {
        val (text, truncated) = readTextBounded(tempFile("hello world"), Charsets.UTF_8, 80_000)
        assertEquals("hello world", text)
        assertFalse(truncated)
    }

    @Test
    fun `read stops at the char cap and reports the truncation`() {
        val (text, truncated) = readTextBounded(tempFile("x".repeat(1000)), Charsets.UTF_8, 100)
        assertEquals(100, text.length)
        assertTrue(truncated)
    }

    @Test
    fun `exactly maxChars is not flagged as truncated`() {
        val (text, truncated) = readTextBounded(tempFile("y".repeat(50)), Charsets.UTF_8, 50)
        assertEquals("y".repeat(50), text)
        assertFalse(truncated)
    }

    @Test
    fun `char cap counts chars not bytes`() {
        // 每个 '好' 是 3 个 UTF-8 字节：上限按字符数（与旧 readText().take()
        // 的字符语义一致），且不能在多字节序列中间截出乱码。
        val (text, truncated) = readTextBounded(tempFile("好".repeat(200)), Charsets.UTF_8, 100)
        assertEquals("好".repeat(100), text)
        assertTrue(truncated)
    }

    @Test
    fun `truncation never ends on a lone high surrogate`() {
        // 截断点恰好落在增补码点（emoji）的高代理上：按 char 单元切会以孤立
        // 高代理结尾，JSON 序列化产出非法的 \uD83D 转义，严格客户端会拒绝
        // 整个响应。修复后把孤立的前半丢弃（truncated 仍如实上报）。
        val emoji = "\uD83D\uDE80" // 🚀 U+1F680, one UTF-16 surrogate pair
        val content = "x".repeat(9) + emoji
        val (text, truncated) = readTextBounded(tempFile(content), Charsets.UTF_8, 10)
        assertTrue(truncated)
        assertEquals(9, text.length)
        assertFalse(
            "output must not end on a lone high surrogate",
            Character.isHighSurrogate(text.last()),
        )
        assertEquals("x".repeat(9), text)
    }

    @Test
    fun `a supplementary code point that fits inside the cap survives whole`() {
        // 对照：emoji 完整落在上限之内时原样保留（代理对不被误删）。
        val emoji = "\uD83D\uDE80"
        val content = "x".repeat(8) + emoji
        val (text, truncated) = readTextBounded(tempFile(content), Charsets.UTF_8, 10)
        assertFalse(truncated)
        assertEquals(content, text)
    }

    @Test
    fun `utf-16 text decodes with the requested charset`() {
        val content = "你好 file_read"
        val (text, truncated) = readTextBounded(tempFile(content, Charsets.UTF_16LE), Charsets.UTF_16LE, 80_000)
        assertEquals(content, text)
        assertFalse(truncated)
    }

    @Test
    fun `empty file yields empty text`() {
        val (text, truncated) = readTextBounded(tempFile(""), Charsets.UTF_8, 10)
        assertEquals("", text)
        assertFalse(truncated)
    }

    @Test
    fun `mcp file_read limit mirrors the agent-side large-file guard`() {
        // MCP file_read 与 agent 端 FileReadTool 共用同一个拒绝阈值（50 MB）：
        // 两张 file_read 面对同一个巨型日志给出一致的答复。
        assertEquals(
            com.openminis.app.tools.FileReadTool.MAX_STREAM_FILE_SIZE,
            50L * 1024 * 1024,
        )
    }
}
