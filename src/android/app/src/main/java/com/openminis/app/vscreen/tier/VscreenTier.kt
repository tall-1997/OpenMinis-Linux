package com.openminis.app.vscreen.tier

/**
 * [T-android-vscreen-tier] The three display-provisioning lanes of the
 * tiered virtual screen, in priority order (SU/KSU first, Android-native
 * last). A tier is about WHAT KIND of screen is created; the command lane
 * (Shizuku binder vs host su) that DRIVES the screen stays in
 * [com.openminis.app.offload.HostSuFallback] and is orthogonal — a NATIVE
 * screen plus a working shell still launches third-party apps exactly as
 * before this refactor.
 *
 * - [ROOT] — a privileged `app_process` host (running as root uid) creates
 *   the display with hidden flag values passed through the public API:
 *   TRUSTED + OWN_FOCUS + STEAL_TOP_FOCUS_DISABLED make the screen neither
 *   steal the user's focus nor fight the launcher (the agent-mobile-use /
 *   vdisplay-android verified mode). Only a root device can pass these.
 * - [SHIZUKU] — the Shizuku user service (shell uid 2000) creates a PUBLIC
 *   display with a surface handed over the binder from this process, so
 *   frames render into OUR ImageReader while the display's OWNER is the
 *   shell — the `am start --display` owner branch, the only non-root way
 *   to host a third-party app on a virtual display (vdisplay-android).
 * - [NATIVE] — this app creates its own private OWN_CONTENT_ONLY display
 *   (the pre-refactor behaviour). Always available; the ultimate fallback.
 */
enum class VscreenTier(val order: Int) {
    ROOT(0),
    SHIZUKU(1),
    NATIVE(2);

    companion object {
        /** Priority order, highest first. */
        val PRIORITY: List<VscreenTier> = entries.sortedBy { it.order }
    }
}

/**
 * Capabilities a TIER grants to a screen by its mere type. Command-lane
 * capabilities (input injection, appops mute, task move, shell screencap)
 are NOT here — they follow the guard (Shizuku/su), not the tier, and the
 tool layer reports the intersection of both sources.
 */
enum class VscreenCapability {
    /** A third-party app can be started ON this display (public/owner branch). */
    LAUNCH_THIRD_PARTY,

    /** Frames render into this process' ImageReader (screenshot without a shell). */
    SCREENSHOT_READER,

    /** The display does not steal the user's focus (TRUSTED/OWN_FOCUS privileged flags). */
    FOCUS_ISOLATION,
}
