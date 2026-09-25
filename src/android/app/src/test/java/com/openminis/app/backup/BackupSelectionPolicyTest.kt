package com.openminis.app.backup

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSelectionPolicyTest {
    @Test
    fun `large reproducible trees are opt in`() {
        assertFalse(BackupCategory.ROOTFS in BackupCategory.backupable)
        assertFalse(BackupCategory.CACHE in BackupCategory.backupable)
        assertTrue(BackupCategory.ROOTFS in BackupCategory.optionalLarge)
        assertTrue(BackupCategory.CACHE in BackupCategory.optionalLarge)
    }

    @Test
    fun `rootfs and cache use file tree policy`() {
        assertTrue(BackupCategory.ROOTFS.carriesFileTree)
        assertTrue(BackupCategory.CACHE.carriesFileTree)
    }
}
