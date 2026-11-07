package com.openminis.app.vscreen.tier

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.HostSuFallback
import com.openminis.app.offload.ShizukuManager
import com.openminis.app.vscreen.VirtualScreenManager

/**
 * [T-android-vscreen-tier] The ROOT tier: a root-uid `app_process` host
 * (deployed from app assets by [VdHostDeployer], started via the su lane)
 * creates a privileged virtual display — TRUSTED / OWN_FOCUS /
 * STEAL_TOP_FOCUS_DISABLED flags step down a ladder when a ROM refuses
 * them, ending at a plain PUBLIC screen, so a stubborn ROM still ends up
 * with a usable display instead of no display at same.
 *
 * Single-screen model (matching the host protocol): one host process owns
 * exactly one display; a create while a fresh host is already running is
 * an honest failure pointing at the existing screen. Screenshots ride the
 * command lane (`screencap -d`), so no frame ever leaves the host process.
 */
class RootDisplayProvider(
    private val su: HostSuRunner,
    private val nowEpochSec: () -> Long = { System.currentTimeMillis() / 1000 },
    private val sleeper: (Long) -> Unit = { ms -> Thread.sleep(ms) },
    /**
     * Deployment seam: the default implementation is [VdHostDeployer]
     * (asset extract + su copy + SHA-256 gate), which needs a real app
     * context; tests inject `{ null }` (deployed) and the context gate
     * lives in the default only.
     */
    private val deployer: (Context?) -> String? = { ctx ->
        val app = ctx?.applicationContext
        if (app == null) "no app context for host deployment"
        else VdHostDeployer.ensureDeployed(app, su)
    },
) : DisplayProvider {

    override val tier: VscreenTier = VscreenTier.ROOT

    override fun probe(context: Context?): ProbeResult {
        // The su binary probe is cheap (path-table File.exists) and already
        // runs on every guard decision today, so re-reading it here adds no
        // new order of magnitude.
        if (!HostSuFallback.isAvailable()) {
            return ProbeResult.Unavailable(
                "no host su binary (Magisk/KernelSU) — the privileged display tier needs root",
            )
        }
        // FOCUS_ISOLATION is NOT promised here: a ROM that refuses the
        // privileged rungs steps the ladder down to plain PUBLIC, which the
        // create note reports truthfully after the fact.
        return ProbeResult.Available(setOf(VscreenCapability.LAUNCH_THIRD_PARTY))
    }

    override fun create(request: ProviderCreateRequest, context: Context?): ProviderOutcome {
        if (!HostSuFallback.isAvailable()) {
            return ProviderOutcome.Failed("no host su binary (Magisk/KernelSU)")
        }
        // 1. A fresh host already owns its one display — do not fight it.
        val existing = readHostState()
        if (existing != null && isFresh(existing)) {
            return ProviderOutcome.Failed(
                "root host already runs display ${existing.displayId} (pid ${existing.pid}); " +
                    "destroy it first or operate on the existing screen",
            )
        }
        // 2. A stale host (dead process, old heartbeat) is cleaned up first.
        if (existing != null) {
            su.run(arrayOf(VdHostProtocol.cleanupCommand(existing.pid)), 10_000L)
        }
        // 3. Deploy (SHA-256 gated) + start + poll for the state file.
        deployer(context)?.let { return ProviderOutcome.Failed(it) }
        su.run(arrayOf(VdHostProtocol.clearStopCommand()), 5_000L)
        val started = su.run(arrayOf(VdHostProtocol.startCommand()), 10_000L)
        if (started.exitCode != 0) {
            return ProviderOutcome.Failed("host start failed (exit ${started.exitCode}): ${started.combined.take(200)}")
        }
        val state = pollForState()
            ?: return ProviderOutcome.Failed(
                "root host did not report a display within 20s — see $LOG_PATH_NOTE on the device",
            )
        AppLogger.info(TAG, "root host display ${state.displayId} up (flags=0x${Integer.toHexString(state.flags)})")
        return ProviderOutcome.Created(
            state.displayId,
            "privileged display (flags=0x${Integer.toHexString(state.flags)}, " +
                "${state.width}x${state.height}@${state.dpi}); third-party apps can start on it",
        )
    }

    override fun destroy(displayId: Int) {
        val state = readHostState() ?: return
        if (state.displayId != displayId) return
        su.run(arrayOf(VdHostProtocol.requestStopCommand()), 5_000L)
    }

    // ── internals ─────────────────────────────────────────────────────────

    /**
     * Polls the state file through the su lane until the host reports its
     * display or [maxAttempts] are spent (40 × 500ms ≈ 20s of real time when
     * the sleeper actually sleeps — and an attempt BUDGET, not a wall-clock
     * deadline, so a no-op test sleeper cannot spin this into an infinite
     * loop). The host needs a few seconds to boot the framework inside
     * app_process.
     */
    private fun pollForState(maxAttempts: Int = 40): VdHostProtocol.HostState? {
        repeat(maxAttempts) {
            val state = readHostState()
            if (state != null) return state
            sleeper(500)
        }
        return null
    }

    private fun readHostState(): VdHostProtocol.HostState? {
        val result = su.run(arrayOf(VdHostProtocol.readStateCommand()), 5_000L)
        if (result.exitCode != 0) return null
        return VdHostProtocol.parseState(result.stdout)
    }

    private fun isFresh(state: VdHostProtocol.HostState): Boolean =
        VdHostProtocol.isHeartbeatFresh(state, nowEpochSec())

    companion object {
        private const val TAG = "RootDisplayProvider"
        private const val LOG_PATH_NOTE = VdHostProtocol.LOG_PATH
    }
}
