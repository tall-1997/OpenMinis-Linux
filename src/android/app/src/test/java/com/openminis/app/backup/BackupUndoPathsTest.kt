package com.openminis.app.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-backup-undo-paths] Pins the category -> on-disk-root table that the undo
 * snapshot depends on.
 *
 * Each expectation cites the `BackupImporter` function that writes there. If a
 * future importer change moves a category's write target, this test is what
 * fails — not a user's undo point that silently stops covering chats.
 */
class BackupUndoPathsTest {

    @Test
    fun `chats covers database bodies and session artefacts`() {
        assertEquals(
            listOf(
                BackupUndoPaths.DB_DIR,
                BackupUndoPaths.BODIES_DIR,
                BackupUndoPaths.SESSIONS_DIR,
            ),
            BackupUndoPaths.pathsFor(setOf(BackupCategory.CHATS)),
        )
    }

    @Test
    fun `providers and env vars share one prefs root without duplicating it`() {
        assertEquals(
            listOf(BackupUndoPaths.PREFS_DIR),
            BackupUndoPaths.pathsFor(
                setOf(BackupCategory.PROVIDERS, BackupCategory.ENVIRONMENT_VARIABLES),
            ),
        )
    }

    @Test
    fun `global tree categories map to their own subdirectories`() {
        assertEquals(
            listOf("minis-global/shared", "minis-global/skills", "minis-global/memory", "minis-global/mcp-servers"),
            BackupUndoPaths.pathsFor(
                setOf(
                    BackupCategory.SHARED_FILES,
                    BackupCategory.SKILLS,
                    BackupCategory.MEMORY,
                    BackupCategory.MCP_SERVERS,
                ),
            ),
        )
    }

    @Test
    fun `non backupable categories contribute nothing`() {
        assertTrue(
            BackupUndoPaths.pathsFor(
                setOf(BackupCategory.ROOTFS, BackupCategory.CACHE, BackupCategory.VOICE_CORRECTIONS),
            ).isEmpty(),
        )
    }

    @Test
    fun `empty selection has no roots`() {
        assertTrue(BackupUndoPaths.pathsFor(emptySet()).isEmpty())
    }

    @Test
    fun `every backupable category is covered by at least one root`() {
        for (category in BackupCategory.backupable) {
            assertTrue(
                "no undo root for ${category.key}",
                BackupUndoPaths.pathsFor(setOf(category)).isNotEmpty(),
            )
        }
    }
}
