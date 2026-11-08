package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-download-tool-card] JVM tests for the download-event parsing and card
 * pairing helpers (pure functions, no framework deps).
 */
class ChatDownloadCardTest {

    private fun msg(id: String, vararg blocks: AssistantBlock) = ChatMessage(
        id = id, role = "system", content = "", toolBlocks = blocks.toList(),
    )

    @Test
    fun parsesDownloadingEvent() {
        val e = parseBrowserDownloadEvent("Downloading index-1.bin…")
        assertEquals(ToolBlockStatus.RUNNING, e!!.status)
        assertEquals("index-1.bin", e.fileName)
        assertEquals("", e.detail)
    }

    @Test
    fun parsesDownloadingWithEllipsizedName() {
        val e = parseBrowserDownloadEvent("Downloading a…b.apk…")
        assertEquals("a…b.apk", e!!.fileName)
    }

    @Test
    fun parsesDownloadedWithSize() {
        val e = parseBrowserDownloadEvent("Downloaded ciliciliuodiye-2.apk (36 MB)")
        assertEquals(ToolBlockStatus.SUCCESS, e!!.status)
        assertEquals("ciliciliuodiye-2.apk", e.fileName)
        assertEquals("36 MB", e.detail)
    }

    @Test
    fun parsesDownloadedWithNameContainingParen() {
        val e = parseBrowserDownloadEvent("Downloaded a (1).apk (256 B)")
        assertEquals("a (1).apk", e!!.fileName)
        assertEquals("256 B", e.detail)
    }

    @Test
    fun parsesDownloadFailed() {
        val e = parseBrowserDownloadEvent("Download failed: big.bin — timeout after 30s")
        assertEquals(ToolBlockStatus.FAILED, e!!.status)
        assertEquals("big.bin", e.fileName)
        assertEquals("timeout after 30s", e.detail)
    }

    @Test
    fun unknownTextReturnsNull() {
        assertNull(parseBrowserDownloadEvent("随便什么系统通知"))
        assertNull(parseBrowserDownloadEvent(""))
    }

    @Test
    fun malformedDownloadedMissingSizeReturnsNull() {
        assertNull(parseBrowserDownloadEvent("Downloaded x.apk"))
    }

    @Test
    fun malformedFailedMissingDashReturnsNull() {
        assertNull(parseBrowserDownloadEvent("Download failed: x.apk"))
    }

    @Test
    fun blankNameReturnsNull() {
        assertNull(parseBrowserDownloadEvent("Downloading  …"))
        assertNull(parseBrowserDownloadEvent("Downloaded  (256 B)"))
    }

    @Test
    fun downloadCardBlockShape() {
        val e = parseBrowserDownloadEvent("Downloaded f.apk (2 KB)")!!
        val b = downloadCardBlock(e, "Downloaded f.apk (2 KB)", "blk1")
        assertEquals("tool_use", b.kind)
        assertEquals(DOWNLOAD_TOOL_NAME, b.toolName)
        assertEquals(ToolBlockStatus.SUCCESS, b.toolStatus)
        assertEquals("Downloaded f.apk (2 KB)", b.content)
        assertEquals("blk1", b.id)
    }

    @Test
    fun findRunningDownloadCardHitsNewest() {
        val m1 = msg("m1", downloadCardBlock(parseBrowserDownloadEvent("Downloading f.apk…")!!, "Downloading f.apk…", "b1"))
        val m2 = msg("m2", downloadCardBlock(parseBrowserDownloadEvent("Downloading f.apk…")!!, "Downloading f.apk…", "b2"))
        assertEquals("m2" to "b2", findRunningDownloadCard(listOf(m1, m2), "f.apk"))
    }

    @Test
    fun findRunningDownloadCardSkipsOtherNameAndTerminal() {
        val running = downloadCardBlock(parseBrowserDownloadEvent("Downloading other.apk…")!!, "Downloading other.apk…", "b1")
        val done = downloadCardBlock(parseBrowserDownloadEvent("Downloaded f.apk (1 B)")!!, "Downloaded f.apk (1 B)", "b2")
        assertNull(findRunningDownloadCard(listOf(msg("m1", running)), "f.apk"))
        assertNull(findRunningDownloadCard(listOf(msg("m1", done)), "f.apk"))
    }

    @Test
    fun nonDownloadToolCardsAreIgnored() {
        val tool = AssistantBlock(id = "b1", kind = "tool_use", toolName = "shell_execute", toolStatus = ToolBlockStatus.RUNNING)
        assertNull(findRunningDownloadCard(listOf(msg("m1", tool)), "f.apk"))
    }

    @Test
    fun markDownloadCardUpdatesInPlace() {
        val m = msg("m1", downloadCardBlock(parseBrowserDownloadEvent("Downloading f.apk…")!!, "Downloading f.apk…", "b1"))
        val out = markDownloadCard(listOf(m), "m1", "b1", ToolBlockStatus.SUCCESS, "Downloaded f.apk (2 KB)")
        val b = out[0].toolBlocks[0]
        assertEquals(ToolBlockStatus.SUCCESS, b.toolStatus)
        assertEquals("Downloaded f.apk (2 KB)", b.content)
        // Copy-on-write: the original list is untouched.
        assertEquals(ToolBlockStatus.RUNNING, m.toolBlocks[0].toolStatus)
    }
}
