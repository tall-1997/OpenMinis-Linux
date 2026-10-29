package com.openminis.app.a2a

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Pins the two guarantees that keep a truncated panel bundle from ever
 * being published:
 *
 *  - every extracted entry is written through a closed stream (the
 *    BufferedOutputStream tail reaches disk), and
 *  - before the destructive swap, every zip entry is verified size-for-size
 *    against the staged files — a torn extraction fails the deploy loudly
 *    instead of shipping a panel whose css/js end mid-token.
 *
 * The regression this guards: the extract loop wrote each entry through
 * `target.outputStream().buffered()` and never closed it. The unflushed
 * tail of EVERY file (up to 8KB, everything for files smaller than the
 * buffer) stayed in the userspace buffer and was silently dropped — the
 * gateway then served truncated assets (app.js cut from 51173 to 46478
 * bytes on-device) and a blank/一半渲染 panel. `expectedSizes` /
 * `verifyStagedBundle` are pure, so the JVM test needs no Robolectric;
 * the python mirror (sim_native_deploy.py) replays the same checks on a
 * real zip extracted the buggy way to prove the gate catches it.
 */
class A2aInstallerTest {

    // --- expectedSizes ---------------------------------------------------

    @Test fun `expectedSizes reads only file entries with declared sizes`() {
        val zip = zipWith(
            "index.html" to "abc",
            "css/app.css" to "abcdef",
            "assets/" to null,
        )
        val sizes = A2aInstaller.expectedSizes(zip.inputStream())
        assertEquals(2, sizes.size)
        assertEquals(3L, sizes["index.html"])
        assertEquals(6L, sizes["css/app.css"])
    }

    @Test fun `expectedSizes is empty for a bare zip`() {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { } // no entries
        assertTrue(A2aInstaller.expectedSizes(out.toByteArray().inputStream()).isEmpty())
    }

    // --- verifyStagedBundle ----------------------------------------------

    @Test fun `verify passes a complete tree`() {
        val root = tempDir()
        write(root.resolve("index.html"), "abc")
        write(root.resolve("css/app.css"), "abcdef")
        val problems = A2aInstaller.verifyStagedBundle(
            root,
            linkedMapOf("index.html" to 3L, "css/app.css" to 6L),
        )
        assertEquals(emptyList<String>(), problems)
    }

    @Test fun `verify flags a missing file`() {
        val root = tempDir()
        write(root.resolve("index.html"), "abc")
        val problems = A2aInstaller.verifyStagedBundle(
            root,
            linkedMapOf("index.html" to 3L, "js/app.js" to 10L),
        )
        assertEquals(listOf("missing js/app.js"), problems)
    }

    @Test fun `verify flags a truncated file`() {
        // The exact on-device regression shape: the entry declared 51173
        // bytes but the unflushed buffer tail never reached disk.
        val root = tempDir()
        write(root.resolve("app.js"), "x".repeat(46478))
        val problems = A2aInstaller.verifyStagedBundle(
            root,
            linkedMapOf("app.js" to 51173L),
        )
        assertEquals(listOf("short app.js (46478/51173)"), problems)
    }

    @Test fun `verify flags a zeroed small file`() {
        // Files smaller than the buffered writer's buffer never flushed a
        // single byte — the file exists but is empty.
        val root = tempDir()
        write(root.resolve("css/tooltip.css"), "")
        val problems = A2aInstaller.verifyStagedBundle(
            root,
            linkedMapOf("css/tooltip.css" to 6638L),
        )
        assertEquals(listOf("short css/tooltip.css (0/6638)"), problems)
    }

    @Test fun `verify accumulates every problem`() {
        val root = tempDir()
        write(root.resolve("a.js"), "12345")
        val problems = A2aInstaller.verifyStagedBundle(
            root,
            linkedMapOf("a.js" to 100L, "b.js" to 200L, "c.js" to 300L),
        )
        assertEquals(
            listOf("short a.js (5/100)", "missing b.js", "missing c.js"),
            problems,
        )
    }

    // --- helpers ---------------------------------------------------------

    private fun zipWith(vararg entries: Pair<String, String?>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            // STORED puts the real size and CRC into the local header (no
            // data descriptor), which is the shape the bundled asset zip has
            // on-device. DEFLATED here would go through a data descriptor
            // and ZipInputStream on the JVM would read the size back as -1.
            zip.setMethod(ZipOutputStream.STORED)
            for ((name, content) in entries) {
                val entry = if (content != null) {
                    val bytes = content.toByteArray()
                    ZipEntry(name).apply {
                        size = bytes.size.toLong()
                        crc = CRC32().apply { update(bytes) }.value
                    }
                } else {
                    ZipEntry(name).apply {
                        size = 0
                        crc = 0
                    }
                }
                zip.putNextEntry(entry)
                if (content != null) zip.write(content.toByteArray())
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun tempDir(): File =
        kotlin.io.path.createTempDirectory("a2a-installer-test").toFile().apply {
            deleteOnExit()
        }

    private fun write(file: File, content: String) {
        file.parentFile?.mkdirs()
        file.writeText(content)
    }
}