package com.openminis.app.harness

import java.io.RandomAccessFile
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Adapted from taixu WorkspaceFileAccessTest (GPL-3.0-or-later).
 * https://github.com/wkbin/taixu
 *
 * 相对上游的改动：溢出目录名随实现从 `.taixu-outputs` 改为 `.openminis-outputs`。
 */
class WorkspaceFileAccessTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `edit rejects files larger than the bounded in-memory limit`() = runBlocking {
        val root = temporaryFolder.newFolder("workspace")
        val large = root.resolve("large.txt")
        RandomAccessFile(large, "rw").use { it.setLength(WorkspaceFileAccess.MAX_EDIT_BYTES + 1) }

        val result = WorkspaceFileAccess(root).edit("large.txt", "a", "b")

        assertTrue(result.isFailure)
        assertTrue(result.errorOrNull()?.message.orEmpty().contains("文件过大"))
    }

    @Test
    fun `write limit is measured in utf8 bytes rather than characters`() = runBlocking {
        val root = temporaryFolder.newFolder("workspace")
        val multibyte = "界".repeat(WorkspaceFileAccess.MAX_WRITE_BYTES / 2)

        val result = WorkspaceFileAccess(root).write("too-large.txt", multibyte)

        assertTrue(result.isFailure)
        assertTrue(result.errorOrNull()?.message.orEmpty().contains("内容过长"))
    }

    @Test
    fun `edit falls back to line trimmed strategy and returns unified diff`() = runBlocking {
        val root = temporaryFolder.newFolder("workspace")
        val file = root.resolve("Main.kt")
        file.writeText("class A {\n        fun b() {\n            return 1\n        }\n}\n")

        val result = WorkspaceFileAccess(root).editDetailed(
            path = "Main.kt",
            oldText = "    fun b() {\n        return 1\n    }",
            newText = "    fun b() {\n        return 2\n    }",
        )

        assertTrue(result.isSuccess)
        val outcome = result.getOrNull()
        assertNotNull(outcome)
        assertEquals("line_trimmed", outcome!!.strategy)
        assertNotNull(outcome.diff)
        assertTrue(outcome.diff!!.contains("+            return 2"))
        assertTrue(file.readText(Charsets.UTF_8).contains("return 2"))
    }

    @Test
    fun `large spill is read with explicit streaming page`() = runBlocking {
        val root = temporaryFolder.newFolder("workspace")
        val spillDir = root.resolve(".openminis-outputs").also { it.mkdirs() }
        val spill = spillDir.resolve("tool-large.txt")
        spill.bufferedWriter().use { writer ->
            repeat(40_000) { writer.appendLine("line-$it-${"x".repeat(32)}") }
        }
        assertTrue(spill.length() > WorkspaceFileAccess.MAX_READ_BYTES)

        val result = WorkspaceFileAccess(root).read(".openminis-outputs/tool-large.txt", offset = 20_001, limit = 2)

        assertTrue(result.isSuccess)
        val content = result.getOrNull().orEmpty()
        assertTrue(content.contains("line-20000-"))
        assertTrue(content.contains("line-20001-"))
        assertTrue(!content.contains("line-19999-"))
    }

    @Test
    fun `read raw bytes returns binary content without utf8 decoding`() = runBlocking {
        val root = temporaryFolder.newFolder("workspace")
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)
        root.resolve("chart.png").writeBytes(bytes)

        val result = WorkspaceFileAccess(root).readRawBytes("chart.png")

        assertTrue(result.isSuccess)
        assertTrue(result.getOrNull()!!.contentEquals(bytes))
    }
}
