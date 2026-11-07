package com.openminis.app.vscreen

import com.openminis.app.offload.ShizukuManager
import com.openminis.app.vscreen.tier.HostDisplayClient
import com.openminis.app.vscreen.tier.VdHostProtocol
import com.openminis.app.vscreen.tier.VscreenTier
import com.openminis.app.vscreen.VirtualScreenManager.CommandOutcome
import java.io.File

/**
 * [T-android-vscreen3] Screenshot lane orchestration, extracted from
 * VirtualScreenManager for the line ceiling (the policy baseline only
 * tightens — extraction is the sanctioned move). Pure orchestration: every
 * privileged seam (shell, destroy, Shizuku liveness text, guest path
 * mapping, tmp prefix) is injected — no state of its own.
 *
 * Layer 0 (root host's loopback JPEG server) → layer 1 (ImageReader,
 * app-uid, no Shizuku) → layer 2 (Shizuku screencap + cp into
 * [offloadsDir]) → layer 3 (Shizuku screencap + chunked base64 through
 * stdout). All layers end with app-side bytes written under [offloadsDir];
 * the returned guest path is `/var/minis/offloads/<file>` so `read_image`
 * resolves it. `uiautomator dump` has NO display parameter, so a text
 * accessibility-tree dump of the virtual screen is not possible — the
 * screenshot is the only read surface.
 */
internal object VirtualScreenShots {

    fun capture(
        displayId: Int,
        s: VirtualScreenManager.VirtualScreen,
        offloadsDir: File?,
        runShell: (Array<String>, Long) -> ShizukuManager.ProcessResult,
        destroy: (Int) -> Unit,
        shizukuUnavailable: () -> String?,
        guestPathFor: (String) -> String,
        tmpPrefix: String,
    ): CommandOutcome {
        // [T-android-vscreen3] Dead-screen cleanup: a platform-stopped display
        // can never produce another frame — destroy the corpse and tell the
        // agent to re-create, instead of letting every read attempt fail
        // while the zombie keeps its LRU slot.
        s.handle?.let { handle ->
            if (handle.lifecycle == VdLifecycle.STOPPED) {
                destroy(displayId)
                return CommandOutcome.Failed(
                    "display $displayId was stopped by the platform (its surface was released) — " +
                        "the corpse has been destroyed; create a new virtual screen and retry",
                )
            }
        }

        if (offloadsDir == null) {
            return CommandOutcome.Failed(
                "no app context available — cannot write the screenshot file",
            )
        }

        // Layer 0: root-tier privileged host — JPEG straight from the
        // host's loopback server (no screencap spawn, no PNG transcode).
        // The same read doubles as the liveness probe: a gone state file or
        // a stale heartbeat means the host died and the screen is a corpse
        // — destroy it and tell the agent, instead of letting every read
        // fail while the zombie keeps its LRU slot. A failed shell read
        // (Shizuku down) proves nothing about the host, so the screen is
        // left alone and the existing lanes take over.
        if (s.tier == VscreenTier.ROOT) {
            val (shellWorked, hostState) = readHostStateViaShell(runShell)
            if (shellWorked) {
                val fresh = hostState?.let {
                    VdHostProtocol.isHeartbeatFresh(it, System.currentTimeMillis() / 1000)
                }
                when (val decision = VdHostProtocol.hostJpegDecision(s.tier, hostState, fresh)) {
                    is VdHostProtocol.HostJpegDecision.DeadHost -> {
                        destroy(displayId)
                        return CommandOutcome.Failed(
                            "display $displayId's privileged host is dead (${decision.reason}) — the corpse " +
                                "has been destroyed; create a new virtual screen (it will fall back to a " +
                                "lower tier if root is gone)",
                        )
                    }
                    is VdHostProtocol.HostJpegDecision.TryShot ->
                        HostDisplayClient.shot(decision.port)?.let { bytes ->
                            return VirtualScreenCapture.writeScreenshot(
                                offloadsDir, displayId, bytes, "host-jpeg", VirtualScreenCapture.JPEG_SUFFIX,
                            )
                        }
                    else -> {}
                }
            }
        }
        offloadsDir.mkdirs()

        // Layer 1: our own display, our own uid.
        VirtualScreenCapture.captureViaImageReader(s.handle)?.let { bytes ->
            return VirtualScreenCapture.writeScreenshot(offloadsDir, displayId, bytes, "ImageReader", VirtualScreenCapture.JPEG_SUFFIX)
        }

        // Layers 2 and 3 ride the Shizuku shell.
        shizukuUnavailable()?.let {
            return CommandOutcome.ShizukuUnavailable(
                "$it (the ImageReader capture returned no frame, so the " +
                    "screencap fallback needs Shizuku too)",
            )
        }
        if (VscreenDisplayProbes.screencapSupportsDisplayFlag(runShell) == false) {
            return CommandOutcome.NotSupported(
                "this ROM's screencap has no -d option (probed via its usage output) and the " +
                    "ImageReader capture returned no frame — no read path remains for this display",
            )
        }
        val tmp = "$tmpPrefix${System.currentTimeMillis()}${VirtualScreenCapture.PNG_SUFFIX}"
        val cap = runShell(arrayOf("sh", "-c", "screencap -d $displayId -p $tmp"), 20_000L)
        if (cap.exitCode != 0) {
            return CommandOutcome.Failed(
                "screencap -d $displayId failed: ${cap.combined.ifBlank { "exitCode=${cap.exitCode}" }} " +
                    "— some ROMs expect a physical display id here rather than the logical id",
            )
        }

        // Layer 2: cp into the app-readable dir (root-mode Shizuku works here;
        // adb-mode shell usually cannot write app data dirs — probed).
        val direct = File(offloadsDir,
            "${VirtualScreenCapture.PNG_PREFIX}${displayId}_${System.currentTimeMillis()}${VirtualScreenCapture.PNG_SUFFIX}")
        val cp = runShell(arrayOf("sh", "-c", "cp $tmp '${direct.absolutePath.replace("'", "'\\''")}'"), 10_000L)
        if (cp.exitCode == 0 && direct.isFile && direct.length() > 0) {
            runShell(arrayOf("sh", "-c", "rm -f $tmp"), 5_000L)
            return CommandOutcome.Ok(
                displayId,
                "screenshot ${direct.length()} bytes via screencap+cp",
                guestPathFor(direct.name),
            )
        }

        // Layer 3: chunked base64 through Shizuku stdout.
        val bytes = VirtualScreenCapture.readChunked(tmp, displayId) { argv, t -> runShell(argv, t) }
        runShell(arrayOf("sh", "-c", "rm -f $tmp"), 5_000L)
        if (bytes == null) {
            return CommandOutcome.Failed(
                "screencap wrote the capture to $tmp but no app-readable retrieval path worked " +
                    "(cp into the offloads dir refused by the ROM, and the chunked base64 read failed)",
            )
        }
        return VirtualScreenCapture.writeScreenshot(offloadsDir, displayId, bytes, "chunked base64")
    }

    /**
     * Reads the root host's state file through the Shizuku shell lane.
     * Returns (shellWorked, state): a null state with shellWorked=true means
     * the file is really gone (host died); shellWorked=false means the shell
     * lane itself failed (Shizuku down) — liveness stays unknown and the
     * caller must fall through rather than kill the screen.
     */
    private fun readHostStateViaShell(
        runShell: (Array<String>, Long) -> ShizukuManager.ProcessResult,
    ): Pair<Boolean, VdHostProtocol.HostState?> {
        val result = runShell(arrayOf("cat", VdHostProtocol.STATE_PATH), 5_000L)
        if (result.exitCode != 0) return false to null
        return true to VdHostProtocol.parseState(result.stdout)
    }
}
