package com.openminis.app.plugins.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-builtin-plugins] Pure-command contract tests for the guest gateway
 * service manager. The python mirror (sim_gateway_service.py) runs the
 * same commands for real in the guest against an installed payload; these
 * pin the shapes the guest service depends on:
 *  - startCommand mkdir-guards the log/pid redirect, nohup-detaches the
 *    launcher, and writes the pid the stop command reads back
 *  - stopCommand is pidfile-kill + install-root-scoped pkill sweep, and
 *    idempotent (`true` tail) — a dead gateway is a success
 *
 * Native plugins never reach this manager — NativeGatewayServiceTest pins
 * their host-process contract instead.
 *
 * The sweep pattern uses the bracket trick (`[t]ool/bin` for
 * `tool/bin`): the command string itself rides in the wrapper shell's
 * argv, so a pattern matching that text kills its own ancestry and
 * SIGTERMs the process group before `true` runs. The bracketed token must
 * be absent from the rest of the command — `tool/bin` never appears in
 * the pidfile path — so the pattern can only match real launchers.
 */
class GatewayServiceManagerTest {

    private val ofm = BuiltinPluginCatalog.ALL.first()

    // --- startCommand ------------------------------------------------------

    @Test fun `startCommand mkdir-guards the data dir before the redirect`() {
        val cmd = GatewayServiceManager.startCommand(ofm)
        assertTrue(cmd.contains("mkdir -p /var/minis/shared/builtin-plugins/ofm/data"))
        // mkdir must precede the redirect, or a missing data/ fails the
        // redirection before nohup even runs.
        assertTrue(cmd.indexOf("mkdir -p") < cmd.indexOf("nohup"))
    }

    @Test fun `startCommand nohup-detaches the launcher and writes the pidfile`() {
        val cmd = GatewayServiceManager.startCommand(ofm)
        assertTrue(cmd.contains("nohup /var/minis/shared/builtin-plugins/ofm/tool/bin/ofm-gateway"))
        assertTrue(cmd.contains("> /var/minis/shared/builtin-plugins/ofm/data/gateway.log 2>&1 &"))
        assertTrue(cmd.contains("echo $! > /var/minis/shared/builtin-plugins/ofm/data/gateway.pid"))
    }

    // --- stopCommand -------------------------------------------------------

    @Test fun `stopCommand kills the pidfile pid then sweeps the install root`() {
        val cmd = GatewayServiceManager.stopCommand(ofm)
        assertTrue(cmd.contains("kill \"$(cat /var/minis/shared/builtin-plugins/ofm/data/gateway.pid 2>/dev/null)\""))
        // Bracket trick: the sweep token must NOT appear literally in the
        // command string, or it matches the wrapper shell's own argv (this
        // command text) and kills the process group running the stop.
        assertTrue(cmd.contains("pkill -f \"/var/minis/shared/builtin-plugins/ofm/[t]ool/bin/\""))
    }

    @Test fun `stopCommand is idempotent`() {
        // A dead gateway must be a success, not an error the card surfaces.
        assertTrue(GatewayServiceManager.stopCommand(ofm).endsWith("true"))
    }

    @Test fun `stopCommand sweep is scoped to the plugin root`() {
        // The pkill pattern is the bracketed launcher dir under the plugin
        // root — never a bare id that could match unrelated processes,
        // never a literal path that matches the stop command's own wrapper.
        // ofm is the only guest gateway; a2a left the catalog for
        // com.openminis.app.a2a.
        val cmd = GatewayServiceManager.stopCommand(ofm)
        assertTrue(cmd.contains("pkill -f \"/var/minis/shared/builtin-plugins/ofm/[t]ool/bin/\""))
        assertEquals(1, Regex("pkill -f").findAll(cmd).count())
    }

    @Test fun `stopCommand sweep pattern does not match the command string itself`() {
        // The self-match is the whole reason for the bracket: simulate
        // pkill -f against the wrapper argv (the command itself) and
        // require no match. This is the device-shaped string — the pidfile
        // path contains the plugin id, so a bracketed id token ([o]fm)
        // would still self-match; only a token absent from the pidfile
        // path (tool/bin) is safe.
        val cmd = GatewayServiceManager.stopCommand(ofm)
        val pattern = Regex("pkill -f \"([^\"]+)\"").find(cmd)!!.groupValues[1]
        assertFalse(Regex(pattern).containsMatchIn(cmd))
    }
}
