package com.openminis.app.backup

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
import com.openminis.app.logging.AppLogger
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

/**
 * User-chosen folders on phone storage (SAF tree URIs) used as backup
 * destinations outside the app sandbox.
 */
class PhoneBackupFolderStore(private val context: Context) {

    @Serializable
    data class Folder(
        val id: String,
        val name: String,
        val treeUri: String,
        val enabled: Boolean = true,
    )

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * [T-backup-decode-honesty] The stored folders, or an empty list when none
     * is configured OR the stored bytes are unreadable. Both return empty (the
     * UI must not crash), but an unreadable config also latches
     * [configDecodeFailed] so the next backup run reports the condition instead
     * of quietly backing up to nothing. The latch clears on the first
     * successful read or write.
     */
    val folders: List<Folder>
        get() {
            val raw = runCatching { prefs.getString(KEY, null) }.getOrNull()
                ?: return emptyList() // never configured — not a failure
            val decoded = decodeFolders(raw)
            if (decoded != null) {
                if (configDecodeFailed) {
                    prefs.edit().putBoolean(KEY_CONFIG_DECODE_FAILED, false).apply()
                    AppLogger.info(TAG, "[Backup] phone-folder config readable again")
                }
                return decoded
            }
            prefs.edit().putBoolean(KEY_CONFIG_DECODE_FAILED, true).apply()
            AppLogger.error(
                TAG,
                "[Backup] stored phone-folder config is UNREADABLE; returning zero folders " +
                    "until it is re-saved (raw length=${raw.length})",
            )
            return emptyList()
        }

    /**
     * [T-backup-decode-honesty] True while the stored phone-folder config could
     * not be decoded. Backup runs consult this and refuse to write a success
     * record — the phone destinations the user configured are silently absent.
     */
    val configDecodeFailed: Boolean
        get() = runCatching { prefs.getBoolean(KEY_CONFIG_DECODE_FAILED, false) }.getOrDefault(false)

    val enabledFolders: List<Folder> get() = folders.filter { it.enabled }

    fun add(treeUri: Uri, displayName: String?): Folder {
        val flags = android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        runCatching {
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
        }
        val name = displayName?.trim()?.takeIf { it.isNotEmpty() }
            ?: DocumentFile.fromTreeUri(context, treeUri)?.name
            ?: "Phone storage"
        val folder = Folder(
            id = UUID.randomUUID().toString(),
            name = name,
            treeUri = treeUri.toString(),
            enabled = true,
        )
        save(folders + folder)
        return folder
    }

    fun setEnabled(id: String, enabled: Boolean) {
        save(folders.map { if (it.id == id) it.copy(enabled = enabled) else it })
    }

    fun remove(id: String) {
        save(folders.filterNot { it.id == id })
    }

    /**
     * Copy a finished package into [folder] as [displayName], replacing any
     * previous package of the same name.
     *
     * NOT delete-then-copy — anywhere. The old order (`existing?.delete()`
     * before a verified replacement existed) meant a stream failure, a full
     * disk or a process kill left the folder with NO package at all — the one
     * backup copy outside the app sandbox, destroyed by the act of replacing
     * it. The replacement is therefore always built and verified under a
     * scratch name first, and the old package is touched only once its
     * verified successor exists on the same provider (see [promote]).
     */
    fun copyPackage(folder: Folder, packageFile: File, displayName: String) {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(folder.treeUri))
            ?: throw IllegalStateException("Phone folder is no longer accessible.")
        // Scratch from a killed run is invisible to the restore list (the
        // filter requires `.minisbak`), so it would otherwise sit there
        // forever, full-size. Sweep it the way the rclone path sweeps its
        // own `.partial` uploads — but only OLD scratch: a concurrent backup
        // staging into the same folder owns a fresh file, and its mtime is
        // the proof.
        sweepStaleStaged(tree)
        val existing = tree.listFiles().firstOrNull { it.name == displayName }
        val staged = tree.createFile("application/octet-stream", stagedScratchName(displayName))
            ?: throw IllegalStateException("Could not stage $displayName in ${folder.name}.")
        try {
            context.contentResolver.openOutputStream(staged.uri)?.use { out ->
                packageFile.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("Could not write to ${folder.name}.")
            val copied = staged.length()
            if (copied != packageFile.length()) {
                throw IllegalStateException(
                    "Staged $displayName holds $copied bytes but the package is ${packageFile.length()}.",
                )
            }
            promote(tree, staged, existing, displayName, packageFile.length())
        } catch (e: Exception) {
            // The stage is ours — the old package is only ever touched after a
            // verified replacement exists, so removing the scratch cannot make
            // things worse.
            runCatching { staged.delete() }
            throw e
        }
    }

    /**
     * Move the verified stage into [displayName]'s place, never destroying
     * the old package before a verified successor exists.
     *
     * Order matters three ways:
     *
     *  - A rename within one directory is a single provider operation, so it
     *    is tried first, and the name is re-checked AFTER the rename: a
     *    provider that rewrites the requested name (deduping, character
     *    sanitising) used to pass a boolean-only check while stranding the
     *    package where [listPackages] can never match it.
     *  - On a name conflict the stage is copied to a SECOND, verified
     *    temporary document BEFORE the old package is deleted, so a mid-copy
     *    failure (full disk, dead provider) keeps the old package: the delete
     *    happens only once a byte-verified replacement already exists.
     *  - Only when the provider has no rename at all does the final land via
     *    a stream — fed from the verified temporary, and with that temporary
     *    kept on failure, so the bytes stay recoverable even then.
     */
    private fun promote(
        tree: DocumentFile,
        staged: DocumentFile,
        existing: DocumentFile?,
        displayName: String,
        expectedBytes: Long,
    ) {
        val renamed = runCatching {
            staged.renameTo(displayName) && staged.name == displayName
        }.getOrDefault(false)
        if (renamed) return // The stage IS the final now; nothing to clean up.

        if (existing == null) {
            // No package to protect: put the final bytes in place however the
            // provider allows. A failure leaves no package — but there was
            // none to lose.
            streamIntoFinal(tree, staged, displayName, expectedBytes)
            runCatching { staged.delete() }
            return
        }

        // Name conflict. Build a verified replacement under a temporary name
        // first; every failure below this point still has the old package.
        val temp = tree.createFile("application/octet-stream", stagedScratchName(displayName))
            ?: throw IllegalStateException("Could not stage the replacement of $displayName.")
        try {
            streamDocument(staged, temp)
            val copied = temp.length()
            if (copied != expectedBytes) {
                throw IllegalStateException(
                    "Replacement of $displayName holds $copied bytes but the package is $expectedBytes.",
                )
            }
        } catch (e: Exception) {
            // Old package untouched — remove only the scratch.
            runCatching { temp.delete() }
            throw e
        }
        // The verified successor exists. NOW the old package may go.
        if (!runCatching { existing.delete() }.getOrDefault(false)) {
            // A refused delete is a safe failure: nothing was destroyed.
            runCatching { temp.delete() }
            throw IllegalStateException(
                "Could not replace $displayName: the previous package could not be deleted.",
            )
        }
        val swapped = runCatching {
            temp.renameTo(displayName) && temp.name == displayName
        }.getOrDefault(false)
        if (!swapped) {
            // The provider has no rename at all: stream the verified temp
            // into the final name. The old package is already gone, but the
            // temp is a complete, byte-verified copy — on failure it is
            // deliberately KEPT (the scratch sweep reclaims it later) so the
            // bytes stay recoverable.
            streamIntoFinal(tree, temp, displayName, expectedBytes)
            runCatching { temp.delete() }
        }
        runCatching { staged.delete() }
    }

    /**
     * Stream [source]'s verified bytes into a fresh document named
     * [displayName], refusing to report success unless the provider stored
     * the real name AND the full byte count. A provider that rewrites the
     * requested name would otherwise leave a complete package sitting where
     * [listPackages] can never match it.
     */
    private fun streamIntoFinal(
        tree: DocumentFile,
        source: DocumentFile,
        displayName: String,
        expectedBytes: Long,
    ) {
        val final = tree.createFile("application/octet-stream", displayName)
            ?: throw IllegalStateException("Could not create $displayName.")
        try {
            if (final.name != displayName) {
                throw IllegalStateException(
                    "The provider stored $displayName as '${final.name}' — " +
                        "the package would never appear in the restore list.",
                )
            }
            streamDocument(source, final)
            val copied = final.length()
            if (copied != expectedBytes) {
                throw IllegalStateException(
                    "$displayName holds $copied bytes but the package is $expectedBytes.",
                )
            }
        } catch (e: Exception) {
            runCatching { final.delete() }
            throw e
        }
    }

    private fun streamDocument(source: DocumentFile, target: DocumentFile) {
        context.contentResolver.openInputStream(source.uri)?.use { input ->
            context.contentResolver.openOutputStream(target.uri)?.use { output ->
                input.copyTo(output)
            } ?: throw IllegalStateException("Could not write to ${target.name}.")
        } ?: throw IllegalStateException("Could not read the staged copy.")
    }

    /**
     * Delete staged scratch copies left behind by runs that died mid-copy.
     *
     * Recognised by BOTH the prefix and the suffix, so a provider that
     * rewrites the middle of the name still matches and nothing a user keeps
     * in this folder can collide on both ends. Only scratch older than
     * [STAGED_MAX_AGE_MS] is removed: a file whose age is fresh (or unknown)
     * belongs to a backup that may be running RIGHT NOW in another
     * destination's delivery, and deleting it was exactly how this sweep used
     * to destroy a concurrent run's staging copy.
     */
    private fun sweepStaleStaged(tree: DocumentFile) {
        val oldest = System.currentTimeMillis() - STAGED_MAX_AGE_MS
        for (child in tree.listFiles()) {
            val name = child.name ?: continue
            if (!child.isFile) continue
            if (!name.startsWith(STAGED_PREFIX) || !name.endsWith(".$STAGED_SUFFIX")) continue
            // An unreadable mtime is treated as "in use": failing safe here
            // strands a scratch file for one more run at worst.
            val modified = runCatching { child.lastModified() }.getOrDefault(0L)
            if (modified <= 0 || modified > oldest) continue
            runCatching { child.delete() }
        }
    }

    /**
     * Scratch name for an in-flight package copy: recognisable prefix, unique
     * middle (so two concurrent deliveries never stage into the same file),
     * the package name (so a human can tell what it was), scratch suffix.
     */
    private fun stagedScratchName(displayName: String): String =
        "$STAGED_PREFIX-${System.currentTimeMillis()}-${UUID.randomUUID()}." +
            "$displayName.$STAGED_SUFFIX"

    fun listPackages(folder: Folder): List<DocumentFile> {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(folder.treeUri)) ?: return emptyList()
        return tree.listFiles()
            .filter { it.isFile && (it.name?.endsWith(".minisbak", ignoreCase = true) == true) }
            .sortedByDescending { it.lastModified() }
    }

    /**
     * [GH#367] Delete one package from a phone folder. Returns false only when
     * the folder is unreachable or the SAF provider refuses the delete; a file
     * that is already gone counts as success (idempotent cleanup).
     *
     * The reachability check is the fix for the "delete succeeded but the file
     * is still there" case: when the persisted tree permission was revoked,
     * `DocumentFile.listFiles()` can throw (the old body let it escape into the
     * caller's runCatching) or, worse, report an EMPTY list. An empty list then
     * read as "package already deleted" -> the caller deleted the local
     * fallback too, destroying the last copy. So:
     *  - resolve and `exists()` the tree first; unreachable -> false (failure);
     *  - a `listFiles()`/permission failure -> false (query failure, NOT
     *    "absent");
     *  - only a reachable tree with no matching file is treated as success.
     */
    fun deletePackage(folder: Folder, displayName: String): Boolean {
        val tree = runCatching {
            DocumentFile.fromTreeUri(context, Uri.parse(folder.treeUri))
        }.getOrNull() ?: return false
        // exists() itself can throw SecurityException after a permission revoke.
        if (!runCatching { tree.exists() }.getOrDefault(false)) return false
        val files = runCatching { tree.listFiles() }.getOrNull() ?: return false
        val matches = files.filter { it.isFile && it.name == displayName }
        if (matches.isEmpty()) return true
        return matches.all { file -> runCatching { file.delete() }.getOrDefault(false) }
    }

    private fun save(list: List<Folder>) {
        // [T-backup-decode-honesty] A successful write replaces whatever corrupt
        // bytes were there — the unreadable latch must not outlive them.
        prefs.edit()
            .putString(KEY, json.encodeToString(ListSerializer(Folder.serializer()), list))
            .putBoolean(KEY_CONFIG_DECODE_FAILED, false)
            .apply()
    }

    companion object {
        private const val TAG = "Backup"
        private const val PREFS = "minis_phone_backup_folders"
        private const val KEY = "folders"

        /**
         * Prefix + suffix pair that identifies the in-flight copy of a package
         * delivery. Hidden from the restore list by not ending in `.minisbak`,
         * and swept by [copyPackage] on the next run — but only once
         * [STAGED_MAX_AGE_MS] old, so a concurrent backup's fresh staging file
         * is never destroyed by another run's housekeeping.
         */
        private const val STAGED_PREFIX = "minis-staged"

        private const val STAGED_SUFFIX = "partial"

        /** Scratch older than this is an orphan; anything younger may belong
         *  to a concurrent backup still copying into it. */
        private const val STAGED_MAX_AGE_MS = 1L * 60 * 60 * 1000

        /** [T-backup-decode-honesty] Latched while the stored config is unreadable. */
        private const val KEY_CONFIG_DECODE_FAILED = "config_decode_failed"

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        /**
         * [T-backup-decode-honesty] Decode the stored folder list; null when the
         * bytes are unreadable. Pure so the contract is testable without prefs.
         */
        internal fun decodeFolders(raw: String): List<Folder>? =
            runCatching { json.decodeFromString(ListSerializer(Folder.serializer()), raw) }.getOrNull()

        fun treeDisplayName(context: Context, uri: Uri): String? {
            val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            val last = docId?.substringAfterLast(':')?.substringAfterLast('/')
            return last?.takeIf { it.isNotBlank() }
                ?: DocumentFile.fromTreeUri(context, uri)?.name
        }
    }
}
