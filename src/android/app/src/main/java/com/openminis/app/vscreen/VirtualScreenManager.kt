package com.openminis.app.vscreen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.ShizukuManager
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Base64
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

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

    private const val FRAME_TIMEOUT_MS = 3_000L
    /** base64 of one chunk stays inside the 64KB BoundedOutputBuffer head cap. */
    private const val CHUNK_BYTES = 48_000
    private const val TMP_PREFIX = "/data/local/tmp/minis_vs_"
    private const val PNG_PREFIX = "vscreen_"
    private const val PNG_SUFFIX = ".png"

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

    // ─── create / list / destroy ──────────────────────────────────────────

    /**
     * Create a virtual display (app-level DisplayManager; no Shizuku). The
     * display is created with NO surface — the target app can still be
     * started and driven on it, but nothing renders (recorded tradeoff).
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
            return CreateOutcome.Created(screen, evicted)
        }
    }

    fun screen(displayId: Int): VirtualScreen? = synchronized(lock) { screens[displayId] }

    fun list(): List<VirtualScreen> = synchronized(lock) {
        screens.values.sortedBy { it.displayId }
    }

    /** Releases the display and forgets it. Returns the removed screen, or null. */
    fun destroy(displayId: Int): VirtualScreen? = synchronized(lock) {
        screens.remove(displayId)?.also { it.handle?.release() }
    }

    // ─── launch ───────────────────────────────────────────────────────────

    /**
     * Start [pkg] on the virtual display. With an explicit [activity] the
     * component is used verbatim; without one, `cmd package resolve-activity
     * --brief` locates the launcher activity (grep of the project found no
     * existing shell resolve pattern — this is the first). Resolution failure
     * is reported honestly: the fallback (`monkey -p`) has no --display flag
     * and would silently misroute the launch to display 0, so it is NOT used.
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
        val argv = arrayOf("am", "start", "-W", "--display", displayId.toString(), "-n", component)
        val r = runShell(argv, 15_000L)
        val out = r.combined.trim()
        return if (r.exitCode == 0 && !out.contains("Status: error") && !out.contains("Error type")) {
            CommandOutcome.Ok(displayId, "started $component on display $displayId\n$out")
        } else {
            CommandOutcome.Failed(
                "am start on display $displayId failed: ${out.ifBlank { "exitCode=${r.exitCode}" }} " +
                    "— some ROMs refuse launching other apps on app-owned displays",
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
        captureViaImageReader(s.handle, s.width, s.height)?.let { bytes ->
            return writePng(offloadsDir, displayId, bytes, "ImageReader")
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
        val tmp = "$TMP_PREFIX${System.currentTimeMillis()}$PNG_SUFFIX"
        val cap = runShell(arrayOf("sh", "-c", "screencap -d $displayId -p $tmp"), 20_000L)
        if (cap.exitCode != 0) {
            return CommandOutcome.Failed(
                "screencap -d $displayId failed: ${cap.combined.ifBlank { "exitCode=${cap.exitCode}" }} " +
                    "— some ROMs expect a physical display id here rather than the logical id",
            )
        }

        // Layer 2: cp into the app-readable dir (root-mode Shizuku works here;
        // adb-mode shell usually cannot write app data dirs — probed).
        val direct = File(offloadsDir, "$PNG_PREFIX${displayId}_${System.currentTimeMillis()}$PNG_SUFFIX")
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
        val bytes = readChunked(tmp, displayId)
        runShell(arrayOf("sh", "-c", "rm -f $tmp"), 5_000L)
        if (bytes == null) {
            return CommandOutcome.Failed(
                "screencap wrote the capture to $tmp but no app-readable retrieval path worked " +
                    "(cp into the offloads dir refused by the ROM, and the chunked base64 read failed)",
            )
        }
        return writePng(offloadsDir, displayId, bytes, "chunked base64")
    }

    private fun writePng(dir: File, displayId: Int, bytes: ByteArray, via: String): CommandOutcome {
        val file = File(dir, "$PNG_PREFIX${displayId}_${System.currentTimeMillis()}$PNG_SUFFIX")
        return runCatching { file.writeBytes(bytes) }
            .map {
                CommandOutcome.Ok(
                    displayId,
                    "screenshot ${bytes.size} bytes via $via",
                    guestPathFor(file.name),
                )
            }
            .getOrElse {
                CommandOutcome.Failed("could not write the screenshot file: ${it.message}")
            }
    }

    /**
     * Layer 1. Attaches an ImageReader surface to our own display, awaits one
     * frame (bounded), reads it to a PNG and detaches — all app-uid. Returns
     * null on any failure (timeout, no frame, attach refused) so the caller
     * falls through to the screencap layers.
     */
    private fun captureViaImageReader(handle: DisplayHandle?, width: Int, height: Int): ByteArray? {
        if (handle == null) return null
        var reader: ImageReader? = null
        var thread: HandlerThread? = null
        return try {
            val frame = AtomicReference<Image>(null)
            val latch = CountDownLatch(1)
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            thread = HandlerThread("minis-vscreen-capture").also { it.start() }
            reader.setOnImageAvailableListener({ r ->
                if (frame.get() != null) return@setOnImageAvailableListener
                runCatching { r.acquireLatestImage() }.getOrNull()?.let {
                    frame.set(it)
                    latch.countDown()
                }
            }, Handler(thread.looper))
            if (!handle.attach(reader.surface)) return null
            latch.await(FRAME_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            handle.detach()
            val img = frame.get() ?: return null
            val png = imageToPng(img)
            img.close()
            png
        } catch (t: Throwable) {
            AppLogger.warning(TAG, "ImageReader capture failed: ${t.message}")
            null
        } finally {
            runCatching { reader?.close() }
            runCatching { thread?.quitSafely() }
        }
    }

    private fun imageToPng(img: Image): ByteArray? = runCatching {
        val plane = img.planes[0]
        val buffer = plane.buffer
        buffer.rewind()
        val rowStridePx = plane.rowStride / plane.pixelStride
        val full = Bitmap.createBitmap(rowStridePx, img.height, Bitmap.Config.ARGB_8888)
        full.copyPixelsFromBuffer(buffer)
        val scaled = if (full.width == img.width) {
            full
        } else {
            Bitmap.createBitmap(full, 0, 0, img.width, img.height).also { full.recycle() }
        }
        val out = ByteArrayOutputStream()
        scaled.compress(Bitmap.CompressFormat.PNG, 100, out)
        scaled.recycle()
        out.toByteArray()
    }.getOrNull()

    /**
     * Layer 3. Reads [tmp] in [CHUNK_BYTES] pieces — each chunk's base64 stays
     * inside the 64KB BoundedOutputBuffer head cap — and decodes the
     * concatenation. Returns null on the first failed chunk (the caller
     * reports honestly instead of handing back a corrupt PNG).
     */
    private fun readChunked(tmp: String, displayId: Int): ByteArray? {
        val size = runShell(arrayOf("sh", "-c", "wc -c < $tmp"), 5_000L)
        val total = size.stdout.trim().toIntOrNull()
        if (total == null || total <= 0) {
            AppLogger.warning(TAG, "screencap output for display $displayId unreadable: ${size.combined.ifBlank { "no size" }}")
            return null
        }
        val b64 = StringBuilder()
        var offset = 1 // tail -c +1 is 1-based
        while (offset <= total) {
            val chunk = runShell(
                arrayOf("sh", "-c", "tail -c +$offset $tmp | head -c $CHUNK_BYTES | base64"),
                10_000L,
            )
            if (chunk.exitCode != 0) return null
            b64.append(chunk.stdout.filter { !it.isWhitespace() })
            offset += CHUNK_BYTES
        }
        return runCatching { Base64.getDecoder().decode(b64.toString()) }.getOrNull()
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
    }

    private fun evictLeastRecentlyUsed(): VirtualScreen? {
        if (screens.size < MAX_SCREENS) return null
        val victim = screens.values.minByOrNull { it.lastUsedAtMs } ?: return null
        screens.remove(victim.displayId)
        victim.handle?.release()
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
    }

    /** back / home / enter mapped to the universally-safe numeric keycodes. */
    private val KEYCODES = mapOf("back" to 4, "home" to 3, "enter" to 66)
}
