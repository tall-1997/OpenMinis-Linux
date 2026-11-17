package com.openminis.app.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-tool-undo-replay] Pure aggregation behind the ZCode-style "changes
 * this turn" card. Restore IO lives in the ViewModel; these tests pin the
 * block→per-path net change contract (first-touch order, one row per path,
 * streaming/failure/path-less exclusion).
 */
class TurnChangesTest {

    private fun block(
        name: String,
        args: String,
        status: ToolBlockStatus = ToolBlockStatus.SUCCESS,
        start: Long = 1000L,
        duration: Long = 50L,
        kind: String = "tool_use",
    ) = AssistantBlock(
        id = "b-$name-${args.hashCode()}",
        kind = kind,
        content = "",
        toolStatus = status,
        toolName = name,
        toolArgs = args,
        durationMs = duration,
        startTimeMs = start,
    )

    @Test
    fun `collects one row per path in first-touch order`() {
        val changes = TurnChanges.collect(
            listOf(
                block("file_write", """{"path":"/a/one.txt","content":"x"}""", start = 100),
                block("file_edit", """{"path":"/b/two.txt","old_string":"a","new_string":"b"}""", start = 200),
                block("multi_edit", """{"path":"/a/one.txt","edits":[]}""", start = 300),
            ),
        )
        assertEquals(listOf("/a/one.txt", "/b/two.txt"), changes.map { it.path })
    }

    @Test
    fun `merges repeated touches of the same path`() {
        val changes = TurnChanges.collect(
            listOf(
                block("file_write", """{"path":"/a.txt"}""", start = 100, duration = 10),
                block("file_edit", """{"path":"/a.txt"}""", start = 200, duration = 30),
            ),
        )
        assertEquals(1, changes.size)
        val merged = changes.single()
        assertEquals(listOf("file_write", "file_edit"), merged.toolNames)
        assertEquals(100L, merged.firstStartMs)
        assertEquals(230L, merged.lastEndMs)
    }

    @Test
    fun `excludes streaming and failed and path-less calls`() {
        val changes = TurnChanges.collect(
            listOf(
                block("file_write", """{"path":"/streaming.txt"}""", status = ToolBlockStatus.STREAMING),
                block("file_edit", """{"path":"/failed.txt"}""", status = ToolBlockStatus.FAILED),
                block("file_write", """{"content":"no path"}"""),
                block("file_write", "not json"),
                block("file_edit", """{"path":"","old_string":"x"}"""),
            ),
        )
        assertTrue(changes.isEmpty())
    }

    @Test
    fun `ignores non-file tools and non-tool blocks`() {
        val changes = TurnChanges.collect(
            listOf(
                block("shell", """{"command":"ls"}"""),
                block("file_edit", """{"path":"/kept.txt"}"""),
                block("thinking", """{}""", kind = "thinking"),
            ),
        )
        assertEquals(listOf("/kept.txt"), changes.map { it.path })
    }

    @Test
    fun `revertedKey pairs message id with path`() {
        assertEquals("m1\n/p.txt", TurnChanges.revertedKey("m1", "/p.txt"))
    }
}
