package com.openminis.app.a2a

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.GZIPOutputStream

/**
 * Contract tests for the ustar deployer: header parsing byte-for-byte,
 * the size verification that gates the destructive swap, and the
 * zip-slip guard. The tarball is built by `scripts/build_a2a_glibc.sh`,
 * which is why the parser stays deliberately small — these tests pin the
 * subset it actually uses.
 */
class A2aGlibcDeployTest {

    @get:Rule
    val tmp = TemporaryFolder()

    // ---- header parsing ----

    @Test fun `parseHeader reads name size type and link`() {
        val block = ByteArray(512)
        block[257] = 'u'.code.toByte() // ustar magic
        "etc/zoneinfo/CST".toByteArray().copyInto(block, 0)
        "00000000044\u0000".toByteArray().copyInto(block, 124) // octal 44
        block[156] = '5'.code.toByte() // directory
        assertEquals(
            A2aGlibcDeploy.TarEntry("etc/zoneinfo/CST", 36L, '5', "", 0),
            A2aGlibcDeploy.parseHeader(block),
        )
    }

    @Test fun `parseHeader resolves the ustar prefix field`() {
        val block = ByteArray(512)
        block[257] = 'u'.code.toByte()
        "longdir".toByteArray().copyInto(block, 345) // prefix
        "file.txt".toByteArray().copyInto(block, 0)
        "00000000003\u0000".toByteArray().copyInto(block, 124)
        assertEquals("longdir/file.txt", A2aGlibcDeploy.parseHeader(block)?.name)
    }

    @Test fun `parseHeader returns null on the end-of-archive block`() {
        assertNull(A2aGlibcDeploy.parseHeader(ByteArray(512)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `parseHeader rejects long-name entries`() {
        val block = ByteArray(512)
        block[257] = 'u'.code.toByte()
        block[156] = 'L'.code.toByte()
        A2aGlibcDeploy.parseHeader(block)
    }

    // ---- extraction + verification ----

    private fun header(name: String, size: Int, type: Char, link: String = "", mode: Int? = null): ByteArray {
        val block = ByteArray(512)
        block[257] = 'u'.code.toByte()
        name.toByteArray().copyInto(block, 0)
        "%011o\u0000".format(size).toByteArray().copyInto(block, 124)
        block[156] = type.code.toByte()
        link.toByteArray().copyInto(block, 157)
        if (mode != null) "%07o\u0000".format(mode).toByteArray().copyInto(block, 100)
        return block
    }

    private fun plainTar(entries: List<ByteArray>): ByteArray {
        val body = ByteArrayOutputStream()
        for (e in entries) body.write(e)
        body.write(ByteArray(1024)) // two end blocks
        return body.toByteArray()
    }

    private fun gzipTar(entries: List<ByteArray>): ByteArray {
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(plainTar(entries)) }
        return gz.toByteArray()
    }

    @Test fun `round trip extracts and verifies exact sizes`() {
        val payload = "hello glibc runtime".toByteArray()
        val tar = gzipTar(
            listOf(
                header("glibc/ld-linux-aarch64.so.1", payload.size, '0') + payload +
                    ByteArray(512 - (payload.size % 512)),
            ),
        )
        val expected = A2aGlibcDeploy.expectedEntries(ByteArrayInputStream(tar))
        assertEquals(mapOf("glibc/ld-linux-aarch64.so.1" to payload.size.toLong()), expected)

        val staging = tmp.newFolder("staging")
        assertTrue(A2aGlibcDeploy.extract(ByteArrayInputStream(tar), staging, null).isEmpty())
        assertTrue(
            File(staging, "glibc/ld-linux-aarch64.so.1").readBytes().contentEquals(payload),
        )
        assertTrue(A2aGlibcDeploy.verify(staging, expected).isEmpty())
    }

    @Test fun `a plain tar without gzip deploys exactly like the gzipped one`() {
        val payload = "plain pipeline runtime".toByteArray()
        val tar = plainTar(
            listOf(
                header("glibc/ld-linux-aarch64.so.1", payload.size, '0') + payload +
                    ByteArray(512 - (payload.size % 512)),
            ),
        )
        val expected = A2aGlibcDeploy.expectedEntries(ByteArrayInputStream(tar))
        assertEquals(mapOf("glibc/ld-linux-aarch64.so.1" to payload.size.toLong()), expected)

        val staging = tmp.newFolder("staging-plain")
        assertTrue(A2aGlibcDeploy.extract(ByteArrayInputStream(tar), staging, null).isEmpty())
        assertTrue(
            File(staging, "glibc/ld-linux-aarch64.so.1").readBytes().contentEquals(payload),
        )
        assertTrue(A2aGlibcDeploy.verify(staging, expected).isEmpty())
    }

    @Test fun `openArchive passes plain streams through and unwraps gzip`() {
        val plain = plainTar(listOf(header("glibc/ld", 0, '0')))
        val passthrough = A2aGlibcDeploy.openArchive(ByteArrayInputStream(plain))
        assertTrue(passthrough.readBytes().contentEquals(plain))

        val gz = gzipTar(listOf(header("glibc/ld", 0, '0')))
        val unwrapped = A2aGlibcDeploy.openArchive(ByteArrayInputStream(gz))
        assertTrue(unwrapped.readBytes().contentEquals(plain))
    }

    @Test fun `openArchive survives empty and one-byte streams`() {
        val empty = A2aGlibcDeploy.openArchive(ByteArrayInputStream(ByteArray(0)))
        assertTrue(empty.readBytes().isEmpty())

        val one = A2aGlibcDeploy.openArchive(ByteArrayInputStream(byteArrayOf(0x1f)))
        assertTrue(one.readBytes().contentEquals(byteArrayOf(0x1f)))
    }

    @Test fun `verify flags a truncated extraction before the swap`() {
        val payload = "0123456789".toByteArray()
        val tar = gzipTar(
            listOf(header("glibc/libc.so.6", payload.size, '0') + payload + ByteArray(512 - 10)),
        )
        val expected = A2aGlibcDeploy.expectedEntries(ByteArrayInputStream(tar))

        val staging = tmp.newFolder("staging")
        // Simulate the torn write: file present but short.
        File(staging, "glibc").mkdirs()
        File(staging, "glibc/libc.so.6").writeBytes("0123".toByteArray())
        val problems = A2aGlibcDeploy.verify(staging, expected)
        assertEquals(1, problems.size)
        assertTrue(problems.single().startsWith("short glibc/libc.so.6"))
    }

    @Test fun `zip slip entries are rejected by the target guard`() {
        // The guard is private; exercise it through extraction: a `..` name
        // must end up rejected, never written outside the staging root.
        val payload = "evil".toByteArray()
        val tar = gzipTar(
            listOf(
                header("../outside.txt", payload.size, '0') + payload +
                    ByteArray(512 - (payload.size % 512)),
            ),
        )
        val staging = tmp.newFolder("staging")
        val problems = A2aGlibcDeploy.extract(ByteArrayInputStream(tar), staging, null)
        assertEquals(listOf("rejected entry ../outside.txt"), problems)
        assertFalse(File(staging.parentFile, "outside.txt").exists())
    }

    @Test fun `symlinks are created and reported when the platform refuses`() {
        val tar = gzipTar(
            listOf(header("glibc/libc.so.6", 0, '2', link = "ld-linux-aarch64.so.1")),
        )
        val staging = tmp.newFolder("staging")
        val problems = A2aGlibcDeploy.extract(ByteArrayInputStream(tar), staging, null)
        val link = File(staging, "glibc/libc.so.6")
        if (problems.isEmpty()) {
            assertTrue(java.nio.file.Files.isSymbolicLink(link.toPath()))
        }
    }

    // ---- top-level dir normalization + exec bit ----

    @Test fun `topDirOf finds the shared root directory`() {
        assertEquals(
            "a2a-glibc",
            A2aGlibcDeploy.topDirOf(
                listOf("a2a-glibc/", "a2a-glibc/ld-linux.so", "a2a-glibc/zoneinfo/UTC"),
            ),
        )
        // A directory whose own entry is absent still counts — the contract
        // is name-shaped, not entry-shaped.
        assertEquals(
            "out",
            A2aGlibcDeploy.topDirOf(listOf("out/loader", "out/zoneinfo/UTC")),
        )
        // No shared prefix -> flat archive, nothing to strip.
        assertNull(A2aGlibcDeploy.topDirOf(listOf("ld-linux.so", "zoneinfo/UTC")))
        assertNull(A2aGlibcDeploy.topDirOf(emptyList()))
    }

    @Test fun `deployName strips the shared dir and maps the root entry to empty`() {
        assertEquals("ld-linux.so", A2aGlibcDeploy.deployName("a2a-glibc/ld-linux.so", "a2a-glibc"))
        assertEquals("", A2aGlibcDeploy.deployName("a2a-glibc", "a2a-glibc"))
        assertEquals("ld-linux.so", A2aGlibcDeploy.deployName("ld-linux.so", null))
    }

    @Test fun `extract strips the shared top dir and applies the exec bit`() {
        val payload = "loader-bytes".toByteArray()
        val tar = gzipTar(
            listOf(
                header("a2a-glibc/", 0, '5'),
                header(
                    "a2a-glibc/ld-linux-aarch64.so.1", payload.size, '0',
                    mode = 0b111_101_101,
                ) + payload + ByteArray(512 - (payload.size % 512)),
            ),
        )
        val raw = A2aGlibcDeploy.expectedEntries(ByteArrayInputStream(tar))
        val topDir = A2aGlibcDeploy.topDirOf(raw.keys)
        assertEquals("a2a-glibc", topDir)

        val staging = tmp.newFolder("staging")
        val problems = A2aGlibcDeploy.extract(ByteArrayInputStream(tar), staging, topDir)
        assertTrue(problems.isEmpty())
        val loader = File(staging, "ld-linux-aarch64.so.1")
        assertTrue(loader.isFile)
        assertTrue(loader.canExecute())
        assertFalse(File(staging, "a2a-glibc").exists())
        // The deploy pairs the extract with the normalized size table —
        // verify against it to pin the exact install flow.
        val expected = raw.mapKeys { (name, _) -> A2aGlibcDeploy.deployName(name, topDir) }
        assertTrue(A2aGlibcDeploy.verify(staging, expected).isEmpty())
    }

    @Test fun `a 0644 file stays non-executable`() {
        val payload = "data".toByteArray()
        val tar = gzipTar(
            listOf(
                header("file.bin", payload.size, '0', mode = 0b110_100_100) +
                    payload + ByteArray(512 - (payload.size % 512)),
            ),
        )
        val staging = tmp.newFolder("staging")
        assertTrue(A2aGlibcDeploy.extract(ByteArrayInputStream(tar), staging, null).isEmpty())
        assertFalse(File(staging, "file.bin").canExecute())
    }

    // ---- non-block-aligned payloads must not drift the stream ----

    @Test fun `extract keeps parsing after a non-block-aligned payload`() {
        // Regression for the double-padding bug: copyExactly handed the
        // already-aligned padding to skipPayload, which aligned it AGAIN
        // and swallowed 512-padding extra bytes, misparsing every header
        // that followed. Sizes here deliberately avoid 512 multiples so
        // the padding branch is exercised with a real entry after it.
        val first = ByteArray(1000) { (it % 251).toByte() }
        val second = "tail-marker".toByteArray()
        val tar = plainTar(
            listOf(
                header("glibc/libm.so.6", first.size, '0') + first +
                    ByteArray(512 - (first.size % 512)),
                header("glibc/tail.txt", second.size, '0') + second +
                    ByteArray(512 - (second.size % 512)),
            ),
        )
        val expected = A2aGlibcDeploy.expectedEntries(ByteArrayInputStream(tar))
        assertEquals(
            mapOf("glibc/libm.so.6" to 1000L, "glibc/tail.txt" to second.size.toLong()),
            expected,
        )

        val staging = tmp.newFolder("staging")
        assertTrue(A2aGlibcDeploy.extract(ByteArrayInputStream(tar), staging, null).isEmpty())
        assertTrue(File(staging, "glibc/libm.so.6").readBytes().contentEquals(first))
        assertTrue(File(staging, "glibc/tail.txt").readBytes().contentEquals(second))
        assertTrue(A2aGlibcDeploy.verify(staging, expected).isEmpty())
    }

    @Test fun `extract handles the real-world loader size 72424`() {
        // 72424 is the exact byte size of libnss_files.so.2 in the shipped
        // a2a-glibc.tar — 141 whole blocks plus 232 bytes, padding 280.
        // The buggy code skipped 512 instead of 280 and corrupted the next
        // header on-device.
        val first = ByteArray(72424) { (it % 127).toByte() }
        val second = ByteArray(500) { (it % 61).toByte() }
        val tar = plainTar(
            listOf(
                header("glibc/libnss_files.so.2", first.size, '0') + first +
                    ByteArray(512 - (first.size % 512)),
                header("glibc/libpthread.so.0", second.size, '0') + second +
                    ByteArray(512 - (second.size % 512)),
            ),
        )
        val expected = A2aGlibcDeploy.expectedEntries(ByteArrayInputStream(tar))
        assertEquals(2, expected.size)

        val staging = tmp.newFolder("staging")
        assertTrue(A2aGlibcDeploy.extract(ByteArrayInputStream(tar), staging, null).isEmpty())
        assertTrue(File(staging, "glibc/libnss_files.so.2").readBytes().contentEquals(first))
        assertTrue(File(staging, "glibc/libpthread.so.0").readBytes().contentEquals(second))
        assertTrue(A2aGlibcDeploy.verify(staging, expected).isEmpty())
    }

    @Test fun `block-aligned and empty payloads keep their old behavior`() {
        // Zero-padding shapes: an empty file and a full-block file both
        // have padding 0, pinning that the fix changed nothing on the
        // previously-covered paths.
        val empty = ByteArray(0)
        val full = ByteArray(1024) { (it % 211).toByte() }
        val tar = plainTar(
            listOf(
                header("glibc/empty.so", 0, '0'),
                header("glibc/full.so", full.size, '0') + full,
            ),
        )
        val expected = A2aGlibcDeploy.expectedEntries(ByteArrayInputStream(tar))
        assertEquals(2, expected.size)

        val staging = tmp.newFolder("staging")
        assertTrue(A2aGlibcDeploy.extract(ByteArrayInputStream(tar), staging, null).isEmpty())
        assertTrue(File(staging, "glibc/empty.so").readBytes().isEmpty())
        assertTrue(File(staging, "glibc/full.so").readBytes().contentEquals(full))
        assertTrue(A2aGlibcDeploy.verify(staging, expected).isEmpty())
    }
}
