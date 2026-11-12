package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [GH#119] Pins the wording/data both the user and the model receive. */
class TruncatedArgsNoticeTest {

    @Test
    fun `byte count is read from the tool result when the tool reports one`() {
        assertEquals(1234, TruncatedArgsNotice.writtenBytes("Wrote to /a.txt (1234 bytes)"))
        assertEquals(12, TruncatedArgsNotice.writtenBytes("Edited /a (1 replacement(s), 12 bytes)"))
        assertEquals(null, TruncatedArgsNotice.writtenBytes("(no output)"))
        // [GH#119] 负例：正文里的 "N bytes" 没有右括号，不能被当成写入字节数；
        // 路径里带 "100 bytes" 时仍应取真正的 (12 bytes)。
        assertEquals(null, TruncatedArgsNotice.writtenBytes("The file has 100 bytes"))
        assertEquals(12, TruncatedArgsNotice.writtenBytes("Wrote to /tmp/100 bytes.txt (12 bytes)"))
    }

    @Test
    fun `ui line names the truncation and the written bytes`() {
        val line = TruncatedArgsNotice.uiLine("truncation+\"}", 1234, 999)
        assertTrue(line.contains("参数流在传输中被截断"))
        assertTrue(line.contains("已写入 1234 字节"))
        assertTrue(line.contains("truncation+\"}"))
    }

    @Test
    fun `ui line falls back to argument size when the tool reports none`() {
        val line = TruncatedArgsNotice.uiLine("truncation+\"", null, 777)
        assertTrue(line.contains("修复后的参数共 777 字节"))
    }

    @Test
    fun `model note carries the repair strategy and asks for verification`() {
        val note = TruncatedArgsNotice.modelNote("truncation+\"}", 88, 120)
        assertTrue(note.contains("<system-reminder>"))
        assertTrue(note.contains("truncation+\"}"))
        assertTrue(note.contains("88 bytes written"))
        assertTrue(note.contains("Re-read"))
        assertTrue(note.contains("</system-reminder>"))
    }

    @Test
    fun `argument size is utf8 bytes not char count`() {
        assertEquals(6, TruncatedArgsNotice.argumentBytes("中文"))
    }
}
