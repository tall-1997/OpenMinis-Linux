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

    /**
     * Copy [packageFile] into [folder] as [displayName].
     *
     * Written to a `.partial` scratch name and renamed only after the bytes are
     * flushed ([T-android-backup-local-folder]): a copy interrupted by process
     * death, a full volume or a pulled SD card must not leave a truncated file
     * under a name the restore picker accepts — the picker matches `.minisbak`,
     * so a leftover stays recognisable as scratch rather than as a corrupt
     * backup.
     *
     * [isCancelled] is polled per buffer, so Stop ends a copy to a slow folder
     * (an SD card, a USB drive over OTG, a network-backed provider) mid-file
     * instead of after it. Cancelling throws [kotlinx.coroutines.CancellationException]
     * — the run is stopped, not failed — and the partial file is removed.
     */
    fun copyPackage(
        folder: Folder,
        packageFile: File,
        displayName: String,
        isCancelled: () -> Boolean = { false },
    ) {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(folder.treeUri))
            ?: throw IllegalStateException("Phone folder is no longer accessible.")
        val partialName = "$displayName.partial"
        // Re-delivering the same backup id must not accumulate copies, and SAF
        // de-duplicates names silently ("name (1).minisbak") rather than
        // overwriting — so clear the way first.
        tree.findFile(partialName)?.delete()
        val partial = tree.createFile(MIME_TYPE, partialName)
            ?: throw IllegalStateException("Could not create $displayName in ${folder.name}.")
        try {
            context.contentResolver.openOutputStream(partial.uri, "w")?.use { out ->
                packageFile.inputStream().use { input ->
                    val buf = ByteArray(DEFAULT_BUFFER_SIZE)
                    var sent = 0L
                    while (true) {
                        if (isCancelled()) throw kotlinx.coroutines.CancellationException("stopped")
                        val read = input.read(buf)
                        if (read <= 0) break
                        out.write(buf, 0, read)
                        sent += read
                    }
                    // The provider buffers; without this the rename below can
                    // promote a file whose tail is still in flight.
                    out.flush()
                }
            } ?: throw IllegalStateException("Could not write to ${folder.name}.")
        } catch (e: Exception) {
            // Leave nothing half-written behind under any name.
            runCatching { partial.delete() }
            throw e
        }
        // Replacing an existing package of the same name is deliberate: the
        // name carries the backup id, so a same-name file IS this backup being
        // re-delivered.
        tree.findFile(displayName)?.delete()
        if (!partial.renameTo(displayName)) {
            runCatching { partial.delete() }
            throw IllegalStateException("Couldn't finish writing to ${folder.name}.")
        }
    }

    fun listPackages(folder: Folder): List<DocumentFile> {
        val tree = DocumentFile.fromTreeUri(context, Uri.parse(folder.treeUri)) ?: return emptyList()
        return tree.listFiles()
            .filter { it.isFile && (it.name?.endsWith(".minisbak", ignoreCase = true) == true) }
            .sortedByDescending { it.lastModified() }
    }

    private fun save(list: List<Folder>) {
        prefs.edit()
            .putString(KEY, json.encodeToString(ListSerializer(Folder.serializer()), list))
            .apply()
    }

    companion object {
        private const val PREFS = "minis_phone_backup_folders"
        private const val KEY = "folders"

        /**
         * `.minisbak` has no registered MIME type. octet-stream keeps SAF from
         * appending an extension of its own choosing to the name we pass —
         * which is what it does with a type it recognises.
         */
        private const val MIME_TYPE = "application/octet-stream"
        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun treeDisplayName(context: Context, uri: Uri): String? {
            val docId = runCatching { DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
            val last = docId?.substringAfterLast(':')?.substringAfterLast('/')
            return last?.takeIf { it.isNotBlank() }
                ?: DocumentFile.fromTreeUri(context, uri)?.name
        }
    }
}
