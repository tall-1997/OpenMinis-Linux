package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class StorageScannerTest {

    private fun tempDir(prefix: String): File {
        val dir = File(System.getProperty("java.io.tmpdir"), prefix + "-" + System.nanoTime())
        assertTrue(dir.mkdirs())
        return dir
    }

    @Test
    fun directorySizeSkipsVirtualTrees() {
        val root = tempDir("minis-storage")
        try {
            File(root, "usr/bin").mkdirs()
            File(root, "usr/bin/bash").writeText("x".repeat(100))
            File(root, "proc/1").mkdirs()
            File(root, "proc/1/status").writeText("huge".repeat(10_000))
            File(root, "sys").mkdirs()
            File(root, "sys/fake").writeText("nope")
            val size = StorageScanner.directorySize(root)
            assertEquals(100L, size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun directorySizeHonorsMaxFiles() {
        val root = tempDir("minis-storage-cap")
        try {
            repeat(8) { i ->
                File(root, "f$i").writeText("ab")
            }
            val size = StorageScanner.directorySize(root, maxFiles = 3)
            assertEquals(6L, size)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun mediaSizesBySessionGroupsByParentName() {
        val media = tempDir("minis-media")
        try {
            val s1 = File(media, "sess-a").apply { mkdirs() }
            File(s1, "a.png").writeText("1234")
            val s2 = File(media, "sess-b").apply { mkdirs() }
            File(s2, "b.png").writeText("12")
            File(media, "other").mkdirs()
            File(File(media, "other"), "c.png").writeText("xxxxx")
            val sizes = StorageScanner.mediaSizesBySession(media, setOf("sess-a", "sess-b"))
            assertEquals(4L, sizes["sess-a"])
            assertEquals(2L, sizes["sess-b"])
            assertTrue("other" !in sizes)
        } finally {
            media.deleteRecursively()
        }
    }
}
