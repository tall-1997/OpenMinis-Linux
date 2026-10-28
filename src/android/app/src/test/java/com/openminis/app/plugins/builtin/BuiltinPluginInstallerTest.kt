package com.openminis.app.plugins.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-builtin-plugins] Pure-function contract tests for the installer's
 * command builders. The python mirror (sim_builtin_installer.py) runs the
 * same commands for real in the guest; these pin the shapes the guest
 * script depends on:
 *  - nodeMajorOf parses every `node --version` shape we've seen
 *  - ensureNodeCommand no-ops on a passing probe and only fetches when
 *    short, from the npmmirror tarball, into /usr/local (first candidate
 *    of the upstream launcher's pick_node)
 *  - installCommand unzips the staged zip, guards the install script, and
 *    applies the payload's own `<PREFIX>_*` env with `env` so it survives
 *    shell wrapping
 *  - verifyCommand runs upstream verify.sh under the same env
 */
class BuiltinPluginInstallerTest {

    private val ofm = BuiltinPluginCatalog.ALL.first()
    private val a2a = BuiltinPluginCatalog.ALL.first { it.id == "a2a" }

    // --- native routing ---------------------------------------------------

    @Test fun `native plugins never enter the guest pipeline`() {
        assertTrue(a2a.isNative)
        // The guest entry point refuses a native descriptor before any
        // guest work starts. The gate is pure — no Context needed, so the
        // JVM test pins the contract without Robolectric.
        val thrown = runCatching {
            BuiltinPluginInstaller.requireGuestPlugin(a2a)
        }
        assertTrue(thrown.exceptionOrNull() is IllegalArgumentException)
    }

    // --- nodeMajorOf -----------------------------------------------------

    @Test fun `nodeMajorOf parses v22 style`() {
        assertEquals(22, BuiltinPluginInstaller.nodeMajorOf("v22.14.0"))
    }

    @Test fun `nodeMajorOf parses bare 18 style`() {
        assertEquals(18, BuiltinPluginInstaller.nodeMajorOf("18.19.1"))
    }

    @Test fun `nodeMajorOf rejects garbage`() {
        assertNull(BuiltinPluginInstaller.nodeMajorOf("command not found"))
        assertNull(BuiltinPluginInstaller.nodeMajorOf(""))
    }

    // --- ensureNodeCommand -----------------------------------------------

    @Test fun `ensureNodeCommand skips fetch when node is new enough`() {
        val cmd = BuiltinPluginInstaller.ensureNodeCommand(22)
        assertTrue(cmd.contains("m=$(node --version"))
        assertTrue(cmd.contains("-ge 22"))
        assertTrue(cmd.contains("node --version; exit 0"))
    }

    @Test fun `ensureNodeCommand fetches pinned arm64 tarball from npmmirror`() {
        val cmd = BuiltinPluginInstaller.ensureNodeCommand(22)
        val v = BuiltinPluginInstaller.NODE_VERSION
        assertTrue(cmd.contains("node-v$v-linux-arm64.tar.xz"))
        assertTrue(cmd.contains("/usr/local/lib/node-v$v"))
        assertTrue(cmd.contains("ln -sfn /usr/local/lib/node-v$v/bin/node /usr/local/bin/node"))
    }

    // --- installCommand ---------------------------------------------------

    @Test fun `installCommand unzips staged zip into install root`() {
        val cmd = BuiltinPluginInstaller.installCommand(ofm, "payload-2.3.0.zip")
        // -o: reinstall overwrites without the interactive replace prompt.
        assertTrue(cmd.contains("unzip -o -q /var/minis/shared/builtin-plugins/ofm/payload-2.3.0.zip"))
        assertTrue(cmd.contains("-d /var/minis/shared/builtin-plugins/ofm"))
    }

    @Test fun `installCommand guards the upstream install script`() {
        val cmd = BuiltinPluginInstaller.installCommand(ofm, "payload-2.3.0.zip")
        assertTrue(cmd.contains("test -f /var/minis/shared/builtin-plugins/ofm/payload/scripts/install.sh"))
        assertTrue(cmd.contains("exit 6"))
    }

    @Test fun `installCommand applies the payload's own env contract`() {
        val cmd = BuiltinPluginInstaller.installCommand(ofm, "payload-2.3.0.zip")
        val env = BuiltinPluginCatalog.installEnv(ofm)
        for ((k, v) in env) {
            assertTrue("missing $k", cmd.contains("$k='$v'"))
        }
    }

    // --- verifyCommand -----------------------------------------------------

    @Test fun `verifyCommand runs upstream verify under the payload's env`() {
        val cmd = BuiltinPluginInstaller.verifyCommand(ofm)
        assertTrue(cmd.contains("sh /var/minis/shared/builtin-plugins/ofm/payload/scripts/verify.sh"))
        assertTrue(cmd.contains("OFM_TOOL_DIR='/var/minis/shared/builtin-plugins/ofm/tool'"))
    }
}
