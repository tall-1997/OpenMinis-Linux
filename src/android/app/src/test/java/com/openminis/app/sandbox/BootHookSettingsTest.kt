package com.openminis.app.sandbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * [GH#511] The boot hook's privilege gate.
 *
 * `/var/minis/boot.sh` lives inside the agent-writable rootfs, so executing it
 * without an explicit user opt-in turns "write a file" into "run code on every
 * future launch". These tests pin:
 *  - the master switch is OFF for upgraded/new installs,
 *  - OFF means [BootHook.launch] never executes the script,
 *  - ON still refuses when the script does not exist.
 */
@RunWith(RobolectricTestRunner::class)
class BootHookSettingsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        BootHookSettings.resetForTest(context)
        BootHook.resetForTest()
    }

    @Test
    fun `master switch defaults to off`() {
        assertFalse(BootHookSettings.isEnabled(context))
    }

    @Test
    fun `master switch persists an explicit opt-in`() {
        BootHookSettings.setEnabled(context, true)
        assertTrue(BootHookSettings.isEnabled(context))

        BootHookSettings.setEnabled(context, false)
        assertFalse(BootHookSettings.isEnabled(context))
    }

    @Test
    fun `disabled hook skips an existing script without executing it`() {
        val rootfs = File(context.filesDir, "rootfs-disabled")
        val script = File(rootfs, "var/minis/boot.sh")
        script.parentFile?.mkdirs()
        script.writeText("echo should-not-run")

        BootHook.launch(context, rootfs)

        assertEquals("skipped: disabled in Settings", BootHook.lastStatus)
    }

    @Test
    fun `enabled hook still refuses when the script is absent`() {
        BootHookSettings.setEnabled(context, true)
        val rootfs = File(context.filesDir, "rootfs-empty").apply { mkdirs() }

        BootHook.launch(context, rootfs)

        assertEquals("skipped: /var/minis/boot.sh not present", BootHook.lastStatus)
    }
}
