package com.openminis.app.backup

import android.content.Context
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.db.FolderEntity
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import com.openminis.app.util.IsoTime

/**
 * Restores a `.minisbak` package on Android (backup-restore design §8),
 * mirroring `src/ios/Agent/Backup/BackupImporter.swift`.
 *
 * Scope: **Merge mode** (§8.2's default) — match by id, newer `updatedAt` wins.
 * Replace / Skip-existing are stage 5.
 *
 * Flow (§8.1): read manifest → downgrade guard → unlock → integrity →
 * per-category import → report. A category that throws is reported as failed
 * and the rest continue, per §8.3's transaction boundary: a restore that got
 * five of six categories in is meaningfully different from one that got none.
 */
class BackupImporter(
    private val context: Context,
    private val db: AppDatabase,
) {

    data class Options(
        /** null = every category present in the package. */
        val categories: Set<BackupCategory>? = null,
        /**
         * Skip the integrity pass. Diagnostics only — a normal restore must
         * verify, or a truncated package is applied half-way before anything
         * notices.
         */
        val skipIntegrityCheck: Boolean = false,
        /** Required when the package declares `encryption`; ignored otherwise. */
        val passphrase: String? = null,
    )

    data class CategoryReport(
        val category: String,
        var imported: Int = 0,
        var updated: Int = 0,
        var skipped: Int = 0,
        var unreadable: Int = 0,
        var filesWritten: Int = 0,
        var bytesWritten: Long = 0,
        var sizeSkippedInPackage: Int = 0,
        var notDownloadedInPackage: Int = 0,
        var missingBlobs: Int = 0,
        var rejectedPaths: Int = 0,
        /**
         * Providers only: credentials actually WRITTEN to this device, and
         * credentials that were in the package but KEPT because a value already
         * existed locally. These drive the restore-complete credentials message
         * (iOS parity, fix 93cad55ae): restored>0 → "restored N"; else kept>0 →
         * "existing kept"; else → "no keys in package".
         */
        var credentialsRestored: Int = 0,
        var credentialsKept: Int = 0,
        var failed: String? = null,
    )

    /**
     * [T-android-restore-progress-counts] Which category is being written, and
     * how far into it we are.
     *
     * [done] counts records handled so far; [total] is what the manifest said
     * the category holds, or null when it did not say. Both are needed for an
     * honest "chats 1200/2350" — a bare running count on a long category tells
     * the user it is moving but not whether it is nearly finished.
     *
     * The category is passed as the raw key rather than a formatted sentence so
     * the UI can localise it; the importer has no business writing user-facing
     * English.
     */
    data class Progress(
        val categoryKey: String,
        val done: Int = 0,
        val total: Int? = null,
    )

    data class Report(
        val backupId: String,
        val createdAt: String?,
        val sourcePlatform: String?,
        val categories: MutableList<CategoryReport> = mutableListOf(),
        var integrityChecked: Int = 0,
        var integrityFailed: List<String> = emptyList(),
        var wasEncrypted: Boolean = false,
        val warnings: MutableList<String> = mutableListOf(),
        var durationMillis: Long = 0,
    ) {
        val totalImported: Int get() = categories.sumOf { it.imported }
        val totalUpdated: Int get() = categories.sumOf { it.updated }
        val totalSkipped: Int get() = categories.sumOf { it.skipped }
        val totalMissingBlobs: Int get() = categories.sumOf { it.missingBlobs }
    }

    /**
     * Restore from an already-extracted package directory. Serialised
     * process-wide with export: overlapping restores share mutable
     * destinations no rollback can describe (iOS review I3).
     */
    suspend fun import(
        packageRoot: File,
        options: Options = Options(),
        onProgress: ((String) -> Unit)? = null,
        onCount: ((Progress) -> Unit)? = null,
    ): Report = activityLock.withLock {
        importBody(packageRoot, options, onProgress, onCount)
    }

    private suspend fun importBody(
        extractedRoot: File,
        options: Options,
        onProgress: ((String) -> Unit)?,
        onCount: ((Progress) -> Unit)?,
    ): Report {
        val started = System.currentTimeMillis()
        // iOS wraps the tree in an outer NSFileCoordinator folder: the real
        // root may be one level down.
        val root = BackupZip.packageRoot(extractedRoot)
        val reader = BackupPackageReader(root)
        val manifest = reader.readManifest()

        val report = Report(
            backupId = manifest.backupId,
            createdAt = manifest.createdAt.takeIf { it.isNotEmpty() },
            sourcePlatform = manifest.app.platform,
        )

        // Downgrade guard first: a manifest with its `encryption` block
        // stripped must not read as a successful empty restore.
        reader.assertNoUndeclaredEncryption(manifest)

        var keys: BackupCrypto.Keys? = null
        if (manifest.encryption != null) {
            val passphrase = options.passphrase
            if (passphrase.isNullOrEmpty()) {
                throw BackupException("This backup is encrypted. Enter its passphrase to restore.")
            }
            onProgress?.invoke("Checking passphrase…")
            // unlock() verifies the verifier AND the manifest MAC before any
            // payload is touched.
            keys = reader.unlock(passphrase, manifest)
            report.wasEncrypted = true
        }

        // Decrypted plaintext scratch tree, tracked so the finally below can
        // wipe it — see [T-restore-plaintext-scratch].
        var scratchWork: File? = null

        try {
            if (!options.skipIntegrityCheck) {
                onProgress?.invoke("Verifying integrity…")
                val failures = reader.verifyIntegrity(manifest)
                report.integrityChecked = manifest.integrity.size
                report.integrityFailed = failures
                if (failures.isNotEmpty()) {
                    throw BackupException(
                        "Integrity check failed for ${failures.size} file(s): " +
                            failures.take(3).joinToString(", ")
                    )
                }
            }

            // Decrypt every member in one pass so a wrong-key failure
            // surfaces before any data is written.
            val work = if (keys != null) {
                onProgress?.invoke("Decrypting…")
                decryptMembers(root, keys).also { scratchWork = it }
            } else {
                root
            }

            // [T-restore-plaintext-scratch] A package whose CACHE tree addresses
            // `cache/restore-work/…` would restore back INTO the decrypted
            // scratch tree mid-run (CACHE precedes PROVIDERS/env-vars in ORDER).
            // Refuse it via assertNoScratchCollision rather than clobbering the
            // still-unread secrets.json and chat bodies.

            val fileIndex = readFileIndex(work)
            val wanted = options.categories
                ?: manifest.categories.keys.mapNotNull(BackupCategory::fromKey).toSet()

            val scratch = scratchWork
            if (scratch != null && BackupCategory.CACHE in wanted) {
                assertNoScratchCollision(scratch, fileIndex)
            }

            // Chats writes sessions before the messages that reference them.
            for (category in ORDER.filter { it in wanted }) {
                onProgress?.invoke("Restoring ${category.key}…")
                // The manifest's count for the "/ total" half of the button;
                // absent on an older package → a bare running count.
                val total = manifest.categories[category.key]?.entries?.takeIf { it > 0 }
                onCount?.invoke(Progress(category.key, 0, total))
                val report0: (Int) -> Unit = { done ->
                    onCount?.invoke(Progress(category.key, done, total))
                }
                val categoryReport = try {
                    when (category) {
                        BackupCategory.CHATS -> importChats(work, fileIndex, report0)
                        BackupCategory.SHARED_FILES -> importSharedFiles(work, fileIndex)
                        BackupCategory.SKILLS -> importSkills(work, fileIndex)
                        BackupCategory.ROOTFS -> importTree(
                            work, fileIndex, BackupCategory.ROOTFS,
                            File(context.filesDir, "ubuntu-rootfs"), "rootfs/",
                        )
                        BackupCategory.CACHE -> importTree(
                            work, fileIndex, BackupCategory.CACHE,
                            context.cacheDir, "cache/",
                        )
                        BackupCategory.MEMORY -> importMemory(work)
                        BackupCategory.MCP_SERVERS -> importMcpServers(work)
                        BackupCategory.PROVIDERS -> {
                            // [T-backup-restore-settings] Prefs ride with the
                            // providers category, applied after its data. The
                            // block still yields the providers report — the
                            // when's result type feeds the per-category log.
                            val providersReport = importProviders(work)
                            importSettings(work)
                            providersReport
                        }
                        BackupCategory.ENVIRONMENT_VARIABLES -> importEnvironmentVariables(work)
                        else -> null
                    }
                } catch (e: Exception) {
                    AppLogger.error(TAG, "[Restore] category ${category.key} failed: ${e.message}")
                    CategoryReport(category.key, failed = e.message ?: e.toString())
                }
                categoryReport?.let {
                    report.categories.add(it)
                    // [T-android-restore-logging] Per-category outcome on disk:
                    // the summary only reports totals, so a partial category
                    // once read exactly like a complete one.
                    val declared = total?.toString() ?: "?"
                    AppLogger.info(
                        TAG,
                        "[Restore] ${it.category}: imported=${it.imported} " +
                            "updated=${it.updated} skipped=${it.skipped} " +
                            "unreadable=${it.unreadable} declared=$declared " +
                            "files=${it.filesWritten} missingBlobs=${it.missingBlobs}" +
                            (it.failed?.let { f -> " FAILED=$f" } ?: "")
                    )
                    // A category that wrote materially less than the manifest
                    // promised is the signal that matters.
                    val wrote = it.imported + it.updated
                    if (total != null && wrote < total) {
                        AppLogger.warning(
                            TAG,
                            "[Restore] ${it.category}: SHORT — package declared $total, " +
                                "wrote $wrote (skipped=${it.skipped}, unreadable=${it.unreadable})"
                        )
                    }
                }
            }

            if (report.totalMissingBlobs > 0) {
                // Surfaced while the user still has the source device to
                // re-export from.
                report.warnings.add(
                    "${report.totalMissingBlobs} file(s) were listed in the backup but their " +
                        "content was missing from the package."
                )
            }
            report.durationMillis = System.currentTimeMillis() - started
            AppLogger.info(
                TAG,
                "[Restore] done id=${manifest.backupId} imported=${report.totalImported} " +
                    "updated=${report.totalUpdated} skipped=${report.totalSkipped} " +
                    "in ${report.durationMillis}ms"
            )
            return report
        } finally {
            keys?.destroy()
            // [T-restore-plaintext-scratch] decryptMembers() writes every
            // decrypted member as plaintext under cacheDir/restore-work; wipe
            // the scratch tree on every exit path, failure paths included (a
            // CACHE-category export could otherwise sweep it into a package).
            scratchWork?.let { w ->
                runCatching { w.deleteRecursively() }
                    .onFailure {
                        AppLogger.warning(TAG, "[Restore] could not wipe plaintext scratch tree: ${it.message}")
                    }
            }
        }
    }

    // MARK: - Chats

    private suspend fun importChats(
        root: File,
        fileIndex: List<BackupFileIndexEntry>,
        // Called as messages land — the only category long enough for the
        // count to be readable.
        onCount: ((Int) -> Unit)? = null,
    ): CategoryReport {
        val report = CategoryReport(BackupCategory.CHATS.key)
        val dao = db.chatDao()
        val dataDir = File(root, "data")

        // Content-addressed message bodies first, before rows point at them.
        val bodyRoot = File(context.filesDir, "bodies")
        BackupRestoreFiles.restore(root, fileIndex, BackupCategory.CHATS, bodyRoot) { path ->
            if (!path.startsWith("chats/_bodies/")) null
            else File(bodyRoot, path.removePrefix("chats/_bodies/"))
        }

        // Folders first, so session folderIds resolve on arrival.
        readJsonl(dataDir, "folders") { rec ->
            val f = rec.obj ?: return@readJsonl
            val id = f.str("id") ?: return@readJsonl
            val incomingUpdated = f.millis("updatedAt") ?: 0
            // Merge by the same rule as sessions: an older backup must not undo
            // a rename the user made after taking it.
            val existing = dao.getFolder(id)
            if (existing != null && existing.updatedAt >= incomingUpdated) {
                report.skipped += 1
                return@readJsonl
            }
            dao.insertFolder(
                FolderEntity(
                    id = id,
                    name = f.str("name") ?: "",
                    icon = f.str("icon"),
                    color = f.str("color"),
                    origin = f.str("origin") ?: FolderEntity.ORIGIN_MANUAL,
                    sortIndex = f.int("sortIndex") ?: 0,
                    pinnedAt = f.millis("pinnedAt"),
                    // iOS writes `desc`, Android `description` — accept both.
                    description = f.str("description") ?: f.str("desc"),
                    createdAt = f.millis("createdAt") ?: incomingUpdated,
                    updatedAt = incomingUpdated,
                )
            )
            if (existing == null) report.imported += 1 else report.updated += 1
        }

        // Sessions before messages — a message row needs its parent to exist,
        // and the schema enforces it with a foreign key.
        val restoredSessionIds = mutableSetOf<String>()
        // [XSessionDiag] Restore wall clock, sampled once — the comparison only
        // needs "roughly now", not per-row precision in the hot import path.
        val nowMs = System.currentTimeMillis()
        // [T-android-restore-logging] Raw record counts: a session counted
        // "skipped" (locally newer) is still a legitimate message parent, which
        // the report alone cannot show.
        var sessionsSeen = 0
        readJsonl(dataDir, "sessions") { rec ->
            sessionsSeen += 1
            val envelope = rec.obj ?: return@readJsonl
            // [T-android-restore-ios-session-nesting] iOS nests the session
            // under a "session" key with wrapper fields as SIBLINGS; Android
            // writes the same fields flat. Merge the two levels — inner wins,
            // outer fills the wrapper — so one reader handles both.
            val s = envelope.unwrapNested("session")
            val id = s.str("id")
            if (id == null) {
                report.unreadable += 1
                return@readJsonl
            }
            val incomingUpdated = s.millis("updatedAt") ?: 0
            val existing = dao.getSession(id)
            // Merge (§8.2): newer updatedAt wins, so an older backup cannot
            // silently overwrite post-backup work.
            if (existing != null && existing.updatedAt >= incomingUpdated) {
                report.skipped += 1
                restoredSessionIds.add(id)
                return@readJsonl
            }
            dao.insertSession(
                ChatSessionEntity(
                    id = id,
                    title = s.str("title"),
                    modelId = s.str("modelId") ?: existing?.modelId ?: "",
                    createdAt = s.millis("createdAt") ?: incomingUpdated,
                    updatedAt = incomingUpdated,
                    category = s.str("category"),
                    lastMessage = s.str("lastMessage"),
                    modelBinding = s.str("modelBinding"),
                    source = s.str("source"),
                    memoryEnabled = if (s.bool("memoryEnabled") != false) 1 else 0,
                    pinnedAt = s.millis("pinnedAt"),
                    editCount = s.int("editCount") ?: 0,
                    thinkingOverride = s.str("thinkingOverride"),
                    folderId = s.str("folderId"),
                )
            )
            if (existing == null) report.imported += 1 else report.updated += 1
            restoredSessionIds.add(id)
            // [XSessionDiag] A restored session keeps the BACKUP's updatedAt, so
            // one in use shortly before export lands looking "recently active"
            // and auto launch resumes it. Logged ONLY inside the 15-minute
            // auto-resume window — those are the rows that can produce the
            // symptom; everything else is noise.
            if (nowMs - incomingUpdated < XSESSION_DIAG_AUTO_WINDOW_MS) {
                AppLogger.info(
                    TAG,
                    "[XSessionDiag] restore/session: id=${id.take(8)} " +
                        "title=${s.str("title")?.take(24)} " +
                        "backupUpdatedAt=$incomingUpdated restoreNow=$nowMs " +
                        "ageAtRestoreMs=${nowMs - incomingUpdated} " +
                        "(inside 15min auto-resume window -> can be auto-resumed as 'new chat')",
                )
            }
        }

        AppLogger.info(
            TAG,
            "[Restore] chats: sessions.jsonl held $sessionsSeen record(s); " +
                "${restoredSessionIds.size} usable as message parents"
        )

        // The manifest counts MESSAGES for the chats category, so this is the
        // loop whose progress the button reports.
        var seen = 0
        // Orphans + their distinct sessions: the ratio tells a
        // truncated-sessions story apart from a few dangling rows.
        var orphanedMessages = 0
        val orphanSessionIds = mutableSetOf<String>()
        // Old exports may omit sortOrder or carry the legacy 0 everywhere:
        // allocate a unique monotonic cursor per session so later keyset
        // paging cannot loop over the same sort_order.
        val nextSortBySession = mutableMapOf<String, Int>()
        // Snapshot existing cursors before the synchronous JSONL reader; the
        // per-record callback must remain non-suspending.
        val usedSortBySession = restoredSessionIds.associateWith { id ->
            dao.sortOrders(id).toMutableSet()
        }.toMutableMap()
        fun importedSortOrder(sessionId: String, raw: Int?): Int {
            val used = usedSortBySession.getOrPut(sessionId) { mutableSetOf() }
            var next = nextSortBySession[sessionId] ?: ((used.maxOrNull() ?: -1) + 1)
            val candidate = raw?.takeIf { it >= 0 && it !in used } ?: next
            var assigned = candidate
            while (!used.add(assigned)) assigned = (assigned + 1).coerceAtMost(Int.MAX_VALUE)
            nextSortBySession[sessionId] = (assigned + 1).coerceAtMost(Int.MAX_VALUE)
            return assigned
        }
        readJsonl(dataDir, "messages") { rec ->
            // Every 200 records: at ~50k messages a per-record emit would blur
            // rather than inform.
            if (++seen % 200 == 0) onCount?.invoke(seen)
            val m = rec.obj ?: return@readJsonl
            val id = m.str("id")
            val sessionId = m.str("sessionId")
            if (id == null || sessionId == null) {
                report.unreadable += 1
                return@readJsonl
            }
            // A message whose session was skipped as locally-newer still has a
            // parent row; one whose session is absent entirely violates the
            // foreign key.
            if (dao.getSession(sessionId) == null) {
                // [T-android-restore-logging] The importer's most destructive
                // skip — logged, never silent again.
                orphanedMessages += 1
                orphanSessionIds.add(sessionId)
                report.skipped += 1
                return@readJsonl
            }
            val createdAt = m.millis("createdAt") ?: 0
            val incomingUpdated = m.millis("updatedAt") ?: createdAt
            val existingMessage = dao.getMessage(sessionId, id)
            if (existingMessage != null && (existingMessage.updatedAt ?: existingMessage.createdAt) >= incomingUpdated) {
                report.skipped += 1
                return@readJsonl
            }
            dao.insertMessage(
                MessageEntity(
                    id = id,
                    sessionId = sessionId,
                    role = m.str("role") ?: "user",
                    // Re-serialised from the parsed element, so any part type
                    // this build doesn't model is preserved verbatim.
                    partsJson = (m["parts"]?.toString()) ?: "[]",
                    createdAt = createdAt,
                    tokenUsage = m["tokenUsage"]?.takeIf { it.toString() != "null" }?.toString(),
                    sortOrder = importedSortOrder(sessionId, m.int("sortOrder")),
                    reasoningContent = m.str("reasoningContent"),
                    streamInterruptCount = m.int("streamInterruptCount") ?: 0,
                    updatedAt = incomingUpdated,
                    bodyBytes = m.millis("bodyBytes") ?: 0,
                    bodyRef = m.str("bodyRef"),
                    bodySha = m.str("bodySha"),
                    // errorInfo is device-local (§0.2) and is never restored.
                    errorInfo = null,
                    // [T-token-attribution-snapshot] Absent in older packages —
                    // null then, the "estimated" state the Usage page renders.
                    modelId = m.str("modelId"),
                    modelDisplayName = m.str("modelDisplayName"),
                    providerType = m.str("providerType"),
                    providerInstanceId = m.str("providerInstanceId"),
                )
            )
            report.imported += 1
        }

        if (orphanedMessages > 0) {
            AppLogger.warning(
                TAG,
                "[Restore] chats: dropped $orphanedMessages message(s) whose parent session " +
                    "was not in the database, across ${orphanSessionIds.size} distinct " +
                    "session(s). Sample: " +
                    orphanSessionIds.take(5).joinToString(", ") { it.take(8) }
            )
        }

        readJsonl(dataDir, "compact_markers") { rec ->
            val c = rec.obj ?: return@readJsonl
            val id = c.str("id") ?: return@readJsonl
            val sessionId = c.str("sessionId") ?: return@readJsonl
            if (dao.getSession(sessionId) == null) {
                report.skipped += 1
                return@readJsonl
            }
            // insertCompactMarker is ABORT-on-conflict, so a re-run would throw
            // on rows that already exist. Merge must be idempotent.
            runCatching {
                dao.insertCompactMarker(
                    CompactMarkerEntity(
                        id = id,
                        sessionId = sessionId,
                        summary = c.str("summary") ?: "",
                        firstKeptSortOrder = c.int("firstKeptSortOrder") ?: 0,
                        compactedCount = c.int("compactedCount") ?: 0,
                        createdAt = c.millis("createdAt") ?: 0,
                        uiBoundarySortOrder = c.int("uiBoundarySortOrder"),
                        boundaryMessageId = c.str("boundaryMessageId"),
                        firstKeptMessageId = c.str("firstKeptMessageId"),
                        lastCompactedMessageId = c.str("lastCompactedMessageId"),
                    )
                )
                report.imported += 1
            }.onFailure { report.skipped += 1 }
        }

        // [T-android-restore-preview] Rebuild each restored session's
        // `last_message`. iOS packages carry no such field (0 of 2350 real
        // records had it). Derived, not transported: recompute from the newest
        // message via the same extractTextPreview the live chat path uses.
        for (sid in restoredSessionIds) {
            val parts = dao.lastMessageParts(sid) ?: continue
            val preview = ChatRepository.extractTextPreview(parts) ?: continue
            val s = dao.getSession(sid) ?: continue
            dao.updateLastMessage(sid, preview, s.updatedAt)
        }

        // The session file trees. Containment root is the sessions directory:
        // a path in the index that escapes it is refused outright.
        val sessionsRoot = File(context.filesDir, "minis-sessions")
        val files = BackupRestoreFiles.restore(
            packageRoot = root,
            fileIndex = fileIndex,
            category = BackupCategory.CHATS,
            containmentRoot = sessionsRoot,
        ) { path ->
            // "chats/<sid>/<rest…>"
            val parts = path.split('/')
            if (parts.size < 3 || parts[0] != "chats" || parts[1] == "_bodies") null
            else File(sessionsRoot, parts.drop(1).joinToString("/"))
        }
        applyFileResult(report, files)
        return report
    }

    // MARK: - Shared files / Skills / Memory / MCP

    private fun importSharedFiles(
        root: File,
        fileIndex: List<BackupFileIndexEntry>,
    ): CategoryReport {
        val report = CategoryReport(BackupCategory.SHARED_FILES.key)
        val dest = File(context.filesDir, "minis-global/shared")
        val files = BackupRestoreFiles.restore(
            root, fileIndex, BackupCategory.SHARED_FILES, dest
        ) { path ->
            if (!path.startsWith("shared/")) null
            else File(dest, path.removePrefix("shared/"))
        }
        applyFileResult(report, files)
        // Per §3.2 no meta.db equivalent is needed here: PRoot bind-mounts this
        // directory, so the guest sees the files on the next boot.
        return report
    }

    private fun importSkills(root: File, fileIndex: List<BackupFileIndexEntry>): CategoryReport {
        val report = CategoryReport(BackupCategory.SKILLS.key)
        val dest = File(context.filesDir, "minis-global/skills")
        val files = BackupRestoreFiles.restore(root, fileIndex, BackupCategory.SKILLS, dest) { path ->
            if (!path.startsWith("skills/")) null
            else File(dest, path.removePrefix("skills/"))
        }
        applyFileResult(report, files)
        return report
    }

    private fun importTree(
        root: File,
        fileIndex: List<BackupFileIndexEntry>,
        category: BackupCategory,
        dest: File,
        prefix: String,
    ): CategoryReport {
        val report = CategoryReport(category.key)
        val files = BackupRestoreFiles.restore(root, fileIndex, category, dest) { path ->
            if (!path.startsWith(prefix)) null else File(dest, path.removePrefix(prefix))
        }
        applyFileResult(report, files)
        return report
    }

    private fun importMemory(root: File): CategoryReport {
        val report = CategoryReport(BackupCategory.MEMORY.key)
        val src = File(root, "data/memory")
        if (!src.isDirectory) return report
        val dest = File(context.filesDir, "minis-global/memory").apply { mkdirs() }
        val destRoot = dest.canonicalFile
        for (file in src.walkTopDown().filter { it.isFile }) {
            val rel = file.relativeTo(src).path.replace(File.separatorChar, '/')
            val out = File(dest, rel)
            // Same containment rule as the blob path: `data/memory` names come
            // from inside the package too.
            val parent = out.parentFile?.let { it.mkdirs(); it.canonicalFile }
            if (parent == null || !(parent.path == destRoot.path ||
                    parent.path.startsWith(destRoot.path + File.separator))
            ) {
                report.rejectedPaths += 1
                continue
            }
            file.copyTo(out, overwrite = true)
            report.filesWritten += 1
            report.bytesWritten += file.length()
        }
        return report
    }

    private fun importMcpServers(root: File): CategoryReport {
        val report = CategoryReport(BackupCategory.MCP_SERVERS.key)
        val src = File(root, "data/mcp_servers.json")
        if (!src.isFile) return report
        val dest = File(context.filesDir, "minis-global/mcp-servers/servers.json")
        dest.parentFile?.mkdirs()
        src.copyTo(dest, overwrite = true)
        report.filesWritten = 1
        report.bytesWritten = src.length()
        report.imported = 1
        return report
    }

    // MARK: - Providers / thinking rules / environment variables

    private val app: com.openminis.app.MinisApp?
        get() = context.applicationContext as? com.openminis.app.MinisApp

    /**
     * Restore `data/provider_config.json` via an order-preserving union merge
     * ([ProviderRepository.mergeBackupProviderConfig]), then custom thinking
     * rules from `data/thinking_rules.jsonl`, then credentials from
     * `secrets.json`. Mirrors iOS `importProviders`.
     *
     * Counting (iOS parity, fix 93cad55ae): imported = instances added; skipped
     * = package instances already present (union-by-id, not overwritten) —
     * NEVER reported as "updated". credentialsRestored/Kept come from the
     * secrets restore and drive the UI's credentials message.
     */
    private fun importProviders(root: File): CategoryReport {
        val report = CategoryReport(BackupCategory.PROVIDERS.key)
        val repo = app?.providerRepositoryOrNull ?: run {
            report.failed = "provider repository unavailable"
            return report
        }
        val configFile = File(root, "data/provider_config.json")
        if (!configFile.isFile) return report

        val parsed = try {
            parseProviderConfigLeniently(configFile.readText())
        } catch (e: Exception) {
            report.unreadable += 1
            AppLogger.error(TAG, "[Restore] provider_config.json unreadable: ${e.message}")
            return report
        }
        val config = parsed.config
        // Undecodable instances counted individually, so the report says
        // "1 of 8 unreadable" instead of failing the category.
        report.unreadable += parsed.droppedInstances
        if (parsed.droppedInstances > 0) {
            AppLogger.warning(
                TAG,
                "[Restore] provider_config.json: dropped ${parsed.droppedInstances} " +
                    "undecodable instance(s), kept ${config.instances.size}",
            )
        }

        // 第一个返回值是实际改动数量（替换 + 新增），页面显示它。
        val (changed, _) = repo.mergeBackupProviderConfig(config)
        report.imported = changed
        report.skipped = 0

        // Thinking rules AFTER the instance merge, so a rule's instance exists.
        val ruleRecords = readJsonlList(File(root, "data"), "thinking_rules").mapNotNull { env ->
            env.obj?.let {
                runCatching {
                    BackupFormat.json.decodeFromJsonElement(
                        BackupThinkingRuleRecord.serializer(), it
                    )
                }.getOrNull()
            }
        }
        if (ruleRecords.isNotEmpty()) {
            val (written, skipped) = repo.restoreBackupThinkingRules(ruleRecords)
            report.imported += written
            report.skipped += skipped
        }

        // Credentials (secrets.json lives at the work root, already decrypted).
        val secrets = readSecrets(root)
        if (secrets != null) {
            var restored = 0
            var kept = 0
            for (s in secrets.providers) {
                if (repo.restoreBackupProviderSecret(s)) restored++ else kept++
            }
            report.credentialsRestored = restored
            report.credentialsKept = kept

            // [T-backup-restore-oauth] Full sign-in restore: the bundle goes
            // back under the same key shape the OAuth manager reads at login,
            // so a restored provider is signed in without a re-login. Every
            // key and value comes from the package - no literals here.
            if (secrets.oauth.isNotEmpty()) {
                var oauthRestored = 0
                val prefs = com.openminis.app.util.EncryptedPrefsFactory.safeCreate(context, "oauth_prefs")
                val editor = prefs.edit()
                for (o in secrets.oauth) {
                    if (o.instanceId.isBlank() || o.tokensJson.isBlank()) continue
                    editor.putString("oauth_tokens_${o.instanceId}", o.tokensJson)
                    o.manualBearer?.let { editor.putString("oauth_manual_bearer_token_${o.instanceId}", it) }
                    oauthRestored++
                }
                editor.apply()
                if (oauthRestored > 0) {
                    report.credentialsRestored += oauthRestored
                    AppLogger.info(TAG, "[Restore] oauth sign-ins restored: $oauthRestored")
                }
            }

            // [T-backup-restore-network] Proxy passwords back into their
            // encrypted store, keyed by the proxy ids the settings restore
            // re-creates.
            for (p in secrets.proxyPasswords) {
                val pass = runCatching {
                    String(android.util.Base64.decode(p.value, android.util.Base64.NO_WRAP))
                }.getOrNull() ?: continue
                com.openminis.app.network.NetworkSecrets.setPassword(context, p.name, pass)
            }
        }
        return report
    }

    /**
     * [T-backup-restore-settings] Apply the `data/settings.json` snapshot
     * (network settings JSON + tool-limit prefs). Absent member (older
     * package, or an iOS one) → nothing to do. Only whitelisted tool-limit
     * keys are applied; the network blob decodes through
     * [com.openminis.app.network.NetworkSettingsStore], whose tolerant decode
     * discards anything this build cannot understand.
     */
    private fun importSettings(root: File) {
        val f = File(root, "data/settings.json")
        if (!f.isFile) return
        val body = runCatching { org.json.JSONObject(f.readText()) }.getOrNull() ?: return

        body.optString("network").takeIf { it.isNotBlank() }?.let { raw ->
            runCatching {
                com.openminis.app.network.NetworkSettingsStore.save(
                    context,
                    com.openminis.app.network.NetworkSettingsStore.decode(raw),
                )
            }
            AppLogger.info(TAG, "[Restore] network settings applied")
        }

        val limits = body.optJSONObject("toolLimits") ?: return
        // Whitelist: exactly the prefs ToolLimitPrefs owns - a package cannot
        // plant arbitrary SharedPreferences entries.
        val allowed = setOf(
            "shellTimeoutSec", "fileReadMaxChars", "fileReadMaxLines",
            "subagentMaxTurns", "mainSessionMaxTurns",
        )
        val editor = context.getSharedPreferences(
            com.openminis.app.data.ToolLimitPrefs.PREFS, android.content.Context.MODE_PRIVATE,
        ).edit()
        var applied = 0
        for (key in limits.keys()) {
            if (key !in allowed) continue
            when (val v = limits.opt(key)) {
                // Braces: Kotlin 2.1's when grammar has no `-> stmt; stmt`
                // entries (a `;`-sequence must be a block body).
                is Int -> { editor.putInt(key, v); applied++ }
                is Long -> { editor.putLong(key, v); applied++ }
                is Boolean -> { editor.putBoolean(key, v); applied++ }
                is String -> { editor.putString(key, v); applied++ }
            }
        }
        if (applied > 0) {
            editor.apply()
            AppLogger.info(TAG, "[Restore] tool limits applied: $applied")
        }
    }

    /**
     * Restore env-var metadata (`data/env_vars.json`) + values (from
     * `secrets.json`). A variable already present by key is kept (skipped);
     * absent ones are added with their restored value. Mirrors the iOS env-var
     * restore, which applies values from secrets.json.
     */
    private fun importEnvironmentVariables(root: File): CategoryReport {
        val report = CategoryReport(BackupCategory.ENVIRONMENT_VARIABLES.key)
        val repo = app?.let { if (it.subsystemsReady()) it.envVarRepository else null } ?: run {
            report.failed = "env-var repository unavailable"
            return report
        }
        val metaFile = File(root, "data/env_vars.json")
        if (!metaFile.isFile) return report

        val metas = try {
            BackupFormat.json.decodeFromString(
                kotlinx.serialization.builtins.ListSerializer(BackupEnvVarMeta.serializer()),
                metaFile.readText(),
            )
        } catch (e: Exception) {
            report.unreadable += 1
            return report
        }

        // Value lookup by NAME from secrets.json (base64).
        val valuesByName = readSecrets(root)?.envVars?.associate { s ->
            s.name to runCatching {
                String(android.util.Base64.decode(s.value, android.util.Base64.NO_WRAP))
            }.getOrNull()
        } ?: emptyMap()

        for (meta in metas) {
            if (repo.isDuplicateKey(meta.key)) {
                report.skipped += 1
                continue
            }
            val value = valuesByName[meta.key] ?: ""
            if (repo.add(meta.key, value, meta.note)) report.imported += 1
            else report.skipped += 1
        }
        return report
    }

    /** Read + decode `secrets.json` from the work root; null if absent/unreadable. */
    private fun readSecrets(root: File): BackupSecrets? {
        val f = File(root, "secrets.json")
        if (!f.isFile) return null
        return runCatching {
            BackupFormat.json.decodeFromString(BackupSecrets.serializer(), f.readText())
        }.getOrNull()
    }

    private fun applyFileResult(report: CategoryReport, files: BackupRestoreFiles.Result) {
        report.filesWritten += files.written
        report.bytesWritten += files.bytes
        report.missingBlobs += files.missingBlobs
        report.sizeSkippedInPackage += files.sizeSkippedInPackage
        report.notDownloadedInPackage += files.notDownloadedInPackage
        report.rejectedPaths += files.rejectedPaths
    }

    // MARK: - Package plumbing

    /**
     * Decrypt every `.enc` member into a scratch tree, leaving the package
     * untouched.
     *
     * Decrypting in place would destroy the original on a failed run, and the
     * package may be a file the user still wants after a restore goes wrong.
     */
    private fun decryptMembers(root: File, keys: BackupCrypto.Keys): File {
        val work = File(context.cacheDir, "restore-work").apply {
            deleteRecursively(); mkdirs()
        }
        val base = root.canonicalFile
        for (file in base.walkTopDown().filter { it.isFile }) {
            val rel = file.relativeTo(base).path.replace(File.separatorChar, '/')
            val out = File(work, rel.removeSuffix(".enc")).apply { parentFile?.mkdirs() }
            if (rel.endsWith(".enc")) {
                val logical = rel.removeSuffix(".enc")
                val key = if (logical == "secrets.json") keys.secretsKey else keys.dataKey
                // AAD binds to the name the member ships under, `.enc` included.
                BackupCrypto.decryptFile(file, out, key, rel)
            } else {
                file.copyTo(out, overwrite = true)
            }
        }
        return work
    }

    /**
     * [T-restore-plaintext-scratch] Refuse a restore whose CACHE destination
     * resolves under the decrypted scratch root.
     *
     * CACHE restores land in `cacheDir`, so `cache/restore-work/<x>` maps onto
     * `cacheDir/restore-work/<x>` — the directory the importer decrypted the
     * package INTO and is still reading from (secrets.json, provider config,
     * chat bodies). A package carrying such paths (e.g. a CACHE export taken
     * while a previous restore's scratch tree existed) would overwrite the
     * mid-run source data. Checked on canonical paths before ANY category is
     * written, so the refusal is whole-package, not half-applied.
     */
    private fun assertNoScratchCollision(scratch: File, fileIndex: List<BackupFileIndexEntry>) {
        val scratchPath = scratch.canonicalPath + File.separator
        val cacheDest = context.cacheDir
        val clash = fileIndex.firstOrNull { entry ->
            entry.category == BackupCategory.CACHE.key && entry.skipped == null &&
                entry.path.startsWith("cache/") &&
                runCatching {
                    val dest = File(cacheDest, entry.path.removePrefix("cache/"))
                    val resolved = if (dest.exists()) dest.canonicalPath else File(
                        dest.parentFile?.canonicalFile ?: return@runCatching false, dest.name,
                    ).path
                    resolved == scratch.canonicalPath || resolved.startsWith(scratchPath)
                }.getOrDefault(false)
        }
        if (clash != null) {
            throw BackupException(
                "This package contains cache paths that collide with the restore work area " +
                    "(${clash.path}). Re-export from the source device without the cache " +
                    "category, or restore the remaining categories separately.",
            )
        }
    }

    private fun readFileIndex(root: File): List<BackupFileIndexEntry> {
        val file = File(root, "files.index.jsonl")
        if (!file.isFile) return emptyList()
        return file.readLines().mapNotNull { line ->
            if (line.isBlank()) null
            else runCatching {
                BackupFormat.json.decodeFromString(BackupFileIndexEntry.serializer(), line)
            }.getOrNull()
        }
    }

    /**
     * Read one JSONL family, including its rollover shards (name order, hence
     * the writer's zero-padding). §2.2 rule 3: a line that fails to parse is
     * skipped, never fatal.
     *
     * `inline` so the callback can suspend: every caller writes each record to
     * the DAO as it arrives.
     */
    private inline fun readJsonl(dataDir: File, baseName: String, onRecord: (Envelope) -> Unit) {
        val shards = (dataDir.listFiles() ?: emptyArray())
            .filter { it.isFile && (it.name == "$baseName.jsonl" ||
                (it.name.startsWith("$baseName-") && it.name.endsWith(".jsonl"))) }
            .sortedBy { it.name }
        for (shard in shards) {
            // Plain reader loop: a non-inline lambda would force `crossinline`
            // and forbid the `return@readJsonl` the callers use as `continue`.
            shard.bufferedReader().use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    if (line.isBlank()) continue
                    // §2.2 rule 3: an unparseable line is skipped, never fatal.
                    val envelope = try {
                        Envelope(
                            BackupFormat.json.parseToJsonElement(line).jsonObject["d"]?.jsonObject
                        )
                    } catch (_: Exception) {
                        continue
                    }
                    onRecord(envelope)
                }
            }
        }
    }

    /**
     * List form, for the one family small enough to hold whole.
     *
     * [T-android-restore-jsonl-oom] Everything else streams. Materialising
     * `messages.jsonl` — one parsed `JsonObject` tree per message, all live at
     * once — exhausted a Pixel 6's 512MB heap mid-restore with every caller
     * using each record exactly once; the list was pure waste.
     */
    private fun readJsonlList(dataDir: File, baseName: String): List<Envelope> =
        mutableListOf<Envelope>().also { out -> readJsonl(dataDir, baseName) { out.add(it) } }

    private class Envelope(val obj: JsonObject?)

    companion object {
        private const val TAG = "Restore"

        /**
         * [XSessionDiag] Mirror of the auto launch-mode freshness window in
         * `AppNavigation` (15 min). Diagnostic-only: decides which restored
         * sessions get a log line, never wired into any behaviour.
         */
        private const val XSESSION_DIAG_AUTO_WINDOW_MS = 15L * 60 * 1000

        /**
         * [T-android-restore-provider-type-tolerance] Result of a lenient
         * provider_config.json parse: the config that survived, plus how many
         * instances had to be dropped because this build could not decode them.
         */
        internal data class LenientProviderConfig(
            val config: com.openminis.app.data.model.ProviderConfig,
            val droppedInstances: Int,
        )

        /**
         * [T-android-restore-provider-type-tolerance] Decode provider_config.json
         * so that ONE malformed instance cannot destroy the rest of the file.
         *
         * The failure this exists to prevent, observed in the field: an iOS package
         * contained a provider whose `providerType` was `openAIResponses`, a case
         * Android's enum did not have. kotlinx.serialization threw on that one
         * value, the single whole-document `decodeFromString` aborted, and all
         * EIGHT providers in the package were discarded — credentials included.
         * The user saw "no API keys in this backup", which was never true; nothing
         * had been read at all.
         *
         * Two independent layers, because the enum fix alone is not enough — the
         * next iOS-only type would reproduce it exactly:
         *
         *  1. `providerType` values this build doesn't know are rewritten to
         *     `unsupported` BEFORE decoding, so the instance survives as a visible
         *     (unusable) row rather than taking the file down. Mirrors iOS's
         *     `ProviderType.decoded`, which has always been forgiving here.
         *  2. Anything still undecodable — a genuinely malformed object, a missing
         *     required field — is dropped individually and counted, leaving every
         *     sibling instance intact.
         *
         * Top-level fields (modelEntries, groups, default pointers) are decoded
         * from the same object with the instances array replaced, so a bad instance
         * costs only that instance.
         */
        internal fun parseProviderConfigLeniently(text: String): LenientProviderConfig {
            val root = BackupFormat.json.parseToJsonElement(text).jsonObject
            val rawInstances = root["instances"] as? kotlinx.serialization.json.JsonArray

            // No instances array at all — nothing to be tolerant about; let the
            // ordinary decode handle (or reject) the document.
            if (rawInstances == null) {
                return LenientProviderConfig(
                    BackupFormat.json.decodeFromJsonElement(
                        com.openminis.app.data.model.ProviderConfig.serializer(), root,
                    ),
                    droppedInstances = 0,
                )
            }

            val knownTypes = com.openminis.app.data.model.ProviderType.entries.map { it.name }.toSet()
            val kept = mutableListOf<kotlinx.serialization.json.JsonElement>()
            var dropped = 0
            for (element in rawInstances) {
                val obj = element as? JsonObject ?: run { dropped++; null } ?: continue
                val rawType = (obj["providerType"] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.contentOrNull
                val normalized = if (rawType != null && rawType !in knownTypes) {
                    AppLogger.warning(
                        TAG,
                        "[Restore] provider instance ${obj["id"]?.jsonPrimitive?.contentOrNull}: " +
                            "unknown providerType '$rawType' — importing as unsupported",
                    )
                    JsonObject(
                        obj + ("providerType" to kotlinx.serialization.json.JsonPrimitive(
                            com.openminis.app.data.model.ProviderType.unsupported.name,
                        )),
                    )
                } else obj
                // Prove it decodes before keeping it, so a malformed sibling is
                // rejected here rather than at the whole-document decode below.
                val decodes = runCatching {
                    BackupFormat.json.decodeFromJsonElement(
                        com.openminis.app.data.model.ProviderInstance.serializer(), normalized,
                    )
                }
                if (decodes.isSuccess) {
                    kept.add(normalized)
                } else {
                    dropped++
                    AppLogger.warning(
                        TAG,
                        "[Restore] provider instance dropped (undecodable): " +
                            "${decodes.exceptionOrNull()?.message}",
                    )
                }
            }

            val patched = JsonObject(root + ("instances" to kotlinx.serialization.json.JsonArray(kept)))
            return LenientProviderConfig(
                BackupFormat.json.decodeFromJsonElement(
                    com.openminis.app.data.model.ProviderConfig.serializer(), patched,
                ),
                droppedInstances = dropped,
            )
        }

        /** Shared with the exporter: the two must never run at once. */
        private val activityLock = Mutex()

        /** Sessions must land before the messages that reference them. */
        private val ORDER = listOf(
            BackupCategory.CHATS,
            BackupCategory.SHARED_FILES,
            BackupCategory.SKILLS,
            BackupCategory.ROOTFS,
            BackupCategory.CACHE,
            BackupCategory.MEMORY,
            BackupCategory.MCP_SERVERS,
            // Providers before env-vars, for parity with the iOS restore
            // order; on Android the two are independent.
            BackupCategory.PROVIDERS,
            BackupCategory.ENVIRONMENT_VARIABLES,
        )

        /**
         * [T-android-restore-ios-session-nesting] Flatten `{outer…, key:{inner…}}`
         * into one object, inner winning — iOS wraps some records (a session
         * arrives as `{"memoryEnabled":true,"session":{…}}`) while Android
         * writes them flat; merging keeps one reader for both. Returns `this`
         * unchanged when [key] is absent or not an object.
         */
        private fun JsonObject.unwrapNested(key: String): JsonObject {
            val inner = (this[key] as? JsonObject) ?: return this
            return JsonObject(this.filterKeys { it != key } + inner)
        }

        private fun JsonObject.str(key: String): String? =
            this[key]?.takeIf { it.toString() != "null" }?.runCatching { jsonPrimitive.content }
                ?.getOrNull()

        private fun JsonObject.int(key: String): Int? =
            this[key]?.runCatching { jsonPrimitive.content.toInt() }?.getOrNull()

        private fun JsonObject.bool(key: String): Boolean? =
            this[key]?.runCatching { jsonPrimitive.content.toBooleanStrict() }?.getOrNull()

        /**
         * Parse an ISO-8601 instant into epoch millis; a numeric epoch is also
         * accepted (§2.2's tolerance applied to a value, not just a key).
         */
        fun JsonObject.millis(key: String): Long? {
            val raw = str(key) ?: return null
            raw.toLongOrNull()?.let { return it }
            return IsoTime.parseFlexible(raw)
        }
    }
}
