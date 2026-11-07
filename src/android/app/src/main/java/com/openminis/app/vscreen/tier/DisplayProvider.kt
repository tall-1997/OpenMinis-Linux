package com.openminis.app.vscreen.tier

import android.content.Context

/**
 * [T-android-vscreen-tier] One provisioning lane behind the tiered virtual
 * screen. Implementations own HOW a display of their tier comes into being
 * (privilege context, deployment, binder calls) and are otherwise stateless
 * — the registry of live screens stays in [com.openminis.app.vscreen.
 * VirtualScreenManager], which also releases handles, so [destroy] is only
 * tier-specific cleanup (e.g. stopping the root host process).
 *
 * All methods are synchronous and blocking; the orchestrator runs them on
 * the caller's (daemon) thread, mirroring the pre-refactor create path.
 */
interface DisplayProvider {

    val tier: VscreenTier

    /**
     * Cheap availability check. Must NOT perform the actual work (no
     * deployment, no process spawn) — a probe only reads cached/observable
     * state (su binary presence, Shizuku state, binder liveness) and must be
     * safe to call on every create. Results are cached by
     * [TierStateMachine] anyway; keep this honest and fast.
     */
    fun probe(context: Context?): ProbeResult

    /**
     * Create one display of this tier. A failure here is a probing FACT
     * (the orchestrator marks the tier degraded and falls through), so the
     * [ProviderOutcome.Failed] reason must be specific enough to act on.
     */
    fun create(request: ProviderCreateRequest, context: Context?): ProviderOutcome

    /** Tier-specific teardown for a display this provider created. Best effort. */
    fun destroy(displayId: Int)
}

/** What a screen of this tier needs: dimensions follow the main display unless overridden. */
data class ProviderCreateRequest(
    val label: String,
    val width: Int,
    val height: Int,
    val dpi: Int,
)

sealed class ProbeResult {
    /** The tier can be attempted right now. */
    data class Available(val capabilities: Set<VscreenCapability>) : ProbeResult()

    /**
     * A recent create attempt FAILED — the tier is skipped until the state
     * machine lazily re-probes it (≥10 minutes later), so a transiently
     * broken host does not poison every create with a 15 s timeout.
     */
    data class Degraded(val reason: String, val atMs: Long) : ProbeResult()

    /** Structurally unavailable (Shizuku not installed, no su binary, …). */
    data class Unavailable(val reason: String) : ProbeResult()
}

sealed class ProviderOutcome {
    /**
     * [handle] carries the display resource when it lives outside the
     * manager's own factory seam (a cross-process public display's reader,
     * a root host's drain surface); the registry adopts it so release and
     * frame reads stay single-sourced. NATIVE leaves it null — its handle
     * already flows through the shared factory seam.
     */
    data class Created(
        val displayId: Int,
        val note: String? = null,
        val handle: com.openminis.app.vscreen.VirtualScreenManager.DisplayHandle? = null,
    ) : ProviderOutcome()

    data class Failed(val reason: String) : ProviderOutcome()
}

/**
 * Root-lane execution seam: same shape as
 * [com.openminis.app.offload.HostSuFallback.run] so production binds it
 * directly (`HostSuFallback::run`) and tests script a fake. Every root-tier
 * host interaction — deploy, start, state reads, stop — rides this one seam.
 */
fun interface HostSuRunner {
    fun run(argv: Array<String>, timeoutMs: Long): com.openminis.app.offload.ShizukuManager.ProcessResult
}
