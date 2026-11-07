package com.openminis.app.vscreen.tier

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Wire contract of the root host: state parsing, command assembly, freshness. */
class VdHostProtocolTest {

    private val validState = """
        display=42
        pid=1234
        size=1280x2772@520
        flags=73985
        beat=1700000000
    """.trimIndent()

    @Test
    fun `parseState reads every field of a healthy state file`() {
        val s = VdHostProtocol.parseState(validState)!!
        assertEquals(42, s.displayId)
        assertEquals(1234, s.pid)
        assertEquals(1280, s.width)
        assertEquals(2772, s.height)
        assertEquals(520, s.dpi)
        assertEquals(73985, s.flags)
        assertEquals(1_700_000_000L, s.beatEpochSec)
    }

    @Test
    fun `a half-written state file is never mistaken for a live host`() {
        assertNull(VdHostProtocol.parseState("display=42\npid=1234\n")) // missing size/flags/beat
        assertNull(VdHostProtocol.parseState(""))
        assertNull(VdHostProtocol.parseState("garbage\nlines\nhere"))
        assertNull(VdHostProtocol.parseState(validState.replace("display=42", "display=abc")))
        assertNull(VdHostProtocol.parseState(validState.replace("size=1280x2772@520", "size=junk")))
        assertNull(VdHostProtocol.parseState("display=0\npid=1234\nsize=1x1@120\nflags=1\nbeat=1"))
        assertNull(VdHostProtocol.parseState("display=-1\npid=1234\nsize=1x1@120\nflags=1\nbeat=1"))
    }

    @Test
    fun `port line rides along when the host has a jpeg lane`() {
        val s = VdHostProtocol.parseState(validState + "\nport=51777")!!
        assertEquals(51777, s.port)
    }

    @Test
    fun `missing or garbled port line keeps the state valid with port -1`() {
        // Hosts predating the JPEG lane never write the line.
        assertEquals(-1, VdHostProtocol.parseState(validState)!!.port)
        // A garbled port must not invalidate an otherwise fresh state file.
        assertEquals(-1, VdHostProtocol.parseState(validState + "\nport=garbage")!!.port)
        assertEquals(-1, VdHostProtocol.parseState(validState + "\nport=0")!!.port)
    }

    @Test
    fun `heartbeat freshness honours the stale threshold`() {
        val s = VdHostProtocol.parseState(validState)!!
        assertTrue(VdHostProtocol.isHeartbeatFresh(s, 1_700_000_000L + VdHostProtocol.HEARTBEAT_MAX_AGE_SEC))
        assertFalse(VdHostProtocol.isHeartbeatFresh(s, 1_700_000_000L + VdHostProtocol.HEARTBEAT_MAX_AGE_SEC + 1))
    }

    @Test
    fun `start command detaches the host and points at the deployed jar`() {
        val cmd = VdHostProtocol.startCommand()
        assertTrue(cmd.startsWith("setsid app_process"))
        assertTrue(cmd.contains("-Djava.class.path=${VdHostProtocol.HOST_JAR_PATH}"))
        assertTrue(cmd.contains("vdhost.Main --serve"))
        assertTrue(cmd.contains(">> ${VdHostProtocol.LOG_PATH} 2>&1 &"))
    }

    @Test
    fun `deploy command copies fixes the mode and prints the sha256`() {
        val cmd = VdHostProtocol.deployCommand("/data/data/x/cache/vdhost/minis_vd_host.jar")
        assertTrue(cmd.contains("cp /data/data/x/cache/vdhost/minis_vd_host.jar ${VdHostProtocol.HOST_JAR_PATH}"))
        assertTrue(cmd.contains("chmod 644"))
        assertTrue(cmd.contains("sha256sum"))
    }

    @Test
    fun `stop and cleanup commands target the right files`() {
        assertEquals("touch ${VdHostProtocol.STOP_PATH}", VdHostProtocol.requestStopCommand())
        assertEquals("rm -f ${VdHostProtocol.STOP_PATH}", VdHostProtocol.clearStopCommand())
        assertEquals(
            "rm -f ${VdHostProtocol.STATE_PATH} ${VdHostProtocol.STOP_PATH} && kill -9 1234",
            VdHostProtocol.cleanupCommand(1234),
        )
        assertEquals("rm -f ${VdHostProtocol.STATE_PATH} ${VdHostProtocol.STOP_PATH}", VdHostProtocol.cleanupCommand(0))
    }

    @Test
    fun `parseShotHeader reads the JPEG length and rejects the rest`() {
        assertEquals(123_456, VdHostProtocol.parseShotHeader("JPEG 123456"))
        assertNull(VdHostProtocol.parseShotHeader("ERR no frame rendered yet"))
        assertNull(VdHostProtocol.parseShotHeader("unknown junk"))
        assertNull(VdHostProtocol.parseShotHeader("JPEG 0"))
        assertNull(VdHostProtocol.parseShotHeader("JPEG -5"))
        assertNull(VdHostProtocol.parseShotHeader("JPEG "))
    }

    @Test
    fun `hostJpegDecision routes by tier liveness and lane presence`() {
        val state = VdHostProtocol.parseState(validState + "\nport=51777")!!
        val laneless = VdHostProtocol.parseState(validState)!!
        assertEquals(
            VdHostProtocol.HostJpegDecision.Skip,
            VdHostProtocol.hostJpegDecision(VscreenTier.NATIVE, state, true),
        )
        assertEquals(
            VdHostProtocol.HostJpegDecision.Skip,
            VdHostProtocol.hostJpegDecision(VscreenTier.SHIZUKU, state, true),
        )
        assertTrue(
            VdHostProtocol.hostJpegDecision(VscreenTier.ROOT, null, null)
                is VdHostProtocol.HostJpegDecision.DeadHost,
        )
        assertTrue(
            VdHostProtocol.hostJpegDecision(VscreenTier.ROOT, state, false)
                is VdHostProtocol.HostJpegDecision.DeadHost,
        )
        assertEquals(
            VdHostProtocol.HostJpegDecision.NoLane,
            VdHostProtocol.hostJpegDecision(VscreenTier.ROOT, laneless, true),
        )
        assertEquals(
            VdHostProtocol.HostJpegDecision.TryShot(51777),
            VdHostProtocol.hostJpegDecision(VscreenTier.ROOT, state, true),
        )
    }
}
