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
import com.openminis.app.util.SecretMasking

/**
 * Manages the memory directory (`minis-global/memory/`).
 * Mirrors iOS memory system:
 *   - GLOBAL.md: read-only for agent, user-maintained via Settings
 *   - YYYY-MM-DD.md: daily logs with timestamped entries, agent writes via memory_write
 *   - memory_get: fuzzy keyword search across files
 *   - loadGlobalMemoryFragment() / loadRecentDailyFragments(): emit the
 *     global and per-day daily-log blocks for the system prompt
 */
class MemoryRepository(
    private val memoryDir: File,
    /** [T-memory-shared-promotion] App-wide mirror: host writes are copied here
     *  so a NEW chat's prompt can see what earlier chats learned. Null keeps
     *  the old per-session behaviour (used by tests and non-chat callers).
     *  Position matters: it MUST sit before [clock] so the many
     *  `MemoryRepository(dir) { time }` trailing-lambda call sites keep
     *  binding the lambda to the clock. */
    private val sharedDir: File? = null,
    private val clock: () -> Long = System::currentTimeMillis,
) {

    val learnedPrefs = LearnedPrefsStore(File(memoryDir, LearnedPrefsStore.FILE_NAME))

    // ── [T-prompt-cache] Fragment builder ─────────────────────────
    // GLOBAL.md + daily-log fragments build inside MemoryFragmentBuilder
    // (architecture ratchet extraction). The memoized caches live there;
    // the repository keeps this lazy handle and delegates its three public
    // loaders. Callers get a memoized repository via
    // ChatViewModel.sessionMemoryRepo(), so the cache survives across turns.
    private val fragments: MemoryFragmentBuilder by lazy {
        MemoryFragmentBuilder(memoryDir, learnedPrefs, ::fileFingerprint, clock)
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

        /** Cross-instance lock: every session has its own repository, but all
         *  of them append to the same shared day file. */
        val SHARED_LOCK = Any()

        /** Per-day mirror ceiling (256 KB ≈ 2.5K typical entries). */
        const val MAX_SHARED_DAILY_BYTES = 256 * 1024L

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
         * exist yet. This startup hook also recovers interrupted diary restores,
         * including when the existing GLOBAL.md needs no seeding.
         */
        fun ensureGlobalExists(context: android.content.Context) {
            MemoryRestoreJournal.recoverInterrupted(context.filesDir)
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
        // get MAX_INJECT_LINES_OLDER. 120 + 6*40 lines is roughly 36-40K chars
        // at the ~100 chars/line measured on real logs, sized so the whole
        // 7-day window fits inside MemoryPromptBudget.DEFAULT_BUDGET_CHARS
        // next to the standing fragments. The old single 200-line cap (an
        // iOS mirror) let three files alone ask for ~60K, so the budget
        // silently truncated the block — the real cause of "the model only
        // remembers two days".
        internal const val MAX_INJECT_LINES = 120
        internal const val MAX_INJECT_LINES_OLDER = 40
        // memory_get full-dump (no keywords): cap at 500 lines — matches iOS
        // `maxTotalLines = 500` in AIChatViewModel+MemoryTools.swift.
        private const val MAX_DUMP_LINES = 500
        // memory_get keyword search: cap at 60 lines. iOS caps at 60 *entries*
        // (timestamp-delimited memory_write blocks); Android's keyword search
        // is line-based with ±2 context windows, so we keep the same algorithm
        // and align on the 60 magnitude as the line budget.
        private const val MAX_SEARCH_LINES = 60
        internal const val MAX_LOOKBACK_DAYS = 60
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
        const val MAX_DAILY_ENTRIES = 100

        /** writeMemory 的落盘格式：行首时间戳标记 + 正文 + 空行。行首锚定计数，
         *  正文里出现同形字符串不会被误计为一条记忆。 */
        private val ENTRY_MARK = MemoryDiaryFormat.marker

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

    // -- memory_write --

    /**
     * Append a timestamped entry to today's daily log.
     * New entries are prepended (newest first).
     *
     * [T-memory-revision] [expectedRevision] 启用并发写入冲突检测（Eta 式 SHA-256）：
     * 非空时先校验现有文件内容哈希，不匹配返回 MEMORY_CONFLICT，不覆盖并发更新。
     * 写入成功返回新 revision（哈希前 16 位）。
     */
    /** File-level schema fields apply only when creating a day file. */
    fun writeMemory(
        content: String,
        expectedRevision: String? = null,
        type: String? = null,
        scope: String? = null,
        ttlDays: Int? = null,
    ): String = writeEntry(
        content, expectedRevision, null, null, autoArchive = false, pin = false,
        initialHeader = MemoryMetadataParser.frontmatterFor(type = type ?: "daily", scope = scope, ttlDays = ttlDays),
    )

    /** Only host tool/sedimentation writes enroll entries, including durable notes for usage tracking. */
    internal fun writeHostMemory(
        content: String,
        expectedRevision: String? = null,
        origin: MemoryEntryOrigin,
        ttlDays: Int? = null,
        autoArchive: Boolean,
        pin: Boolean = false,
    ): String {
        if (ttlDays != null && (!autoArchive || ttlDays !in 1..MemoryLifecycle.MAX_TTL_DAYS)) return "Error: Invalid ttl_days"
        val result = writeEntry(content, expectedRevision, origin, ttlDays, autoArchive, pin,
            initialHeader = MemoryMetadataParser.frontmatterFor(type = "daily"),
        )
        if (result.startsWith("Memory saved")) mirrorToShared(content)
        return result
    }

    /** [T-memory-shared-promotion] Best-effort copy into minis-global/memory.
     *  Never throws into the diary transaction: a failed mirror must not lose
     *  the session copy that already landed. Dedup is byte-exact on the body.
     *  Secrets are masked here too — the shared file is readable by every
     *  chat's prompt, so it must never carry a credential the session copy
     *  was allowed to keep. Growth is capped per day file: a runaway
     *  memory_write loop must not fill the app's data partition. */
    private fun mirrorToShared(content: String) {
        val dir = sharedDir ?: return
        val masked = SecretMasking.mask(content.trim())
        if (masked.isEmpty()) return
        runCatching {
            synchronized(SHARED_LOCK) {
                dir.mkdirs()
                val now = clock()
                val file = File(dir, "${IsoTime.formatLocalDate(now)}.md")
                if (file.isFile && file.length() > MAX_SHARED_DAILY_BYTES) return@synchronized
                val existing = if (file.isFile) file.readText() else ""
                if (existing.contains(masked)) return@synchronized
                val header = if (existing.isBlank()) "---\ntype: daily\n---\n" else ""
                file.appendText(header + "<!-- ${IsoTime.formatLocalSeconds(now)} -->\n" + masked + "\n\n")
            }
        }
    }

    private fun writeEntry(
        content: String,
        expectedRevision: String?,
        origin: MemoryEntryOrigin?,
        ttlDays: Int?,
        autoArchive: Boolean,
        pin: Boolean,
        initialHeader: String,
    ): String {
        if (content.isBlank()) return "Error: Missing required 'content' parameter"
        val contentBytes = content.toByteArray(Charsets.UTF_8).size
        if (contentBytes > MAX_ENTRY_BYTES) {
            return "Error: 记忆条目过长（$contentBytes 字节，上限 $MAX_ENTRY_BYTES）。请精简为要点再写。"
        }
        if (ENTRY_MARK.containsMatchIn(content)) {
            return "Error: 条目正文不能包含时间戳标记行（<!-- YYYY-MM-DD HH:mm:ss --> 是落盘格式保留字）。请改写后再写；确需保留示例时，在标记行行首缩进一个空格即可。"
        }
        val now = clock()
        val fileName = "${IsoTime.formatLocalDate(now)}.md"
        val timestamp = IsoTime.formatLocalSeconds(now)
        val normalizedNew = normalizeEntry(content)
        return try {
            MemoryLifecycle.locked(memoryDir) { dir ->
                MemoryRestoreJournal.recover(dir, fileName)
                val file = MemoryLifecycle.child(dir, fileName) ?: throw IOException("invalid diary path")
                // Share the file-tool lock for the whole transaction. Its strict UTF-8 read
                // protects handwritten bytes; the actual write is a same-directory atomic move.
                AtomicFileWrite.readModifyWrite(file) write@{ text ->
                    val existing = if (hasUtf8Bom(file)) "\uFEFF$text" else text
                    if (expectedRevision != null) {
                        // [T-memory-revision-day-scoped] Day tag first, hash second; the
                        // classification lives in MemoryRevision, the wording in the guard.
                        MemoryWriteRevisionGuard.refusal(
                            expectedRevision = expectedRevision,
                            fileName = fileName,
                            dayFileExists = existing.isNotEmpty(),
                            hashOf = { Digest.sha256Hex(it) },
                            existingContent = existing,
                        )?.let { return@write WriteOutcome(it) }
                    }
                    val header = MemoryMetadataParser.header(existing)
                    val bodyStart = header?.bodyStart ?: if (existing.startsWith('\uFEFF')) 1 else 0
                    val body = existing.substring(bodyStart)
                    val blocks = MemoryDiaryFormat.blocks(body)
                    val diary = if (blocks.any { it.id != null }) MemoryLifecycle.read(dir, fileName) else null
                    val exactManaged = diary?.managed()?.firstOrNull { normalizeEntry(it.body) == normalizedNew }
                    if (exactManaged != null || body.isNotBlank() && ENTRY_MARK.split(body).any { normalizeEntry(it) == normalizedNew }) {
                        if ((pin || origin == MemoryEntryOrigin.MEMORY_WRITE && !autoArchive) && exactManaged != null &&
                            MemoryLifecycle.pin(dir, fileName, exactManaged.ref.id, now)
                        ) {
                            indexForEmbeddingsAsync(file)
                            return@write WriteOutcome("Memory saved: existing entry kept as a durable note in $fileName")
                        }
                        return@write WriteOutcome("Skipped: 今日日志已存在逐字相同的条目（写入侧去重），未重复追加。")
                    }
                    if (blocks.size >= MAX_DAILY_ENTRIES) {
                        return@write WriteOutcome("Error: 今日记忆已达上限（$MAX_DAILY_ENTRIES 条）。请精简而不是继续追加。")
                    }
                    val id = origin?.let { MemoryLifecycle.newId() }
                    val marker = "<!-- $timestamp -->" + if (id == null) "" else " <!-- minis-memory:$id -->"
                    val entry = "$marker\n$content\n\n"
                    if (id != null) {
                        MemoryLifecycle.enroll(dir, fileName, id, entry, now, ttlDays, origin, autoArchive)
                    }
                    val prefix = existing.substring(0, bodyStart)
                    val headerForNewFile = if (!file.exists()) initialHeader else ""
                    // Keep the original BOM, header, keys and line endings exactly as read.
                    val separator = if (header != null && !prefix.endsWith('\n') && !prefix.endsWith('\r')) "\n" else ""
                    val newContent = prefix + separator + headerForNewFile + entry + body
                    writeLifecycleAtomic(file, newContent)
                    val newRevision = Digest.sha256Hex(newContent).take(16)
                    Log.i(TAG, "Memory written to $fileName (${content.length} chars, revision $newRevision)")
                    indexForEmbeddingsAsync(file)
                    WriteOutcome("Memory saved to $fileName (${content.length} chars, revision $newRevision)")
                }?.message ?: "Error writing memory: cannot read existing diary safely"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write memory", e)
            "Error writing memory: ${e.message}"
        }
    }

    private data class WriteOutcome(val message: String)

    private fun normalizeEntry(text: String): String = text.lines().joinToString("\n") { it.trimEnd() }.trim()

    private fun hasUtf8Bom(file: File): Boolean = file.isFile && file.inputStream().use {
        it.read() == 0xEF && it.read() == 0xBB && it.read() == 0xBF
    }

    internal fun todayFileName(): String = "${IsoTime.formatLocalDate(clock())}.md"

    /** [T-memory-revision] 今日日志文件当前 revision（不存在返回 null）。 */
    fun currentDailyRevision(): String? = MemoryLifecycle.locked(memoryDir) { dir ->
        val fileName = todayFileName()
        val file = MemoryLifecycle.snapshotFiles(dir, fileName).diary ?: return@locked null
        file.inputStream().use { stream ->
            MemoryRevision.token(fileName.removeSuffix(".md"), stream.readBytes().toString(Charsets.UTF_8))
        }
    }

    // -- memory_get --

    /**
     * Fuzzy keyword search across memory files.
     * @param keywords space-separated, case-insensitive, ALL must match
     * @param scope "daily" (logs only) or "all" (include GLOBAL.md)
     * @return search results with context lines
     */
    fun getMemory(keywords: String, scope: String): String {
        if (scope == "archive") return getArchivedMemory(keywords)
        val now = clock()
        val keywordList = keywords.trim()
            .lowercase()
            .split(Regex("\\s+"))
            .filter { it.isNotEmpty() }

        return MemorySources.withScope(memoryDir, now, includeStanding = true) { sources ->
            val files = sources.filter { source ->
                source.name != LearnedPrefsStore.FILE_NAME && (source.name != GLOBAL_FILE || scope == "all")
            }.sortedWith(compareByDescending<MemorySources.Source> { it.name == GLOBAL_FILE }.thenByDescending { it.name })
            searchMemory(files, keywordList)
        } ?: "No memory files found."
    }

    private fun searchMemory(files: List<MemorySources.Source>, keywordList: List<String>): String {
        if (files.isEmpty()) return "No memory files found."
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

        for (file in files) {
            if (totalLines >= lineCap || byteCapHit) break
            val label = file.name
            val content = file.view()?.text ?: continue
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

    private fun getArchivedMemory(keywords: String): String {
        val result = StringBuilder()
        var bytes = 0
        for ((token, body, status) in MemoryDiaryManagement.archive(memoryDir, clock(), keywords)) {
            val section = "[${status.name.lowercase()} · entry_id=$token]\n$body\n\n"
            bytes += section.toByteArray(Charsets.UTF_8).size
            if (bytes > MAX_OUTPUT_BYTES) return result.append("[Archive output truncated; use more specific keywords.]").toString()
            result.append(section)
        }
        return result.toString().trimEnd().ifEmpty { "No expired or archived entries matched." }
    }

    internal fun archivedEntries(query: String, limit: Int): List<MemoryDiaryManagement.ArchiveEntry> =
        MemoryDiaryManagement.archive(memoryDir, clock(), query, limit).toList()

    /** Entry tokens are relative to this repository, and cannot select another chat or filesystem path. */
    internal fun restoreArchivedMemory(token: String): Boolean {
        val (date, id) = token.split(':', limit = 2).takeIf { it.size == 2 } ?: return false
        if (!MemoryDiaryFormat.id.matches(id)) return false
        val source = "$date.md"
        if (!MemoryDiaryFormat.dateFile.matches(source)) return false
        val restored = MemoryLifecycle.restore(memoryDir, source, id, clock())
        if (restored) indexForEmbeddingsAsync(File(memoryDir, source), invalidate = false)
        return restored
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

    /**
     * [T-daily-recall-gate] The budgeted, relevance-gated daily window. See
     * [MemoryFragmentBuilder.loadRecentDailyFragments] — [recallQuery] is the
     * caller's live-recall fold (null falls back to the DIAG_TASK_KEYWORDS
     * vocabulary inside the gate).
     */
    internal fun loadRecentDailyFragments(
        recallQuery: String? = null,
        now: Long = clock(),
        log: Boolean = true,
    ): MemoryFragmentBuilder.DailyInjection = fragments.loadRecentDailyFragments(recallQuery, now, log)

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
            val content = readFile(file.name)
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
        if (MemoryDiaryFormat.dateFile.matches(name)) return MemoryLifecycle.read(memoryDir, name)?.text.orEmpty()
        val file = File(memoryDir, name)
        return if (file.exists()) try { file.readText() } catch (_: Exception) { "" } else ""
    }

    fun saveFile(name: String, content: String) {
        if (name == LearnedPrefsStore.FILE_NAME) return
        val file = File(memoryDir, name)
        withWritableMemoryFile(name) { writeTextAtomic(it, content) }
        indexForEmbeddingsAsync(file)
    }

    private fun <T> withWritableMemoryFile(name: String, mutate: (File) -> T): T {
        if (!MemoryDiaryFormat.dateFile.matches(name)) return mutate(File(memoryDir, name))
        return MemoryLifecycle.locked(memoryDir) { dir ->
            MemoryRestoreJournal.recover(dir, name)
            mutate(MemoryLifecycle.child(dir, name) ?: throw IOException("Invalid diary path"))
        }
    }

    fun deleteFile(name: String): Boolean {
        if (name == GLOBAL_FILE || name == LearnedPrefsStore.FILE_NAME) return false
        val deleted = runCatching {
            if (!MemoryDiaryFormat.dateFile.matches(name)) {
                withWritableMemoryFile(name) { it.delete() }
            } else {
                // [P3-7] The lifecycle sidecar must go WITH the diary: it
                // otherwise outlives the file, so the archive/restore
                // listings keep seeing the entry and a recreated file with
                // the same name would inherit stale lifecycle state.
                MemoryLifecycle.locked(memoryDir) { dir ->
                    MemoryRestoreJournal.recover(dir, name)
                    val removed = MemoryLifecycle.child(dir, name)?.takeIf { it.isFile }?.delete() == true
                    MemoryLifecycle.sidecar(dir, name)?.delete()
                    removed
                }
            }
        }.getOrDefault(false)
        if (deleted) {
            MemoryLifecycle.invalidateRecall()
            // [#27] Vectors outlive the file otherwise — a deleted diary
            // would stay semantically recallable, and there is no file left
            // for a re-index to drop the rows.
            com.openminis.app.data.memory.MemoryEmbeddingRuntime.indexerOrNull()
                ?.deleteSourceAsync(memoryDir, name)
        }
        return deleted
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

    /** Explicit revoke still applies only to today/yesterday. */
    fun revokeEntry(writtenContent: String): EntryMutationResult = editRecent(writtenContent, null)

    /** A user edit promotes the revised body out of automatic lifecycle management. */
    fun replaceEntryBody(oldContent: String, newContent: String): EntryMutationResult = editRecent(oldContent, newContent)

    private fun editRecent(original: String, replacement: String?): EntryMutationResult =
        MemoryDiaryManagement.editRecent(memoryDir, clock(), original, replacement).also {
            if (it is EntryMutationResult.Success) indexForEmbeddingsAsync(File(memoryDir, "${it.dateStr}.md"))
        }

    // -- Internal --

    /**
     * [T-memory-embedding-recall] Fire-and-forget (re)index of a memory file
     * for semantic recall (#27). No-op unless the embedding runtime is
     * attached (prefs default OFF — this hook costs one volatile read).
     * Deliberately NOT called for GLOBAL.md: the recall engine excludes it as
     * a candidate (its full text rides in the system prompt), so its vectors
     * would never be read.
     */
    private fun indexForEmbeddingsAsync(file: File, invalidate: Boolean = true) {
        if (file.name == GLOBAL_FILE) return
        if (invalidate) MemoryLifecycle.invalidateRecall()
        runCatching {
            com.openminis.app.data.memory.MemoryEmbeddingRuntime.indexerOrNull()?.indexFileAsync(file)
        }
    }

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
