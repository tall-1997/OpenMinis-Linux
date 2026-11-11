package com.openminis.app.backup

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile
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

    val folders: List<Folder>
        get() = runCatching {
            val raw = prefs.getString(KEY, null) ?: return emptyList()
            json.decodeFromString(ListSerializer(Folder.serializer()), raw)
        }.getOrDefault(emptyList())

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
        prefs.edit()
            .putString(KEY, json.encodeToString(ListSerializer(Folder.serializer()), list))
            .apply()
    }

    companion object {
        private const val PREFS = "minis_phone_backup_folders"
        private const val KEY = "folders"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun treeDisplayName(context: Context, uri: Uri): String? {
            val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            val last = docId?.substringAfterLast(':')?.substringAfterLast('/')
            return last?.takeIf { it.isNotBlank() }
                ?: DocumentFile.fromTreeUri(context, uri)?.name
        }
    }
}
