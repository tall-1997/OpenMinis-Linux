package com.openminis.app.ui.settings.backup

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.openminis.app.backup.BackupExporter
import com.openminis.app.backup.BackupHistory
import com.openminis.app.backup.PhoneBackupFolderStore
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [GH#367] Deletion-path contract, with the rclone / SAF sides injected.
 *
 * These branches used to require a real revoked SAF grant or a renamed remote
 * to reproduce. The invariants pinned here are the ones that turn "remove
 * backup" into irreversible data loss when broken:
 *  - an unverifiable / missing destination NEVER deletes the local fallback;
 *  - a failed delete NEVER deletes the local fallback;
 *  - only a fully clean run deletes the local package;
 *  - a local-only record (no destination succeeded) IS deletable — that is the
 *    new history-screen entry.
 */
@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class) // [T-robolectric-bare-app]
class BackupHistoryDeletionTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
    }

    private fun packageName() = "backup-${UUID.randomUUID()}.minisbak"

    private fun localFile(name: String): File =
        File(BackupExporter.backupsDirectory(context), name).apply { writeText("package") }

    private fun record(
        packageName: String,
        destinations: List<BackupHistory.DestinationOutcome> = emptyList(),
    ) = BackupHistory.Record(
        backupId = "backup-id",
        startedAt = System.currentTimeMillis(),
        status = BackupHistory.Status.SUCCEEDED,
        packageName = packageName,
        destinations = destinations,
    )

    private class FakePhone(
        override val folders: List<PhoneBackupFolderStore.Folder>,
        private val result: String?,
        val deletes: MutableList<String> = mutableListOf(),
    ) : PhoneFolderDeletionGateway {
        override fun deletePackage(folder: PhoneBackupFolderStore.Folder, displayName: String): String? {
            deletes += folder.name
            return result
        }
    }

    private class FakeRemote(
        private val names: Set<String>?,
        private val result: String?,
        val deletes: MutableList<String> = mutableListOf(),
    ) : RemotePackageDeletionGateway {
        override fun remoteNames(): Set<String>? = names
        override fun deletePackage(remoteName: String, displayName: String): String? {
            deletes += remoteName
            return result
        }
    }

    private fun remoteOutcome(name: String = "nas") =
        BackupHistory.DestinationOutcome(name = name, succeeded = true)

    private fun phoneOutcome(folder: PhoneBackupFolderStore.Folder) =
        BackupHistory.DestinationOutcome(
            name = folder.name,
            succeeded = true,
            kind = "phone",
            path = folder.treeUri,
        )

    @Test
    fun `remote no longer configured keeps the local fallback`() = runBlocking {
        val name = packageName()
        val local = localFile(name)
        val remote = FakeRemote(names = emptySet(), result = null)

        deleteBackupPackageFiles(
            context = context,
            record = record(name, listOf(remoteOutcome())),
            remoteGateway = remote,
        )

        assertTrue("unverifiable remote must not cost the local copy", local.exists())
        assertTrue("must not attempt a delete on a missing remote", remote.deletes.isEmpty())
    }

    @Test
    fun `unreadable remote list keeps the local fallback`() = runBlocking {
        val name = packageName()
        val local = localFile(name)
        val remote = FakeRemote(names = null, result = null)

        deleteBackupPackageFiles(
            context = context,
            record = record(name, listOf(remoteOutcome())),
            remoteGateway = remote,
        )

        assertTrue("a failed query is not proof the file is gone", local.exists())
        assertTrue(remote.deletes.isEmpty())
    }

    @Test
    fun `remote delete failure keeps the local fallback`() = runBlocking {
        val name = packageName()
        val local = localFile(name)
        val remote = FakeRemote(names = setOf("nas"), result = "network unreachable")

        deleteBackupPackageFiles(
            context = context,
            record = record(name, listOf(remoteOutcome())),
            remoteGateway = remote,
        )

        assertTrue(local.exists())
        assertTrue(remote.deletes.contains("nas"))
    }

    @Test
    fun `remote delete success removes the local fallback`() = runBlocking {
        val name = packageName()
        val local = localFile(name)
        val remote = FakeRemote(names = setOf("nas"), result = null)

        deleteBackupPackageFiles(
            context = context,
            record = record(name, listOf(remoteOutcome())),
            remoteGateway = remote,
        )

        assertFalse("every destination was cleaned", local.exists())
    }

    @Test
    fun `unreachable saf folder keeps the local fallback`() = runBlocking {
        val name = packageName()
        val local = localFile(name)
        val folder = PhoneBackupFolderStore.Folder(
            id = "folder-1",
            name = "Phone",
            treeUri = "content://com.android.externalstorage.documents/tree/primary%3ABackups",
        )
        val phone = FakePhone(
            folders = listOf(folder),
            result = "folder is unreachable or the SAF provider refused the delete",
        )

        deleteBackupPackageFiles(
            context = context,
            record = record(name, listOf(phoneOutcome(folder))),
            phoneGateway = phone,
        )

        assertTrue("revoked SAF grant must not delete the only copy", local.exists())
        assertTrue(phone.deletes.contains("Phone"))
    }

    @Test
    fun `missing saf folder keeps the local fallback`() = runBlocking {
        val name = packageName()
        val local = localFile(name)
        val phone = FakePhone(folders = emptyList(), result = null)

        deleteBackupPackageFiles(
            context = context,
            record = record(
                name,
                listOf(
                    BackupHistory.DestinationOutcome(
                        name = "Phone",
                        succeeded = true,
                        kind = "phone",
                        path = "content://tree/gone",
                    ),
                ),
            ),
            phoneGateway = phone,
        )

        assertTrue(local.exists())
        assertTrue(phone.deletes.isEmpty())
    }

    @Test
    fun `saf delete success removes the local fallback`() = runBlocking {
        val name = packageName()
        val local = localFile(name)
        val folder = PhoneBackupFolderStore.Folder(
            id = "folder-1",
            name = "Phone",
            treeUri = "content://tree/backups",
        )
        val phone = FakePhone(folders = listOf(folder), result = null)

        deleteBackupPackageFiles(
            context = context,
            record = record(name, listOf(phoneOutcome(folder))),
            phoneGateway = phone,
        )

        assertFalse(local.exists())
    }

    @Test
    fun `local-only record still deletes the local package`() = runBlocking {
        val name = packageName()
        val local = localFile(name)

        deleteBackupPackageFiles(
            context = context,
            record = record(name, destinations = emptyList()),
        )

        assertFalse("history screen now offers this cleanup path", local.exists())
    }
}
