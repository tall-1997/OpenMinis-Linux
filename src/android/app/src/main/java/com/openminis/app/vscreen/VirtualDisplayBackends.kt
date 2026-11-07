package com.openminis.app.vscreen

/**
 * [T-android-vscreen-layers] Which privilege layer owns a virtual display.
 *
 * Three layers, in priority order (highest first):
 *
 *  - [ROOT] — KSU / Magisk `su`: the display is created by a root-owned host
 *    process, so the owner uid is 0 (the reference `agent-mobile-use` model:
 *    `DaemonMain` under KSU). Highest capability, but a host must be deployed.
 *  - [SHIZUKU] — Shizuku's shell uid (2000): the reference
 *    `MerlinShieh/vdisplay-android` model, `app_process` host owned by shell.
 *    `am start --display` / `input -d` / `screencap -d` all pass for a
 *    shell-owned display.
 *  - [NATIVE] — the app's own uid creates the display through
 *    `DisplayManager` (today's behaviour). Always available; the driver
 *    commands still ride Shizuku or host su.
 *
 * The rule the planner enforces: never hard-fail on the highest layer. A layer
 * that is missing, unavailable or failed is skipped with a reason, the next
 * layer is tried, and a failed layer cools down before it is probed again — so
 * installing root later upgrades the next create automatically instead of
 * requiring an app restart.
 */
enum class VdBackend { ROOT, SHIZUKU, NATIVE }

/** One layer's state at planning time. */
data class VdBackendProbe(
    val backend: VdBackend,
    val available: Boolean,
    /** Model-facing reason: why it is available, or what is missing. */
    val detail: String,
)

/**
 * Pure planner: order the layers by priority, skip the cooling ones, and keep
 * the reason each layer was skipped. All state is caller-supplied so the whole
 * policy is unit-tested without a device.
 */
internal class VdBackendPlanner(
    /** How long a failed layer stays demoted before it is retried. */
    private val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {
    private val lastFailureMs = HashMap<VdBackend, Long>()

    fun noteFailure(backend: VdBackend, nowMs: Long) {
        lastFailureMs[backend] = nowMs
    }

    fun noteSuccess(backend: VdBackend) {
        lastFailureMs.remove(backend)
    }

    fun isCoolingDown(backend: VdBackend, nowMs: Long): Boolean {
        val at = lastFailureMs[backend] ?: return false
        return nowMs - at < cooldownMs
    }

    /**
     * The layers to try, in order. Available layers first (priority order),
     * then the layers still cooling down — a cooling layer is not dropped, it
     * is retried as the LAST resort so a stale failure can never hide a working
     * path.
     */
    fun plan(probes: List<VdBackendProbe>, nowMs: Long): List<VdBackendProbe> =
        probes
            .filter { it.available }
            .sortedWith(compareBy({ isCoolingDown(it.backend, nowMs) }, { it.backend.ordinal }))

    /** Why [backend] is not in [planned] (or that it is cooling down). */
    fun skipReason(backend: VdBackend, probes: List<VdBackendProbe>, nowMs: Long): String? {
        val probe = probes.firstOrNull { it.backend == backend } ?: return "not probed"
        if (!probe.available) return probe.detail
        if (isCoolingDown(backend, nowMs)) return "cooling down after a failure: ${probe.detail}"
        return null
    }

    companion object {
        const val DEFAULT_COOLDOWN_MS = 5 * 60_000L
    }
}

/**
 * Seam over a privileged host process (root or shell uid) that owns the
 * display. The host is the piece that makes [VdBackend.ROOT] /
 * [VdBackend.SHIZUKU] creation possible: a `VirtualDisplay` dies with the
 * process that created it, so a one-shot `app_process` call cannot hold one —
 * the host must stay alive and serve frames.
 *
 * Null in the manager until such a host is deployed; the planner then reports
 * those layers as unavailable with this reason and falls through to NATIVE.
 */
internal fun interface PrivilegedDisplayHost {
    fun create(spec: DisplaySpec, label: String): Int?
}

/**
 * The live layer policy: probes the two privileged transports and folds them
 * through [VdBackendPlanner]. Kept here (not in the manager) so the whole
 * policy is unit-testable and the manager only exposes a thin wrapper.
 */
internal object VdBackends {
    private val planner = VdBackendPlanner()

    /** Every layer in priority order, with the reason it is or is not usable. */
    fun status(hostReady: Boolean, nowMs: Long): List<VdBackendProbe> {
        val probes = probes(hostReady)
        val planned = planner.plan(probes, nowMs)
        val rest = probes
            .filter { p -> planned.none { it.backend == p.backend } }
            .sortedBy { it.backend.ordinal }
        return planned + rest
    }

    /** The layers to TRY, highest priority first; cooling layers come last. */
    fun plan(hostReady: Boolean, nowMs: Long): List<VdBackendProbe> =
        planner.plan(probes(hostReady), nowMs)

    private fun probes(hostReady: Boolean): List<VdBackendProbe> {
        val hostMissing =
            "no privileged display host deployed — SU/Shizuku cannot own a display yet"
        val root = com.openminis.app.offload.HostSuFallback.isAvailable()
        val shizuku = com.openminis.app.offload.ShizukuManager.isReady()
        return listOf(
            VdBackendProbe(
                VdBackend.ROOT,
                root && hostReady,
                if (root) hostMissing else "no KSU/SU (su unavailable)",
            ),
            VdBackendProbe(
                VdBackend.SHIZUKU,
                shizuku && hostReady,
                if (shizuku) hostMissing else "Shizuku not ready",
            ),
            VdBackendProbe(VdBackend.NATIVE, true, "app uid DisplayManager (always available)"),
        )
    }

    fun noteFailure(backend: VdBackend, nowMs: Long) = planner.noteFailure(backend, nowMs)

    fun noteSuccess(backend: VdBackend) = planner.noteSuccess(backend)
}

