package com.openminis.app.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.File

/**
 * [GH #453] The resource water mark: what it reads, how it groups thread
 * names, when it prints a breakdown, and the per-pid file the next launch
 * attaches to the exit report. /proc is faked with a temp directory.
 */
class ResourceWatermarkTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun fakeProc(threads: Int, maps: Int, fds: Int, names: List<String> = emptyList()): File {
        val proc = tmp.newFolder("proc")
        File(proc, "status").writeText(
            "Name:\tm.openminis.app\nVmSize:\t 7340032 kB\nVmRSS:\t  412345 kB\nThreads:\t$threads\nSigQ:\t0/1\n",
        )
        File(proc, "maps").writeText(buildString { repeat(maps) { append("7000000000-7000001000 r--p 00000000 00:00 0 [anon]\n") } })
        val fd = File(proc, "fd").also { it.mkdirs() }
        repeat(fds) { File(fd, "$it").writeText("") }
        val task = File(proc, "task").also { it.mkdirs() }
        names.forEachIndexed { i, n -> File(task, "${1000 + i}").mkdirs(); File(task, "${1000 + i}/comm").writeText("$n\n") }
        return proc
    }

    @Test fun `a sample reads threads, mappings, fds and memory`() {
        val s = ResourceWatermark.sample(fakeProc(threads = 123, maps = 250, fds = 7))
        assertEquals(123, s.threads)
        assertEquals(250, s.maps)
        assertEquals(7, s.fds)
        assertEquals(7340032L, s.vmSizeKb)
        assertEquals(412345L, s.vmRssKb)
        assertEquals("threads=123 maps=250 fds=7 vmSizeMB=7168 rssMB=402", s.fields())
    }

    @Test fun `an unreadable proc gives -1, never an exception`() {
        val s = ResourceWatermark.sample(File(tmp.root, "missing"))
        assertEquals(-1, s.threads)
        assertEquals(-1, s.maps)
        assertEquals(-1, s.fds)
        assertEquals("threads=-1 maps=-1 fds=-1 vmSizeMB=-1 rssMB=-1", s.fields())
    }

    @Test fun `the mapping count stops at its cap and says so`() {
        val big = ByteArray(200_000) { if (it % 2 == 1) '\n'.code.toByte() else 'x'.code.toByte() } // 100k lines
        assertEquals(70_000, ResourceWatermark.countLines(ByteArrayInputStream(big), 70_000))
        assertEquals(100_000, ResourceWatermark.countLines(ByteArrayInputStream(big), 1_000_000))
        val s = ResourceWatermark.Sample(threads = 900, maps = 70_000, fds = 1, vmSizeKb = 1, vmRssKb = 1)
        assertTrue(s.fields().contains("maps=>=70000"))
    }

    @Test fun `thread names group by their stable part`() {
        assertEquals("pool-#-thread-#", ResourceWatermark.nameKey("pool-3-thread-17"))
        assertEquals("mn-fileio-#", ResourceWatermark.nameKey("mn-fileio-12"))
        assertEquals("binder:#_#", ResourceWatermark.nameKey("binder:1234_5"))
        assertEquals("DefaultDispatch", ResourceWatermark.nameKey("DefaultDispatch"))
        assertEquals("(empty)", ResourceWatermark.nameKey("  "))
    }

    @Test fun `the breakdown lists the largest groups first and folds the rest`() {
        val names = List(5) { "mn-offload-wk" } + List(3) { "DefaultDispatch" } + List(2) { "Thread-$it" } + "main" + "RenderThread"
        assertEquals("mn-offload-wk:5,DefaultDispatch:3,Thread-#:2,RenderThread:1,main:1", ResourceWatermark.breakdown(names))
        assertEquals("mn-offload-wk:5,DefaultDispatch:3,other:4", ResourceWatermark.breakdown(names, topN = 2))
        assertEquals("(unavailable)", ResourceWatermark.breakdown(emptyList()))
    }

    @Test fun `thread names come from each task's comm`() {
        val proc = fakeProc(threads = 3, maps = 1, fds = 1, names = listOf("main", "mn-offload-wk", "mn-offload-wk"))
        assertEquals("mn-offload-wk:2,main:1", ResourceWatermark.breakdown(ResourceWatermark.threadNames(File(proc, "task"))))
        assertTrue(ResourceWatermark.threadNames(File(tmp.root, "nope")).isEmpty())
    }

    private fun s(threads: Int, maps: Int = 3000) = ResourceWatermark.Sample(threads, maps, 100, 1, 1)

    @Test fun `the breakdown fires on crossing, then only on growth after the interval, and re-arms below`() {
        val gate = ResourceWatermark.AlertGate(threadAlert = 400, mapsAlert = 30_000, minIntervalMs = 300_000)
        val min = 60_000L
        assertFalse("normal", gate.shouldReport(s(120), 0))
        assertTrue("crossing", gate.shouldReport(s(410), 1 * min))
        assertFalse("still high, no growth", gate.shouldReport(s(420), 10 * min))
        assertFalse("grew, but inside the interval", gate.shouldReport(s(600), 3 * min))
        assertTrue("grew 25 % and the interval passed", gate.shouldReport(s(600), 7 * min))
        assertFalse("dipped under the threshold but not under 80 %", gate.shouldReport(s(390), 20 * min))
        assertFalse("back over: not re-armed, no growth over 600", gate.shouldReport(s(410), 21 * min))
        assertFalse("under 80 % of both: re-arms", gate.shouldReport(s(300), 22 * min))
        assertTrue("crossing again reports at once", gate.shouldReport(s(401), 22 * min + 1))
        assertTrue("maps alone can cross", ResourceWatermark.AlertGate().shouldReport(s(100, maps = 30_001), 0))
    }

    @Test fun `the default thresholds sit well above our observed range and below the kernel limit`() {
        // Observed [JankDiag] tick threads= in our own long sessions: 40-121.
        assertTrue(ResourceWatermark.THREAD_ALERT >= 3 * 121)
        assertTrue(ResourceWatermark.MAPS_ALERT < 65_530 / 2 + 1)
        assertTrue(ResourceWatermark.MAPS_COUNT_CAP > 65_530)
    }

    @Test fun `the water mark is one file per pid, read back by pid, old ones pruned`() {
        val files = tmp.newFolder("files")
        ResourceWatermark.writeWatermark(files, 488, "threads=1900 maps=65000\n")
        ResourceWatermark.writeWatermark(files, 488, "threads=1950 maps=65400\n")
        assertEquals("threads=1950 maps=65400\n", ResourceWatermark.readWatermark(files, 488)!!.first)
        assertNull(ResourceWatermark.readWatermark(files, 489))
        assertFalse("no temp file left behind", File(files, "diag/res-488.txt.tmp").exists())

        for (pid in 1..12) {
            ResourceWatermark.writeWatermark(files, pid, "x")
            ResourceWatermark.watermarkFile(files, pid).setLastModified(1_000_000L + pid * 1000)
        }
        ResourceWatermark.writeWatermark(files, 999, "own")
        ResourceWatermark.watermarkFile(files, 999).setLastModified(1L)
        ResourceWatermark.pruneWatermarks(files, ownPid = 999, keep = 4)
        val left = File(files, "diag").list()!!.sorted()
        assertEquals(listOf("res-10.txt", "res-11.txt", "res-12.txt", "res-488.txt", "res-999.txt"), left)
    }

    @Test fun `thread names are short enough for the kernel's 15-byte comm`() {
        assertTrue(ThreadNames.of("offload-wk").length <= 15)
        assertFalse(ThreadNames.of("offload-wk").contains('.'))
    }

    // --- [INV-261024-05] which mappings -------------------------------------

    @Test
    fun `mapName takes the name column, folds numbers and the app data dir`() {
        assertEquals("[anon:scudo:secondary]", ResourceWatermark.mapName("7a00000000-7a00001000 rw-p 00000000 00:00 0                              [anon:scudo:secondary]"))
        assertEquals("[anon:stack_and_tls:#]", ResourceWatermark.mapName("7a00000000-7a00001000 rw-p 00000000 00:00 0  [anon:stack_and_tls:12345]"))
        assertEquals("/system/lib#/libc.so", ResourceWatermark.mapName("7a00000000-7a00001000 r-xp 00000000 fd:00 1234 /system/lib64/libc.so"))
        assertEquals("[anon]", ResourceWatermark.mapName("7a00000000-7a00001000 rw-p 00000000 00:00 0"))
        assertEquals("<app>/files", ResourceWatermark.mapName("7a00000000-7a00001000 r--p 00000000 fd:00 9 /data/user/0/com.openminis.ultra/files/ubuntu-rootfs/usr/lib/x.so"))
    }

    @Test
    fun `mapsBreakdown counts mappings by name, largest first`() {
        val maps = tmp.newFile("maps")
        maps.writeText(buildString {
            repeat(5) { append("7a00000000-7a00001000 rw-p 00000000 00:00 0 [anon:scudo:secondary]\n") }
            repeat(2) { append("7a00000000-7a00001000 rw-p 00000000 00:00 0 [anon:stack_and_tls:${100 + it}]\n") }
            append("7a00000000-7a00001000 r-xp 00000000 fd:00 1 /system/lib64/libc.so\n")
        })
        assertEquals(
            "[anon:scudo:secondary]:5,[anon:stack_and_tls:#]:2,/system/lib#/libc.so:1",
            ResourceWatermark.mapsBreakdown(maps),
        )
    }

    @Test
    fun `mapsBreakdown never throws on an unreadable file`() {
        assertEquals("(unavailable)", ResourceWatermark.mapsBreakdown(File(tmp.root, "missing")))
    }
}
