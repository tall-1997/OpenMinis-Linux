package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [GH#94] The persistent-rootfs temp leak: cold guest files in `<rootfs>/tmp`
 * and `<rootfs>/var/tmp` must be reclaimed on boot, while user data under
 * `/var/minis` and anything recently touched must survive.
 */
class StaleTempSweeperTest {

    private val now = 1_800_000_000_000L
    private val day = 24 * 60 * 60 * 1000L

    private fun tempRoot(): File =
        Files.createTempDirectory("stale-sweep-test").toFile().also { root ->
            File(root, "tmp").mkdirs()
            File(root, "var/tmp").mkdirs()
            File(root, "var/minis/offloads").mkdirs()
        }

    private fun file(dir: File, name: String, ageMs: Long, size: Int = 8): File {
        val f = File(dir, name)
        f.parentFile?.mkdirs()
        f.writeBytes(ByteArray(size) { 7 })
        f.setLastModified(now - ageMs)
        return f
    }

    @Test
    fun `removes only files older than the 24 hour cutoff`() {
        val root = tempRoot()
        val oldTmp = file(File(root, "tmp"), "pip-build-old/cache.bin", 25 * 60 * 60 * 1000L)
        val freshTmp = file(File(root, "tmp"), "in-use.part", 60 * 1000L)
        val oldVar = file(File(root, "var/tmp"), "apt-archive.deb", 2 * day)

        val result = StaleTempSweeper.sweep(root, now)

        assertFalse("cold /tmp file must be removed", oldTmp.exists())
        assertTrue("fresh /tmp file must survive", freshTmp.exists())
        assertFalse("cold /var/tmp file must be removed", oldVar.exists())
        assertEquals(2, result.removedFiles)
        assertEquals(16L, result.removedBytes)
        assertEquals(0, result.errors)
    }

    @Test
    fun `never touches user data under var-minis`() {
        val root = tempRoot()
        val export = file(File(root, "var/minis/offloads"), "photo.jpg", 30 * day)

        StaleTempSweeper.sweep(root, now)

        assertTrue("user offload must survive the temp sweep", export.exists())
    }

    @Test
    fun `does not follow symlinks out of tmp`() {
        val root = tempRoot()
        val protected = file(File(root, "var/minis/offloads"), "keep.txt", 30 * day)
        val link = File(root, "tmp/link-to-minis")
        val created = runCatching { Files.createSymbolicLink(link.toPath(), protected.toPath()) }
            .isSuccess
        // Windows without Developer Mode / symlink privilege: nothing to test.
        assumeTrue("symlinks unavailable on this host", created)

        StaleTempSweeper.sweep(root, now)

        assertTrue("symlink target must never be deleted", protected.exists())
    }

    @Test
    fun `missing tmp dirs are a no-op`() {
        val root = Files.createTempDirectory("stale-sweep-empty").toFile()
        val result = StaleTempSweeper.sweep(root, now)
        assertEquals(0, result.removedFiles)
        assertEquals(0, result.errors)
    }
}
