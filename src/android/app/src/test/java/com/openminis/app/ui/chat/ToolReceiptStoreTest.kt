package com.openminis.app.ui.chat

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assume.assumeNoException
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.file.Files

class ToolReceiptStoreTest {
    @get:Rule val tmp = TemporaryFolder()

    private val key = "a".repeat(16)
    private fun path(session: String): File = tmp.root.resolve(LegacyToolReceiptStore.DIR).apply { mkdirs() }
        .resolve("$session.jsonl")

    private fun record(state: String, op: String, key: String = this.key) = JSONObject()
        .put("key", key).put("state", state).put("op", op).put("tool", "file_write")
        .put("success", false).toString()

    @Test
    fun legacyDiagnosticsCountOnlyUnsettledAttemptsWithoutWriting() {
        val file = path("own-chat")
        val lines = listOf(record("not_sent", "first"), record("not_sent", "second"),
            record("accepted", "first"), record("not_sent", "third", "b".repeat(16)),
            record("abandoned", "third", "b".repeat(16)))
        val raw = lines.joinToString("\n", postfix = "\n")
        file.writeText(raw)
        assertEquals(LegacyToolReceiptStore.Diagnostics(1), LegacyToolReceiptStore.readDiagnostics(tmp.root, "own-chat"))
        assertEquals(raw, file.readText())
        assertNull(LegacyToolReceiptStore.readDiagnostics(tmp.root, "other-chat"))
    }

    @Test
    fun ambiguousSanitizedNamesAndTraversalDoNotHaveAnOwner() {
        path("chat_a").writeText(record("not_sent", "first") + "\n")
        assertNull(LegacyToolReceiptStore.readDiagnostics(tmp.root, "chat_a"))
        assertNull(LegacyToolReceiptStore.readDiagnostics(tmp.root, "chat/a"))
        assertNull(LegacyToolReceiptStore.readDiagnostics(tmp.root, "../chat_a"))
    }

    @Test
    fun tornMalformedAndOverlongLinesCannotSettleAnAttempt() {
        val file = path("own-chat")
        file.writeText(record("not_sent", "first") + "\n!\n" +
            record("accepted", "first").padEnd(8 * 1024 + 1, ' ') + "\n" +
            record("accepted", "first"))
        assertEquals(LegacyToolReceiptStore.Diagnostics(1), LegacyToolReceiptStore.readDiagnostics(tmp.root, "own-chat"))
    }

    @Test
    fun oversizedLegacyFileIsNotRead() {
        val file = path("own-chat")
        RandomAccessFile(file, "rw").use { it.setLength(LegacyToolReceiptStore.MAX_BYTES.toLong() + 1) }
        assertNull(LegacyToolReceiptStore.readDiagnostics(tmp.root, "own-chat"))
    }

    @Test
    fun linkedLegacyFileDoesNotEstablishOwnership() {
        val foreign = path("other-chat").apply { writeText(record("not_sent", "first") + "\n") }
        try {
            Files.createSymbolicLink(path("own-chat").toPath(), foreign.toPath())
        } catch (e: IOException) {
            assumeNoException(e) // A Windows host may not permit creating test symlinks.
        } catch (e: UnsupportedOperationException) {
            assumeNoException(e)
        }
        assertNull(LegacyToolReceiptStore.readDiagnostics(tmp.root, "own-chat"))
    }
}
