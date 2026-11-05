package com.openminis.app.data.repository

import android.util.Log
import com.openminis.app.data.authority.MemoryAuthority
import com.openminis.app.evolution.LearnedPrefsStore
import com.openminis.app.evolution.SceneTag
import com.openminis.app.text.BoundedText
import com.openminis.app.logging.AppLogger
import com.openminis.app.tools.AtomicFileWrite
import java.io.File
import java.io.IOException
import com.openminis.app.util.Digest
import com.openminis.app.util.IsoTime

/**
 * Manages the memory directory (`minis-global/memory/`).
 * Mirrors iOS memory system:
 *   - GLOBAL.md: read-only for agent, user-maintained via Settings
 *   - YYYY-MM-DD.md: daily logs with timestamped entries, agent writes via memory_write
 *   - memory_get: fuzzy keyword search across files
 *   - loadGlobalMemoryFragment() / loadRecentDailyFragments(): emit the
 *     global and per-day daily-log blocks for the system prompt
 */
class MemoryRepository(private val memoryDir: File) {

    val learnedPrefs = LearnedPrefsStore(File(memoryDir, LearnedPrefsStore.FILE_NAME))

    // ── [T-prompt-cache] Fragment builder ─────────────────────────
    // GLOBAL.md + daily-log fragments build inside MemoryFragmentBuilder
    // (architecture ratchet extraction). The memoized caches live there;
    // the repository keeps this lazy handle and delegates its three public
    // loaders. Callers get a memoized repository via
    // ChatViewModel.sessionMemoryRepo(), so the cache survives across turns.
    private val fragments: MemoryFragmentBuilder by lazy {
        MemoryFragmentBuilder(memoryDir, learnedPrefs, ::fileFingerprint)
    }

    /**
     * Fingerprint = stat + short content digest; null when the file is missing.
     *
     * [T-prompt-cache-fingerprint] Stat-only (mtime+length) missed same-length
     * edits landing inside the mtime granularity, so a rewritten GLOBAL.md
     * could keep serving a stale prompt fragment. These files are a few KB —
     * hashing them per turn is noise next to the LLM call the fragment feeds.
     * Content beyond [HASH_CAP_BYTES] is not hashed; the length term still
     * catches growth past the cap.
     */
    internal fun fileFingerprint(file: File): String? {
        if (!file.exists()) return null
        val digest = runCatching {
            val md = java.security.MessageDigest.getInstance("SHA-256")
            file.inputStream().use { ins ->
                val buf = ByteArray(8192)
                var total = 0
                while (total < HASH_CAP_BYTES) {
                    val n = ins.read(buf, 0, minOf(buf.size, HASH_CAP_BYTES - total))
                    if (n <= 0) break
                    md.update(buf, 0, n)
                    total += n
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }.take(16)
        }.getOrDefault("")
        return "${file.lastModified()}:${file.length()}:$digest"
    }

    companion object {
        private const val TAG = "MemoryRepository"
        internal const val GLOBAL_FILE = "GLOBAL.md"
        private const val HASH_CAP_BYTES = 256 * 1024

        /**
         * [T-global-md-seed] Default GLOBAL.md content for first-run seeding.
         *
         * GLOBAL.md is the user-maintained standing-rules file the agent
         * treats as read-only background context (see
         * [loadGlobalMemoryFragment]). Unlike SOUL.md it had no default —
         * fresh installs simply got no global rules until the user wrote
         * the file by hand. This constant seeds a starter template on
         * first launch; edit ONLY the raw-string body below to customize
         * the shipped default. Seeding mirrors SoulStore.ensureExists:
         * create-only, never overwrites an existing file.
         */
        /**
         * [T-default-assets] The default GLOBAL.md seed content, loaded
         * from `assets/default_global.md` (single source of truth — edit
         * that file to customize the shipped default), with the embedded
         * skeleton below as fallback. See [ensureGlobalExists].
         */
        val DEFAULT_GLOBAL_CONTENT: String
            get() = assetsGlobalDefault ?: EMBEDDED_GLOBAL_DEFAULT

        @Volatile private var assetsGlobalDefault: String? = null

        /** Read `assets/default_global.md` into DEFAULT_GLOBAL_CONTENT. */
        fun loadGlobalDefaultFromAssets(context: android.content.Context) {
            runCatching {
                context.assets.open("default_global.md").bufferedReader().use { it.readText() }
            }.getOrNull()?.let { assetsGlobalDefault = it }
        }

        private val EMBEDDED_GLOBAL_DEFAULT: String = """
# 全局规则

（默认占位：assets/default_global.md 缺失时的兜底。）
"""

        /**
         * Create GLOBAL.md with [DEFAULT_GLOBAL_CONTENT] iff it does not
         * exist yet. Mirrors SoulStore.ensureExists: safe on every launch,
         * never overwrites user edits.
         */
        fun ensureGlobalExists(context: android.content.Context) {
            val file = File(context.filesDir, "minis-global/memory/$GLOBAL_FILE")
            if (file.exists()) return
            try {
                file.parentFile?.mkdirs()
                writeTextAtomic(file, DEFAULT_GLOBAL_CONTENT)
                com.openminis.app.logging.AppLogger.info(TAG, "seeded GLOBAL.md at ${file.absolutePath}")
            } catch (t: Throwable) {
                com.openminis.app.logging.AppLogger.warning(TAG, "ensureGlobalExists failed: ${t.message}")
            }
        }
        // [T-memory-prompt-budget] Tiered per-day verbatim caps for daily-log
        // injection: today gets MAX_INJECT_LINES, older days in the window
        // get MAX_INJECT_LINES_OLDER. 50 + 6*15 lines is roughly 15-17K chars
        // at the ~100 chars/line measured on real logs, sized so the whole
        // 7-day window fits inside MemoryPromptBudget.DEFAULT_BUDGET_CHARS
        // next to the standing fragments. The old single 200-line cap (an
        // iOS mirror) let three files alone ask for ~60K, so the budget
        // silently truncated the block — the real cause of "the model only
        // remembers two days".
        internal const val MAX_INJECT_LINES = 50
        internal const val MAX_INJECT_LINES_OLDER = 15
        // memory_get full-dump (no keywords): cap at 500 lines — matches iOS
        // `maxTotalLines = 500` in AIChatViewModel+MemoryTools.swift.
        private const val MAX_DUMP_LINES = 500
        // memory_get keyword search: cap at 60 lines. iOS caps at 60 *entries*
        // (timestamp-delimited memory_write blocks); Android's keyword search
        // is line-based with ±2 context windows, so we keep the same algorithm
        // and align on the 60 magnitude as the line budget.
        private const val MAX_SEARCH_LINES = 60
        internal const val MAX_LOOKBACK_DAYS = 30
        // [P5] Verbatim daily window: 7 days (was 3). Days the budget cannot
        // admit drop to a MemoryIndex table-of-contents row instead of
        // vanishing — see MemoryAssembly for the two-pass guarantee.
        internal const val MAX_RECENT_FILES = 7

        /**
         * [XSessionDiag] Vocabulary that suggests an injected daily log describes
         * a TASK a model might try to resume, rather than a plain fact. Purely a
         * log tag — nothing branches on it, and both languages are listed because
         * the reported incidents were Chinese-language sessions.
         */
        val DIAG_TASK_KEYWORDS = listOf(
            "任务", "调研", "继续", "接着", "未完成", "下一步",
            "task", "continue", "resume", "TODO",
        )

        fun looksLikeTaskDiary(text: String): Boolean {
            val window = BoundedText.icuWindow(text).toString()
            return DIAG_TASK_KEYWORDS.any { window.contains(it, ignoreCase = true) }
        }
        // [T-memory-get-truncate-android] Hard byte ceiling on memory_get
        // output. Line caps alone (MAX_DUMP_LINES / MAX_SEARCH_LINES) don't
        // bound bandwidth when a single matched line is itself huge — TG
        // 37452 hit a 70KB single-call result that froze the chat UI for
        // several seconds when expanded. 30KB is the comfort budget for
        // an agent tool result that needs to be both rendered AND fed
        // back into the next LLM call. Counted as UTF-8 bytes (matches
        // what the provider sees over the wire).
        private const val MAX_OUTPUT_BYTES = 30 * 1024  // 30 KB

        // [T-memory-poison-guard] 写入侧静态配额——单条记忆字节上限 + 当日写入
        // 条数上限。诚实命名：这不是时间窗也不是断路器，只是两条硬上限；防的
        // 是失控 agent loop 用 memory_write 灌爆当日日志。字节数按 UTF-8 计
        // （与发给 provider 的线上体积一致）。沙箱侧对 /var/minis/memory 的
        // 直接 shell 写语法由 GuestWorkloadPolicy.memoryQuotaRefusal 按同一
        // 配额拒绝——若配额只站在工具路径上，改用 shell/file_edit 写同一个
        // bind 目录即可绕过，那恰好是本防护声称要拦的场景。
        private val MAX_ENTRY_BYTES = 8 * 1024      // 8 KB UTF-8
        const val MAX_DAILY_ENTRIES = 30

        /** writeMemory 的落盘格式：行首时间戳标记 + 正文 + 空行。行首锚定计数，
         *  正文里出现同形字符串不会被误计为一条记忆。 */
        private val ENTRY_MARK = Regex(
            "(?m)^<!-- \\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2} -->\\s*$"
        )

        /** 当日日志里已有的条目数（供沙箱侧配额闸门复用）。 */
        fun dailyEntryCount(dir: File): Int {
            val file = File(dir, IsoTime.formatLocalDate() + ".md")
            if (!file.exists()) return 0
            return runCatching { ENTRY_MARK.findAll(file.readText()).count() }.getOrDefault(0)
        }

        /**
         * [T-memory-poison-guard] 文件工具侧的同一配额。shell 侧闸门在
         * GuestWorkloadPolicy.memoryQuotaRefusal（ExecutionCoordinator 接线）；
         * 没有这一份，被 memory_write 上限拦住的失控循环改走 file_write /
         * file_edit / multi_edit 写同一个 bind 目录即可继续灌——恰是配额声称
         * 要拦的场景。multi_edit 逐条委托 FileEditTool，因此两个接入点覆盖
         * 全部三个工具。返回拒绝消息，或 null 放行。
         */
        fun fileToolQuotaRefusal(filesDir: File, sessionId: String, guestPath: String): String? {
            if (!guestPath.startsWith("/var/minis/memory/")) return null
            val dir = com.openminis.app.sandbox.SessionWorkspace.memoryDir(filesDir, sessionId)
            if (dailyEntryCount(dir) < MAX_DAILY_ENTRIES) return null
            return "Error: 今日记忆日志已达 $MAX_DAILY_ENTRIES 条上限，对 /var/minis/memory 的写入已暂停" +
                "（memory_write / file_write / file_edit / shell 共用同一配额）。" +
                "旧的按日归档，明日自动开始新日志。"
        }
    }

    init {
        memoryDir.mkdirs()
    }

    // -- memory_write --

    /**
     * Append a timestamped entry to today's daily log.
     * New entries are prepended (newest first).
     *
     * [T-memory-revision] [expectedRevision] 启用并发写入冲突检测（Eta 式 SHA-256）：
     * 非空时先校验现有文件内容哈希，不匹配返回 MEMORY_CONFLICT，不覆盖并发更新。
     * 写入成功返回新 revision（哈希前 16 位）。
     */
    fun writeMemory(content: String, expectedRevision: String? = null): String {
        if (content.isBlank()) return "Error: Missing required 'content' parameter"

        // [T-memory-poison-guard] 写入侧毒窗防护（镜像 Kelivo poison-window
        // breaker 精神）：单条超限 + 单日条目超限。失控的 agent loop 反复写
        // memory_write 时，坏窗口不允许永久毒化当日日志。
        val contentBytes = content.toByteArray(Charsets.UTF_8).size
        if (contentBytes > MAX_ENTRY_BYTES) {
            return "Error: 记忆条目过长（$contentBytes 字节，上限 $MAX_ENTRY_BYTES）。请精简为要点再写。"
        }
        val fileName = "${IsoTime.formatLocalDate()}.md"
        val file = File(memoryDir, fileName)

        val timestamp = IsoTime.formatLocalSeconds(System.currentTimeMillis())
        val entry = "<!-- $timestamp -->\n$content\n\n"

        val existing = if (file.exists()) file.readText() else ""
        if (expectedRevision != null) {
            val actual = Digest.sha256Hex(existing)
            if (!actual.startsWith(expectedRevision)) {
                return "Error: MEMORY_CONFLICT — 日志文件已被并发更新（期望 revision $expectedRevision，实际 ${actual.take(16)}…）。请先 memory_get 重新读取后再写。"
            }
        }
        val entryCount = ENTRY_MARK.findAll(existing).count()
        if (entryCount >= MAX_DAILY_ENTRIES) {
            return "Error: 今日记忆已达上限（$MAX_DAILY_ENTRIES 条）。旧的按日归档，明日自动开始新日志；请精简而不是继续追加。"
        }
        val newContent = entry + existing

        return try {
            writeTextAtomic(file, newContent)
            val newRevision = Digest.sha256Hex(newContent).take(16)
            Log.i(TAG, "Memory written to $fileName (${content.length} chars, revision $newRevision)")
            "Memory saved to $fileName (${content.length} chars, revision $newRevision)"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write memory", e)
            "Error writing memory: ${e.message}"
        }
    }

    /** [T-memory-revision] 今日日志文件当前 revision（不存在返回 null）。 */
    fun currentDailyRevision(): String? {
        val file = File(memoryDir, "${IsoTime.formatLocalDate()}.md")
        if (!file.exists()) return null
        return Digest.sha256Hex(file.readText()).take(16)
    }

    // -- memory_get --

    /**
     * Fuzzy keyword search across memory files.
     * @param keywords space-separated, case-insensitive, ALL must match
     * @param scope "daily" (logs only) or "all" (include GLOBAL.md)
     * @return search results with context lines
     */
    fun getMemory(keywords: String, scope: String): String {
        val keywordList = keywords.trim()
            .lowercase()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }

        val filesToSearch = mutableListOf<Pair<String, File>>() // label to file

        if (scope == "all") {
            val globalFile = File(memoryDir, GLOBAL_FILE)
            if (globalFile.exists() && globalFile.length() > 0) {
                filesToSearch.add(GLOBAL_FILE to globalFile)
            }
        }

        // Daily logs sorted descending
        val dailyFiles = memoryDir.listFiles()
            ?.filter { it.extension == "md" && it.name != GLOBAL_FILE && it.name != LearnedPrefsStore.FILE_NAME }
            ?.sortedByDescending { it.name }
            ?: emptyList()

        for (file in dailyFiles) {
            filesToSearch.add(file.name to file)
        }

        if (filesToSearch.isEmpty()) {
            return "No memory files found."
        }

        val results = mutableListOf<String>()
        var totalLines = 0
        // [T-memory-get-truncate-android] UTF-8 byte tally — see
        // MAX_OUTPUT_BYTES rationale in the companion object. Bytes are
        // accumulated AFTER appending each result entry; the line check is
        // still consulted first so we never over-allocate slicing windows.
        var totalBytes = 0
        // Total ranges/files matched vs. files actually included in the
        // returned output. Reported in the truncation note so the caller
        // can tell whether the cap dropped further matches.
        var totalMatchedFiles = 0
        var includedFiles = 0
        var byteCapHit = false
        // Two separate caps so a full dump (no keywords) gets enough room to
        // show recent daily logs while keyword searches stay tight enough not
        // to flood agent context.
        val lineCap = if (keywordList.isEmpty()) MAX_DUMP_LINES else MAX_SEARCH_LINES

        for ((label, file) in filesToSearch) {
            if (totalLines >= lineCap || byteCapHit) break
            val content = try { file.readText() } catch (_: Exception) { continue }
            if (content.isEmpty()) continue
            val budget = lineCap - totalLines

            val entry: String? = if (keywordList.isEmpty()) {
                // Return file preview
                val lines = content.lines()
                val take = minOf(lines.size, budget)
                val preview = lines.take(take).joinToString("\n")
                val truncated = if (lines.size > take) " (showing first $take of ${lines.size} lines)" else ""
                totalLines += take
                totalMatchedFiles += 1
                "[$label$truncated]\n$preview"
            } else {
                // Keyword search with ±2 context window
                val lines = content.lines()
                val matchedRanges = mutableListOf<IntRange>()

                for (i in lines.indices) {
                    val windowStart = maxOf(0, i - 2)
                    val windowEnd = minOf(lines.size - 1, i + 2)
                    val windowText = lines.subList(windowStart, windowEnd + 1)
                        .joinToString(" ").lowercase()

                    if (keywordList.all { windowText.contains(it) }) {
                        matchedRanges.add(windowStart..windowEnd)
                    }
                }

                if (matchedRanges.isEmpty()) {
                    null
                } else {
                    totalMatchedFiles += 1
                    val merged = mergeRanges(matchedRanges)
                    val fileMatches = mutableListOf<String>()

                    for (range in merged) {
                        val chunkLines = range.last - range.first + 1
                        if (totalLines + chunkLines > lineCap) {
                            val remaining = lineCap - totalLines
                            if (remaining > 0) {
                                fileMatches.add(lines.subList(range.first, range.first + remaining).joinToString("\n"))
                                totalLines += remaining
                            }
                            break
                        }
                        fileMatches.add(lines.subList(range.first, range.last + 1).joinToString("\n"))
                        totalLines += chunkLines
                    }

                    if (fileMatches.isNotEmpty())
                        "[$label — ${fileMatches.size} match(es)]\n${fileMatches.joinToString("\n---\n")}"
                    else null
                }
            }

            if (entry != null) {
                results.add(entry)
                includedFiles += 1
                // Byte accounting is conservative: count the entry itself
                // PLUS the "\n\n" separator between entries (added at the
                // joinToString tail). We break AFTER appending so a single
                // large entry never gets silently dropped — the cap acts as
                // a "this was the last one we'll show" gate, not a guillotine
                // on the current entry.
                totalBytes += entry.toByteArray(Charsets.UTF_8).size + 2
                if (totalBytes >= MAX_OUTPUT_BYTES) byteCapHit = true
            }
        }

        if (results.isEmpty()) {
            return "No matches found for keywords: ${keywordList.joinToString(", ")}"
        }

        // Compose the truncation note. Line-cap and byte-cap can both fire;
        // include whichever applies. Counts use "files" because the loop is
        // file-by-file — for the agent the distinction between "matched file"
        // and "matched entry" is fine here, the keyword scope is unambiguous.
        val notes = mutableListOf<String>()
        if (byteCapHit && includedFiles < totalMatchedFiles) {
            val totalKb = totalBytes / 1024
            notes.add(
                "[Truncated: $totalMatchedFiles file(s) matched, showing first " +
                    "$includedFiles, ~${totalKb}KB. Use more specific keywords " +
                    "to narrow results.]",
            )
        } else if (byteCapHit) {
            val totalKb = totalBytes / 1024
            notes.add("[Truncated at ${MAX_OUTPUT_BYTES / 1024}KB byte cap (~${totalKb}KB returned).]")
        }
        if (totalLines >= lineCap) {
            notes.add("[Output truncated at $lineCap lines]")
        }
        val truncatedNote = if (notes.isNotEmpty()) "\n\n" + notes.joinToString("\n") else ""
        return results.joinToString("\n\n") + truncatedNote
    }

    // -- System Prompt Fragment --

    /**
     * Builds the `<memory>` XML fragment for system prompt injection.
     * Includes GLOBAL.md + up to 3 most recent daily logs (today, yesterday, etc.)
     * Loaders delegate to [MemoryFragmentBuilder]; every fragment is wrapped
     * in a [MemoryAuthority] envelope (useAs + instructionAuthority + boundary)
     * before reaching the prompt.
     */
    fun loadGlobalMemoryFragment(sessionScoped: Boolean = false): String? =
        fragments.loadGlobalMemoryFragment(sessionScoped)

    /**
     * [T-memory-instruction-dedupe] Raw GLOBAL.md body, for cross-scope dedupe.
     * See [MemoryFragmentBuilder.loadGlobalMemoryBody].
     */
    fun loadGlobalMemoryBody(sessionScoped: Boolean = false): String? =
        fragments.loadGlobalMemoryBody(sessionScoped)

    fun loadLearnedPrefsFragment(scene: SceneTag = SceneTag.GENERAL): String? =
        fragments.loadLearnedPrefsFragment(scene)

    internal fun loadRecentDailyFragments(): List<MemoryAssembly.DailyFragment> =
        fragments.loadRecentDailyFragments()

    // -- File Management (for Settings UI) --

    data class MemoryFileInfo(
        val name: String,
        val isGlobal: Boolean,
        val modifiedDate: String,
        val fileSize: String,
        val preview: String,
    )

    /**
     * List all memory files: GLOBAL.md first, then daily logs descending.
     */
    fun listAllFiles(): List<MemoryFileInfo> {
        val items = mutableListOf<MemoryFileInfo>()

        // GLOBAL.md always first
        val globalFile = File(memoryDir, GLOBAL_FILE)
        val globalContent = if (globalFile.exists()) try { globalFile.readText() } catch (_: Exception) { "" } else ""
        val globalModDate = if (globalFile.exists()) IsoTime.formatMinutes(globalFile.lastModified()) else ""
        items.add(MemoryFileInfo(
            name = GLOBAL_FILE,
            isGlobal = true,
            modifiedDate = globalModDate,
            fileSize = formatFileSize(globalFile.length()),
            preview = firstContentLine(globalContent),
        ))

        // Daily logs sorted descending
        val dailyFiles = memoryDir.listFiles()
            ?.filter { it.extension == "md" && it.name != GLOBAL_FILE && it.name != LearnedPrefsStore.FILE_NAME }
            ?.sortedByDescending { it.name }
            ?: emptyList()

        for (file in dailyFiles) {
            val content = try { file.readText() } catch (_: Exception) { "" }
            items.add(MemoryFileInfo(
                name = file.name,
                isGlobal = false,
                modifiedDate = IsoTime.formatMinutes(file.lastModified()),
                fileSize = formatFileSize(file.length()),
                preview = firstContentLine(content),
            ))
        }

        return items
    }

    fun loadGlobalMd(): String {
        val file = File(memoryDir, GLOBAL_FILE)
        return if (file.exists()) try { file.readText() } catch (_: Exception) { "" } else ""
    }


    fun saveGlobalMd(content: String) {
        writeTextAtomic(File(memoryDir, GLOBAL_FILE), content)
    }

    fun readFile(name: String): String {
        val file = File(memoryDir, name)
        return if (file.exists()) try { file.readText() } catch (_: Exception) { "" } else ""
    }

    fun saveFile(name: String, content: String) {
        if (name == LearnedPrefsStore.FILE_NAME) return
        writeTextAtomic(File(memoryDir, name), content)
    }

    fun deleteFile(name: String): Boolean {
        if (name == GLOBAL_FILE) return false // Cannot delete GLOBAL.md
        if (name == LearnedPrefsStore.FILE_NAME) return false
        return File(memoryDir, name).delete()
    }

    // -- Entry-level operations (used by Session Memory revoke/edit) --

    /**
     * Result of [revokeEntry] / [replaceEntryBody]. Mirrors iOS
     * `revokeEntry` / `replaceEntryInLog` return shapes.
     */
    sealed class EntryMutationResult {
        /** Entry found in [dateStr].md and the requested mutation succeeded. */
        data class Success(val dateStr: String) : EntryMutationResult()

        /** Scanned today + yesterday but the body never matched. */
        data object NotFound : EntryMutationResult()

        /** Match found but writing the new file content failed. */
        data class IOError(val message: String) : EntryMutationResult()
    }

    /**
     * Remove a memory_write entry whose body matches [writtenContent] from
     * today's or yesterday's daily log. Mirrors iOS
     * `MemoryWriteDetailView.revokeEntry()`.
     *
     * Each entry on disk is `<!-- YYYY-MM-DD HH:mm:ss -->\n{body}\n\n`. The
     * comment marker is the canonical entry boundary; we split on it via
     * regex, locate the entry whose trimmed body equals the trimmed
     * [writtenContent], then erase the entire range (marker + body +
     * trailing whitespace).
     *
     * Scope is intentionally limited to today + yesterday to match iOS — older
     * entries are presumed already syndicated into the model's longer-term
     * memory and shouldn't be silently mutated by an undo button.
     */
    fun revokeEntry(writtenContent: String): EntryMutationResult {
        val trimmedTarget = writtenContent.trim()
        val candidates = candidateDateStrings()
        val markerRegex = Regex("""<!-- \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} -->\n""")

        for (dateStr in candidates) {
            val file = File(memoryDir, "$dateStr.md")
            if (!file.exists()) continue
            val content = try { file.readText() } catch (_: Exception) { continue }

            val matches = markerRegex.findAll(content).toList()
            if (matches.isEmpty()) continue

            for ((i, match) in matches.withIndex()) {
                val bodyStart = match.range.last + 1
                val entryEnd = matches.getOrNull(i + 1)?.range?.first ?: content.length
                val body = content.substring(bodyStart, entryEnd)
                if (body.trim() != trimmedTarget) continue

                val newContent = content.removeRange(match.range.first, entryEnd)
                return try {
                    writeTextAtomic(file, newContent)
                    Log.i(TAG, "Revoked memory entry from $dateStr.md")
                    EntryMutationResult.Success(dateStr)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write $dateStr.md after revoke", e)
                    EntryMutationResult.IOError(e.message ?: "Unknown I/O error")
                }
            }
        }
        return EntryMutationResult.NotFound
    }

    /**
     * Replace the body of an existing memory_write entry whose body matches
     * [oldContent], substituting [newContent]. Same scoping/matching rules as
     * [revokeEntry]. Mirrors iOS `MemoryWriteDetailView.replaceEntryInLog()`.
     */
    fun replaceEntryBody(oldContent: String, newContent: String): EntryMutationResult {
        val trimmedOld = oldContent.trim()
        val trimmedNew = newContent.trim()
        val candidates = candidateDateStrings()
        val markerRegex = Regex("""<!-- \d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2} -->\n""")

        for (dateStr in candidates) {
            val file = File(memoryDir, "$dateStr.md")
            if (!file.exists()) continue
            val content = try { file.readText() } catch (_: Exception) { continue }

            val matches = markerRegex.findAll(content).toList()
            if (matches.isEmpty()) continue

            for ((i, match) in matches.withIndex()) {
                val bodyStart = match.range.last + 1
                val entryEnd = matches.getOrNull(i + 1)?.range?.first ?: content.length
                val body = content.substring(bodyStart, entryEnd)
                if (body.trim() != trimmedOld) continue

                // iOS replaces with `trimmed + "\n\n"` so the on-disk
                // separator between entries stays uniform.
                val replacement = "$trimmedNew\n\n"
                val newFileContent = content.replaceRange(bodyStart, entryEnd, replacement)
                return try {
                    writeTextAtomic(file, newFileContent)
                    Log.i(TAG, "Replaced memory entry body in $dateStr.md")
                    EntryMutationResult.Success(dateStr)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to write $dateStr.md after edit", e)
                    EntryMutationResult.IOError(e.message ?: "Unknown I/O error")
                }
            }
        }
        return EntryMutationResult.NotFound
    }

    private fun candidateDateStrings(): List<String> {
        val now = System.currentTimeMillis()
        return listOf(IsoTime.formatLocalDate(now), IsoTime.formatLocalDate(now - 86400_000L))
    }

    // -- Internal --

    private fun formatFileSize(bytes: Long): String {
        if (bytes < 1024) return "$bytes B"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        return "%.1f MB".format(mb)
    }

    private fun firstContentLine(content: String): String {
        return content.lines()
            .firstOrNull { it.isNotBlank() && !it.startsWith("<!--") }
            ?.take(100)
            ?: ""
    }

    private fun mergeRanges(ranges: List<IntRange>): List<IntRange> {
        if (ranges.isEmpty()) return emptyList()
        val sorted = ranges.sortedBy { it.first }
        val result = mutableListOf(sorted[0])
        for (i in 1 until sorted.size) {
            val last = result.last()
            val current = sorted[i]
            if (current.first <= last.last + 1) {
                result[result.lastIndex] = last.first..maxOf(last.last, current.last)
            } else {
                result.add(current)
            }
        }
        return result
    }
}

/**
 * Atomic write: `.tmp` sibling plus rename, with a copy fallback.
 *
 * Memory files are read on every system-prompt build, including while a
 * memory_write or a Settings save is in flight. A plain writeText leaves a
 * window in which a concurrent reader sees a truncated or empty file — and
 * the readers here treat empty as "nothing to inject", so one unlucky turn
 * silently dropped the user's global rules or diary entry. Rename is atomic
 * on every filesystem this app writes to.
 *
 * Delegates to [AtomicFileWrite] (per-path lock, short-write detection, post-write re-read verify);
 * its copy-over covers the old rename-failed writeText fallback, null surfaces as the same IOException.
 *
 * Top-level rather than a member so the companion-object seeding path and the
 * instance save paths share one implementation.
 */
internal fun writeTextAtomic(file: File, content: String) {
    if (AtomicFileWrite.write(file, content, temp = File(file.parentFile, "${file.name}.tmp")) == null) {
        throw IOException("atomic write failed for ${file.name}")
    }
}

/**
 * Read with one retry, warning when both attempts fail.
 *
 * A single failed read used to collapse to "" and then to "no fragment", i.e.
 * the source vanished from the system prompt with no trace in any log — the
 * intermittent "persona / global rules stopped applying" report. The retry
 * absorbs transient IO failures; the warning makes a persistent one visible
 * instead of invisible.
 */
internal fun readTextResilient(file: File, what: String): String? {
    var last: Exception? = null
    for (attempt in 0..1) {
        try {
            return file.readText()
        } catch (e: Exception) {
            last = e
            if (attempt == 0) Thread.sleep(20)
        }
    }
    Log.w("MemoryRepository", "$what: read failed twice for ${file.name}: ${last?.message}")
    return null
}
