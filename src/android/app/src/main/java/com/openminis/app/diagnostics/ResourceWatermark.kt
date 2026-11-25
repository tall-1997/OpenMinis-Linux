package com.openminis.app.diagnostics

import java.io.File
import java.io.FileInputStream
import java.io.InputStream

/**
 * [GH #453] Process resource water marks: thread count, memory-mapping count,
 * open fds, VmSize / VmRSS, and, past a threshold, how many threads share each
 * name.
 *
 * Why: a 1.15-beta.4 field report died after hours (once after 13.6 h in the
 * background) with `pthread_create (1040KB stack) failed: Try again` and
 * `Scudo ERROR: internal map failure` alternating - a process running out of
 * threads or of memory mappings (the kernel's vm.max_map_count, 65530 by
 * default; every thread costs a stack mapping plus a guard). Nothing in the
 * logs said which, or who was creating them. JankDiag's 30 s heartbeat already
 * printed `threads=`, but only while an Activity was resumed, so a process that
 * grew in the background printed nothing.
 *
 * Everything here is plain file reads on whatever thread calls it (JankDiag's
 * background HandlerThread). Nothing is held between calls except the
 * reporting gate's two numbers, nothing allocates per mapping, and every read
 * swallows its own failure and reports -1: a diagnostic must keep working in a
 * process that is already failing to allocate, and must never be the thing
 * that throws.
 */
object ResourceWatermark {

    /**
     * Thread count above which the per-name breakdown is printed. The
     * `[JankDiag] tick` lines in our own long-session logs (Pixel 4a / 6,
     * chats, shells, sub-agents, browser) ranged 40-121 threads; 400 is over
     * three times that, and still far below where pthread_create starts
     * failing, so the breakdown is on disk long before the crash.
     */
    const val THREAD_ALERT = 400

    /**
     * Mapping count above which the breakdown is printed. The kernel default
     * limit is 65530; a healthy 64-bit ART app sits at a few thousand (shared
     * libraries, dex/oat/vdex, JIT, one stack + guard per thread). 30000 is
     * under half the limit and several times the normal level, and leaves
     * room for the logs to show the growth before the limit. We have no
     * measured field baseline for `maps=` yet - this line is what will give
     * us one.
     */
    const val MAPS_ALERT = 30_000

    /** At most one breakdown per this interval, however fast the numbers move. */
    const val ALERT_MIN_INTERVAL_MS = 5 * 60_000L

    /** Past the first breakdown, the next needs this much growth (1.25 = +25 %). */
    const val ALERT_GROWTH = 1.25

    /** Below this fraction of the thresholds the gate re-arms. */
    const val ALERT_REARM = 0.8

    /**
     * Stop counting `/proc/self/maps` past this many lines. Above the kernel's
     * default limit, so any real count is exact; a larger value only means the
     * limit was raised, and "over 70000" answers the question as well.
     */
    const val MAPS_COUNT_CAP = 70_000

    /** Stop listing threads past this many (bounds the breakdown's cost). */
    const val TASK_SCAN_CAP = 8_192

    /** Names shown in a breakdown. */
    const val TOP_N = 15

    data class Sample(
        val threads: Int,
        val maps: Int,
        val fds: Int,
        val vmSizeKb: Long,
        val vmRssKb: Long,
    ) {
        /** `threads=… maps=… fds=… vmSizeMB=… rssMB=…`, the same fields everywhere. */
        fun fields(): String =
            "threads=$threads maps=${if (maps >= MAPS_COUNT_CAP) ">=$MAPS_COUNT_CAP" else "$maps"} fds=$fds " +
                "vmSizeMB=${mb(vmSizeKb)} rssMB=${mb(vmRssKb)}"

        private fun mb(kb: Long) = if (kb < 0) "-1" else "${kb / 1024}"
    }

    // ── reads ────────────────────────────────────────────────────────────────

    fun sample(proc: File = File("/proc/self")): Sample {
        val status = readStatus(File(proc, "status"))
        return Sample(
            threads = status[0].toInt(),
            maps = countMaps(File(proc, "maps")),
            fds = countFds(File(proc, "fd")),
            vmSizeKb = status[1],
            vmRssKb = status[2],
        )
    }

    /** [Threads, VmSize kB, VmRSS kB] from `/proc/self/status`; -1 for anything missing. */
    internal fun readStatus(file: File): LongArray {
        val out = longArrayOf(-1, -1, -1)
        runCatching {
            file.useLines { lines ->
                for (line in lines) {
                    val slot = when {
                        line.startsWith("Threads:") -> 0
                        line.startsWith("VmSize:") -> 1
                        line.startsWith("VmRSS:") -> 2
                        else -> continue
                    }
                    out[slot] = line.substringAfter(':').trim().substringBefore(' ').toLongOrNull() ?: -1
                }
            }
        }
        return out
    }

    internal fun countMaps(file: File): Int =
        runCatching { FileInputStream(file).use { countLines(it, MAPS_COUNT_CAP) } }.getOrDefault(-1)

    /**
     * Newlines in [input], stopping at [cap]. One 16 KB buffer for the whole
     * read: no String per line, so a 65k-line maps file costs one buffer.
     */
    internal fun countLines(input: InputStream, cap: Int): Int {
        val buf = ByteArray(16 * 1024)
        var lines = 0
        while (lines < cap) {
            val n = input.read(buf)
            if (n <= 0) break
            for (i in 0 until n) if (buf[i] == '\n'.code.toByte()) lines++
        }
        return minOf(lines, cap)
    }

    internal fun countFds(dir: File): Int = runCatching { dir.list()?.size ?: -1 }.getOrDefault(-1)

    // ── per-name breakdown ───────────────────────────────────────────────────

    /**
     * Thread names from `/proc/self/task/<tid>/comm`, not
     * `Thread.getAllStackTraces()`: comm covers native threads too (proot
     * helpers, codec/audio, Go runtimes) and costs one small read per thread
     * instead of suspending every thread to walk its stack.
     *
     * comm holds at most 15 bytes. ART keeps a dotted name's LAST 15 characters
     * and anything else's first 15, which is why our own pool names are short
     * and dot-free (see [ThreadNames]).
     */
    fun threadNames(taskDir: File = File("/proc/self/task")): List<String> {
        val tids = runCatching { taskDir.list() }.getOrNull() ?: return emptyList()
        val out = ArrayList<String>(minOf(tids.size, TASK_SCAN_CAP))
        for (tid in tids) {
            if (out.size >= TASK_SCAN_CAP) break
            // A thread that exited between list() and read is simply skipped.
            val name = runCatching { File(taskDir, "$tid/comm").readText().trim() }.getOrNull() ?: continue
            out.add(name)
        }
        return out
    }

    /**
     * Group names by their stable part: every run of digits becomes `#`, so
     * `pool-3-thread-17`, `DefaultDispatch`, `mn-fileio-12` and `binder:1234_5`
     * each collapse to one key.
     */
    fun nameKey(name: String): String = name.trim().replace(DIGITS, "#").ifEmpty { "(empty)" }

    private val DIGITS = Regex("[0-9]+")

    /** `key:count` for the [topN] largest groups, largest first, then the rest as `other:N`. */
    fun breakdown(names: List<String>, topN: Int = TOP_N): String {
        if (names.isEmpty()) return "(unavailable)"
        val counts = HashMap<String, Int>()
        for (n in names) { val k = nameKey(n); counts[k] = (counts[k] ?: 0) + 1 }
        val sorted = counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key })
        val shown = sorted.take(topN).joinToString(",") { "${it.key}:${it.value}" }
        val rest = sorted.drop(topN).sumOf { it.value }
        return if (rest > 0) "$shown,other:$rest" else shown
    }

    /**
     * [INV-261024-05] What the mappings are: `/proc/self/maps` grouped by name,
     * the [topN] largest groups. A count alone (all GH #453 and OpenMinis#426
     * had) says the process is running out of mappings, not who is taking
     * them; the name does - `[anon:scudo:secondary]` is large native
     * allocations, `[anon:stack_and_tls:#]` thread stacks, a `.so` / `.apk`
     * path a file mapped over and over, `/dev/ashmem/…` shared memory.
     *
     * Only read when [AlertGate] fires (at most every few minutes, and only
     * past [MAPS_ALERT] or [THREAD_ALERT]), and stops at [MAPS_COUNT_CAP]
     * lines. One String per line is fine at that rate.
     */
    fun mapsBreakdown(file: File = File("/proc/self/maps"), topN: Int = TOP_N): String =
        runCatching {
            val names = ArrayList<String>()
            file.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (names.size >= MAPS_COUNT_CAP) break
                    names.add(mapName(line))
                }
            }
            breakdown(names, topN)
        }.getOrDefault("(unavailable)")

    /**
     * The name column of one maps line (`[anon]` when it has none), the same
     * digit folding as thread names so numbered stacks and ashmem regions
     * group. A mapping's path is app or system code, never user data - the
     * only user files Minis Ultra maps are under its own data dir, and those paths
     * are folded to the directory below.
     */
    internal fun mapName(line: String): String {
        // address perms offset dev inode [name]
        var field = 0
        var i = 0
        while (i < line.length && field < 5) {
            while (i < line.length && line[i] != ' ') i++
            while (i < line.length && line[i] == ' ') i++
            field++
        }
        val name = if (field < 5 || i >= line.length) "[anon]" else line.substring(i).trim()
        val dataDir = name.indexOf("/com.openminis.ultra/")
        val short = if (dataDir >= 0 && name.startsWith("/data/")) {
            // Keep the app-relative top directory (files, cache, databases...) only.
            val rest = name.substring(dataDir + "/com.openminis.ultra/".length)
            "<app>/" + rest.substringBefore('/')
        } else name
        return nameKey(short)
    }

    // ── when to print the breakdown ──────────────────────────────────────────

    /**
     * Decides when a sample earns a breakdown. Fires on crossing a threshold;
     * after that, only when threads or maps have grown by [ALERT_GROWTH] over
     * the last breakdown and [ALERT_MIN_INTERVAL_MS] has passed, so a process
     * that stays high prints once and a process that keeps growing prints a
     * growth curve without flooding the log. Re-arms once both fall below
     * [ALERT_REARM] of their thresholds. Not thread-safe: one caller (JankDiag's
     * background thread).
     */
    class AlertGate(
        private val threadAlert: Int = THREAD_ALERT,
        private val mapsAlert: Int = MAPS_ALERT,
        private val minIntervalMs: Long = ALERT_MIN_INTERVAL_MS,
    ) {
        private var armed = true
        private var lastAtMs = 0L
        private var lastThreads = 0
        private var lastMaps = 0

        fun shouldReport(s: Sample, nowMs: Long): Boolean {
            val over = s.threads > threadAlert || s.maps > mapsAlert
            if (!over) {
                if (s.threads < threadAlert * ALERT_REARM && s.maps < mapsAlert * ALERT_REARM) armed = true
                return false
            }
            val report = when {
                armed -> true
                nowMs - lastAtMs < minIntervalMs -> false
                else -> s.threads >= lastThreads * ALERT_GROWTH || s.maps >= lastMaps * ALERT_GROWTH
            }
            if (report) { armed = false; lastAtMs = nowMs; lastThreads = s.threads; lastMaps = s.maps }
            return report
        }
    }

    // ── the last sample, for the next launch ─────────────────────────────────

    /**
     * The newest sample, kept on disk so the NEXT launch can attach it to the
     * exit report ([com.openminis.app.crash.ExitInfoCollector]): a process that
     * dies of exhaustion cannot describe itself. One small file per pid, so the
     * collector matches the record's own pid and never races the new process
     * writing its first sample.
     */
    const val WATERMARK_DIR = "diag"
    private const val WATERMARK_PREFIX = "res-"
    private const val WATERMARK_KEEP = 8

    fun watermarkFile(filesDir: File, pid: Int): File = File(File(filesDir, WATERMARK_DIR), "$WATERMARK_PREFIX$pid.txt")

    /** Replace this pid's file (write + rename: a crash mid-write leaves the previous one). */
    fun writeWatermark(filesDir: File, pid: Int, text: String) {
        runCatching {
            val f = watermarkFile(filesDir, pid)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(text)
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    /** The file the process with [pid] left behind, or null. */
    fun readWatermark(filesDir: File, pid: Int): Pair<String, Long>? = runCatching {
        val f = watermarkFile(filesDir, pid)
        if (!f.isFile) null else f.readText() to f.lastModified()
    }.getOrNull()

    /** Keep the newest [keep] files (other processes'), plus [ownPid]'s. */
    fun pruneWatermarks(filesDir: File, ownPid: Int, keep: Int = WATERMARK_KEEP) {
        runCatching {
            val own = watermarkFile(filesDir, ownPid).name
            File(filesDir, WATERMARK_DIR).listFiles { f -> f.name.startsWith(WATERMARK_PREFIX) && f.name != own }
                ?.sortedByDescending { it.lastModified() }
                ?.drop(keep)
                ?.forEach { it.delete() }
        }
    }
}

/**
 * [GH #453] Names for threads Minis Ultra creates, so a per-name count shows which
 * component a pile of threads belongs to. Each name is dot-free and at most
 * 15 characters before any per-thread number, because the kernel's comm (what
 * `/proc`, tombstones and [ResourceWatermark.threadNames] see) keeps only 15
 * bytes. Names carry no user data.
 */
object ThreadNames {
    const val PREFIX = "mn-"
    fun of(component: String): String = PREFIX + component
}
