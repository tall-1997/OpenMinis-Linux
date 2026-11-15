package com.openminis.app.tools

import java.io.File

/**
 * Streaming, bounded scan engine behind [GrepSourceTool].
 *
 * Ported from the pi bounded-enumeration contract (packages/env/src/list-files.ts:
 * every enumeration carries explicit `maxEntries`/`maxBytes`/`timeoutMs` bounds
 * and a `truncated` flag; packages/env/src/watch.ts reports `overflow` for
 * changes lost while a watcher was down): losing coverage is REPORTED, never
 * silent.
 *
 * Why a hand-rolled walker instead of `File.walkTopDown` (P2 2-1):
 *  - The walk NEVER materializes the whole file list. Memory at any instant is
 *    one directory's listing + counters + the match buffer. The intermediate
 *    version collected every file into an ArrayList first — hundreds of MB on
 *    a mounted drive with a few hundred thousand entries, before matching even
 *    started.
 *  - Symlink cycles (`a -> b -> a`) are broken by a visited
 *    canonical-directory set. Kotlin's FileTreeWalk cannot detect them and
 *    would spin until killed; the deadline alone would turn every loop into a
 *    45s CPU burn with garbage coverage.
 *  - Depth and directory-count caps are secondary guards for filesystems
 *    where canonicalization lies or junctions alias under different names.
 *  - A wall-clock deadline bounds the entire call. The P2 2-1 spec mandates a
 *    30-60s overall timeout; 45s sits inside that band and leaves room for
 *    the match work inside one tool-call budget.
 *
 * Coverage bookkeeping (P2 2-2 / 2-4): files that cannot be read are counted
 * into [ScanStats.filesSkippedIO] so the model can tell "nothing there" from
 * "could not look", and when the match loop stops early (match cap, output
 * cap) the SAME walk keeps running with content reads disabled, counting the
 * files it never reached, so the report can say "about K more files unscanned"
 * instead of letting the model guess what it never saw.
 */
internal object GrepStreamScan {

    // ------------------------------------------------------------ limits ----

    /** Default context lines around each match (schema contract). */
    const val DEFAULT_CONTEXT = 2

    /** Upper bound of the `context` parameter (schema contract). */
    const val MAX_CONTEXT = 6

    /** Default cap on returned matches (schema contract). */
    const val MAX_MATCHES = 50

    /** Upper bound of the `max_matches` parameter (schema contract). */
    const val MAX_MATCHES_CAP = 500

    /** Files larger than this are skipped and counted, not read. */
    const val MAX_FILE_BYTES = 512 * 1024

    /** Hard cap on the match body in characters, mirroring the old behavior. */
    const val OUTPUT_HARD_CAP = 40_000

    /**
     * Overall wall-clock deadline for one scan, in milliseconds. Source: the
     * P2 2-1 mandated 30-60s overall timeout band (PI-PORT-ANALYSIS-2026-11-15
     * §3, bounded enumeration contract) — a pathological tree returns PARTIAL
     * results with the truncation reported instead of hanging the lane.
     */
    const val SCAN_DEADLINE_MS = 45_000L

    /**
     * Secondary guards behind the visited-set cycle breaker: a broken
     * filesystem where canonicalization fails (or a layer that reports
     * directories it cannot enumerate) could still defeat the set. Tripping
     * either one is reported as a truncation reason, never silent.
     */
    const val MAX_WALK_DEPTH = 100
    const val MAX_DIRS_VISITED = 100_000

    /**
     * [T-grep-source-coverage] Directories that carry no source the model can
     * act on but dominate walk cost in a real repo (OpenMinis itself has
     * ~190k files under .git/node_modules/build). Pruning them keeps the
     * full-source scan affordable WITHOUT capping coverage: the pre-P2 version
     * walked the FIRST 200 files in directory order and never looked further —
     * a target past the cap produced a confident "No matches" and the model
     * concluded the symbol didn't exist.
     */
    val PRUNED_DIR_NAMES = setOf(
        ".git", ".gradle", ".idea", ".svn", ".hg",
        "node_modules", "build", "dist", "target", "out",
        "__MACOSX", ".venv", "venv", "__pycache__",
    )

    // ------------------------------------------------------------ seams ----

    /**
     * Tunables and test seams. Production callers use the defaults; tests
     * inject a fake [clock] (deterministic deadline trips without sleeping)
     * and a failing [readLines] (unreadable-file blind spots without
     * filesystem permissions tricks).
     */
    data class Options(
        val maxMatches: Int = MAX_MATCHES,
        val contextLines: Int = DEFAULT_CONTEXT,
        val outputHardCap: Int = OUTPUT_HARD_CAP,
        val maxFileBytes: Long = MAX_FILE_BYTES.toLong(),
        val deadlineMs: Long = SCAN_DEADLINE_MS,
        val maxWalkDepth: Int = MAX_WALK_DEPTH,
        val maxDirsVisited: Int = MAX_DIRS_VISITED,
        /** Injectable wall clock so tests can trip the deadline deterministically. */
        val clock: () -> Long = System::currentTimeMillis,
        /**
         * Injectable per-file reader. `null` means the file could not be read
         * (unreadable / vanished mid-scan / decode failure) — the scan counts
         * it as a skipped-IO blind spot instead of pretending it was searched.
         */
        val readLines: (File) -> List<String>? = { file ->
            try {
                file.bufferedReader().useLines { it.toList() }
            } catch (_: Exception) {
                null
            }
        },
    )

    // ------------------------------------------------------------ stats ----

    /**
     * Structured outcome of one scan. Every truncation reason is carried
     * explicitly; [GrepStreamScan.formatReport] turns them into the
     * model-visible coverage brackets.
     */
    data class ScanStats(
        val totalMatches: Int,
        val hitFiles: Int,
        val filesScanned: Int,
        val filesSkippedLarge: Int,
        val filesSkippedIO: Int,
        /**
         * Files the walk reached after the match loop stopped early, i.e.
         * candidates the model never saw. `null` means the count itself was
         * cut short by the deadline — unknown is reported as unknown, never
         * as zero.
         *
         * Null too when [stoppedByWalkGuard] is set (review P2-10): the walk
         * guard abandoned whole subtrees (depth / directory-count guard,
         * already-queued leftovers excepted), so any figure here is a LOWER
         * bound — the `[walk guard hit — partial coverage]` bracket is the
         * independent, honest report for that case, and pairing it with a
         * silently-underestimated "N more unscanned" would be the kind of
         * half-truth the bounded-enumeration contract forbids. Pruned noise
         * dirs ([PRUNED_DIR_NAMES]) are different: they are out of scope by
         * contract, never part of the remainder.
         */
        val approxRemainingUnscanned: Long?,
        val stoppedByMatchCap: Boolean,
        val stoppedByOutputCap: Boolean,
        val stoppedByTimeout: Boolean,
        val stoppedByWalkGuard: Boolean,
        val output: String,
    ) {
        /** The match loop stopped before the tree was exhausted. */
        val stoppedEarly: Boolean get() = stoppedByMatchCap || stoppedByOutputCap

        /** Any truncation reason at all — the report must say so explicitly. */
        val truncated: Boolean get() = stoppedEarly || stoppedByTimeout || stoppedByWalkGuard
    }

    // ------------------------------------------------------------ scan -----

    /**
     * Scan [root] (file or directory) for [matcher] hits under [options].
     *
     * Traversal is pre-order DFS implemented with an explicit stack so a very
     * deep tree cannot blow the call stack. Within one directory, files are
     * processed in listing order and subdirectories are pushed in reverse
     * listing order so they pop in listing order — deterministic traversal
     * that keeps match selection under the caps stable across runs.
     */
    fun scan(root: File, matcher: (String) -> Boolean, options: Options = Options()): ScanStats {
        val out = StringBuilder()
        var totalMatches = 0
        var hitFiles = 0
        var scanned = 0
        var skippedLarge = 0
        var skippedIO = 0
        var stoppedByMatchCap = false
        var stoppedByOutputCap = false
        var stoppedByTimeout = false
        var stoppedByWalkGuard = false

        val startedAt = options.clock()
        val deadline = startedAt + options.deadlineMs

        // Remainder estimate state (P2 2-4). After the match loop stops early
        // the SAME walk keeps running with reads disabled, counting the files
        // the match loop never reached. `remainingKnown` flips to false when
        // the deadline exhausts mid-count: an unknown remainder is reported as
        // unknown, never as zero (silent-loss avoidance again). It is ALSO
        // reported as unknown when the walk guard tripped (see the
        // approxRemainingUnscanned KDoc below).
        var countingRemainder = false
        var remaining = 0L
        var remainingKnown = true

        fun timedOut(): Boolean {
            if (options.clock() <= deadline) return false
            stoppedByTimeout = true
            // A deadline that trips mid-count means the remainder figure is
            // itself a partial count: report unknown, never a silently
            // truncated number.
            if (countingRemainder) remainingKnown = false
            return true
        }

        fun processFile(file: File) {
            val size = file.length()
            if (countingRemainder) {
                // Same admission window as the match phase (0 < size <= cap),
                // minus the content read — this is what the match phase would
                // have searched had it not stopped.
                if (size > 0L && size <= options.maxFileBytes) remaining++
                return
            }
            // [T-grep-source-blindspot] 0-byte files are NOTHING to match,
            // not a "skip" — counting them as blind spots lied about
            // coverage; >cap files ARE a blind spot and are counted.
            if (size == 0L) return
            if (size > options.maxFileBytes) {
                skippedLarge++
                return
            }
            scanned++
            val lines = options.readLines(file)
            if (lines == null) {
                // [T-grep-source-blindspot] (P2 2-2) binary or unreadable —
                // count the blind spot so "no match" can be distinguished
                // from "could not look".
                skippedIO++
                return
            }
            // (P2-9) The deadline must bound the MATCH LOOP, not only the walk
            // between files: a line pattern that is SLOW BUT RETURNS can burn
            // seconds per line, so the previous file-level checks never ran.
            // One clock comparison per line keeps the per-line cost negligible
            // while making the deadline authoritative ACROSS lines. Known
            // boundary, recorded honestly rather than overstated: this check
            // constrains only matchers that RETURN. Catastrophic backtracking
            // INSIDE one matcher invocation (one line whose matcher call never
            // returns) is still unbounded — the JVM regex engine has no
            // in-match interruption, so this check cannot run until the
            // matcher returns; the wall-clock deadline bounds everything else.
            // Tripping mid-file abandons the WHOLE file, not just the rest of
            // it: the early return below also discards the hits already
            // collected in hitIdx, so the file contributes nothing to the
            // output (it stays in the scanned count, and the report's
            // "scan deadline hit — partial coverage" bracket is the only
            // signal), and the next child/directory check stops the walk.
            val hitIdx = ArrayList<Int>()
            for (i in lines.indices) {
                if (timedOut()) return
                if (matcher(lines[i])) hitIdx.add(i)
            }
            if (hitIdx.isEmpty()) return
            hitFiles++
            out.append("=== ").append(file.absolutePath).append(" ===\n")
            var lastPrinted = -1
            for (i in hitIdx) {
                if (totalMatches >= options.maxMatches) {
                    out.append("  … more matches in this file (cap) …\n")
                    break
                }
                val lo = (i - options.contextLines).coerceAtLeast(0)
                val hi = (i + options.contextLines).coerceAtMost(lines.size - 1)
                if (lo > lastPrinted + 1 && lastPrinted >= 0) out.append("  --\n")
                for (n in lo..hi) {
                    if (n <= lastPrinted) continue
                    val mark = if (n == i) ">" else " "
                    out.append(mark).append(n + 1).append(": ").append(lines[n]).append('\n')
                    lastPrinted = n
                }
                totalMatches++
            }
        }

        if (root.isFile) {
            processFile(root)
        } else if (root.isDirectory) {
            // One directory's listing at a time: the stack holds directory
            // entries only, never the file list of the whole tree.
            val pendingDirs = ArrayDeque<DirEntry>()
            // Canonical paths of every directory ENTERED. The root itself is
            // pre-registered so a symlink pointing straight back at the root
            // cannot resurrect it.
            val visitedDirs = HashSet<String>()
            visitedDirs.add(visitedKey(root))
            pendingDirs.addLast(DirEntry(root, 0))
            // Set once the walk guard trips: no deeper descent for the rest
            // of the walk, but already-queued directories are still drained
            // (their files are legitimate, cheap, and may hold matches).
            var walkGuardTripped = false

            while (pendingDirs.isNotEmpty()) {
                if (timedOut()) break
                val entry = pendingDirs.removeLast()
                val children = try {
                    entry.dir.listFiles()
                } catch (_: Exception) {
                    null
                }
                if (children == null) {
                    // [T-grep-source-blindspot] (P2 2-2) A listing that fails
                    // is an IO blind spot: the files under it were neither
                    // matched nor counted.
                    if (!countingRemainder) skippedIO++
                    continue
                }
                val subDirs = ArrayList<DirEntry>()
                for (child in children) {
                    if (timedOut()) break
                    if (child.isDirectory) {
                        // [T-grep-source-coverage] The prune applies to every
                        // directory BELOW the start point: a repo literally
                        // named build/dist would otherwise be pruned to zero
                        // files and reported as an empty search (the root is
                        // pushed directly, never name-pruned).
                        if (child.name in PRUNED_DIR_NAMES) continue
                        if (entry.depth + 1 > options.maxWalkDepth) {
                            stoppedByWalkGuard = true
                            walkGuardTripped = true
                            continue
                        }
                        val canonical = visitedKey(child)
                        // Cycle breaker: a symlink back into any ancestor (or
                        // the root) canonicalizes to an already-visited path
                        // and is dropped. This is what FileTreeWalk cannot do.
                        if (!visitedDirs.add(canonical)) continue
                        if (visitedDirs.size > options.maxDirsVisited) {
                            stoppedByWalkGuard = true
                            walkGuardTripped = true
                            continue
                        }
                        if (!walkGuardTripped) subDirs.add(DirEntry(child, entry.depth + 1))
                    } else if (child.isFile) {
                        processFile(child)
                        if (countingRemainder) continue
                        if (totalMatches >= options.maxMatches) {
                            // (P2 2-4) Keep the walk alive in count-only mode:
                            // the remainder becomes the "about K more files"
                            // figure instead of an unexamined guess.
                            stoppedByMatchCap = true
                            countingRemainder = true
                        } else if (out.length > options.outputHardCap) {
                            stoppedByOutputCap = true
                            countingRemainder = true
                        }
                    }
                }
                // Push in reverse so the pop order matches listing order.
                for (i in subDirs.indices.reversed()) pendingDirs.addLast(subDirs[i])
            }
        }

        return ScanStats(
            totalMatches = totalMatches,
            hitFiles = hitFiles,
            filesScanned = scanned,
            filesSkippedLarge = skippedLarge,
            filesSkippedIO = skippedIO,
            // (P2-10) A guard-tripped walk cannot vouch for a complete
            // remainder count: report unknown, same policy as a deadline that
            // trips mid-count.
            approxRemainingUnscanned = if (remainingKnown && !stoppedByWalkGuard) remaining else null,
            stoppedByMatchCap = stoppedByMatchCap,
            stoppedByOutputCap = stoppedByOutputCap,
            stoppedByTimeout = stoppedByTimeout,
            stoppedByWalkGuard = stoppedByWalkGuard,
            output = out.toString(),
        )
    }

    // ---------------------------------------------------------- report -----

    /**
     * Assemble the model-visible report. Backward compatibility contract: the
     * header fields and every existing bracket are byte-identical to the
     * pre-refactor format; coverage bookkeeping (skipped counts, deadline,
     * walk guard, remaining estimate) only ever ADDS brackets, never rewrites
     * or reorders existing ones.
     */
    fun formatReport(pattern: String, rawPath: String, stats: ScanStats, maxMatches: Int): String {
        if (stats.totalMatches == 0) {
            val why = buildString {
                if (stats.filesSkippedLarge > 0) {
                    append(" (${stats.filesSkippedLarge} skipped >${MAX_FILE_BYTES / 1024} KB)")
                }
                if (stats.filesSkippedIO > 0) append(" (${stats.filesSkippedIO} unreadable)")
                if (stats.stoppedByTimeout) append(" (scan deadline hit)")
                if (stats.stoppedByWalkGuard) append(" (walk guard hit)")
                val remaining = stats.approxRemainingUnscanned
                if (remaining != null && remaining > 0 && stats.truncated) {
                    append(" (~$remaining more file(s) unscanned)")
                }
            }
            return "No matches for \"$pattern\" in $rawPath (scanned ${stats.filesScanned} file(s))$why"
        }

        var body = stats.output.trimEnd()
        var note = ""
        if (body.length > OUTPUT_HARD_CAP) {
            body = body.take(OUTPUT_HARD_CAP)
            note = "\n…[output truncated at $OUTPUT_HARD_CAP chars — narrow path or pattern]…"
        }
        val header = buildString {
            append("[grep_source: \"$pattern\" in $rawPath — ")
            append(stats.totalMatches).append(" match(es) across ").append(stats.hitFiles)
            append(" file(s), scanned ").append(stats.filesScanned).append("]")
            if (stats.totalMatches >= maxMatches) append(" (match cap reached)")
            // [T-grep-source-blindspot] Quantified blind spots: skipped
            // large/unreadable files are counted, not silent.
            if (stats.filesSkippedLarge > 0 || stats.filesSkippedIO > 0) {
                append(" [skipped: ")
                if (stats.filesSkippedLarge > 0) append("${stats.filesSkippedLarge} >512KB, ")
                if (stats.filesSkippedIO > 0) append("${stats.filesSkippedIO} unreadable")
                append("]")
            }
            if (stats.stoppedByTimeout) append(" [scan deadline hit — partial coverage]")
            if (stats.stoppedByWalkGuard) append(" [walk guard hit — partial coverage]")
            // [P2 2-4] The match cap stops the scan mid-tree; say how much was
            // left unread instead of letting the model guess. When the walk
            // guard ALSO tripped the figure is a lower bound and is reported
            // as unknown (null) instead — the walk-guard bracket below is the
            // independent honest signal for that case (review P2-10).
            val remaining = stats.approxRemainingUnscanned
            if (stats.stoppedEarly && remaining != null && remaining > 0) {
                append(" [~$remaining more file(s) unscanned]")
            }
        }
        return header + "\n\n" + body + note
    }

    // --------------------------------------------------------- helpers ----

    /** One pending directory in the explicit walk stack. */
    private data class DirEntry(val dir: File, val depth: Int)

    /**
     * Canonical key for cycle detection. `Path.toRealPath()` is the resolver
     * of record: it FOLLOWS symlinks and returns the true target path —
     * `File.canonicalFile` does NOT resolve directory symlinks on win32
     * (probe-verified: a link `a\toB -> b` canonicalizes to `a\toB`, so a
     * visited set keyed on canonical paths never sees the cycle and the walk
     * loops until the match cap). Broken links and unreadable paths fall back
     * to `canonicalFile` and then the absolute path, keeping the walk alive
     * with the depth/count guards as the last line of defense.
     *
     * The `\\?\` device-prefix strip and Windows case-folding stay as
     * belt-and-braces for JDK builds whose real-path output still carries
     * the prefix or differs in case.
     */
    private fun visitedKey(file: File): String {
        val real = try {
            file.toPath().toRealPath()
        } catch (_: Exception) {
            try {
                file.canonicalFile.toPath()
            } catch (_: Exception) {
                file.absoluteFile.toPath()
            }
        }
        var path = real.toString()
        if (path.startsWith("\\\\?\\")) {
            path = if (path.startsWith("\\\\?\\UNC\\")) "\\\\" + path.substring(8) else path.substring(4)
        }
        val windows = System.getProperty("os.name", "").lowercase().contains("windows")
        return if (windows) path.lowercase() else path
    }
}
