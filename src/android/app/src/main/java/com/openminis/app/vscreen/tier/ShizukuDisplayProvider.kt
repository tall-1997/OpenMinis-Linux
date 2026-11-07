package com.openminis.app.vscreen.tier

import android.content.Context
import android.graphics.PixelFormat
import android.media.Image
import android.media.ImageReader
import com.openminis.app.offload.ShizukuManager
import com.openminis.app.vscreen.IVscreenService
import com.openminis.app.vscreen.VirtualScreenManager

/**
 * [T-android-vscreen-tier] The Shizuku tier: a PUBLIC virtual display whose
 * OWNER is the Shizuku user service process (shell uid 2000), rendering into
 * an ImageReader that lives in THIS process — the surface crosses the binder
 * ([IVscreenService.createPublicDisplay]) while the frames come back through
 * shared memory. The shell-uid owner is what makes the `am start --display`
 * owner branch accept third-party app launches (the vdisplay-android
 * verified mode, and the only non-root way to host a foreign app on a
 * virtual display).
 *
 * Lifecycle: the service process dies with the Shizuku binding, and a dead
 * owner takes the display with it — the manager's leftover reconcile
 * (mute ledger, handoff records) already treats vanished display ids as
 * dead, so no extra resurrection logic is needed here. [release] still
 * tries the explicit destroy first (binder may outlive one call) and closes
 * the reader either way.
 */
class ShizukuDisplayProvider(
    private val serviceClient: () -> IVscreenService?,
) : DisplayProvider {

    override val tier: VscreenTier = VscreenTier.SHIZUKU

    override fun probe(context: Context?): ProbeResult {
        // Cheap structural check first (StateFlow read, no binder traffic):
        // without Shizuku the user service can never bind, so report that
        // honestly instead of burning the 5s bind timeout on every re-probe.
        ShizukuManager.refresh()
        val state = ShizukuManager.snapshot.value.state
        if (state != ShizukuManager.State.READY) {
            return ProbeResult.Unavailable(
                "Shizuku is ${state.name.lowercase()} — the shell-uid public display " +
                    "needs an authorized Shizuku (Settings -> Permissions -> Shizuku)",
            )
        }
        val service = serviceClient()
            ?: return ProbeResult.Unavailable("Shizuku user service unreachable (bind failed)")
        val alive = runCatching { service.asBinder().pingBinder() }.getOrDefault(false)
        return if (alive) {
            ProbeResult.Available(setOf(VscreenCapability.LAUNCH_THIRD_PARTY, VscreenCapability.SCREENSHOT_READER))
        } else {
            ProbeResult.Unavailable("Shizuku user service binder is dead")
        }
    }

    override fun create(request: ProviderCreateRequest, context: Context?): ProviderOutcome {
        val service = serviceClient()
            ?: return ProviderOutcome.Failed("Shizuku user service unreachable (bind failed)")
        // Frames are consumed HERE (the reader lives in this process); the
        // display's owner is the shell-uid service process.
        val reader = ImageReader.newInstance(request.width, request.height, PixelFormat.RGBA_8888, 2)
        val displayId = runCatching {
            service.createPublicDisplay(
                request.label, request.width, request.height, request.dpi, reader.surface,
            )
        }.getOrElse {
            runCatching { reader.close() }
            return ProviderOutcome.Failed("public display binder call failed: ${it.message}")
        }
        if (displayId <= 0) {
            runCatching { reader.close() }
            return ProviderOutcome.Failed(
                "the ROM refused the shell-uid public display (createPublicDisplay returned $displayId)",
            )
        }
        return ProviderOutcome.Created(
            displayId,
            "public display owned by shell uid; third-party apps can start on it",
            ShizukuDisplayHandle(service, displayId, reader),
        )
    }

    override fun destroy(displayId: Int) {
        // Explicit teardown rides the handle; nothing provider-side to undo.
    }

    /** Reader in this process + explicit destroy through the service binder. */
    private class ShizukuDisplayHandle(
        private val service: IVscreenService,
        override val displayId: Int,
        private val reader: ImageReader,
    ) : VirtualScreenManager.DisplayHandle {

        override fun acquireLatestFrame(): Image? =
            runCatching { reader.acquireLatestImage() }.getOrNull()

        override fun release() {
            runCatching { service.destroyPublicDisplay(displayId) }
            runCatching { reader.close() }
        }
    }
}
