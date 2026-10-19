package com.openminis.app.backup

import android.content.Context
import androidx.room.withTransaction
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.db.FolderEntity
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.logging.AppLogger
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.io.File

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
     * Restore from an already-extracted package directory.
     *
     * Serialised process-wide with export: two concurrent restores share
     * mutable destinations (the same session directories, the same provider
     * config), so overlapping them corrupts state no rollback can describe
     * (iOS review I3).
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
        // iOS zips through NSFileCoordinator, which wraps the tree in an outer
        // folder, so the real root may be one level down.
        val root = BackupZip.packageRoot(extractedRoot)
        val reader = BackupPackageReader(root)
        val manifest = reader.readManifest()

        val report = Report(
            backupId = manifest.backupId,
            createdAt = manifest.createdAt.takeIf { it.isNotEmpty() },
            sourcePlatform = manifest.app.platform,
        )

        // Downgrade guard before anything else: a manifest with its
        // `encryption` block stripped would otherwise make every category read
        // zero records and report success on an empty restore.
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

        // The decrypted plaintext scratch tree, when this package is
        // encrypted. Tracked here so importBody's finally can wipe it — see
        // [T-restore-plaintext-scratch] below the decrypt call.
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

            // Decrypt every member up front. Done as one pass rather than
            // lazily per category so a wrong-key failure surfaces before any
            // data has been written to the device.
            val work = if (keys != null) {
                onProgress?.invoke("Decrypting…")
                decryptMembers(root, keys).also { scratchWork = it }
            } else {
                root
            }

            // [T-restore-plaintext-scratch] A package whose CACHE tree addresses
            // `cache/restore-work/…` would restore back INTO the very directory
            // the decrypted scratch tree lives in — CACHE lands before
            // PROVIDERS/env-vars in ORDER, so the clobber happens mid-run, over
            // the still-unread secrets.json and chat bodies. Refuse such a
            // package outright rather than half-restoring over our own feet.

            val fileIndex = readFileIndex(work)
            val wanted = options.categories
                ?: manifest.categories.keys.mapNotNull(BackupCategory::fromKey).toSet()

            val scratch = scratchWork
            if (scratch != null && BackupCategory.CACHE in wanted) {
                assertNoScratchCollision(scratch, fileIndex)
            }

            // Order matters: chats writes sessions before the messages that
            // reference them.
            for (category in ORDER.filter { it in wanted }) {
                // [T-restore-cancel-propagation] Stop between categories: most
                // importers below never check for cancellation themselves.
                coroutineContext.ensureActive()
                onProgress?.invoke("Restoring ${category.key}…")
                // The manifest's own count for this category — what the button
                // needs for the "/ total" half. Absent on an older package, in
                // which case the UI shows a bare running count.
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
                        BackupCategory.PROVIDERS -> importProviders(work)
                        BackupCategory.ENVIRONMENT_VARIABLES -> importEnvironmentVariables(work)
                        else -> null
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    // [T-restore-cancel-propagation] CancellationException IS an
                    // Exception: the generic clause below would swallow the Stop
                    // thrown by importChats' ensureActive(), report chats as
                    // "failed" and go on restoring every remaining category.
                    throw e
                } catch (e: Exception) {
                    AppLogger.error(TAG, "[Restore] category ${category.key} failed: ${e.message}")
                    CategoryReport(category.key, failed = e.message ?: e.toString())
                }
                categoryReport?.let {
                    report.categories.add(it)
                    // [T-android-restore-logging] Per-category outcome, on
                    // disk. The summary below only reports totals, so a
                    // category that wrote 500 of 2350 records read exactly like
                    // one that wrote everything — and the numbers that say
                    // WHICH ("skipped", "unreadable") lived only on the
                    // completion screen, which is gone the moment it is
                    // dismissed. Diagnosing the real restore meant grepping a
                    // 2.65 GB package to infer what the importer had already
                    // counted.
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
                    // promised is the signal that matters, and it is easy to
                    // miss inside a line of counters.
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
                // Surfaced rather than buried: the user must be told their
                // package was incomplete while they still have the source
                // device to re-export from.
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
            // [T-restore-plaintext-scratch] decryptMembers() writes EVERY
            // decrypted member — chats, provider secrets.json, env-var values —
            // as plaintext under cacheDir/restore-work. Wiping the keys alone
            // left that full plaintext copy of the user's data sitting on disk
            // until the OS evicted the cache or the NEXT restore's first pass
            // deleted it (and until then a CACHE-category export could sweep it
            // into a new package). Scrub the scratch tree on every exit path,
            // including failure paths after partial decryption.
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
        // Called as messages land. Chats is the only category long enough for
        // the count to matter — the rest finish before a number could be read.
        onCount: ((Int) -> Unit)? = null,
    ): CategoryReport {
        val report = CategoryReport(BackupCategory.CHATS.key)
        val dao = db.chatDao()
        val dataDir = File(root, "data")

        // Restore content-addressed message bodies before rows point at them.
        val bodyRoot = File(context.filesDir, "bodies")
        BackupRestoreFiles.restore(root, fileIndex, BackupCategory.CHATS, bodyRoot) { path ->
            if (!path.startsWith("chats/_bodies/")) null
            else File(bodyRoot, path.removePrefix("chats/_bodies/"))
        }

        // Folders first: sessions carry a folderId, so applying folders
        // beforehand means the reference resolves immediately.
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
                    // iOS names this field `desc` (ChatStore.Folder.desc);
                    // Android's exporter writes `description`. Accept both, or
                    // a folder restored from an iPhone silently loses its
                    // one-line description.
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
        // [T-android-restore-cancel-empty-sessions] Sessions THIS run newly
        // inserted — never ones that already existed locally, which a cancel
        // must not touch even if empty.
        val newlyInsertedSessionIds = mutableListOf<String>()
        // [XSessionDiag] Restore wall clock, sampled once rather than per row —
        // the comparison below only needs "roughly now", and calling
        // currentTimeMillis() inside a loop over thousands of records would be
        // needless work in the hot import path.
        val nowMs = System.currentTimeMillis()
        // [T-android-restore-logging] Raw record counts, independent of the
        // report's imported/updated split: a session can be counted "skipped"
        // (locally newer) and still be a legitimate parent, so the report alone
        // cannot answer "is every session in the package present in the DB?" —
        // which is exactly the question a message-loss investigation asks.
        var sessionsSeen = 0
        // [T-android-restore-perf] ONE transaction for the whole loop. Without
        // it every insert is its own implicit transaction, so SQLite fsyncs
        // once per row: measured mid-restore at 119 records/s, i.e. 8.4 ms per
        // record — flash commit latency rather than any work this code does.
        //
        // [T-android-restore-cancel-empty-sessions] Durability: the import
        // writes straight into the LIVE database (there is no staging DB) and
        // is not resumable mid-category. Sessions and messages are SEPARATE
        // transactions, so an interruption between them does not simply leave
        // "every committed batch" behind as a consistent state: a Stop during
        // the messages loop rolled the messages back and left the sessions
        // committed. The cancel path below removes those; a crash (no
        // CancellationException) can still leave them until the restore is
        // re-run, which re-inserts the messages by id.
        db.withTransaction {
        readJsonl(dataDir, "sessions") { rec ->
            sessionsSeen += 1
            // [T-android-restore-ui] Cooperative check, on a wide beat:
            // sessions are fewer but individually heavier than messages.
            if (sessionsSeen % 100 == 0) coroutineContext.ensureActive()
            val envelope = rec.obj ?: return@readJsonl
            // [T-android-restore-ios-session-nesting] iOS nests the session
            // under a "session" key, with wrapper fields like memoryEnabled as
            // its SIBLINGS; Android writes those same fields flat. Reading only
            // the flat shape made every record from an iPhone backup look like
            // it had no id, so all 2350 sessions were counted "unreadable" and
            // skipped — and because a message row needs its parent session to
            // satisfy the foreign key, every message went with them. The
            // restore then reported success having written no chats at all.
            //
            // Merge the two levels rather than picking one: inner fields win
            // (that is the record proper), outer fields fill in the wrapper.
            val s = envelope.unwrapNested("session")
            val id = s.str("id")
            if (id == null) {
                report.unreadable += 1
                return@readJsonl
            }
            val incomingUpdated = s.millis("updatedAt") ?: 0
            val existing = dao.getSession(id)
            // Merge (§8.2): newer updatedAt wins. Without this comparison an
            // older backup would silently overwrite work the user did after it
            // was taken.
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
            if (existing == null) {
                report.imported += 1
                // Only a session THIS run created is ever a cleanup candidate:
                // a pre-existing (user-owned) empty session must outlive a Stop.
                newlyInsertedSessionIds.add(id)
            } else {
                report.updated += 1
            }
            restoredSessionIds.add(id)
            // [XSessionDiag] Hypothesis 1: a restored session keeps the BACKUP's
            // own updatedAt (incomingUpdated above), not the restore wall clock.
            // A session the user was using on the source device shortly before
            // exporting therefore lands looking "recently active", and auto launch
            // mode resumes it as the landing chat — see the launch/auto line in
            // AppNavigation.
            //
            // Deliberately logged ONLY for rows that would still be inside the
            // 15-minute auto-resume window at restore time: a backup can hold
            // thousands of sessions, and logging every one would be exactly the
            // high-frequency noise this diagnostic is supposed to avoid. Those
            // are also the only rows that can produce the reported symptom.
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
        }

        AppLogger.info(
            TAG,
            "[Restore] chats: sessions.jsonl held $sessionsSeen record(s); " +
                "${restoredSessionIds.size} usable as message parents"
        )

        // [T-android-restore-cancel-empty-sessions] From here on the sessions
        // transaction has committed. A Stop anywhere below (the messages loop's
        // ensureActive(), or a later withTransaction suspension) rolls back
        // the in-flight batch only, so the sessions this run created would
        // survive as empty "No messages yet" rows. The catch at the end of
        // this block removes those before the cancellation propagates.
        try {
        // The manifest counts MESSAGES for the chats category, so this is the
        // loop whose progress the button reports.
        var seen = 0
        // Messages dropped for want of a parent row, and the distinct sessions
        // they pointed at — the ratio tells a truncated-sessions story apart
        // from a few genuinely dangling rows.
        var orphanedMessages = 0
        val orphanSessionIds = mutableSetOf<String>()
        // Old exports may omit sortOrder or contain the legacy default 0 for
        // every row. Allocate a unique monotonic cursor per session while
        // importing so later keyset/range paging cannot loop over the same
        // sort_order and strand the middle of the transcript.
        val nextSortBySession = mutableMapOf<String, Int>()
        // Snapshot existing cursors before entering the synchronous JSONL reader;
        // the per-record callback must remain non-suspending.
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
        // [T-android-restore-perf] One transaction for all messages — the
        // hottest loop in the importer (202,705 records on the measured
        // package). See the sessions loop above for the measurement.
        //
        // The cost of batching this far is WAL growth: measured at 635 MB peak
        // for a 633 MB database, since nothing can checkpoint while the
        // transaction is open. It is reclaimed on commit, and the WAL is
        // truncated on the way out, below.
        db.withTransaction {
        readJsonl(dataDir, "messages") { rec ->
            // Every 200 records, not every one: at ~50k messages a per-record
            // StateFlow emit would post more frames than the UI can draw and
            // the counter would blur rather than inform.
            if (++seen % 200 == 0) {
                onCount?.invoke(seen)
                // [T-android-restore-ui] Cooperative cancellation, on the same
                // beat as the progress emit rather than per record: this loop
                // runs 200k+ times and ensureActive() is not free, while 200
                // records is well inside what reads as "stopped immediately".
                //
                // Throwing here unwinds out of the enclosing withTransaction,
                // so the batch in flight rolls back whole and the database is
                // left on a record boundary, never mid-message.
                coroutineContext.ensureActive()
            }
            val m = rec.obj ?: return@readJsonl
            val id = m.str("id")
            val sessionId = m.str("sessionId")
            if (id == null || sessionId == null) {
                report.unreadable += 1
                return@readJsonl
            }
            // A message whose session was skipped as locally-newer still
            // belongs to a session that exists; one whose session is absent
            // entirely would violate the foreign key.
            //
            // [T-android-restore-perf] A set lookup, not a DB round trip: this
            // ran `dao.getSession()` for EVERY message to answer a yes/no
            // question whose answer is already in memory — restoredSessionIds
            // holds every session inserted or confirmed present above, and the
            // query was `SELECT *`.
            if (sessionId !in restoredSessionIds) {
                // [T-android-restore-logging] The single most destructive skip
                // in the importer: it silently discards a message because its
                // parent session is absent. 356 of 500 sessions restored empty
                // on a real package and this branch was where every one of
                // those messages went — with nothing on disk to say so.
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
                    // [T-token-attribution-snapshot] Absent in packages written
                    // before this existed (and in any category the other
                    // platform hasn't updated yet) — null then, which is
                    // exactly the "estimated" state the Usage page renders.
                    modelId = m.str("modelId"),
                    modelDisplayName = m.str("modelDisplayName"),
                    providerType = m.str("providerType"),
                    providerInstanceId = m.str("providerInstanceId"),
                )
            )
            report.imported += 1
        }
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

        // [T-android-restore-perf] Same batching for compact markers.
        db.withTransaction {
        readJsonl(dataDir, "compact_markers") { rec ->
            val c = rec.obj ?: return@readJsonl
            val id = c.str("id") ?: return@readJsonl
            val sessionId = c.str("sessionId") ?: return@readJsonl
            // [T-android-restore-perf] Same set lookup as the messages loop.
            if (sessionId !in restoredSessionIds) {
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
        }

        // [T-android-restore-preview] Rebuild each restored session's
        // `last_message` from the messages just written.
        //
        // iOS does not carry the field: of 2350 session records in a real
        // iPhone package, ZERO had `lastMessage` — Android's exporter writes
        // it, iOS's does not. The importer only ever READ it, so every session
        // restored from an iPhone landed with a null preview and the home list
        // rendered "No messages yet" over a session holding a thousand
        // messages.
        //
        // Derived, not transported: the preview is a function of the newest
        // message, so recomputing it locally is both correct for any writer
        // and immune to the two disagreeing. Uses the same extractTextPreview
        // the live chat path uses, so a tool-only turn shows its tool summary
        // rather than falling through to the same empty string.
        // [T-android-restore-perf] Batched like the loops above: this is three
        // queries and a write PER restored session, so on a package with
        // thousands of sessions it is its own multi-minute stretch of
        // one-fsync-per-row.
        db.withTransaction {
            for (sid in restoredSessionIds) {
                val parts = dao.lastMessageParts(sid) ?: continue
                val preview = ChatRepository.extractTextPreview(parts) ?: continue
                val s = dao.getSession(sid) ?: continue
                dao.updateLastMessage(sid, preview, s.updatedAt)
            }
        }
        } catch (e: kotlinx.coroutines.CancellationException) {
            // NonCancellable: the job is already cancelled, so any suspending
            // DAO call would otherwise throw immediately and skip the cleanup.
            val removed = withContext(NonCancellable) {
                deleteEmptySessions(
                    newlyInsertedSessionIds,
                    messageCount = { dao.messageCountForSession(it) },
                    delete = { dao.deleteSession(it) },
                )
            }
            AppLogger.info(
                TAG,
                "[Restore] chats: cancelled — removed ${removed.size} of " +
                    "${newlyInsertedSessionIds.size} newly-inserted session(s) without messages"
            )
            throw e
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

        // [T-android-restore-perf] Truncate the WAL now that the big
        // transactions have committed. Measured peak was 635 MB for a 633 MB
        // database; SQLite reclaims it on its own schedule, and the categories
        // that follow are small, so without this the file can sit at its high
        // water mark for the rest of the restore. Best-effort: a failed
        // checkpoint costs disk space, never data, so it must not fail the
        // import.
        runCatching {
            db.openHelper.writableDatabase.query("PRAGMA wal_checkpoint(TRUNCATE)").use { it.moveToFirst() }
        }.onFailure {
            AppLogger.warning(TAG, "[Restore] WAL checkpoint failed: ${it.message}")
        }
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
        // Instances the package carried but this build could not decode at all.
        // Counted individually so the report says "1 of 8 unreadable" rather
        // than failing the whole category.
        report.unreadable += parsed.droppedInstances
        if (parsed.droppedInstances > 0) {
            AppLogger.warning(
                TAG,
                "[Restore] provider_config.json: dropped ${parsed.droppedInstances} " +
                    "undecodable instance(s), kept ${config.instances.size}",
            )
        }

        val (before, after) = repo.mergeBackupProviderConfig(config)
        report.imported = maxOf(0, after - before)
        // Everything the package carried that did not newly insert was already
        // present and left untouched — skipped, not updated.
        report.skipped = maxOf(0, config.instances.size - report.imported)

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
        }
        return report
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
     * Read one JSONL family, including its rollover shards.
     *
     * §2.2 rule 3: a line that fails to parse is skipped, never fatal — it may
     * come from a newer writer. Shards are read in name order, which is why the
     * writer zero-pads them.
     */
    // `inline` so the callback can suspend: every caller writes each record to
    // the DAO as it arrives, which is the whole point of streaming.
    private inline fun readJsonl(dataDir: File, baseName: String, onRecord: (Envelope) -> Unit) {
        val shards = (dataDir.listFiles() ?: emptyArray())
            .filter { it.isFile && (it.name == "$baseName.jsonl" ||
                (it.name.startsWith("$baseName-") && it.name.endsWith(".jsonl"))) }
            .sortedBy { it.name }
        for (shard in shards) {
            // Plain reader loop rather than `forEachLine { runCatching { … } }`:
            // both of those take the callback as a non-inline lambda, which
            // would force `crossinline` here and forbid the `return@readJsonl`
            // the callers use in place of `continue`.
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
     * [T-android-restore-jsonl-oom] Everything else streams. `messages.jsonl`
     * is one line per message across every session ever backed up, and
     * materialising it as `List<Envelope>` — a parsed `JsonObject` tree per
     * message, all live at once — is what exhausted a Pixel 6's 512MB heap
     * mid-restore:
     *
     *     OutOfMemoryError: Failed to allocate a 48 byte allocation with
     *     2470096 free bytes ... at BackupImporter.readJsonl(BackupImporter.kt:643)
     *
     * The heap climbed 324MB → 463MB over six seconds of back-to-back GCs
     * before giving up. Nothing needed the list: every caller was a `for` loop
     * that used each record once and dropped it, so the peak was pure waste.
     */
    private fun readJsonlList(dataDir: File, baseName: String): List<Envelope> =
        mutableListOf<Envelope>().also { out -> readJsonl(dataDir, baseName) { out.add(it) } }

    private class Envelope(val obj: JsonObject?)

    companion object {
        private const val TAG = "Restore"

        /**
         * [T-android-restore-cancel-empty-sessions] Deletes each of [sessionIds]
         * whose [messageCount] is zero; returns the ids deleted. Callers pass
         * only sessions the current run newly inserted, so a pre-existing
         * (user-owned) empty session is never in scope. Best-effort per id: one
         * failing lookup or delete must not stop the rest of the cleanup.
         */
        internal suspend fun deleteEmptySessions(
            sessionIds: Collection<String>,
            messageCount: suspend (String) -> Int,
            delete: suspend (String) -> Unit,
        ): List<String> {
            val deleted = mutableListOf<String>()
            for (id in sessionIds) {
                val count = runCatching { messageCount(id) }.getOrNull() ?: continue
                if (count != 0) continue
                runCatching { delete(id) }.onSuccess { deleted += id }
            }
            return deleted
        }

        /**
         * [XSessionDiag] Mirror of the auto launch-mode freshness window in
         * `AppNavigation` (15 min). Diagnostic-only: it decides which restored
         * sessions are worth a log line, and is deliberately NOT wired into the
         * navigation decision — duplicating the value keeps this logging free of
         * any behavioural coupling. If the navigation threshold ever changes,
         * this one only affects how much we log.
         */
        private const val XSESSION_DIAG_AUTO_WINDOW_MS = 15L * 60 * 1000

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
            // Providers before env-vars: both pull VALUES from secrets.json, but
            // provider import is where credentials for BOTH are applied on iOS.
            // On Android env-var values are keyed by name and applied in
            // importEnvironmentVariables, so the two are independent; keep
            // providers first for parity with the iOS restore order.
            BackupCategory.PROVIDERS,
            BackupCategory.ENVIRONMENT_VARIABLES,
        )

    }
}
