package com.openminis.app.sandbox

import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [GH#94] Startup sweep for stale guest temp files.
 *
 * The rootfs is PERSISTENT: `/tmp` and `/var/tmp` inside it survive app
 * restarts, unlike a real Linux boot. Guest tooling (apt, pip, model harnesses)
 * leaves multi-hundred-MB staging trees behind — deleted installer archives,
 * `pip-*` unpack dirs, partial downloads — and nothing ever removed them, so
 * the storage screen's "Shell container" figure only ever grew. The native
 * offload reply files had the same leak and are handled separately by
 * `NativeOffloadServer.sweepStaleReplies`; THIS sweep is the general
 * counterpart for everything else.
 *
 * Safety rules, in order of importance:
 *  - Only `<rootfs>/tmp` and `<rootfs>/var/tmp` are scanned. `/var/minis`
 *    (user workspace / attachments / offloads) and the rest of the rootfs
 *    are NEVER touched. Bind mounts that happen to appear under /tmp are
 *    rejected by canonical-path containment as well.
 *  - Symlinks are never followed and never deleted: a `/tmp/x -> /var/minis`
 *    link must not turn this into a workspace wipe. `lstat`-equivalent via
 *    [Files.isSymbolicLink].
 *  - Files whose mtime is newer than [MAX_AGE_MS] are kept. A file an active
 *    guest process is still writing therefore stays; an orphan from a previous
 *    boot is removed after it has been cold for a day.
 *  - Traversal is depth-bounded and entry-bounded so a pathological `/tmp`
 *    cannot stall startup or allocate without bound.
 *
 * Everything is best-effort: failures are returned in [Result.errors] and
 * never thrown, because a cleanup problem must not break app boot.
 */
object StaleTempSweeper {

    /** Only files untouched for a full day are considered cold enough. */
    const val MAX_AGE_MS: Long = 24 * 60 * 60 * 1000L

    private const val MAX_DEPTH = 12
    private const val MAX_ENTRIES = 20_000

    private val started = AtomicBoolean(false)

    data class Result(
        val removedFiles: Int = 0,
        val removedBytes: Long = 0L,
        val scannedDirs: Int = 0,
        val errors: Int = 0,
    )

    /**
     * Run [sweep] once per process on a daemon thread. Called from
     * `NativeOffloadServer.start`, which is the one point every boot (cold app
     * start and pre-proot kernel boot) funnels through, and which already owns
     * the rootfs temp directory.
     */
    fun sweepAsyncOnce(rootfsDir: File, now: Long = System.currentTimeMillis()) {
        if (!started.compareAndSet(false, true)) return
        val t = Thread(
            {
                try {
                    sweep(rootfsDir, now)
                } catch (_: Throwable) {
                    // Best-effort: never let cleanup break boot.
                }
            },
            "stale-temp-sweep",
        )
        t.isDaemon = true
        t.start()
    }

    fun sweep(rootfsDir: File, now: Long = System.currentTimeMillis()): Result {
        val cutoff = now - MAX_AGE_MS
        val protectedRoot = canonicalOrNull(File(rootfsDir, "var/minis"))
        var result = Result()

        val roots = listOf(File(rootfsDir, "tmp"), File(rootfsDir, "var/tmp"))
        var visited = 0

        for (root in roots) {
            if (!root.isDirectory) continue
            // A symlinked /tmp must never be walked (it could point anywhere).
            if (Files.isSymbolicLink(root.toPath())) continue
            val rootCanonical = canonicalOrNull(root) ?: continue
            result = result.copy(scannedDirs = result.scannedDirs + 1)

            // Iterative DFS: (dir, depth). Files are checked in place.
            val stack = ArrayDeque<Pair<File, Int>>()
            stack.addLast(root to 0)
            while (stack.isNotEmpty() && visited < MAX_ENTRIES) {
                val (dir, depth) = stack.removeLast()
                val children = dir.listFiles() ?: continue
                for (child in children) {
                    visited++
                    if (visited > MAX_ENTRIES) break
                    // Never follow or delete links (GH#94: a /tmp link into
                    // /var/minis must not become a workspace wipe).
                    if (Files.isSymbolicLink(child.toPath())) continue
                    if (child.isDirectory) {
                        if (depth + 1 <= MAX_DEPTH) stack.addLast(child to depth + 1)
                        continue
                    }
                    if (!child.isFile) continue
                    // Canonical containment: only delete inside the scanned
                    // root, and never under /var/minis even if it were mounted
                    // below /tmp.
                    val canonical = canonicalOrNull(child)
                    if (canonical == null) {
                        result = result.copy(errors = result.errors + 1)
                        continue
                    }
                    if (!canonical.startsWith(rootCanonical)) continue
                    if (protectedRoot != null && canonical.startsWith(protectedRoot)) continue

                    val modified = child.lastModified()
                    // 0 = unknown mtime: treat as cold (matches the offload
                    // sweep's rule); a file with a future mtime is kept.
                    if (modified > cutoff) continue
                    val length = child.length()
                    if (runCatching { child.delete() }.getOrDefault(false)) {
                        result = result.copy(
                            removedFiles = result.removedFiles + 1,
                            removedBytes = result.removedBytes + length,
                        )
                    } else {
                        result = result.copy(errors = result.errors + 1)
                    }
                }
            }
        }
        return result
    }

    private fun canonicalOrNull(file: File): File? =
        runCatching { file.canonicalFile }.getOrNull()
}
