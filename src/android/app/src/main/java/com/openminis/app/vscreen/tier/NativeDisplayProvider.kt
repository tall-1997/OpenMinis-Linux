package com.openminis.app.vscreen.tier

import android.content.Context
import com.openminis.app.vscreen.VirtualScreenManager

/**
 * [T-android-vscreen-tier] The pre-refactor provisioning path, explicit as
 * the ultimate fallback tier: this app creates its own private
 * OWN_CONTENT_ONLY display with an app-owned ImageReader attached for the
 * display's whole lifetime (the surface contract — a surface-less display
 * sleeps and drops every injected input event).
 *
 * The factory closure is injected so tests keep flowing their fakes through
 * the existing [VirtualScreenManager.resetForTest] seam — this provider adds
 * tier bookkeeping, not a second creation mechanism. Handle ownership stays
 * with the manager registry; [destroy] is intentionally empty.
 */
class NativeDisplayProvider(
    private val factory: (Context?) -> VirtualScreenManager.DisplayHandleFactory?,
) : DisplayProvider {

    override val tier: VscreenTier = VscreenTier.NATIVE

    override fun probe(context: Context?): ProbeResult {
        // DisplayManager is a framework service — structurally present on
        // every device, so this tier is the ladder's terminal fallback. A
        // missing factory only means no context has reached the manager yet
        // (the first tool call supplies it), which the orchestrator surfaces
        // through the create failure below rather than a false "unavailable".
        return ProbeResult.Available(setOf(VscreenCapability.SCREENSHOT_READER))
    }

    override fun create(request: ProviderCreateRequest, context: Context?): ProviderOutcome {
        val handle = runCatching {
            factory(context)?.create(request.label, request.width, request.height, request.dpi)
        }.getOrElse { return ProviderOutcome.Failed("display factory failed: ${it.message}") }
            ?: return ProviderOutcome.Failed(
                "DisplayManager.createVirtualDisplay returned null — the device refused " +
                    "the virtual display (ROM policy or a system display limit)",
            )
        // The handle rides the outcome: the registry needs it for frame
        // reads and release, exactly as the pre-refactor direct factory path.
        return ProviderOutcome.Created(handle.displayId, handle = handle)
    }

    override fun destroy(displayId: Int) {
        // The handle (and its ImageReader) is released by the manager
        // registry; nothing tier-specific to undo here.
    }
}
