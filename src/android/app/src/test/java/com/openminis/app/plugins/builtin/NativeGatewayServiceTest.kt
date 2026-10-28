package com.openminis.app.plugins.builtin

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * [T-builtin-plugins] Pure-function contract tests for the native gateway
 * service. The host process is not runnable in a JVM unit test, so these pin
 * the shapes the launch contract depends on:
 *  - launchArgv is exactly one element — the APK's own binary from
 *    nativeLibraryDir, no shell, no wrapper, no CLI args (the upstream
 *    binary parses none; even `--help` boots the server)
 *  - the native env carries the home layout the binary expects
 *  - orphanPids scans /proc by argv[0] — the stop path's orphan sweep
 *
 * Robolectric supplies the [Context]: launchArgv resolves the binary out of
 * `applicationInfo.nativeLibraryDir`, which a bare JVM `Context` cannot
 * provide (the earlier hand-rolled FakeContext never compiled). The field is
 * pointed at a fixed fake path so the assertion pins the argv shape, not
 * whatever directory the host runner happens to use.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class NativeGatewayServiceTest {

    private val a2a = BuiltinPluginCatalog.ALL.first { it.id == "a2a" }

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.applicationInfo.nativeLibraryDir = "/fake/nativeDir"
    }

    @Test fun `launchArgv is the bare nativeLibraryDir binary`() {
        val argv = NativeGatewayService.launchArgv(context, a2a)
        assertEquals(listOf("/fake/nativeDir/liba2a.so"), argv)
    }

    @Test fun `native env carries home data and tmp`() {
        val env = BuiltinPluginCatalog.nativeEnv(a2a, "/data/files")
        assertEquals("127.0.0.1", env["AGENT2API_HOST"])
        assertEquals("18901", env["AGENT2API_PROXY_PORT"])
        assertEquals("/data/files/builtin-plugins/a2a/data", env["AGENT2API_PROXY_HOME"])
        assertEquals("/data/files/builtin-plugins/a2a/ui", env["AGENT2API_UI_DIR"])
        assertEquals("1", env["AGENT2API_ALLOW_NO_KEY"])
        assertEquals("/data/files/builtin-plugins/a2a/data", env["HOME"])
        assertEquals("/data/files/builtin-plugins/a2a/data/tmp", env["TMPDIR"])
    }

    @Test fun `native home dir is filesDir-scoped`() {
        assertEquals(
            "/data/files/builtin-plugins/a2a",
            BuiltinPluginCatalog.nativeHomeDir("/data/files", "a2a"),
        )
    }

    /**
     * The orphan sweep must match on the exact binary path, nothing looser.
     * On the JVM there is no /proc entry running our fake binary, so the
     * scan finds nothing — which pins the two properties that matter: it
     * never throws on a host without /proc entries, and it never matches
     * foreign processes (empty result, not a wrong pid).
     */
    @Test fun `orphanPids finds nothing on a host without our binary`() {
        val pids = NativeGatewayService.orphanPids(context, a2a)
        assertTrue("expected no matches, got $pids", pids.isEmpty())
    }
}
