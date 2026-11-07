package com.openminis.app.vscreen.tier

import com.openminis.app.offload.HostSuFallback
import com.openminis.app.offload.ShizukuManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ROOT tier's create chain over a scripted su lane: occupied-host
 * refusal, deploy sha gate, start, state polling, stop sentinel. The su
 * probe is pointed at a fixture so no real binary lookup ever runs.
 */
class RootDisplayProviderTest {

    private class ScriptedSu : HostSuRunner {
        val calls = mutableListOf<String>()
        val results = mutableListOf<ShizukuManager.ProcessResult>()

        /** Queues answers by command-head substring; an empty queue = exit 1. */
        val queue = linkedMapOf<String, ArrayDeque<ShizukuManager.ProcessResult>>()

        fun script(head: String, vararg results: ShizukuManager.ProcessResult) {
            queue[head] = ArrayDeque(results.toList())
        }

        override fun run(argv: Array<String>, timeoutMs: Long): ShizukuManager.ProcessResult {
            val cmd = argv.joinToString(" ")
            calls += cmd
            val entry = queue.entries.firstOrNull { cmd.contains(it.key) }
            return entry?.value?.removeFirstOrNull()
                ?: ShizukuManager.ProcessResult(1, "", "unscripted: $cmd")
        }
    }

    private fun ok(out: String = "") = ShizukuManager.ProcessResult(0, out, "")
    private fun fail(out: String = "boom") = ShizukuManager.ProcessResult(1, out, "")

    private val stateText = "display=42\npid=1234\nsize=1080x1920@320\nflags=73985\nbeat=${System.currentTimeMillis() / 1000}\n"

    private fun provider(su: ScriptedSu, deployer: (android.content.Context?) -> String? = { null }): RootDisplayProvider =
        RootDisplayProvider(su = su, sleeper = { }, deployer = deployer)

    @Test
    fun `a fresh existing host refuses a second display honestly`() {
        HostSuFallback.suProbe = { "/system/bin/su" }
        val su = ScriptedSu()
        su.script("cat", ok(stateText))
        val outcome = provider(su).create(ProviderCreateRequest("l", 1080, 1920, 320), null)
        assertTrue(outcome is ProviderOutcome.Failed)
        assertTrue((outcome as ProviderOutcome.Failed).reason.contains("already runs display 42"))
        HostSuFallback.suProbe = { null }
    }

    @Test
    fun `a failed deploy aborts before any start`() {
        HostSuFallback.suProbe = { "/system/bin/su" }
        val su = ScriptedSu()
        val outcome = provider(su, deployer = { "host deploy sha256 mismatch (local=a remote=b)" })
            .create(ProviderCreateRequest("l", 1080, 1920, 320), null)
        assertTrue(outcome is ProviderOutcome.Failed)
        assertTrue((outcome as ProviderOutcome.Failed).reason.contains("sha256 mismatch"))
        assertTrue("no start was attempted", su.calls.none { it.contains("setsid app_process") })
        HostSuFallback.suProbe = { null }
    }

    @Test
    fun `a successful start polls the state file and reports the flags`() {
        HostSuFallback.suProbe = { "/system/bin/su" }
        val su = ScriptedSu()
        su.script("setsid", ok())
        su.script("cat", fail(), ok(stateText))
        val outcome = RootDisplayProvider(
            su = su,
            sleeper = { },
            deployer = { null }, // deployed (the asset path needs a real context)
        ).create(ProviderCreateRequest("l", 1080, 1920, 320), null)
        assertTrue(outcome is ProviderOutcome.Created)
        assertEquals(42, (outcome as ProviderOutcome.Created).displayId)
        assertTrue(outcome.note!!.contains("flags=0x"))
        HostSuFallback.suProbe = { null }
    }

    @Test
    fun `a host that never reports is an honest failure pointing at the log`() {
        HostSuFallback.suProbe = { "/system/bin/su" }
        val su = ScriptedSu()
        su.script("setsid", ok())
        val outcome = RootDisplayProvider(
            su = su,
            sleeper = { },
            deployer = { null },
        ).create(ProviderCreateRequest("l", 1080, 1920, 320), null)
        assertTrue(outcome is ProviderOutcome.Failed)
        assertTrue((outcome as ProviderOutcome.Failed).reason.contains("minis_vd.log"))
        HostSuFallback.suProbe = { null }
    }

    @Test
    fun `destroy posts the stop sentinel for its own display`() {
        val su = ScriptedSu()
        su.script("cat", ok(stateText))
        provider(su).destroy(42)
        // Fire-and-forget: the stop rides a daemon thread, so poll briefly.
        val deadline = System.currentTimeMillis() + 2_000
        while (System.currentTimeMillis() < deadline &&
            su.calls.none { it.contains("touch ${VdHostProtocol.STOP_PATH}") }
        ) Thread.sleep(20)
        assertTrue(su.calls.any { it.contains("touch ${VdHostProtocol.STOP_PATH}") })
        // A foreign display id must never stop the host.
        val touchesBefore = su.calls.count { it.contains("touch") }
        provider(su).destroy(43)
        Thread.sleep(300)
        assertEquals(touchesBefore, su.calls.count { it.contains("touch") })
    }
}
