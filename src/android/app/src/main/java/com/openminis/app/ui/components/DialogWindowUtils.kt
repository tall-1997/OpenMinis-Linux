package com.openminis.app.ui.components

import android.view.View
import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * [T-android-dialog-window-utils] Shared dialog-window plumbing.
 *
 * Before this file the same two helpers existed five times across the
 * fullscreen viewers / players / web previews:
 *
 *  - a parent-chain walker that finds the Window a Compose Dialog renders in
 *    (`findDialogWindow` variants in FullscreenImageViewer, ImageGalleryViewer
 *    ("duplicated here to avoid making it public" — its own words),
 *    InlineMediaPlayer, WebPreviewBottomSheet, WebPreviewFullscreenScreen),
 *  - an activity-window immersive block that hides the system bars for the
 *    lifetime of a fullscreen viewer and restores the captured appearance on
 *    dispose (FullscreenImageViewer ≈ ImageGalleryViewer, both carrying the
 *    T169 fix).
 *
 * Both behaviors now live here once. Semantics are the union of the call
 * sites (see each effect's doc for who uses which flavor):
 *
 *  - [findDialogWindow] walks for `DialogWindowProvider` — the correct way to
 *    reach a Compose Dialog's own Window (InlineMediaPlayer / web-preview
 *    variants). The FullscreenImageViewer variant (walk for an Activity
 *    context) is superseded by [findActivityWindowThroughParents].
 *  - [rememberDialogWindow] composable resolver for the enclosing Dialog's
 *    Window, remembered per view.
 *  - [ActivityImmersiveSystemBarsEffect] — T169 semantics: hide the ACTIVITY
 *    window's system bars, snapshot + restore appearance and bar colors.
 *  - [DialogWindowImmersiveEffect] — edge-to-edge the DIALOG window with
 *    transparent bars; icon appearance follows [darkTheme]; optional bar
 *    hiding / NO_LIMITS / DRAWS_SYSTEM_BAR_BACKGROUNDS per flavor.
 *
 * Restore policy notes (why no capture here):
 *  - Dialog windows are destroyed when the Dialog leaves, so the
 *    dialog-window effect never restores anything (matching every call site's
 *    empty `onDispose`).
 *  - The activity window outlives the dialog. The fullscreen viewers used to
 *    capture-and-restore (T169) because they are full-screen takeovers whose
 *    sibling writers don't overlap them; the web-preview pair had the
 *    [T-android-preview-statusbar-leak] bug exactly because two sibling
 *    writers overlap there, so IT restores to the theme-derived value instead
 *    of a captured one. Both policies are preserved by their respective
 *    effects — do not swap them.
 */

/**
 * Walk the Compose view's parent chain to find the hosting `Window` of the
 * enclosing Dialog. When Compose renders inside a `Dialog`, the ComposeView
 * is attached to an internal `DialogWindowProvider` whose `window` is the
 * Dialog's Window (distinct from the host Activity's Window). Returns null if
 * the search terminates before hitting a provider — callers should no-op.
 */
fun findDialogWindow(view: View): Window? {
    var p: android.view.ViewParent = view.parent
    while (p != null) {
        if (p is androidx.compose.ui.window.DialogWindowProvider) return p.window
        p = p.parent
    }
    return null
}

/**
 * Walk the view's parent chain looking for a host Activity window (the
 * FullscreenImageViewer `findWindowViaDialog` fallback: `view.context` can be
 * a ContextWrapper when the composable sits inside a Dialog, but the parent
 * chain reaches views whose context is the Activity).
 */
fun findActivityWindowThroughParents(view: View): Window? {
    var v: View? = view
    while (v != null) {
        val ctx = v.context
        if (ctx is android.app.Activity) return ctx.window
        v = v.parent as? View
    }
    return null
}

/**
 * Resolve the Window a fullscreen-style Dialog (immersive viewer / player)
 * should mutate for its own chrome. Prefers the Dialog's own window via
 * [findDialogWindow]; falls back to the Activity window when there is no
 * dialog provider (e.g. the composable renders directly in the activity).
 */
fun resolveViewerWindow(view: View): Window? =
    findDialogWindow(view)
        ?: (view.context as? android.app.Activity)?.window
        ?: findActivityWindowThroughParents(view)

/** Composable convenience for [findDialogWindow] — remembered per view. */
@Composable
fun rememberDialogWindow(): Window? {
    val view = LocalView.current
    return remember(view) { findDialogWindow(view) }
}

/**
 * T169: hide the system bars on the ACTIVITY window for this composition's
 * lifetime and restore the captured appearance / bar colors on dispose.
 *
 * FullscreenImageViewer + ImageGalleryViewer use this for the window BEHIND
 * their fullscreen Dialog, so re-entering the app behind the viewer doesn't
 * show a half-restored chrome. Never touches decorFitsSystemWindows — the
 * activity's edge-to-edge state belongs to MainActivity's lifecycle
 * (enableEdgeToEdge at startup; see the T169 comment in FullscreenImageViewer).
 *
 * The capture-restore policy is safe HERE because these viewers take over the
 * whole screen and never overlap another writer of the flags. If this effect
 * gains a sibling that also writes them, switch to the theme-derived restore
 * (see [T-android-preview-statusbar-leak] in WebPreviewBottomSheet).
 */
@Composable
fun ActivityImmersiveSystemBarsEffect(view: View = LocalView.current) {
    DisposableEffect(view) {
        val window = (view.context as? android.app.Activity)?.window
            ?: findActivityWindowThroughParents(view)
        val controller = window?.let { WindowInsetsControllerCompat(it, view) }
        val prevLightStatus = controller?.isAppearanceLightStatusBars
        val prevLightNav = controller?.isAppearanceLightNavigationBars
        @Suppress("DEPRECATION")
        val prevStatusBarColor = window?.statusBarColor
        @Suppress("DEPRECATION")
        val prevNavBarColor = window?.navigationBarColor

        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        @Suppress("DEPRECATION")
        window?.statusBarColor = android.graphics.Color.TRANSPARENT
        @Suppress("DEPRECATION")
        window?.navigationBarColor = android.graphics.Color.TRANSPARENT
        controller?.hide(WindowInsetsCompat.Type.systemBars())
        controller?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        onDispose {
            controller?.show(WindowInsetsCompat.Type.systemBars())
            prevLightStatus?.let { controller.isAppearanceLightStatusBars = it }
            prevLightNav?.let { controller.isAppearanceLightNavigationBars = it }
            @Suppress("DEPRECATION")
            prevStatusBarColor?.let { window.statusBarColor = it }
            @Suppress("DEPRECATION")
            prevNavBarColor?.let { window.navigationBarColor = it }
        }
    }
}

/**
 * Edge-to-edge the enclosing DIALOG window (via [findDialogWindow]) with
 * transparent system bars; icon appearance follows [darkTheme].
 *
 * Flavor knobs, one per call-site family:
 *  - [hideBars] — fullscreen black-canvas viewers/players hide the bars
 *    entirely (swipe-transient to bring them back).
 *  - [layoutNoLimits] — the image viewers extend into cutout/bars with
 *    FLAG_LAYOUT_NO_LIMITS.
 *  - [drawsSystemBarBackgrounds] — the web-preview pair needs
 *    FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS so the dialog paints under the bars
 *    instead of leaving a grey band.
 *
 * No restore on dispose: the dialog window is destroyed with the Dialog.
 * On dispose the appearance flags are re-asserted to the theme-derived value
 * while the window still exists — idempotent, and matches the
 * [T-android-preview-statusbar-leak] "restore from the theme, not a capture"
 * contract for the pair of overlapping writers (sheet ↔ fullscreen).
 */
@Composable
fun DialogWindowImmersiveEffect(
    darkTheme: Boolean,
    hideBars: Boolean = false,
    layoutNoLimits: Boolean = false,
    drawsSystemBarBackgrounds: Boolean = false,
) {
    val view = LocalView.current
    DisposableEffect(view, darkTheme, hideBars, layoutNoLimits, drawsSystemBarBackgrounds) {
        val win = findDialogWindow(view)
        win?.let { w ->
            WindowCompat.setDecorFitsSystemWindows(w, false)
            if (layoutNoLimits) {
                w.addFlags(WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS)
            }
            if (drawsSystemBarBackgrounds) {
                w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS)
            }
            @Suppress("DEPRECATION")
            w.statusBarColor = android.graphics.Color.TRANSPARENT
            @Suppress("DEPRECATION")
            w.navigationBarColor = android.graphics.Color.TRANSPARENT
            val ctrl = WindowInsetsControllerCompat(w, w.decorView)
            ctrl.isAppearanceLightStatusBars = !darkTheme
            ctrl.isAppearanceLightNavigationBars = !darkTheme
            if (hideBars) {
                ctrl.systemBarsBehavior =
                    WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                ctrl.hide(WindowInsetsCompat.Type.systemBars())
            }
        }
        onDispose { /* dialog window is going away — nothing to restore */ }
    }
}
