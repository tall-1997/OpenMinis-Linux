package com.openminis.app.a2a

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Contract tests for the glibc runtime's DNS files — the exact text forms
 * the bundled glibc parses, and the idempotence of the static seeds
 * (existing files must never be overwritten, so a manual hosts edit
 * survives every gateway start).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class A2aDnsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.applicationInfo.nativeLibraryDir = "/fake/nativeDir"
    }

    @Test fun `resolv text pins the nameserver format`() {
        val text = A2aDns.resolvConfText(listOf("192.168.1.1", "2400:3200::1"))
        assertEquals(
            "# written by com.openminis.app a2a (do not edit — rewritten on gateway start)\n" +
                "nameserver 192.168.1.1\n" +
                "nameserver 2400:3200::1\n",
            text,
        )
    }

    @Test fun `resolv text is null for an empty server list`() {
        // An empty list means the network reported nothing — the caller
        // must keep the last-known file instead of writing an empty one.
        assertNull(A2aDns.resolvConfText(emptyList()))
    }

    @Test fun `static hosts carries the loopback lines`() {
        val text = A2aDns.staticHostsText()
        assertTrue(text, text.contains("127.0.0.1 localhost"))
        assertTrue(text, text.contains("::1 localhost ip6-localhost"))
    }

    @Test fun `nsswitch resolves files before dns`() {
        assertEquals(
            "# nsswitch.conf for the a2a glibc runtime\nhosts: files dns\n",
            A2aDns.staticNsswitchText(),
        )
    }

    @Test fun `ensureStatic seeds missing files only`() {
        val data = File(A2aGatewayProcess.homeDir(context.filesDir.path), "data")
        assertTrue(data.mkdirs())
        val hosts = File(data, A2aDns.HOSTS)
        val nsswitch = File(data, A2aDns.NSSWITCH)

        A2aDns.ensureStatic(context)
        assertTrue(hosts.isFile)
        assertTrue(nsswitch.isFile)

        // Manual edits survive — the seed never overwrites existing files.
        hosts.writeText("# my own hosts\n")
        A2aDns.ensureStatic(context)
        assertEquals("# my own hosts\n", hosts.readText())

        // The resolv.conf is NOT part of the static seed (only refresh
        // writes it) — the seed never fabricates a DNS state.
        assertTrue(!File(data, A2aDns.RESOLV_CONF).exists())
    }
}
