package com.openminis.app.vscreen

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.ShizukuManager
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
 * Tradeoff recorded at create time: the display is created with NO surface
 * (OWN_CONTENT_ONLY class), so nothing renders and no frame is composited
 * until the screenshot flow attaches one. The target app still starts,
 * runs and receives input on that display — verify state via `screenshot`,
 * which attaches an ImageReader for the capture and detaches it after.
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
    const val DEFAULT_WIDTH = 1080
    const val DEFAULT_HEIGHT = 1920
    const val DEFAULT_DPI = 320

    private const val MIN_EDGE = 320
    private const val MAX_EDGE = 4096
    private const val MIN_DPI = 120
    private const val MAX_DPI = 640

    private const val TMP_PREFIX = "/data/local/tmp/minis_vs_"

    /** Live handle over one created virtual display. Test seam. */
    interface DisplayHandle {
        val displayId: Int
        fun attach(surface: Surface): Boolean
        fun detach()
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
    )

    sealed class CreateOutcome {
        data class Created(val screen: VirtualScreen, val evicted: VirtualScreen?) : CreateOutcome()
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

    /** Which display-targeting form the ROM's `input` command accepts. */
    internal enum class InputDisplayForm { UNKNOWN, DASH_D, LONG_DISPLAY, NONE }

    private val screens = LinkedHashMap<Int, VirtualScreen>()
    private val lock = Any()

    @Volatile private var installedFactory: DisplayHandleFactory? = null
    @Volatile private var shellRunnerOverride: ShellRunner? = null
    @Volatile private var shizukuGuardOverride: (() -> String?)? = null
    @Volatile private var inputFormProbe: InputDisplayForm? = null
    @Volatile private var screencapProbe: Boolean? = null

    // ── [T-android-vscreen2] component wiring lives in VirtualScreenUpkeep ──

    @Volatile private var schedulerOverride: Scheduler? = null

    /** Reap runnable already queued on the scheduler. */
    private val reapPosted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Delayed-work seam: production posts on the main looper; tests capture runnables. */
    fun interface Scheduler {
        fun post(r: Runnable, delayMs: Long)
    }

    private val defaultScheduler = Scheduler { r, delayMs ->
        if (Looper.myLooper() == null) Looper.prepare()
        Handler(Looper.getMainLooper()).postDelayed(r, delayMs)
    }

    private val reapRunnable = Runnable { reapIdle() }

    private val defaultShellRunner = ShellRunner { argv, timeoutMs ->
        ShizukuManager.runProcess(argv, null, null, timeoutMs)
    }

    private val defaultShizukuGuard: () -> String? = {
        ShizukuManager.refresh()
        when (ShizukuManager.snapshot.value.state) {
            ShizukuManager.State.NOT_INSTALLED ->
                "Shizuku is not installed. Open Settings -> Permissions -> Shizuku to install it."
            ShizukuManager.State.NOT_RUNNING ->
                "Shizuku service is not running. Start it via adb or root, then retry."
            ShizukuManager.State.NEED_PERMISSION ->
                "Minis is not authorized for Shizuku. Grant permission in Settings -> Permissions -> Shizuku."
            ShizukuManager.State.READY -> null
        }
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
     * Create a virtual display (app-level DisplayManager; no Shizuku). The
     * display is created with NO surface — the target app can still be
     * started and driven on it, but nothing renders (recorded tradeoff).
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
        val factory = ensureFactory(context)
            ?: return CreateOutcome.FactoryMissing(
                "no display factory installed and no app context available — " +
                    "virtual_screen needs an Android context to create displays",
            )
        val w = sanitizeEdge(width, DEFAULT_WIDTH)
        val h = sanitizeEdge(height, DEFAULT_HEIGHT)
        // 0 = unspecified → default; otherwise clamp (2000dpi reads as 640).
        val d = when {
            dpi == 0 -> DEFAULT_DPI
            dpi < MIN_DPI -> MIN_DPI
            dpi > MAX_DPI -> MAX_DPI
            else -> dpi
        }
        val name = (label ?: "minis-virtual-screen").take(64)
        synchronized(lock) {
            val evicted = evictLeastRecentlyUsed()
            val handle = runCatching { factory.create(name, w, h, d) }.getOrElse {
                return CreateOutcome.Failed("display factory failed: ${it.message}")
            } ?: return CreateOutcome.Failed(
                "DisplayManager.createVirtualDisplay returned null — the device refused " +
                    "the virtual display (ROM policy or a system display limit)",
            )
            val now = System.currentTimeMillis()
            val screen = VirtualScreen(handle.displayId, w, h, d, name, now)
            screen.handle = handle
            screens[screen.displayId] = screen
            AppLogger.info(TAG, "created display ${screen.displayId} ${w}x${h}@${d}dpi" +
                (evicted?.let { " (evicted LRU display ${it.displayId})" } ?: ""))
            // Leftover restore (mute ledger entries of dead displays) — cheap when empty.
            runCatching { reconcileState() }
                .onFailure { AppLogger.warning(TAG, "reconcile after create failed: ${it.message}") }
            upkeep.applyImePolicyAsync(screen)
            scheduleReap()
            return CreateOutcome.Created(screen, evicted)
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
        if (offloadsDir == null) {
            return CommandOutcome.Failed(
                "no app context available — cannot write the screenshot file",
            )
        }
        offloadsDir.mkdirs()

        // Layer 1: our own display, our own uid.
        VirtualScreenCapture.captureViaImageReader(s.handle, s.width, s.height)?.let { bytes ->
            return VirtualScreenCapture.writePng(offloadsDir, displayId, bytes, "ImageReader")
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
        return VirtualScreenCapture.writePng(offloadsDir, displayId, bytes, "chunked base64")
    }

    /** Probe: does this ROM's screencap accept `-d`? Null = inconclusive (try the capture). */
    internal fun screencapSupportsDisplayFlag(): Boolean? {
        screencapProbe?.let { return it }
        val r = runShell(arrayOf("screencap", "-h"), 5_000L)
        val text = r.combined
        if (text.isBlank()) return null
        val supported = text.contains("-d") && text.lowercase().contains("display")
        screencapProbe = supported
        return supported
    }

    // ─── drive (input) ────────────────────────────────────────────────────

    fun tap(displayId: Int, x: Int, y: Int): CommandOutcome =
        driveInput(displayId, listOf("tap", x.toString(), y.toString()))

    fun swipe(displayId: Int, x1: Int, y1: Int, x2: Int, y2: Int, durationMs: Int?): CommandOutcome {
        val sub = mutableListOf("swipe", x1.toString(), y1.toString(), x2.toString(), y2.toString())
        if (durationMs != null && durationMs > 0) sub.add(durationMs.toString())
        return driveInput(displayId, sub)
    }

    fun text(displayId: Int, text: String): CommandOutcome {
        if (text.isBlank()) return CommandOutcome.Failed("text is required for the text action")
        // `input text` maps %s to a space on older ROMs where a literal space
        // breaks the argv; newer ROMs accept both forms.
        return driveInput(displayId, listOf("text", text.replace(" ", "%s")))
    }

    fun key(displayId: Int, key: String): CommandOutcome {
        val code = KEYCODES[key.trim().lowercase()] ?: key.trim().toIntOrNull()
        if (code == null) {
            return CommandOutcome.Failed(
                "key must be back, home, enter, or a numeric Android keycode",
            )
        }
        return driveInput(displayId, listOf("keyevent", code.toString()))
    }

    /**
     * Inject input through Shizuku `input`. The display-targeting flag drifted
     * across Android versions (`input -d <id> <cmd>` vs `--display` vs none),
     * so the form is PROBED at runtime (usage output first; one harmless
     * corner tap on OUR display when the ROM prints no usage) and cached.
     * A ROM without display targeting returns [CommandOutcome.NotSupported]
     * with the accessibility-stack fallback hint instead of guessing.
     */
    private fun driveInput(displayId: Int, sub: List<String>): CommandOutcome {
        val s = screen(displayId) ?: return CommandOutcome.NotFound(notFoundMessage(displayId))
        touch(s)
        shizukuUnavailableMessage()?.let { return CommandOutcome.ShizukuUnavailable(it) }
        val outOfBounds = boundsMessage(s, sub)
        if (outOfBounds != null) return CommandOutcome.Failed(outOfBounds)
        val form = inputDisplayForm(displayId)
        if (form == InputDisplayForm.NONE) {
            return CommandOutcome.NotSupported(
                "this ROM's `input` command has no display targeting (probed via its usage " +
                    "output and a probe tap) — injections always land on the physical screen, " +
                    "so they were refused. Fallbacks: the accessibility stack (ui_read / " +
                    "ui_action) if the Accessibility permission is granted, or " +
                    "shell_execute -> android-shizuku-cli exec 'input -d <id> <cmd>'.",
            )
        }
        val argv = when (form) {
            InputDisplayForm.DASH_D ->
                (arrayOf("input", "-d", displayId.toString()) + sub.toTypedArray())
            InputDisplayForm.LONG_DISPLAY ->
                (arrayOf("input", "--display", displayId.toString()) + sub.toTypedArray())
            else -> return CommandOutcome.NotSupported("input display form unresolved")
        }
        val r = runShell(argv, 8_000L)
        return if (r.exitCode == 0) {
            CommandOutcome.Ok(displayId, "injected ${sub.joinToString(" ")} on display $displayId")
        } else {
            CommandOutcome.Failed(
                "input on display $displayId failed: ${r.combined.ifBlank { "exitCode=${r.exitCode}" }}",
            )
        }
    }

    /**
     * Coordinates are model-supplied and must be plain integers in range —
     * the virtual display's coordinate space is exactly the size it was
     * created with, so an out-of-range tap is a wasted round trip reported
     * honestly instead of silently clamped by the display.
     */
    private fun boundsMessage(s: VirtualScreen, sub: List<String>): String? {
        var i = 0
        while (i < sub.size) {
            if (sub[i] == "tap" || sub[i] == "swipe") {
                val count = if (sub[i] == "tap") 2 else 4
                if (i + count >= sub.size) return null
                // Coordinates alternate x/y starting at j=1: odd = x (width), even = y (height).
                for (j in 1..count) {
                    val v = sub[i + j].toIntOrNull() ?: return null
                    val axis = if (j % 2 == 1) s.width else s.height
                    if (v < 0 || v > axis) {
                        return "coordinate ${sub[i + j]} falls outside virtual screen " +
                            "${s.width}x${s.height} (display ${s.displayId}) — " +
                            "check the latest screenshot's dimensions"
                    }
                }
                i += count
            }
            i++
        }
        return null
    }

    internal fun inputDisplayForm(displayId: Int): InputDisplayForm {
        inputFormProbe?.let { return it }
        val help = runShell(arrayOf("input"), 5_000L)
        val resolved = formFromHelp(help.combined)
            .takeIf { it != InputDisplayForm.UNKNOWN }
            ?: probeByRun(displayId)
        inputFormProbe = resolved
        return resolved
    }

    /** Usage-output probe: `--display` wins over `-d`, a no-display help is NONE. */
    internal fun formFromHelp(help: String): InputDisplayForm {
        if (help.isBlank()) return InputDisplayForm.UNKNOWN
        val lower = help.lowercase()
        return when {
            lower.contains("--display") -> InputDisplayForm.LONG_DISPLAY
            !lower.contains("display") -> InputDisplayForm.NONE
            Regex("-d\\b").containsMatchIn(help) -> InputDisplayForm.DASH_D
            else -> InputDisplayForm.NONE
        }
    }

    /**
     * The ROM printed no usage text — probe by running one harmless tap at
     * (1,1) on OUR display in each known form; the first that exits 0 wins.
     */
    private fun probeByRun(displayId: Int): InputDisplayForm {
        for (form in listOf(InputDisplayForm.DASH_D, InputDisplayForm.LONG_DISPLAY)) {
            val argv = when (form) {
                InputDisplayForm.DASH_D ->
                    arrayOf("input", "-d", displayId.toString(), "tap", "1", "1")
                else ->
                    arrayOf("input", "--display", displayId.toString(), "tap", "1", "1")
            }
            if (runShell(argv, 5_000L).exitCode == 0) return form
        }
        return InputDisplayForm.NONE
    }

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

    /**
     * OWN_CONTENT_ONLY class (API 19+; project minSdk is 26, so no runtime
     * gate is needed). Null surface — see the class KDoc tradeoff.
     */
    private fun buildRealFactory(app: Context): DisplayHandleFactory =
        DisplayHandleFactory { label, w, h, dpi ->
            val dm = app.getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager
            val vd = runCatching {
                dm?.createVirtualDisplay(
                    label, w, h, dpi, null,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
                    null, null,
                )
            }.getOrNull()
            vd?.let { v ->
                object : DisplayHandle {
                    override val displayId: Int = v.display.displayId
                    override fun attach(surface: Surface): Boolean =
                        runCatching { v.surface = surface }.isSuccess
                    override fun detach() {
                        runCatching { v.surface = null }
                    }
                    override fun release() {
                        runCatching { v.release() }
                    }
                }
            }
        }

    private fun runShell(argv: Array<String>, timeoutMs: Long): ShizukuManager.ProcessResult =
        (shellRunnerOverride ?: defaultShellRunner).run(argv, timeoutMs)

    private fun shizukuUnavailableMessage(): String? =
        (shizukuGuardOverride ?: defaultShizukuGuard).invoke()

    private fun touch(s: VirtualScreen) {
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
            val waiting = handoffNote(id)?.contains("WAITING") == true
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
        return victim
    }

    private fun sanitizeEdge(value: Int, default: Int): Int = when {
        value <= 0 -> default
        value < MIN_EDGE -> MIN_EDGE
        value > MAX_EDGE -> MAX_EDGE
        else -> value
    }

    private fun notFoundMessage(displayId: Int): String =
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
        inputFormProbe = null
        screencapProbe = null
        // [T-android-vscreen2] component state lives in [VirtualScreenUpkeep]:
        // fresh defaults (MemoryPrefs) unless overridden, no leftover context.
        upkeep.resetForTest(muter, mover, controller)
        schedulerOverride = scheduler
        reapPosted.set(false)
        AudioMuter.cachedUser = null
    }

    /** back / home / enter mapped to the universally-safe numeric keycodes. */
    private val KEYCODES = mapOf("back" to 4, "home" to 3, "enter" to 66)

    // ── [T-android-vscreen2] constants ────────────────────────────────────

    /** The user's physical display (spillover watch + reconcile share it). */
    const val MAIN_DISPLAY_ID = 0

    /** Session idle time before a virtual screen releases itself. */
    const val IDLE_RELEASE_MS = 10 * 60_000L
}
