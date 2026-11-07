package com.openminis.app.vscreen

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.HostSuFallback
import com.openminis.app.offload.ShizukuManager
import com.openminis.app.vscreen.tier.CreateRequestSanitizer
import com.openminis.app.vscreen.tier.DisplayProvider
import com.openminis.app.vscreen.tier.HostDisplayClient
import com.openminis.app.vscreen.tier.NativeDisplayProvider
import com.openminis.app.vscreen.tier.ProbeResult
import com.openminis.app.vscreen.tier.RootDisplayProvider
import com.openminis.app.vscreen.tier.ShizukuDisplayProvider
import com.openminis.app.vscreen.tier.TierStateMachine
import com.openminis.app.vscreen.tier.VdHostProtocol
import com.openminis.app.vscreen.tier.VscreenTier
import com.openminis.app.vscreen.tier.VscreenTierOrchestrator
import java.io.File

/**
 * [T-android-vscreen] Agent-owned virtual screens (VirtualDisplay).
 *
 * WHY: the accessibility stack (ui_read / ui_action) drives the USER's
 * physical screen — every gesture steals the phone from whoever is holding
 * it, and the whole chain dies when the Accessibility permission is not
 * granted. A virtual display inverts both: the target app is started ON an
 * agent-owned display, so reads/taps/typing happen off the user's screen,
 * and the driving commands go through the Shizuku shell — no Accessibility
 * permission involved at any point.
 *
 * Ops and their dependencies:
 *   create/destroy/list  — app-level DisplayManager API only (no Shizuku).
 *   launch               — Shizuku `am start -W --display <id> -n pkg/cls`.
 *   screenshot           — layered, see below.
 *   tap/swipe/text/key   — Shizuku `input` with display targeting, probed.
 *
 * Screenshot layers (R&D: every layer is probed, never assumed):
 *   1. ImageReader attached to our OWN display (app-uid, no Shizuku, no
 *      permission at all).
 *   2. Shizuku `screencap -d <id>` to /data/local/tmp, then `cp` into the
 *      app-readable offloads dir (works with root-mode Shizuku; adb-mode
 *      shell usually cannot write app data dirs — probed, not assumed).
 *   3. Chunked base64 through Shizuku stdout — [ShizukuManager.runProcess]
 *      bounds its output with BoundedOutputBuffer (64KB head + 64KB tail and
 *      a marker in between), so a whole-PNG base64 CANNOT ride one call;
 *      chunks stay under the head cap and are concatenated here.
 *
 * Surface contract (root cause of T-android-vscreen3): the display is
 * created WITH a surface — an app-owned ImageReader attached for the
 * display's WHOLE lifetime. A surface-less virtual display is put to
 * sleep by the platform (WMS holds an internal "Display-off" sleep
 * token), which hides its windows and drops every injected input event,
 * so a transiently-attached capture surface can never be driven.
 * OWN_CONTENT_ONLY keeps the frames private to our reader (the user's
 * screen shows nothing) at the cost of compositing on our display while
 * it lives — bounded by [MAX_SCREENS] and the idle release. Screenshots
 * read the reader directly; there is no attach/detach churn.
 *
 * Caps and seams:
 *   - At most [MAX_SCREENS] displays; the least-recently-used one is released
 *     (LRU) when a create overflows. lastUsedAtMs is touched on every action.
 *   - [DisplayHandle] / [DisplayHandleFactory] / [ShellRunner] are seams so
 *     the LRU, probe and degradation logic run on the JVM in tests without
 *     DisplayManager, Shizuku or a device (ShizukuBackendStateTest style).
 *
 * V2 ([T-android-vscreen2], DSH AGENT-SCREEN-V2.md semantics, self-written):
 *   - SILENT LEDGER: apps started on a screen are muted via Shizuku
 *     `appops PLAY_AUDIO ignore` (previous mode recorded in prefs); the
 *     original value is restored when the app leaves the screen — destroy,
 *     LRU eviction, hand_to_user, or the leftover reconcile.
 *   - HAND_TO_USER: [HandoffController] moves the display's top task to the
 *     main display (user service binder first, shell probe table second),
 *     restores its sound and posts the "needs you" notification; the three
 *     user choices drive a pure [HandoffStateMachine] persisted in prefs.
 *   - TASK MOVE: [TaskMover] with runtime probing; every failure is honest.
 *   - SPILLOVER RECOVERY: a 3s watcher after launch pulls jumped-back apps
 *     to the agent screen (best effort).
 *   - IDLE RELEASE: screens idle for [IDLE_RELEASE_MS] destroy themselves.
 */
object VirtualScreenManager {
    private const val TAG = "VirtualScreenManager"

    const val MAX_SCREENS = 2

    // [T-android-vscreen-tier] Clamping defaults moved to CreateRequestSanitizer.
    const val DEFAULT_WIDTH = CreateRequestSanitizer.DEFAULT_WIDTH
    const val DEFAULT_HEIGHT = CreateRequestSanitizer.DEFAULT_HEIGHT
    const val DEFAULT_DPI = CreateRequestSanitizer.DEFAULT_DPI

    private const val TMP_PREFIX = "/data/local/tmp/minis_vs_"

    /** Live handle over one created virtual display. Test seam. */
    interface DisplayHandle {
        val displayId: Int

        /**
         * Platform-reported lifecycle of the display surface. Defaults to
         * [VdLifecycle.RUNNING] so fakes and privileged-host handles (which
         * have no app-side [VirtualDisplay.Callback]) stay valid — only the
         * real app-uid factory overrides it, which is what lets the
         * screenshot lane detect and clean up a platform-stopped display.
         */
        val lifecycle: VdLifecycle get() = VdLifecycle.RUNNING

        /**
         * Latest frame rendered on the display, or null when none is
         * available yet. The caller owns the returned [Image] and must
         * close it. The display surface stays attached — [release] is the
         * only teardown, so the display keeps input focus for its lifetime.
         */
        fun acquireLatestFrame(): Image?
        fun release()
    }

    /** Factory seam: real one wraps DisplayManager; tests fake it. */
    fun interface DisplayHandleFactory {
        fun create(label: String, width: Int, height: Int, dpi: Int): DisplayHandle?
    }

    /** Shell seam: real one is ShizukuManager.runProcess; tests fake it. */
    fun interface ShellRunner {
        fun run(argv: Array<String>, timeoutMs: Long): ShizukuManager.ProcessResult
    }

    class VirtualScreen(
        val displayId: Int,
        val width: Int,
        val height: Int,
        val dpi: Int,
        val label: String,
        val createdAtMs: Long,
        @Volatile var lastUsedAtMs: Long = createdAtMs,
        @Volatile internal var handle: DisplayHandle? = null,
        /** Best-effort spillover recovery result, consumed by the next tool result. */
        @Volatile var spillbackNote: String? = null,
        /** True while the post-launch spillover watcher thread is still polling. */
        @Volatile var spillbackWatching: Boolean = false,
        /** IME policy outcome of the async apply after create (null = unknown yet). */
        @Volatile var imePolicyNote: String? = null,
        /**
         * [T-android-vscreen-tier] Which provisioning lane created this
         * screen (ROOT / SHIZUKU / NATIVE). Fixed at creation — a privilege
         * upgrade never migrates a live screen, it only affects NEW creates.
         */
        val tier: VscreenTier = VscreenTier.NATIVE,
    )

    sealed class CreateOutcome {
        data class Created(
            val screen: VirtualScreen,
            val evicted: VirtualScreen?,
            /** Why lower-priority tiers were skipped, when any were. */
            val fallbackNote: String? = null,
            /**
             * [T-android-vscreen-tier] The provisioning provider's own note
             * (the ROOT ladder's resolved flags, the SHIZUKU owner info) —
             * surfaced verbatim in the tool result next to the tier's
             * standard guidance.
             */
            val providerNote: String? = null,
        ) : CreateOutcome()
        data class FactoryMissing(val message: String) : CreateOutcome()
        data class Failed(val message: String) : CreateOutcome()
    }

    sealed class CommandOutcome {
        data class Ok(val displayId: Int, val detail: String, val guestPath: String? = null) : CommandOutcome()
        /** Shizuku not installed / not running / not authorized. */
        data class ShizukuUnavailable(val message: String) : CommandOutcome()
        /** No such virtual display. */
        data class NotFound(val message: String) : CommandOutcome()
        /** The device ROM lacks the API variant (no display-targeted input/screencap). */
        data class NotSupported(val message: String) : CommandOutcome()
        data class Failed(val message: String) : CommandOutcome()
    }

    private val screens = LinkedHashMap<Int, VirtualScreen>()
    private val lock = Any()

    @Volatile private var installedFactory: DisplayHandleFactory? = null
    @Volatile private var shellRunnerOverride: ShellRunner? = null
    @Volatile private var shizukuGuardOverride: (() -> String?)? = null

    // ── [T-android-vscreen2] component wiring lives in VirtualScreenUpkeep ──

    @Volatile private var schedulerOverride: Scheduler? = null

    /** Reap runnable already queued on the scheduler. */
    private val reapPosted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Delayed-work seam: production posts on the main looper; tests capture runnables. */
    fun interface Scheduler {
        fun post(r: Runnable, delayMs: Long)
    }

    // [v3] The post target is the MAIN looper regardless of the calling
    // thread — the old `if (Looper.myLooper() == null) Looper.prepare()`
    // prepared a throwaway looper on whatever background thread happened to
    // schedule the reap (never quit → thread leak) and was then ignored in
    // favor of getMainLooper() anyway. Handler(Looper.getMainLooper()) is
    // legal from any thread.
    private val defaultScheduler = Scheduler { r, delayMs ->
        Handler(Looper.getMainLooper()).postDelayed(r, delayMs)
    }

    private val reapRunnable = Runnable { reapIdle() }

    private val defaultShellRunner = ShellRunner { argv, timeoutMs ->
        ShizukuManager.runProcess(argv, null, null, timeoutMs)
    }

    private val defaultShizukuGuard: () -> String? = {
        ShizukuManager.refresh()
        // [T-host-su-fallback] guardDecision lets a su-equipped device pass
        // because runProcess routes its commands through host su when the
        // binder is not READY.
        HostSuFallback.guardDecision(
            ShizukuManager.snapshot.value.state,
            HostSuFallback.isAvailable(),
        )
    }

    // ── [T-android-vscreen2] component wiring + upkeep ─────────────────────

    /**
     * The mute ledger, task mover and handoff controller wiring plus the
     * leftover reconcile / launch-mute / IME-policy upkeep ops live in
     * [VirtualScreenUpkeep]; the shell closure binds to THIS object's
     * current override so test fakes keep flowing through.
     */
    private val upkeep = VirtualScreenUpkeep(shell = { argv, timeoutMs -> runShell(argv, timeoutMs) })

    /** The mute ledger (override-aware; tests reach it directly). */
    internal fun muter(): AudioMuter = upkeep.muter()

    /** The handoff controller for the hand_to_user flow (see [VirtualScreenUpkeep]). */
    fun handoffController(context: Context? = null): HandoffController? = upkeep.handoffController(context)

    /**
     * Apply a handoff resolution (from [HandoffBroadcastReceiver]) — blocking;
     * the receiver wraps it in a worker thread.
     */
    fun resolveHandoff(displayId: Int, action: HandoffStateMachine.Action): HandoffController.ResolveOutcome? =
        upkeep.handoffController()?.resolve(displayId, action)

    /**
     * Model-facing handoff annotation for [displayId] (state guidance plus
     * record note), or null. Used by the tool on screenshot/list results.
     */
    fun handoffNote(displayId: Int): String? = upkeep.handoffNote(displayId)

    /** Consumes the pending spillover-recovery note of [displayId], if any. */
    fun consumeSpillbackNote(displayId: Int): String? = synchronized(lock) {
        SpillbackWatcher.consumeNote(screens[displayId])
    }

    /**
     * Leftover sweep, run from the tool dispatch path and after each create:
     *  - handoff records of dead displays expire (their move-back target is gone);
     *  - mute ledger entries whose display no longer exists, or whose app is
     *    now visible on the main display, get their sound back.
     *
     * Returns model-facing notes of everything it did (usually empty).
     */
    fun reconcileState(): List<String> =
        upkeep.reconcileState(synchronized(lock) { screens.keys.toSet() })

    // ─── create / list / destroy ──────────────────────────────────────────

    /**
     * Create a virtual display through the tier ladder
     * ([VscreenTierOrchestrator]: ROOT → SHIZUKU → NATIVE, first success
     * wins; failures mark the tier degraded and fall through). The ladder
     * runs OUTSIDE the registry lock — a root deployment can block for
     * seconds — and only the screen registration takes it.
     *
     * [T-android-vscreen2] After the display exists: leftover mute entries
     * are reconciled (leftover restore), and the per-display IME policy is
     * applied ASYNC best-effort through the user service (Android 12+,
     * DISPLAY_IME_POLICY_HIDING) so the headless screen can never pull the
     * keyboard away from the user — the outcome lands on
     * [VirtualScreen.imePolicyNote] for the next list result, never blocks
     * and never fails the create.
     */
    fun create(width: Int, height: Int, dpi: Int, label: String?, context: Context?): CreateOutcome {
        // Environment-missing short circuit (pre-refactor semantics): with
        // no app context NO tier can work — the NATIVE factory needs one to
        // reach DisplayManager and the ROOT host needs one to read its
        // deploy asset. Reported as FactoryMissing before the ladder runs so
        // the tool layer keeps mapping it to UNSUPPORTED.
        if (context == null && installedFactory == null) {
            return CreateOutcome.FactoryMissing(
                "no display factory installed and no app context available — " +
                    "virtual_screen needs an Android context to create displays",
            )
        }
        val orchestrator = ensureTierLadder(context)
            ?: return CreateOutcome.FactoryMissing(
                "no display factory installed and no app context available — " +
                    "virtual_screen needs an Android context to create displays",
            )
        val req = CreateRequestSanitizer.sanitize(width, height, dpi, label)
        return when (val step = orchestrator.create(req, context)) {
            is VscreenTierOrchestrator.Orchestrated -> synchronized(lock) {
                val evicted = evictLeastRecentlyUsed()
                val now = System.currentTimeMillis()
                val screen = VirtualScreen(
                    step.displayId, req.width, req.height, req.dpi, req.label, now,
                    tier = step.tier,
                )
                // A provider running outside this process (Shizuku public
                // display, root host) returns its handle on the outcome;
                // NATIVE keeps the handle through the shared factory seam.
                screen.handle = step.handle
                screens[screen.displayId] = screen
                AppLogger.info(
                    TAG,
                    "created display ${screen.displayId} ${req.width}x${req.height}@${req.dpi}dpi " +
                        "tier=${step.tier.name.lowercase()}" +
                        (evicted?.let { " (evicted LRU display ${it.displayId})" } ?: ""),
                )
                // Leftover restore (mute ledger entries of dead displays) — cheap when empty.
                runCatching { reconcileState() }
                    .onFailure { AppLogger.warning(TAG, "reconcile after create failed: ${it.message}") }
                upkeep.applyImePolicyAsync(screen)
                scheduleReap()
                CreateOutcome.Created(
                    screen, evicted,
                    step.fallbackNotes.takeIf { it.isNotEmpty() }?.joinToString("; "),
                    providerNote = step.note,
                )
            }
            is VscreenTierOrchestrator.OrchestratedFailure ->
                CreateOutcome.Failed(step.summary)
        }
    }

    fun screen(displayId: Int): VirtualScreen? = synchronized(lock) { screens[displayId] }

    fun list(): List<VirtualScreen> = synchronized(lock) {
        screens.values.sortedBy { it.displayId }
    }

    /** Releases the display and forgets it. Returns the removed screen, or null. */
    fun destroy(displayId: Int): VirtualScreen? = destroyDetailed(displayId)?.screen

    /**
     * Full teardown ([T-android-vscreen2]): release the display, restore the
     * PLAY_AUDIO mode of every app this screen muted, and clear any handoff
     * record so a destroyed screen cannot leave a WAITING notification
     * pointing at a dead move-back target.
     */
    fun destroyDetailed(displayId: Int): DestroyReport? {
        val removed = synchronized(lock) { screens.remove(displayId) } ?: return null
        val restored = runCatching { muter().restoreForDisplay(displayId) }
            .onFailure { AppLogger.warning(TAG, "mute restore on destroy failed: ${it.message}") }
            .getOrDefault(emptyList())
        val clearedHandoff = runCatching { handoffController()?.clearForDestroyedDisplay(displayId) }
            .getOrNull()
        removed.handle?.release()
        // [T-android-vscreen-tier] Tier-specific teardown (the ROOT host's
        // stop sentinel; NATIVE/SHIZUKU are no-ops here — their handles
        // release the display directly).
        runCatching { activeTierProviders[removed.tier]?.destroy(displayId) }
            .onFailure { AppLogger.warning(TAG, "tier destroy on display $displayId failed: ${it.message}") }
        scheduleReap()
        return DestroyReport(removed, restored, clearedHandoff != null)
    }

    /** What a destroy actually did beyond releasing the handle. */
    class DestroyReport(
        val screen: VirtualScreen,
        /** Packages whose original PLAY_AUDIO mode was restored. */
        val soundRestored: List<String>,
        /** True when a WAITING handoff record was cleared with the screen. */
        val handoffCleared: Boolean,
    )

    // ─── launch ───────────────────────────────────────────────────────────

    /**
     * Start [pkg] on the virtual display. With an explicit [activity] the
     * component is used verbatim; without one, `cmd package resolve-activity
     * --brief` locates the launcher activity (grep of the project found no
     * existing shell resolve pattern — this is the first). Resolution failure
     * is reported honestly: the fallback (`monkey -p`) has no --display flag
     * and would silently misroute the launch to display 0, so it is NOT used.
     *
     * [T-android-vscreen2] The app is SILENCED before it starts (silent
     * ledger): `appops set <pkg> PLAY_AUDIO ignore` via the Shizuku shell with
     * the previous mode recorded — the mute note rides in the result detail,
     * the original mode comes back when the app leaves this display (destroy,
     * hand_to_user, reconcile). A package the user currently has open on the
     * main display is never muted. After a successful launch a spillover
     * watcher runs for [SpillbackWatcher.SPILL_WINDOW_MS]: an app that jumps to the main
     * display within that window is moved back (best effort, see
     * [SpillbackWatcher.watch]).
     */
    fun launch(displayId: Int, pkg: String, activity: String?): CommandOutcome {
        val s = screen(displayId) ?: return CommandOutcome.NotFound(notFoundMessage(displayId))
        touch(s)
        shizukuUnavailableMessage()?.let { return CommandOutcome.ShizukuUnavailable(it) }
        val cleanPkg = pkg.trim()
        if (cleanPkg.isEmpty()) {
            return CommandOutcome.Failed("package is required for launch")
        }
        val component = activity?.trim()?.takeIf { it.isNotEmpty() } ?: resolveLauncherActivity(cleanPkg)
        if (component == null) {
            return CommandOutcome.Failed(
                "could not resolve a launcher activity for $cleanPkg " +
                    "(`cmd package resolve-activity --brief` returned nothing usable). " +
                    "Pass activity explicitly, e.g. $cleanPkg/.MainActivity.",
            )
        }
        val muteNote = upkeep.muteForLaunch(displayId, cleanPkg)
        val argv = arrayOf("am", "start", "-W", "--display", displayId.toString(), "-n", component)
        val r = runShell(argv, 15_000L)
        val out = r.combined.trim()
        return if (r.exitCode == 0 && !out.contains("Status: error") && !out.contains("Error type")) {
            SpillbackWatcher.watch(s, upkeep.mover(), upkeep.muter(), upkeep.handoffController())
            CommandOutcome.Ok(displayId, "started $component on display $displayId\n$muteNote\n$out")
        } else {
            CommandOutcome.Failed(
                "am start on display $displayId failed: ${out.ifBlank { "exitCode=${r.exitCode}" }} " +
                    "— some ROMs refuse launching other apps on app-owned displays\n$muteNote",
            )
        }
    }

    /**
     * `cmd package resolve-activity --brief <pkg>` prints an intent summary
     * line and then the component. The component is the last non-blank line
     * that contains "/" but no spaces.
     */
    internal fun resolveLauncherActivity(pkg: String): String? {
        val r = runShell(arrayOf("cmd", "package", "resolve-activity", "--brief", pkg), 8_000L)
        if (r.exitCode != 0) return null
        return resolveComponentFromOutput(r.combined)
    }

    internal fun resolveComponentFromOutput(output: String): String? =
        output.lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && it.contains('/') && !it.contains(' ') }
            .lastOrNull()

    // ─── screenshot (layered) ─────────────────────────────────────────────

    /**
     * Capture the virtual display. Layer 1 (ImageReader, app-uid, no
     * Shizuku) → layer 2 (Shizuku screencap + cp into [offloadsDir]) →
     * layer 3 (Shizuku screencap + chunked base64 through stdout). All
     * layers end with app-side bytes written under [offloadsDir]; the
     * returned guest path is `/var/minis/offloads/<file>` so `read_image`
     * resolves it. `uiautomator dump` has NO display parameter, so a text
     * accessibility-tree dump of the virtual screen is not possible — the
     * screenshot is the only read surface.
     */
    fun screenshot(displayId: Int, offloadsDir: File?): CommandOutcome {
        val s = screen(displayId) ?: return CommandOutcome.NotFound(notFoundMessage(displayId))
        touch(s)

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
            val (shellWorked, hostState) = readHostStateViaShell()
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
        shizukuUnavailableMessage()?.let {
            return CommandOutcome.ShizukuUnavailable(
                "$it (the ImageReader capture returned no frame, so the " +
                    "screencap fallback needs Shizuku too)",
            )
        }
        if (screencapSupportsDisplayFlag() == false) {
            return CommandOutcome.NotSupported(
                "this ROM's screencap has no -d option (probed via its usage output) and the " +
                    "ImageReader capture returned no frame — no read path remains for this display",
            )
        }
        val tmp = "$TMP_PREFIX${System.currentTimeMillis()}${VirtualScreenCapture.PNG_SUFFIX}"
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

    /** Probe delegate: see [VscreenDisplayProbes] (extracted for the line ceiling). */
    internal fun screencapSupportsDisplayFlag(): Boolean? =
        VscreenDisplayProbes.screencapSupportsDisplayFlag(::runShell)

    /**
     * Reads the root host's state file through the Shizuku shell lane.
     * Returns (shellWorked, state): a null state with shellWorked=true means
     * the file is really gone (host died); shellWorked=false means the shell
     * lane itself failed (Shizuku down) — liveness stays unknown and the
     * caller must fall through rather than kill the screen.
     */
    private fun readHostStateViaShell(): Pair<Boolean, VdHostProtocol.HostState?> {
        val result = runShell(arrayOf("cat", VdHostProtocol.STATE_PATH), 5_000L)
        if (result.exitCode != 0) return false to null
        return true to VdHostProtocol.parseState(result.stdout)
    }

    internal fun inputDisplayForm(displayId: Int): VscreenDisplayProbes.InputDisplayForm =
        VscreenDisplayProbes.inputDisplayForm(::runShell, displayId)

    // ─── seams and helpers ────────────────────────────────────────────────

    /**
     * Installs the real DisplayManager-backed factory on first use. Lazy on
     * purpose: MinisApp.kt is on the architecture baseline (may not grow), so
     * there is no eager init call site — the first tool call supplies the
     * Application context instead. Tests install a fake via [resetForTest].
     */
    private fun ensureFactory(context: Context?): DisplayHandleFactory? {
        installedFactory?.let { return it }
        if (context == null) return null
        synchronized(lock) {
            if (installedFactory == null) {
                installedFactory = buildRealFactory(context.applicationContext)
            }
        }
        return installedFactory
    }

    // ── [T-android-vscreen-tier] the tier ladder ──────────────────────────

    /** Production ladder, in priority order (ROOT → SHIZUKU → NATIVE). */
    private val productionTierProviders: Map<VscreenTier, DisplayProvider> = mapOf(
        VscreenTier.ROOT to RootDisplayProvider(HostSuFallback::run),
        VscreenTier.SHIZUKU to ShizukuDisplayProvider { upkeep.displayService() },
        VscreenTier.NATIVE to NativeDisplayProvider { ctx -> ensureFactory(ctx) },
    )

    /**
     * Test ladder override: [resetForTest] pins the ladder to NATIVE-ONLY so
     * the pre-refactor behaviour (FakeFactory in, FakeDisplay out) stays
     * byte-identical — the existing suite asserts the NATIVE path, and tier
     * tests install their own fakes through [tierProvidersOverride] instead
     * of probing real su/Shizuku state on the JVM.
     */
    @Volatile internal var tierProvidersOverride: Map<VscreenTier, DisplayProvider>? = null

    private val activeTierProviders: Map<VscreenTier, DisplayProvider>
        get() = tierProvidersOverride ?: productionTierProviders

    @Volatile private var installedTierState: TierStateMachine? = null

    /**
     * The create attempt chain over the probe sheet. Lazy like the factory:
     * the probe cache needs the app context for its prefs file, so the first
     * tool call installs it; tests get a MemoryPrefs-backed state machine
     * from [resetForTest].
     */
    private fun ensureTierLadder(context: Context?): VscreenTierOrchestrator? {
        installedTierState?.let { return VscreenTierOrchestrator(it, activeTierProviders) }
        if (context == null) return null
        synchronized(lock) {
            // Seed the upkeep's app context HERE, not only in hand_to_user:
            // the SHIZUKU tier's service client needs it, and a user who never
            // hand_to_users would otherwise see Shizuku permanently
            // "unreachable" even when granted.
            if (upkeep.appContext == null) {
                upkeep.appContext = context.applicationContext
            }
            if (installedTierState == null) {
                installedTierState = TierStateMachine(
                    activeTierProviders,
                    SharedPrefsVscreenPrefs(context.applicationContext),
                )
            }
        }
        return VscreenTierOrchestrator(installedTierState!!, activeTierProviders)
    }

    /**
     * Tier states for UI / tool results. SNAPSHOT read only — no fingerprint
     * check, no probing (both can spawn a 3s `command -v su` on non-root
     * devices, and this getter runs on the MAIN thread from the settings
     * screen). Fresh probing happens on the create path's background thread.
     */
    val tierSheet: Map<VscreenTier, ProbeResult>
        get() = installedTierState?.sheetSnapshot ?: emptyMap()

    /**
     * [T-android-vscreen-tier] Upgrade hint for the list result when a
     * better tier is available than the one a live screen runs on. Never
     * migrates anything — the model decides whether to recreate. Best-effort
     * by design: reads the cached sheet (no probing, see [tierSheet]) so a
     * `list` call can never stall on a su probe; the hint catches up on the
     * next create, which is when a tier switch can actually happen.
     */
    fun tierUpgradeHint(): String? = installedTierState?.upgradeHintFromSnapshot(
        synchronized(lock) { screens.values.map { it.tier }.toSet() },
    )

    /**
     * OWN_CONTENT_ONLY class (API 19+; project minSdk is 26, so no runtime
     * gate is needed). The reader's surface is attached AT CREATE and never
     * detached — see the class KDoc surface contract. A null-surface display
     * would sleep immediately and drop every injected input event.
     */
    private fun buildRealFactory(app: Context): DisplayHandleFactory =
        DisplayHandleFactory { label, w, h, dpi ->
            val dm = app.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2)
            val state = VirtualDisplayState()
            val vd = dm?.let {
                runCatching {
                    VirtualDisplayRuntime.create(
                        it, label, w, h, dpi, reader.surface,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                        state,
                    )
                }.getOrNull()
            }
            if (vd == null) {
                runCatching { reader.close() }
                null
            } else {
                object : DisplayHandle {
                    override val displayId: Int = vd.display.displayId
                    override val lifecycle: VdLifecycle
                        get() = state.lifecycle
                    override fun acquireLatestFrame(): Image? =
                        runCatching { reader.acquireLatestImage() }.getOrNull()
                    override fun release() {
                        runCatching { vd.release() }
                        runCatching { reader.close() }
                    }
                }
            }
        }

    internal fun runShell(argv: Array<String>, timeoutMs: Long): ShizukuManager.ProcessResult =
        (shellRunnerOverride ?: defaultShellRunner).run(argv, timeoutMs)

    internal fun shizukuUnavailableMessage(): String? =
        (shizukuGuardOverride ?: defaultShizukuGuard).invoke()

    internal fun touch(s: VirtualScreen) {
        s.lastUsedAtMs = System.currentTimeMillis()
        scheduleReap()
    }

    // ── [T-android-vscreen2] idle release ─────────────────────────────────

    /**
     * Queue the idle reap [IDLE_RELEASE_MS] out (deduplicated). Screens
     * released here go through the full destroy path (sound restored,
     * handoff cleared) — the agent simply finds them gone on its next call.
     */
    private fun scheduleReap() {
        if (reapPosted.getAndSet(true)) return
        (schedulerOverride ?: defaultScheduler).post({
            reapPosted.set(false)
            reapIdle()
        }, IDLE_RELEASE_MS)
    }

    /**
     * Release every screen idle for [IDLE_RELEASE_MS] or more (10 minutes),
     * keeping screens with a WAITING handoff alive — the user may still tap
     * 处理好了 and the move-back needs the display. Returns released ids.
     *
     * HONEST LIMIT: while no tool call and no process activity happen, the
     * reap fires only via the scheduled runnable on the main looper — a dead
     * process cannot destroy anything (the next process start reconciles the
     * leftover mute ledger instead, which is the state that matters).
     */
    fun reapIdle(nowMs: Long = System.currentTimeMillis()): List<Int> {
        val idle = synchronized(lock) {
            screens.values.filter { nowMs - it.lastUsedAtMs >= IDLE_RELEASE_MS }.map { it.displayId }
        }
        val released = mutableListOf<Int>()
        for (id in idle) {
            // A waiting handoff pins its display: the user's move-back needs it.
            // [v3] Read the raw HandoffStore state instead of substring-
            // matching the human-facing guidance string.
            val waiting = upkeep.handoffState(id) == HandoffStateMachine.State.WAITING
            if (waiting) {
                synchronized(lock) { screens[id]?.let { it.lastUsedAtMs = nowMs } }
                continue
            }
            destroyDetailed(id)?.let { released.add(it.screen.displayId) }
        }
        if (released.isNotEmpty()) {
            AppLogger.info(TAG, "idle-released displays $released after ${IDLE_RELEASE_MS}ms")
        }
        if (synchronized(lock) { screens.isNotEmpty() }) scheduleReap()
        return released
    }

    private fun evictLeastRecentlyUsed(): VirtualScreen? {
        if (screens.size < MAX_SCREENS) return null
        val victim = screens.values.minByOrNull { it.lastUsedAtMs } ?: return null
        screens.remove(victim.displayId)
        victim.handle?.release()
        // Same cleanup as an explicit destroy — an evicted screen must not
        // leave apps muted or a handoff notification pointing at it.
        runCatching { muter().restoreForDisplay(victim.displayId) }
            .onFailure { AppLogger.warning(TAG, "mute restore on evict failed: ${it.message}") }
        runCatching { handoffController()?.clearForDestroyedDisplay(victim.displayId) }
        runCatching { activeTierProviders[victim.tier]?.destroy(victim.displayId) }
        return victim
    }

    internal fun notFoundMessage(displayId: Int): String =
        "no virtual screen with display_id=$displayId — use action=list for live displays, " +
            "or action=create to make one"

    internal fun guestPathFor(fileName: String): String = "/var/minis/offloads/$fileName"

    /** Test seam: swap in fakes and clear all cached probes and state. */
    fun resetForTest(
        factory: DisplayHandleFactory?,
        shell: ShellRunner?,
        guard: (() -> String?)?,
        muter: AudioMuter? = null,
        mover: TaskMover? = null,
        controller: HandoffController? = null,
        scheduler: Scheduler? = null,
    ) {
        synchronized(lock) {
            screens.values.forEach { it.handle?.release() }
            screens.clear()
        }
        installedFactory = factory
        shellRunnerOverride = shell
        shizukuGuardOverride = guard
        VscreenDisplayProbes.resetForTest()
        // [T-android-vscreen2] component state lives in [VirtualScreenUpkeep]:
        // fresh defaults (MemoryPrefs) unless overridden, no leftover context.
        upkeep.resetForTest(muter, mover, controller)
        // [T-android-vscreen-tier] NATIVE-only ladder + fresh probe cache:
        // the pre-refactor behaviour (fake factory in, display out) stays
        // byte-identical, and no real su/Shizuku probe ever runs on the JVM.
        tierProvidersOverride = mapOf(
            VscreenTier.NATIVE to NativeDisplayProvider { ctx -> ensureFactory(ctx) },
        )
        installedTierState = TierStateMachine(tierProvidersOverride!!, MemoryPrefs())
        schedulerOverride = scheduler
        reapPosted.set(false)
        AudioMuter.cachedUser = null
    }


    // ── [T-android-vscreen2] constants ────────────────────────────────────

    /** The user's physical display (spillover watch + reconcile share it). */
    const val MAIN_DISPLAY_ID = 0

    /** Session idle time before a virtual screen releases itself. */
    const val IDLE_RELEASE_MS = 10 * 60_000L
}
