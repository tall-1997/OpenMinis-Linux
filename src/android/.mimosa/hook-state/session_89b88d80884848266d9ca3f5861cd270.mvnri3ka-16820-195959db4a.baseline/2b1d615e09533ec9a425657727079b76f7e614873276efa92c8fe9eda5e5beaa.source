package com.openminis.app.security

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guest `/data` is not the phone's `/data`, and `/sdcard` only exists once All
 * Files Access makes the bind mount real. Commands aimed at those paths used to
 * "succeed" while doing nothing (`rm` of a missing path exits 0), so the policy
 * has to reject them instead. These cases pin the boundary that [GuestMountPolicy]
 * is wired to enforce.
 */
class GuestMountPolicyTest {

    @Test
    fun rejectsGuestDataEvenWhenSdcardMounted() {
        val msg = GuestMountPolicy.rejection("ls /data/data/com.example", sdcardMounted = true)
        assertNotNull(msg)
        assertTrue(msg!!.contains("/data"))
    }

    @Test
    fun rejectsBareGuestDataPath() {
        assertNotNull(GuestMountPolicy.rejection("stat /data", sdcardMounted = true))
    }

    @Test
    fun rejectsSdcardOnlyWhenNotMounted() {
        assertNotNull(GuestMountPolicy.rejection("ls /sdcard/Download", sdcardMounted = false))
        assertNotNull(GuestMountPolicy.rejection("ls /storage/emulated/0/DCIM", sdcardMounted = false))
        assertNull(GuestMountPolicy.rejection("ls /sdcard/Download", sdcardMounted = true))
        assertNull(GuestMountPolicy.rejection("ls /storage/emulated/0/DCIM", sdcardMounted = true))
    }

    @Test
    fun unwrapsNestedShellSegments() {
        assertNotNull(GuestMountPolicy.rejection("sh -c 'ls /sdcard'", sdcardMounted = false))
        assertNotNull(GuestMountPolicy.rejection("bash -c \"rm -rf /data/tmp\"", sdcardMounted = true))
    }

    @Test
    fun skipsHostSuPayload() {
        // `android-su -c` runs on the real host filesystem, where /data *is* the
        // phone's tree. SuPathPolicy owns those denials; this policy must not
        // second-guess them, or every `android-su` command becomes unusable.
        assertNull(GuestMountPolicy.rejection("android-su -c 'ls /data'", sdcardMounted = false))
        assertNull(GuestMountPolicy.rejection("su -c 'stat /sdcard'", sdcardMounted = false))
    }

    @Test
    fun leavesGuestWorkspaceAlone() {
        assertNull(GuestMountPolicy.rejection("ls /var/minis/workspace", sdcardMounted = false))
        assertNull(GuestMountPolicy.rejection("python3 -c 'print(1)'", sdcardMounted = false))
        // A name that merely starts with the same characters is not the path.
        assertNull(GuestMountPolicy.rejection("ls /database", sdcardMounted = true))
    }
}
