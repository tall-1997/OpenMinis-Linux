package com.openminis.app.vscreen

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.ShizukuManager

/**
 * [T-android-vscreen2] Component wiring + upkeep for the V2 behaviours
 * (silent ledger, task move, handoff), split out of [VirtualScreenManager]
 * (architecture ratchet).
 *
 * Owns the lazily-built component defaults and their test overrides:
 *  - [muter] — the persistent mute ledger (SharedPreferences once a context
 *    is seen, MemoryPrefs before that, override in tests);
 *  - [mover] — user-service-first task move with the shell probe fallback;
 *  - [handoffController] — built once and reused (state lives in
 *    [HandoffStore], prefs-backed, so the shared instance never diverges).
 *
 * Plus the two cross-cutting upkeep ops that ride on those components:
 * [reconcileState] (leftover sweep) and [muteForLaunch] (silence before
 * start). The shell seam is injected as a closure over the manager's
 * CURRENT [VirtualScreenManager.resetForTest] override, so swapping the fake
 * shell in tests keeps flowing into these components.
 */
internal class VirtualScreenUpkeep(
    private val shell: (Array<String>, Long) -> ShizukuManager.ProcessResult,
) {
    @Volatile internal var appContext: Context? = null

    /** Persistent prefs, available once an app context has been seen. */
    @Volatile private var sharedPrefs: SharedPrefsVscreenPrefs? = null

    /** Lazily built defaults; rebuilt when the app context (and thus persistent prefs) appears. */
    @Volatile private var defaultMuter: AudioMuter? = null
    @Volatile private var defaultMover: TaskMover? = null
    @Volatile private var defaultServiceClient: VscreenServiceClient? = null

    /**
     * [v3] Process-level handoff controller, built once and reused. The
     * handoff state itself lives in [HandoffStore] (prefs-backed), so the
     * controller is stateless over prefs and a shared instance never diverges;
     * a fresh instance per call was pure garbage. [resetForTest] drops it so
     * test overrides keep flowing into a rebuilt controller.
     */
    @Volatile private var defaultController: HandoffController? = null

    @Volatile internal var muterOverride: AudioMuter? = null
    @Volatile internal var moverOverride: TaskMover? = null
    @Volatile internal var controllerOverride: HandoffController? = null

    // ── component accessors ───────────────────────────────────────────────

    /**
     * The mute ledger. Prefers an override (tests), then a persistent
     * SharedPreferences-backed instance; falls back to a memory-only instance
     * until an app context is available (a context arriving later rebuilds
     * it, so production always ends up persistent).
     */
    fun muter(): AudioMuter {
        muterOverride?.let { return it }
        val persistent = sharedPrefs
        val current = defaultMuter
        if (current != null && (persistent == null || current.persistent)) return current
        val m = AudioMuter(
            shell = shell,
            prefs = persistent ?: MemoryPrefs(),
            persistent = persistent != null,
        )
        defaultMuter = m
        return m
    }

    /** Task move / listing, user-service-first with the shell probe fallback. */
    fun mover(): TaskMover {
        moverOverride?.let { return it }
        defaultMover?.let { return it }
        val m = TaskMover(
            shell = shell,
            service = { serviceClient()?.obtain() },
        )
        defaultMover = m
        return m
    }

    private fun serviceClient(): VscreenServiceClient? {
        val ctx = appContext ?: return null
        defaultServiceClient?.let { return it }
        val c = VscreenServiceClient(ctx)
        defaultServiceClient = c
        return c
    }

    /**
     * The handoff controller for the hand_to_user flow. Stateless over prefs
     * (state lives in [HandoffStore]) so the cached instance never diverges;
     * [context] only seeds the app context on first use (notification +
     * broadcast paths always ride the application context).
     */
    fun handoffController(context: Context? = null): HandoffController? {
        controllerOverride?.let { return it }
        defaultController?.let { return it }
        val ctx = context ?: appContext ?: return null
        if (appContext == null) {
            appContext = ctx.applicationContext
            sharedPrefs = SharedPrefsVscreenPrefs(ctx.applicationContext)
        }
        val prefs = sharedPrefs ?: return null
        synchronized(this) {
            defaultController?.let { return it }
            val app = appContext
            val c = HandoffController(
                store = HandoffStore(prefs, broadcaster = { record ->
                    record?.let {
                        if (app != null) {
                            runCatching { HandoffNotifier.broadcastStateChanged(app, it.displayId, it.state) }
                        }
                    }
                }),
                mover = mover(),
                muter = muter(),
                notifier = { record ->
                    app?.let { HandoffNotifier.post(it, record) } ?: false
                },
            )
            defaultController = c
            return c
        }
    }

    /**
     * Raw handoff state for [displayId] (no controller build, no
     * guidance-string construction), or null when the display has no record.
     * Honors [controllerOverride] so test fakes keep flowing; production
     * reads [HandoffStore] straight from the prefs. Consumers that only need
     * "is it WAITING" use this instead of substring-matching [handoffNote].
     */
    fun handoffState(displayId: Int): HandoffStateMachine.State? {
        controllerOverride?.let { return it.rawState(displayId) }
        val prefs = sharedPrefs ?: return null
        return HandoffStore(prefs).get(displayId)?.state
    }

    /**
     * Model-facing handoff annotation for [displayId] (state guidance plus
     * record note), or null. Used by the tool on screenshot/list results.
     */
    fun handoffNote(displayId: Int): String? = handoffController()?.noteFor(displayId)

    /** Our own package name, when the app context is known (never mute ourselves). */
    fun ownPackageName(): String? = appContext?.packageName

    // ── upkeep ops ────────────────────────────────────────────────────────

    /**
     * Leftover sweep, run from the tool dispatch path and after each create:
     *  - handoff records of dead displays expire (their move-back target is gone);
     *  - mute ledger entries whose display no longer exists, or whose app is
     *    now visible on the main display, get their sound back.
     *
     * [liveIds] are the manager's live virtual display ids. Returns
     * model-facing notes of everything it did (usually empty).
     */
    fun reconcileState(liveIds: Set<Int>): List<String> {
        val notes = mutableListOf<String>()
        sharedPrefs?.let { prefs ->
            HandoffStore(prefs, broadcaster = {}).expireDead(liveIds, System.currentTimeMillis()).forEach {
                notes.add("handoff of display ${it.displayId} expired (screen released while waiting for the user)")
            }
        }
        val m = muter()
        if (m.mutedPackages().isNotEmpty()) {
            notes.addAll(m.reconcile(liveIds) { pkg -> mainDisplayVisible(pkg) })
        }
        return notes
    }

    /** Is a task of [pkg] on the main display? Null when listing is unavailable. */
    internal fun mainDisplayVisible(pkg: String): Boolean? = when (val o = mover().listTasks()) {
        is TaskMover.ListOutcome.Rows ->
            o.rows.any { it.displayId == VirtualScreenManager.MAIN_DISPLAY_ID && it.packageName == pkg }
        is TaskMover.ListOutcome.Unavailable -> null
    }

    /**
     * Silence [pkg] before it starts on the display. Never blocks the launch:
     * a mute failure is a note, not an error (and our own package is never
     * muted — we would silence ourselves).
     */
    fun muteForLaunch(displayId: Int, pkg: String): String {
        val own = ownPackageName()
        if (own != null && pkg == own) {
            return "not muting our own app"
        }
        return runCatching { muter().mute(displayId, pkg) { candidate -> mainDisplayVisible(candidate) } }
            .onFailure { AppLogger.warning(TAG, "mute($pkg) threw: ${it.message}") }
            .getOrElse { "audio mute failed: ${it.message}" }
    }

    /**
     * IME policy is display-level and only reachable through the user
     * service; applied off-thread so a slow first bind never delays create.
     */
    fun applyImePolicyAsync(screen: VirtualScreenManager.VirtualScreen) {
        Thread {
            val outcome = when (mover().setImePolicy(screen.displayId, IME_POLICY_HIDING)) {
                TaskMover.ImePolicyOutcome.Applied -> "ime_policy: hiding applied (keyboard stays with the user)"
                TaskMover.ImePolicyOutcome.Refused ->
                    "ime_policy: this ROM refused setDisplayImePolicy (probed via the user service) — the background screen may pop the keyboard on the main display"
                TaskMover.ImePolicyOutcome.NoService ->
                    "ime_policy: unavailable (the Shizuku user service is not bound; this ROM exposes no shell equivalent)"
            }
            screen.imePolicyNote = outcome
            AppLogger.info(TAG, "display ${screen.displayId}: $outcome")
        }.apply { isDaemon = true; name = "minis-vscreen-ime-policy" }.start()
    }

    /** Test seam reset: drop overrides and any accumulated context/prefs. */
    fun resetForTest(muter: AudioMuter?, mover: TaskMover?, controller: HandoffController?) {
        appContext = null
        sharedPrefs = null
        defaultMuter = null
        defaultMover = null
        defaultServiceClient = null
        defaultController = null
        muterOverride = muter
        moverOverride = mover
        controllerOverride = controller
    }

    companion object {
        private const val TAG = "VirtualScreenUpkeep"

        /** WindowManager.DISPLAY_IME_POLICY_HIDING — the background screen never requests the IME. */
        const val IME_POLICY_HIDING = 1
    }
}
