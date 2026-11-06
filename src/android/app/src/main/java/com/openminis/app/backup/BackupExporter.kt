package com.openminis.app.backup

import android.content.Context
import org.json.JSONObject
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.CompactMarkerEntity
import com.openminis.app.data.db.FolderEntity
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.logging.AppLogger
import com.openminis.app.tools.AtomicFileWrite
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.io.File
import com.openminis.app.util.IsoTime
import java.util.UUID

/**
 * Builds a `.minisbak` package on Android (backup-restore design §2, §9
 * stage 4), mirroring `src/ios/Agent/Backup/BackupExporter.swift`.
 *
 * Shape: stage into a scratch directory, then zip once — a failure part-way
 * leaves no half-written package. Record fields are the cross-platform
 * contract: iOS Codable camelCase + ISO-8601, reproduced exactly (§2.1's
 * snake_case covers the manifest alone); deviating makes packages mutually
 * unreadable (§5.4).
 */
class BackupExporter(
    private val context: Context,
    private val db: AppDatabase,
) {

    data class Options(
        val categories: Set<BackupCategory> = BackupCategory.backupable.toSet(),
        /** §3.4 — null means unlimited, which is the DEFAULT. */
        val maxFileBytes: Long? = null,
        /**
         * §3.3: credentials ship WITH the Providers category by default, so a
         * restored provider actually works. False = the "share copy".
         */
        val includeCredentials: Boolean = true,
        /** null/empty = unencrypted package. */
        val passphrase: String? = null,
        /**
         * Data cut-off: the package is the state as of this instant. Without
         * it a session written between the index pass and the blob copy lands
         * in files.index while its content never gets stored.
         */
        val snapshotAtMillis: Long = System.currentTimeMillis(),
    )

    data class Summary(
        val packageFile: File,
        val backupId: String,
        val totalBytes: Long,
        val categories: Map<String, BackupManifest.CategoryStat>,
        val skippedFiles: Int,
        val skippedBytes: Long,
        val skippedPaths: List<BackupBlobStore.SkippedPath>,
        val durationMillis: Long,
    )

    /**
     * Export a package. Serialised process-wide: an export racing a restore
     * would read stores the other is mutating — same rule as iOS (review I3).
     */
    suspend fun export(
        options: Options = Options(),
        onProgress: ((String) -> Unit)? = null,
    ): Summary = activityLock.withLock { exportBody(options, onProgress) }

    private suspend fun exportBody(options: Options, onProgress: ((String) -> Unit)?): Summary =
        com.openminis.app.data.body.Admission.occupy(com.openminis.app.data.body.ResourceLimits.SESSION_PREVIEW_BUDGET.toLong()) {
        exportBodyAdmitted(options, onProgress)
    }

    private suspend fun exportBodyAdmitted(options: Options, onProgress: ((String) -> Unit)?): Summary {
        // [T-backup-credentials-without-encryption] No passphrase guard: the
        // unencrypted-credentials risk is surfaced in the UI, not by silently
        // dropping data. Matches iOS, same tag.
        val started = System.currentTimeMillis()
        val backupId = UUID.randomUUID().toString()

        // [Wave3 #10a] Staging lives on getExternalFilesDir(null), not the
        // evictable cacheDir; it stays app-private and wiped on uninstall.
        val staging = File(stagingRoot(context), backupId)
        staging.mkdirs()

        // [Wave3 #10b] Incremental fast path: reuse unchanged files' blob bytes
        // (same size + mtime) from the previous zip — no source read, no hash.
        val incremental = BackupIncrementalManifest(File(backupsDirectory(context), BackupIncrementalManifest.FILE_NAME))
        incremental.load()
        val prevZip: java.util.zip.ZipFile? = incremental.packageName?.let { pkgName ->
            runCatching { java.util.zip.ZipFile(File(backupsDirectory(context), pkgName)) }.getOrNull()
        }
        val blobReuse: BackupBlobStore.Reuse? = prevZip?.let { zip ->
            BackupBlobStore.Reuse { file, logicalPath, size ->
                val cached = incremental.lookupUnchanged(logicalPath, size, file.lastModified())
                    ?: return@Reuse null
                val entryName = "blobs/${cached.sha256.take(2)}/${cached.sha256}"
                val entry = runCatching { zip.getEntry(entryName) }.getOrNull() ?: return@Reuse null
                val tmp = File(File(staging, "blobs/${cached.sha256.take(2)}"), ".reuse-${cached.sha256}")
                val ok = runCatching {
                    zip.getInputStream(entry).use { input ->
                        tmp.outputStream().use { output -> input.copyTo(output) }
                    }
                    tmp.length() == cached.size
                }.getOrDefault(false)
                if (!ok) {
                    tmp.delete()
                    null
                } else {
                    incremental.countReused(size)
                    BackupBlobStore.ReusedBlob(cached.sha256, tmp)
                }
            }
        }
        try {
            val dataDir = File(staging, "data").apply { mkdirs() }
            val blobStore = BackupBlobStore(
                staging, options.maxFileBytes,
                reuse = blobReuse, incremental = incremental,
            )
            val stats = mutableMapOf<String, BackupManifest.CategoryStat>()

            BackupFileIndexWriter(File(staging, "files.index.jsonl")).use { fileIndex ->
                val trees = BackupFileTreeExporter(blobStore, fileIndex, options.snapshotAtMillis)

                if (BackupCategory.CHATS in options.categories) {
                    onProgress?.invoke("Exporting chats…")
                    stats[BackupCategory.CHATS.key] =
                        exportChats(dataDir, trees, options.snapshotAtMillis)
                    exportBodyBlobs(blobStore, fileIndex)
                }
                if (BackupCategory.SHARED_FILES in options.categories) {
                    onProgress?.invoke("Exporting shared files…")
                    stats[BackupCategory.SHARED_FILES.key] = exportSharedFiles(trees)
                }
                if (BackupCategory.SKILLS in options.categories) {
                    onProgress?.invoke("Exporting skills…")
                    stats[BackupCategory.SKILLS.key] = exportSkills(trees)
                }
                if (BackupCategory.ROOTFS in options.categories) {
                    onProgress?.invoke("Exporting Ubuntu rootfs…")
                    stats[BackupCategory.ROOTFS.key] = exportTree(
                        trees, File(context.filesDir, "ubuntu-rootfs"), "rootfs", BackupCategory.ROOTFS,
                    )
                }
                if (BackupCategory.CACHE in options.categories) {
                    onProgress?.invoke("Exporting cache…")
                    stats[BackupCategory.CACHE.key] = exportTree(
                        trees, context.cacheDir, "cache", BackupCategory.CACHE,
                    )
                }
                if (BackupCategory.MEMORY in options.categories) {
                    onProgress?.invoke("Exporting memory…")
                    stats[BackupCategory.MEMORY.key] = exportMemory(dataDir)
                }
                if (BackupCategory.MCP_SERVERS in options.categories) {
                    onProgress?.invoke("Exporting MCP servers…")
                    exportMcpServers(dataDir)?.let { stats[BackupCategory.MCP_SERVERS.key] = it }
                }
                if (BackupCategory.PROVIDERS in options.categories) {
                    onProgress?.invoke("Exporting providers…")
                    stats[BackupCategory.PROVIDERS.key] =
                        exportProviders(dataDir, options.includeCredentials)
                    // [T-backup-restore-settings] User prefs (network routes +
                    // tool limits) ride with the providers category so a
                    // restore brings the app's behaviour back with its data.
                    exportSettings(dataDir)
                }
                if (BackupCategory.ENVIRONMENT_VARIABLES in options.categories) {
                    onProgress?.invoke("Exporting environment variables…")
                    exportEnvironmentVariables(dataDir)?.let {
                        stats[BackupCategory.ENVIRONMENT_VARIABLES.key] = it
                    }
                }
            }

            // secrets.json aggregates credentials for BOTH providers and
            // env-vars, staged next to `data/`. Collected once, after the
            // category exports. Mirrors iOS.
            if (options.includeCredentials) {
                onProgress?.invoke("Collecting credentials…")
                BackupSecretsCollector.collect(context, staging, options.categories)
            }

            // Blob index last — it is only complete once every category has
            // offered its files.
            writeBlobIndex(blobStore.blobIndex, staging)

            // Encryption BEFORE the manifest: §5.3's `integrity` records the
            // CIPHERTEXT hash, verifiable without the passphrase.
            var encryption: BackupManifest.Encryption? = null
            var keys: BackupCrypto.Keys? = null
            if (!options.passphrase.isNullOrEmpty()) {
                onProgress?.invoke("Encrypting…")
                val salt = BackupCrypto.makeSalt()
                val kdf = BackupCrypto.currentKDF(salt)
                val derived = BackupCrypto.deriveKeys(options.passphrase, kdf)
                encryptStagedMembers(staging, derived)
                encryption = BackupManifest.Encryption(BackupCrypto.SCHEME, kdf, derived.verifier)
                keys = derived
                // Every category's bytes are now ciphertext, so the per-category
                // flag must say so rather than keeping the plaintext `false`.
                stats.replaceAll { _, v -> v.copy(encrypted = true) }
            }

            onProgress?.invoke("Writing manifest…")
            val manifest = buildManifest(backupId, stats, blobStore, staging, encryption, options)
            val manifestFile = File(staging, "manifest.json")
            manifestFile.writeText(
                BackupFormat.json.encodeToString(BackupManifest.serializer(), manifest)
            )
            if (keys != null) {
                // Sidecar MAC over the file's exact bytes. The embedded
                // `manifest_mac` authenticates a Swift re-encoding that cannot
                // be byte-reproduced here — a MAC only one platform can compute
                // is worse than none; both importers prefer this sidecar.
                File(staging, "manifest.mac")
                    .writeText(BackupCrypto.manifestMac(manifestFile.readBytes(), keys.macKey))
                keys.destroy()
            }

            onProgress?.invoke("Packaging…")
            val packageFile = archive(staging, backupId)
            val duration = System.currentTimeMillis() - started
            AppLogger.info(
                TAG,
                "[Backup] done id=$backupId bytes=${packageFile.length()} " +
                    "skipped=${blobStore.skippedFiles} in ${duration}ms"
            )

            return Summary(
                packageFile = packageFile,
                backupId = backupId,
                totalBytes = packageFile.length(),
                categories = stats,
                skippedFiles = blobStore.skippedFiles,
                skippedBytes = blobStore.skippedBytes,
                skippedPaths = blobStore.skippedPaths.toList(),
                durationMillis = duration,
            ).also {
                // [Wave3 #10b] Only a successfully finalised package updates
                // the sidecar; a failed run keeps the still-valid previous one.
                incremental.save(packageFile.name)
            }
        } finally {
            prevZip?.close()
            // The archive is the deliverable; the staging tree is scratch and
            // can be several GB. Always clear it, including on failure.
            staging.deleteRecursively()
            stagingRoot(context).takeIf {
                it.listFiles()?.isEmpty() == true
            }?.delete()
        }
    }

    // MARK: - Chats

    private suspend fun exportChats(
        dataDir: File,
        trees: BackupFileTreeExporter,
        snapshotAtMillis: Long,
    ): BackupManifest.CategoryStat {
        val dao = db.chatDao()
        var messageCount = 0
        var fileCount = 0
        var fileBytes = 0L
        var jsonlBytes = 0L

        BackupJsonlWriter(dataDir, "sessions").use { sessions ->
            BackupJsonlWriter(dataDir, "messages").use { messages ->
                BackupJsonlWriter(dataDir, "compact_markers").use { markers ->
                    BackupJsonlWriter(dataDir, "folders").use { folders ->
                        for (session in dao.listSessions()) {
                            // Snapshot cut-off: a session updated after
                            // Create Backup is excluded WHOLE, so the package
                            // never holds a session row without its messages.
                            if (session.updatedAt > snapshotAtMillis) {
                                AppLogger.info(
                                    TAG, "[Backup] skip session newer than snapshot: ${session.id}"
                                )
                                continue
                            }
                            sessions.write("SessionV2", 1, sessionRecord(session))

                            var messageOffset = 0
                            while (true) {
                                val page = dao.loadMessagesPage(session.id, messageOffset, 50)
                                if (page.isEmpty()) break
                                for (msg in page) {
                                    if (msg.createdAt <= snapshotAtMillis) {
                                        messages.write("MessageV2", 1, messageRecord(msg))
                                        messageCount += 1
                                    }
                                }
                                messageOffset += page.size
                                if (page.size < 50) break
                            }
                            for (marker in dao.listCompactMarkers(session.id)) {
                                markers.write("CompactMarkerV2", 1, markerRecord(marker))
                            }

                            // The session's whole on-disk tree: attachments /
                            // offloads / workspace / browser.
                            val dir = File(context.filesDir, "minis-sessions/${session.id}")
                            val r = trees.export(
                                dir, "chats/${session.id}", BackupCategory.CHATS, session.id
                            )
                            fileCount += r.filesIncluded
                            fileBytes += r.bytesIncluded
                        }

                        for (folder in dao.listFolders()) {
                            folders.write("FolderV2", 1, folderRecord(folder))
                        }
                        jsonlBytes = sessions.totalBytes + messages.totalBytes +
                            markers.totalBytes + folders.totalBytes
                    }
                }
            }
        }

        return BackupManifest.CategoryStat(
            entries = messageCount + fileCount,
            bytes = jsonlBytes + fileBytes,
            encrypted = false,
            messages = messageCount,
            files = fileCount,
        )
    }

    /**
     * The session row as iOS writes it: camelCase + ISO-8601 keys; Android-only
     * columns preserved per §2.2 rule 4, device-local fields absent.
     */
    private fun sessionRecord(s: ChatSessionEntity): JsonElement = buildJsonObject {
        put("id", JsonPrimitive(s.id))
        put("title", s.title?.let(::JsonPrimitive) ?: JsonNull)
        put("category", s.category?.let(::JsonPrimitive) ?: JsonNull)
        put("modelId", JsonPrimitive(s.modelId))
        put("createdAt", JsonPrimitive(iso8601(s.createdAt)))
        put("updatedAt", JsonPrimitive(iso8601(s.updatedAt)))
        put("lastMessage", s.lastMessage?.let(::JsonPrimitive) ?: JsonNull)
        put("source", s.source?.let(::JsonPrimitive) ?: JsonNull)
        put("pinnedAt", s.pinnedAt?.let { JsonPrimitive(iso8601(it)) } ?: JsonNull)
        put("folderId", s.folderId?.let(::JsonPrimitive) ?: JsonNull)
        // iOS's SessionRecord wrapper fields.
        put("memoryEnabled", JsonPrimitive(s.memoryEnabled != 0))
        put("modelBinding", s.modelBinding?.let(::JsonPrimitive) ?: JsonNull)
        // Android-only, preserved per §2.2 rule 4.
        put("editCount", JsonPrimitive(s.editCount))
        put("thinkingOverride", s.thinkingOverride?.let(::JsonPrimitive) ?: JsonNull)
    }

    /**
     * Content-addressed message bodies -> blobs + file index; absent when the
     * device has none.
     */
    private fun exportBodyBlobs(blobStore: BackupBlobStore, fileIndex: BackupFileIndexWriter) {
        val root = File(context.filesDir, "bodies")
        if (!root.isDirectory) return
        for (body in root.listFiles().orEmpty()) {
            if (!body.isFile || body.name.endsWith(".tmp")) continue
            runCatching {
                when (val outcome = blobStore.addFile(body, "chats/_bodies/${body.name}")) {
                    is BackupBlobStore.Outcome.Stored, is BackupBlobStore.Outcome.Duplicate -> {
                        val sha = when (outcome) {
                            is BackupBlobStore.Outcome.Stored -> outcome.sha256
                            is BackupBlobStore.Outcome.Duplicate -> outcome.sha256
                            else -> return@runCatching
                        }
                        fileIndex.write(BackupFileIndexEntry.file(
                            "chats/_bodies/${body.name}", body.length(), sha, BackupCategory.CHATS
                        ))
                    }
                    is BackupBlobStore.Outcome.SkippedTooLarge -> fileIndex.write(
                        BackupFileIndexEntry.sizeSkipped("chats/_bodies/${body.name}", body.length(), BackupCategory.CHATS)
                    )
                }
            }
        }
    }

    private fun messageRecord(m: MessageEntity): JsonElement = buildJsonObject {
        put("id", JsonPrimitive(m.id))
        put("sessionId", JsonPrimitive(m.sessionId))
        put("role", JsonPrimitive(m.role))
        put("parts", parseParts(m.partsJson))
        put("createdAt", JsonPrimitive(iso8601(m.createdAt)))
        put("updatedAt", JsonPrimitive(iso8601(m.updatedAt ?: m.createdAt)))
        put("tokenUsage", m.tokenUsage?.let { parseJsonOrNull(it) } ?: JsonNull)
        put("reasoningContent", m.reasoningContent?.let(::JsonPrimitive) ?: JsonNull)
        put("streamInterruptCount", JsonPrimitive(m.streamInterruptCount))
        put("sortOrder", JsonPrimitive(m.sortOrder))
        put("bodyBytes", JsonPrimitive(m.bodyBytes))
        m.bodyRef?.let { put("bodyRef", JsonPrimitive(it)) }
        m.bodySha?.let { put("bodySha", JsonPrimitive(it)) }
        // [T-token-attribution-snapshot] Per-message model attribution,
        // camelCase to match iOS Codable's wire keys (snake_case would not
        // decode there).
        m.modelId?.let { put("modelId", JsonPrimitive(it)) }
        m.modelDisplayName?.let { put("modelDisplayName", JsonPrimitive(it)) }
        m.providerType?.let { put("providerType", JsonPrimitive(it)) }
        m.providerInstanceId?.let { put("providerInstanceId", JsonPrimitive(it)) }
    }

    private fun markerRecord(c: CompactMarkerEntity): JsonElement = buildJsonObject {
        put("id", JsonPrimitive(c.id))
        put("sessionId", JsonPrimitive(c.sessionId))
        put("summary", JsonPrimitive(c.summary))
        put("firstKeptSortOrder", JsonPrimitive(c.firstKeptSortOrder))
        put("compactedCount", JsonPrimitive(c.compactedCount))
        put("createdAt", JsonPrimitive(iso8601(c.createdAt)))
        put("uiBoundarySortOrder", c.uiBoundarySortOrder?.let(::JsonPrimitive) ?: JsonNull)
        put("boundaryMessageId", c.boundaryMessageId?.let(::JsonPrimitive) ?: JsonNull)
        put("firstKeptMessageId", c.firstKeptMessageId?.let(::JsonPrimitive) ?: JsonNull)
        put("lastCompactedMessageId", c.lastCompactedMessageId?.let(::JsonPrimitive) ?: JsonNull)
    }

    private fun folderRecord(f: FolderEntity): JsonElement = buildJsonObject {
        put("id", JsonPrimitive(f.id))
        put("name", JsonPrimitive(f.name))
        put("icon", f.icon?.let(::JsonPrimitive) ?: JsonNull)
        put("color", f.color?.let(::JsonPrimitive) ?: JsonNull)
        put("origin", JsonPrimitive(f.origin))
        put("sortIndex", JsonPrimitive(f.sortIndex))
        put("pinnedAt", f.pinnedAt?.let { JsonPrimitive(iso8601(it)) } ?: JsonNull)
        put("description", f.description?.let(::JsonPrimitive) ?: JsonNull)
        put("createdAt", JsonPrimitive(iso8601(f.createdAt)))
        // Record edits only (rename / pin / icon) — a session moving in or out
        // of the group must not touch it, so this is a stable merge key.
        put("updatedAt", JsonPrimitive(iso8601(f.updatedAt)))
    }

    // MARK: - Shared files / Skills / Memory

    /**
     * §3.2 — the cross-session `/var/minis/shared` bucket. Host-side this is
     * `<filesDir>/minis-global/shared`, NOT anything inside the rootfs.
     */
    private fun exportTree(
        trees: BackupFileTreeExporter,
        root: File,
        prefix: String,
        category: BackupCategory,
    ): BackupManifest.CategoryStat {
        val r = trees.export(root, prefix, category)
        return BackupManifest.CategoryStat(r.filesIncluded, r.bytesIncluded, encrypted = false)
    }

    private fun exportSharedFiles(trees: BackupFileTreeExporter): BackupManifest.CategoryStat {
        val r = trees.export(
            File(context.filesDir, "minis-global/shared"), "shared", BackupCategory.SHARED_FILES
        )
        return BackupManifest.CategoryStat(r.filesIncluded, r.bytesIncluded, encrypted = false)
    }

    /**
     * Skills, counted in SKILLS — not files: `filesIncluded` would count
     * directories, so the count mirrors iOS (`rows.count`).
     */
    private fun exportSkills(trees: BackupFileTreeExporter): BackupManifest.CategoryStat {
        val root = File(context.filesDir, "minis-global/skills")
        val r = trees.export(root, "skills", BackupCategory.SKILLS)
        return BackupManifest.CategoryStat(
            entries = Companion.skillCount(root),
            bytes = r.bytesIncluded,
            encrypted = false,
            files = r.filesIncluded,
        )
    }

    /** `GLOBAL.md` / `SOUL.md` / daily notes, copied verbatim into `data/memory/`. */
    private fun exportMemory(dataDir: File): BackupManifest.CategoryStat {
        val source = File(context.filesDir, "minis-global/memory")
        val dest = File(dataDir, "memory").apply { mkdirs() }
        var entries = 0
        var bytes = 0L
        if (source.isDirectory) {
            for (file in source.walkTopDown().filter { it.isFile }) {
                val rel = file.relativeTo(source).path.replace(File.separatorChar, '/')
                val out = File(dest, rel).apply { parentFile?.mkdirs() }
                file.copyTo(out, overwrite = true)
                entries += 1
                bytes += file.length()
            }
        }
        return BackupManifest.CategoryStat(entries, bytes, encrypted = false)
    }

    /**
     * `servers.json` verbatim. Returns null when there is nothing to write, so
     * an absent MCP config contributes no category rather than an empty one.
     */
    private fun exportMcpServers(dataDir: File): BackupManifest.CategoryStat? {
        val source = File(context.filesDir, "minis-global/mcp-servers/servers.json")
        if (!source.isFile) return null
        val dest = File(dataDir, "mcp_servers.json")
        source.copyTo(dest, overwrite = true)
        // Count SERVERS, not files - a hard-coded 1 once read as a dropped
        // server. The file is copied verbatim either way.
        return BackupManifest.CategoryStat(mcpServerCount(source), source.length(), encrypted = false)
    }

    private fun mcpServerCount(source: File): Int = Companion.mcpServerCount(source)

    // MARK: - Providers / thinking rules / environment variables
    //
    // Credential VALUES go to secrets.json (see BackupSecretsCollector); these three
    // write only the non-secret structure, so a share copy
    // (includeCredentials=false) still carries the layout.

    private val app: com.openminis.app.MinisApp?
        get() = context.applicationContext as? com.openminis.app.MinisApp

        /**
     * `data/provider_config.json` (the whole [ProviderConfig], array order =
     * the user's drag arrangement) + custom thinking rules in
     * `data/thinking_rules.jsonl`. Mirrors iOS `exportProviders`.
     */
    private suspend fun exportProviders(
        dataDir: File,
        includeCredentials: Boolean,
    ): BackupManifest.CategoryStat {
        val repo = app?.providerRepositoryOrNull
        val config = repo?.config?.value ?: com.openminis.app.data.model.ProviderConfig()
        val url = File(dataDir, "provider_config.json")
        url.writeText(
            BackupFormat.json.encodeToString(
                com.openminis.app.data.model.ProviderConfig.serializer(), config
            )
        )
        var bytes = url.length()

        val ruleCount = exportThinkingRules(dataDir)
        if (ruleCount > 0) {
            bytes += File(dataDir, "thinking_rules.jsonl").length()
        }
        // `entries` is PROVIDERS only. Custom thinking rules ride in this
        // category (no category of their own) but are reported separately:
        // 8 providers + 1 rule must not read as "9 providers".
        return BackupManifest.CategoryStat(
            entries = config.instances.size,
            bytes = bytes,
            encrypted = false,
            includesCredentials = includeCredentials,
            thinkingRules = ruleCount.takeIf { it > 0 },
        )
    }

        /**
     * Every persisted (== custom) thinking rule to
     * `data/thinking_rules.jsonl`, one `{t:"ThinkingRuleV1",...}` line each.
     * Android stores only custom rules, so the whole table is the export
     * set. Times: ISO-8601 strings; synthesized epoch-0 here, ignored on
     * import. Returns the count written.
     */
    private suspend fun exportThinkingRules(dataDir: File): Int {
        val dao = com.openminis.app.data.db.ProviderDatabase
            .getInstance(context).providerConfigDao()
        val rows = dao.loadAllThinkingRules()
        if (rows.isEmpty()) return 0
        BackupJsonlWriter(dataDir, "thinking_rules").use { writer ->
            for (row in rows) {
                val record = BackupThinkingRuleRecord(
                    id = row.id,
                    instanceId = row.providerInstanceId,
                    sortOrder = row.sortOrder,
                    scopeKind = row.scopeKind,
                    scopePattern = row.scopePattern,
                    wireFormatJson = row.wireFormatJson ?: "{}",
                    echoField = null,
                    echoTiming = null,
                    label = row.label,
                    createdAt = null,
                    updatedAt = null,
                )
                writer.write(
                    "ThinkingRuleV1", 1,
                    BackupFormat.json.encodeToJsonElement(
                        BackupThinkingRuleRecord.serializer(), record
                    ),
                )
            }
            AppLogger.info(TAG, "[Backup] exported ${rows.size} custom thinking rule(s)")
            return rows.size
        }
    }

        /**
     * Env-var METADATA only to `data/env_vars.json`; VALUES ride in
     * secrets.json. Returns null when there are no variables. Mirrors iOS
     * `exportEnvironmentVariables`.
     */
    private fun exportEnvironmentVariables(dataDir: File): BackupManifest.CategoryStat? {
        val repo = app?.let { if (it.subsystemsReady()) it.envVarRepository else null }
        val entries = repo?.entries?.value ?: emptyList()
        if (entries.isEmpty()) return null
        val meta = entries.map {
            BackupEnvVarMeta(id = it.id, key = it.key, note = it.note, createdAt = it.createdAt)
        }
        val url = File(dataDir, "env_vars.json")
        url.writeText(
            BackupFormat.json.encodeToString(
                kotlinx.serialization.builtins.ListSerializer(BackupEnvVarMeta.serializer()), meta
            )
        )
        return BackupManifest.CategoryStat(entries.size, url.length(), encrypted = false)
    }

        /**
     * [T-backup-restore-settings] User preferences → `data/settings.json`:
     * the network settings JSON verbatim (passwords ride in secrets.json) plus
     * the tool-limit prefs. Two small blobs; BackupHistory is unaffected.
     */
    private fun exportSettings(dataDir: File) {
        val toolLimits = JSONObject()
        runCatching {
            val prefs = context.getSharedPreferences(
                com.openminis.app.data.ToolLimitPrefs.PREFS, android.content.Context.MODE_PRIVATE,
            )
            for ((key, value) in prefs.all) {
                when (value) {
                    is Int -> toolLimits.put(key, value)
                    is Long -> toolLimits.put(key, value)
                    is Boolean -> toolLimits.put(key, value)
                    is String -> toolLimits.put(key, value)
                }
            }
        }
        val network = runCatching {
            com.openminis.app.network.NetworkSettingsStore.encode(
                com.openminis.app.network.NetworkSettingsStore.load(context),
            )
        }.getOrNull()
        if (network == null && toolLimits.length() == 0) return
        val body = JSONObject()
            .put("v", 1)
            .put("network", JSONObject(network ?: "{}"))
            .put("toolLimits", toolLimits)
        File(dataDir, "settings.json").writeText(body.toString())
    }


    // MARK: - Package assembly

    /**
     * Encrypt every staged member in place, appending `.enc` (§5.3).
     * `manifest.json` stays plaintext (§2.1); `secrets.json` gets `K_secrets`
     * rather than `K_data` — "strip the credentials" becomes a file deletion
     * instead of a re-encrypt of everything (§5.4).
     */
    private fun encryptStagedMembers(staging: File, keys: BackupCrypto.Keys) {
        val base = staging.canonicalFile
        val members = base.walkTopDown()
            .filter { it.isFile && it.name != "manifest.json" }.toList()
        for (file in members) {
            val rel = file.relativeTo(base).path.replace(File.separatorChar, '/')
            val key = if (rel == "secrets.json") keys.secretsKey else keys.dataKey
            val dest = File(file.parentFile, "${file.name}.enc")
            // AAD = the member's path INCLUDING the .enc suffix — exactly
            // what the importer reads off disk.
            BackupCrypto.encryptFile(file, dest, key, "$rel.enc")
            file.delete()
        }
        AppLogger.info(TAG, "[Backup] encrypted ${members.size} member(s)")
    }

    private fun writeBlobIndex(entries: List<BackupBlobIndexEntry>, staging: File) {
        File(staging, "blobs.index.jsonl").outputStream().buffered().use { out ->
            for (e in entries) {
                val line = BackupFormat.json
                    .encodeToString(BackupBlobIndexEntry.serializer(), e) + "\n"
                out.write(line.toByteArray(Charsets.UTF_8))
            }
        }
    }

    private fun buildManifest(
        backupId: String,
        categories: Map<String, BackupManifest.CategoryStat>,
        blobStore: BackupBlobStore,
        staging: File,
        encryption: BackupManifest.Encryption?,
        options: Options,
    ): BackupManifest {
        // Integrity covers every packaged file, hashed over whatever bytes
        // actually ship — ciphertext once encryption has run (§5.3).
        val base = staging.canonicalFile
        val integrity = base.walkTopDown()
            .filter { it.isFile && it.name != "manifest.json" } // can't hash itself
            .associate { file ->
                file.relativeTo(base).path.replace(File.separatorChar, '/') to BackupBlobStore.sha256OfFile(file)
            }

        return BackupManifest(
            createdAt = iso8601(System.currentTimeMillis()),
            snapshotAt = iso8601(options.snapshotAtMillis),
            app = BackupManifest.AppInfo(
                platform = "android",
                version = appVersion(),
                build = appBuild(),
            ),
            deviceName = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim(),
            backupId = backupId,
            categories = categories,
            limits = BackupManifest.Limits(
                maxFileBytes = blobStore.maxFileBytesForManifest,
                skippedFiles = blobStore.skippedFiles,
                skippedBytes = blobStore.skippedBytes,
            ),
            encryption = encryption,
            integrity = integrity,
            // Deliberately null — see the sidecar note in exportBody.
            manifestMac = null,
        )
    }

    private fun archive(staging: File, backupId: String): File {
        val stamp = IsoTime.formatCompactMinutes()
        // Minute resolution collides when two exports run in the same minute
        // (testing, or a retry with a different selection) - the second would
        // silently replace the first.
        val short = backupId.take(6).lowercase()
        val out = File(backupsDirectory(context), "backup-$stamp-$short.${BackupFormat.FILE_EXTENSION}")
        out.parentFile?.mkdirs()
        out.delete()

        // Write to a `.partial` sibling and rename on success, so a kill or
        // full disk can never leave a truncated file under a valid `.minisbak`.
        val partial = File(out.parentFile, ".${out.name}.partial")
        partial.delete()
        try {
            BackupZip.archive(staging, partial)
            // moveIntoPlace: per-path lock + copy-over fallback; false (bytes kept, partial NOT deleted) throws the same BackupException.
            if (!AtomicFileWrite.moveIntoPlace(partial, out)) {
                throw BackupException("Could not finalise the backup package.")
            }
        } catch (e: Exception) {
            partial.delete()
            throw e
        }
        return out
    }


    // MARK: - Helpers

    private fun parseParts(partsJson: String): JsonElement =
        parseJsonOrNull(partsJson) ?: BackupFormat.json.parseToJsonElement("[]")

    private fun parseJsonOrNull(raw: String): JsonElement? =
        runCatching { BackupFormat.json.parseToJsonElement(raw) }.getOrNull()

    private fun appVersion(): String = runCatching {
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private fun appBuild(): String = runCatching {
        @Suppress("DEPRECATION")
        context.packageManager.getPackageInfo(context.packageName, 0).versionCode.toString()
    }.getOrDefault("?")

    companion object {
        private const val TAG = "Backup"

        /**
         * Process-wide export/restore lock. Both read the same stores, and a
         * restore mutates them, so they must never overlap (iOS review I3).
         */
        private val activityLock = Mutex()

        /**
         * Where finished packages live — NOT inside `minis-global/shared`:
         * that path is bind-mounted into the guest, so a package (possibly
         * holding API keys) would be agent-readable and the next backup would
         * sweep the previous one in as user data (§6.2.4).
         */
        fun backupsDirectory(context: Context): File =
            File(context.filesDir, BackupFileTreeExporter.BACKUPS_DIR_NAME).apply { mkdirs() }

        /**
         * [Wave3 #10a] Scratch root for the pre-zip staging tree, on
         * [Context.getExternalFilesDir] when available. Never user data:
         * excluded by [BackupFileTreeExporter.isBackupArtifact], cleared in
         * `finally`.
         */
        fun stagingRoot(context: Context): File {
            val base = context.getExternalFilesDir(null) ?: context.filesDir
            return File(base, BackupFileTreeExporter.STAGING_DIR_NAME)
        }

        /** One directory per skill; loose root files are not skills. */
        fun skillCount(skillsRoot: File): Int =
            skillsRoot.listFiles()?.count { it.isDirectory } ?: 0

        /**
         * Servers declared in `servers.json` (an
         * `{ "mcpServers": ... }` map, see MCPRepository). Returns 1 on
         * unreadable JSON rather than 0: the file exists and is backed up.
         */
        fun mcpServerCount(source: File): Int = runCatching {
            org.json.JSONObject(source.readText()).optJSONObject("mcpServers")?.length() ?: 1
        }.getOrDefault(1)

        /** ISO-8601 in UTC, matching Swift's `.iso8601` date encoding strategy. */
        fun iso8601(millis: Long): String = IsoTime.formatUtcSeconds(millis)
    }
}
