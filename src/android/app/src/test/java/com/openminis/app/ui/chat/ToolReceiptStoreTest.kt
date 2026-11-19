package com.openminis.app.ui.chat

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [T-tool-receipt] `not_sent` before dispatch, `accepted` after — an unmatched
 * `not_sent` IS the `unknown` state the model must verify instead of retrying.
 * Read-only tools leave nothing behind.
 */
class ToolReceiptStoreTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("tool-receipts").toFile()
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun onlyMutatingToolsLeaveReceipts() {
        assertTrue(ToolReceiptStore.isMutating("file_edit"))
        assertTrue(ToolReceiptStore.isMutating("shell_execute"))
        assertTrue("aliases must not slip through", ToolReceiptStore.isMutating("shell_exec"))
        assertFalse(ToolReceiptStore.isMutating("file_read"))
        assertFalse(ToolReceiptStore.isMutating("grep"))
    }

    @Test
    fun aSettledCallIsNotReportedAsUnsettled() {
        val sid = "r1"
        val args = """{"path":"/src/A.kt"}"""
        ToolReceiptStore.begin(dir, sid, "file_edit", args)
        ToolReceiptStore.settle(dir, sid, "file_edit", args, success = true)
        assertTrue(ToolReceiptStore.unsettled(dir, sid).isEmpty())
    }

    @Test
    fun aCrashBetweenBeginAndSettleIsObservable() {
        val sid = "r2"
        val args = """{"command":"rm -rf build"}"""
        ToolReceiptStore.begin(dir, sid, "shell_execute", args)
        val open = ToolReceiptStore.unsettled(dir, sid)
        assertEquals(1, open.size)
        assertEquals("shell_execute", open.first().optString("tool"))
    }

    @Test
    fun readOnlyCallsWriteNothing() {
        val sid = "r3"
        ToolReceiptStore.begin(dir, sid, "file_read", """{"path":"/a"}""")
        ToolReceiptStore.settle(dir, sid, "file_read", """{"path":"/a"}""", success = true)
        assertTrue(ToolReceiptStore.unsettled(dir, sid).isEmpty())
        assertFalse(ToolReceiptStore.file(dir, sid).exists())
    }

    @Test
    fun receiptsArePerSession() {
        ToolReceiptStore.begin(dir, "a", "file_write", """{"path":"/a"}""")
        assertEquals(1, ToolReceiptStore.unsettled(dir, "a").size)
        assertTrue(ToolReceiptStore.unsettled(dir, "b").isEmpty())
    }

    @Test
    fun aCorruptLineDoesNotHideTheRest() {
        val sid = "r4"
        val f = ToolReceiptStore.file(dir, sid)
        f.parentFile?.mkdirs()
        f.writeText("not-json\n\n", Charsets.UTF_8)
        ToolReceiptStore.begin(dir, sid, "file_write", """{"path":"/b"}""")
        assertEquals(1, ToolReceiptStore.unsettled(dir, sid).size)
    }

    @Test
    fun abandonClearsTheUnsettledWarning() {
        // [T-tool-receipt-cancel] A cancelled dispatch must not warn every later
        // turn about an "unknown" outcome the process actually observed.
        val sid = "r5"
        val args = """{"path":"/src/B.kt"}"""
        ToolReceiptStore.begin(dir, sid, "file_edit", args)
        assertNotNull(ToolReceiptStore.unsettledNotice(dir, sid))
        ToolReceiptStore.abandon(dir, sid, "file_edit", args)
        assertTrue(ToolReceiptStore.unsettled(dir, sid).isEmpty())
        assertNull(ToolReceiptStore.unsettledNotice(dir, sid))
    }

    @Test
    fun abandonIsAppendOnlyAndKeepsOtherRecords() {
        // [T-tool-receipt-abandon-append] Append-only by contract: rewriting the
        // file raced a concurrent begin/settle and could drop a fresh append.
        val sid = "r6"
        ToolReceiptStore.begin(dir, sid, "file_edit", """{"path":"/a"}""")
        ToolReceiptStore.begin(dir, sid, "file_write", """{"path":"/b"}""")
        ToolReceiptStore.abandon(dir, sid, "file_edit", """{"path":"/a"}""")
        val open = ToolReceiptStore.unsettled(dir, sid)
        assertEquals(1, open.size)
        assertEquals("file_write", open.first().optString("tool"))
        // The abandoned record is still on disk as evidence, not deleted.
        assertTrue(ToolReceiptStore.file(dir, sid).readText().contains("abandoned"))
    }

    @Test
    fun unsafeSessionIdsStayInsideTheDirectory() {
        val f = ToolReceiptStore.file(dir, "../../etc/passwd")
        assertEquals(dir.resolve(ToolReceiptStore.DIR).absolutePath, f.parentFile.absolutePath)
        assertFalse(f.name.contains("/"))
    }
}
