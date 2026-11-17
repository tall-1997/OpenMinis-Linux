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

    fun copyPackage(folder: Folder, packageFile: File, displayName: String) {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(folder.treeUri))
            ?: throw IllegalStateException("Phone folder is no longer accessible.")
        val existing = tree.listFiles().firstOrNull { it.name == displayName }
        existing?.delete()
        val dest = tree.createFile("application/octet-stream", displayName)
            ?: throw IllegalStateException("Could not create $displayName in ${folder.name}.")
        context.contentResolver.openOutputStream(dest.uri)?.use { out ->
            packageFile.inputStream().use { it.copyTo(out) }
        } ?: throw IllegalStateException("Could not write to ${folder.name}.")
    }

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
