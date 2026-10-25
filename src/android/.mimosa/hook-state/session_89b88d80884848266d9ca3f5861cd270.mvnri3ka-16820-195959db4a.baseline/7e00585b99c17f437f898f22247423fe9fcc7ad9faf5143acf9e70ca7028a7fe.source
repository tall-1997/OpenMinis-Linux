package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ProcfsSnapshotTest {

    @Test
    fun parseStatComm_readsParenthesizedName() {
        assertEquals("proot", ProcfsSnapshot.parseStatComm("4123 (proot) S 1 1 1 0 -1"))
        assertEquals("minis ultra", ProcfsSnapshot.parseStatComm("1 (minis ultra) R 0"))
        assertNull(ProcfsSnapshot.parseStatComm("no-parens"))
    }

    @Test
    fun parseStatusUid_readsFirstUidField() {
        val status = "Name:\tapp\nUmask:\t0022\nUid:\t10123 10123 10123 10123\nGid:\t10123\n"
        assertEquals(10123, ProcfsSnapshot.parseStatusUid(status))
        assertNull(ProcfsSnapshot.parseStatusUid("Name:\tapp\n"))
    }
}
