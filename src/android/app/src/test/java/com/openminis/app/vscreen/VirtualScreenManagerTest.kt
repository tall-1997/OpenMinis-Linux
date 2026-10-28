package com.openminis.app.vscreen

import com.openminis.app.offload.ShizukuManager
import com.openminis.app.vscreen.VirtualScreenManager.CommandOutcome
import com.openminis.app.vscreen.VirtualScreenManager.CreateOutcome
import com.openminis.app.vscreen.VirtualScreenManager.DisplayHandle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Base64

/**
 * [T-android-vscreen] JVM tests for the virtual screen logic that runs
 * WITHOUT a device: the LRU cap, the `input` display-form probe and its
 * degradation, the `cmd package resolve-activity` component parser, the
 * layered screenshot degradation and the Shizuku guard — all exercised
 * through the [VirtualScreenManager.DisplayHandle] / ShellRunner / guard
 * seams (ShizukuBackendStateTest style).
 *
 * NOT covered here (needs a real device / Android runtime): the real
 * DisplayManager.createVirtualDisplay behaviour, the ImageReader frame
 * capture, and whether a ROM actually honors `am start --display` /
 * `input -d` / `screencap -d` on an app-owned display.
 */
class VirtualScreenManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var shell: FakeShell

    private class FakeDisplay(override val displayId: Int) : DisplayHandle {
        var released = false
        var attachCalls = 0
        var detachCalls = 0
        override fun attach(surface: android.view.Surface): Boolean {
            attachCalls++
            return true
        }

        override fun detach() {
            detachCalls++
        }

        override fun release() {
            released = true
        }
    }

    /** Factory seam: hands out incrementing ids starting at 2 (logical-id style). */
    private class FakeFactory {
        val displays = mutableListOf<FakeDisplay>()
        private var next = 2
        fun create(): FakeDisplay {
            val d = FakeDisplay(next++)
            displays.add(d)
            return d
        }
    }

    private class FakeShell : VirtualScreenManager.ShellRunner {
        val calls = mutableListOf<Array<String>>()
        var respond: (Array<String>) -> ShizukuManager.ProcessResult = {
            ShizukuManager.ProcessResult(0, "", "")
        }

        override fun run(argv: Array<String>, timeoutMs: Long): ShizukuManager.ProcessResult {
            calls.add(argv)
            return respond(argv)
        }

        fun argvOf(bin: String): List<List<String>> =
            calls.filter { it.firstOrNull() == bin }.map { it.toList() }
    }

    @Before
    fun reset() {
        shell = FakeShell()
        VirtualScreenManager.resetForTest(factory = null, shell = shell, guard = { null })
    }

    // ── create / LRU ──────────────────────────────────────────────────────

    @Test
    fun `create uses default dimensions and registers the display`() {
        val factory = FakeFactory()
        VirtualScreenManager.resetForTest(VirtualScreenManager.DisplayHandleFactory { _, _, _, _ -> factory.create() }, shell, { null })
        val outcome = VirtualScreenManager.create(0, 0, 0, null, null)
        val s = (outcome as CreateOutcome.Created).screen
        assertEquals(1080, s.width)
        assertEquals(1920, s.height)
        assertEquals(320, s.dpi)
        assertEquals(1, VirtualScreenManager.list().size)
        assertEquals(listOf("minis-virtual-screen"), VirtualScreenManager.list().map { it.label })
    }

    @Test
    fun `create clamps out-of-range dimensions and dpi`() {
        VirtualScreenManager.resetForTest(VirtualScreenManager.DisplayHandleFactory { _, _, _, _ -> FakeDisplay(2) }, shell, { null })
        val s = (VirtualScreenManager.create(9999, 100, 2000, null, null) as CreateOutcome.Created).screen
        assertEquals(4096, s.width)
        assertEquals(320, s.height)
        assertEquals(640, s.dpi)
    }

    @Test
    fun `create evicts the least recently used display at the cap and releases it`() {
        val factory = FakeFactory()
        VirtualScreenManager.resetForTest(VirtualScreenManager.DisplayHandleFactory { _, _, _, _ -> factory.create() }, shell, { null })
        val a = (VirtualScreenManager.create(0, 0, 0, null, null) as CreateOutcome.Created).screen
        val b = (VirtualScreenManager.create(0, 0, 0, null, null) as CreateOutcome.Created).screen
        // Deterministic LRU: make B strictly the oldest by lastUsedAtMs (A keeps
        // its real timestamps; using a sleep here would be same-ms flaky).
        b.lastUsedAtMs = 1L
        val outcome = VirtualScreenManager.create(0, 0, 0, null, null) as CreateOutcome.Created
        assertEquals(b.displayId, outcome.evicted!!.displayId)
        assertTrue(factory.displays.first { it.displayId == b.displayId }.released)
        assertFalse(factory.displays.first { it.displayId == a.displayId }.released)
        assertEquals(2, VirtualScreenManager.list().size)
    }

    @Test
    fun `destroy releases the handle and forgets the display`() {
        VirtualScreenManager.resetForTest(VirtualScreenManager.DisplayHandleFactory { _, _, _, _ -> FakeDisplay(2) }, shell, { null })
        VirtualScreenManager.create(0, 0, 0, null, null)
        val removed = VirtualScreenManager.destroy(2)
        assertNotNull(removed)
        assertTrue(VirtualScreenManager.list().isEmpty())
        assertNull(VirtualScreenManager.destroy(2))
    }

    // ── launch ────────────────────────────────────────────────────────────

    @Test
    fun `launch resolves the launcher activity and starts on the display`() {
        seedDisplay(2)
        shell.respond = { argv ->
            when (argv[0]) {
                "cmd" -> ShizukuManager.ProcessResult(0, "priority=0 Intent{act=MAIN}\n  com.android.settings/.Settings", "")
                "am" -> ShizukuManager.ProcessResult(0, "Status: ok", "")
                else -> ShizukuManager.ProcessResult(1, "", "unexpected")
            }
        }
        val r = VirtualScreenManager.launch(2, "com.android.settings", null) as CommandOutcome.Ok
        assertEquals(
            listOf("am", "start", "-W", "--display", "2", "-n", "com.android.settings/.Settings"),
            shell.argvOf("am").single(),
        )
        assertTrue(r.detail.contains("com.android.settings/.Settings"))
    }

    @Test
    fun `launch with an explicit activity skips the resolve round trip`() {
        seedDisplay(2)
        shell.respond = { argv ->
            if (argv[0] == "am") ShizukuManager.ProcessResult(0, "Status: ok", "")
            else ShizukuManager.ProcessResult(1, "", "unexpected")
        }
        VirtualScreenManager.launch(2, "com.example", "com.example/.Bar") as CommandOutcome.Ok
        assertEquals(listOf("am", "start", "-W", "--display", "2", "-n", "com.example/.Bar"), shell.argvOf("am").single())
        assertTrue(shell.argvOf("cmd").isEmpty())
    }

    @Test
    fun `launch reports honestly when no activity is resolvable`() {
        seedDisplay(2)
        shell.respond = { ShizukuManager.ProcessResult(1, "", "no activity found") }
        val r = VirtualScreenManager.launch(2, "com.example", null) as CommandOutcome.Failed
        assertTrue(r.message.contains("Pass activity explicitly"))
        assertTrue(shell.argvOf("am").isEmpty())
    }

    @Test
    fun `launch surfaces the ROM's am output verbatim on failure`() {
        seedDisplay(2)
        shell.respond = { argv ->
            if (argv[0] == "cmd") ShizukuManager.ProcessResult(0, "com.example/.Main", "")
            else ShizukuManager.ProcessResult(0, "Status: error", "SecurityException")
        }
        val r = VirtualScreenManager.launch(2, "com.example", null) as CommandOutcome.Failed
        assertTrue(r.message.contains("SecurityException"))
    }

    // ── drive: input display-form probe ───────────────────────────────────

    @Test
    fun `input probe picks the -d form from the usage output`() {
        seedDisplay(2)
        shell.respond = { argv ->
            if (argv[0] == "input" && argv.size == 1) {
                ShizukuManager.ProcessResult(1, "", "usage: input [-source SOURCE] [-d DISPLAY_ID] command [<arg>...]")
            } else {
                ShizukuManager.ProcessResult(0, "", "")
            }
        }
        VirtualScreenManager.tap(2, 10, 10) as CommandOutcome.Ok
        assertEquals(listOf("input", "-d", "2", "tap", "10", "10"), shell.argvOf("input").last())
    }

    @Test
    fun `input probe picks the --display form when that is what the ROM prints`() {
        seedDisplay(2)
        shell.respond = { argv ->
            if (argv[0] == "input" && argv.size == 1) {
                ShizukuManager.ProcessResult(1, "", "usage: input [--display ID] command")
            } else {
                ShizukuManager.ProcessResult(0, "", "")
            }
        }
        VirtualScreenManager.tap(2, 10, 10) as CommandOutcome.Ok
        assertEquals(listOf("input", "--display", "2", "tap", "10", "10"), shell.argvOf("input").last())
    }

    @Test
    fun `input probe reports NONE when the ROM has no display targeting`() {
        seedDisplay(2)
        shell.respond = { argv ->
            if (argv[0] == "input" && argv.size == 1) {
                ShizukuManager.ProcessResult(1, "", "usage: input [-source SOURCE] command [<arg>...]")
            } else {
                ShizukuManager.ProcessResult(0, "", "")
            }
        }
        val r = VirtualScreenManager.tap(2, 10, 10) as CommandOutcome.NotSupported
        assertTrue(r.message.contains("no display targeting"))
        assertTrue(r.message.contains("ui_action"))
        // No injection was attempted — the tap must never land on the physical screen.
        assertTrue(shell.argvOf("input").all { it.size == 1 })
    }

    @Test
    fun `input probe falls back to a probe tap when the ROM prints no usage`() {
        seedDisplay(2)
        shell.respond = { argv ->
            when {
                argv[0] == "input" && argv.size == 1 -> ShizukuManager.ProcessResult(1, "", "")
                argv.take(3) == listOf("input", "-d", "2") -> ShizukuManager.ProcessResult(0, "", "")
                else -> ShizukuManager.ProcessResult(1, "", "bad args")
            }
        }
        VirtualScreenManager.tap(2, 10, 10) as CommandOutcome.Ok
        // The probe ran the usage call (1) plus one probe tap (1), then the real tap (1).
        assertEquals(3, shell.argvOf("input").size)
        // The form is cached: a second tap adds exactly one more call.
        VirtualScreenManager.tap(2, 20, 20) as CommandOutcome.Ok
        assertEquals(4, shell.argvOf("input").size)
    }

    @Test
    fun `key maps back home enter to numeric keycodes and passes numeric through`() {
        seedDisplay(2)
        shell.respond = { ShizukuManager.ProcessResult(0, "", "") }
        VirtualScreenManager.key(2, "back") as CommandOutcome.Ok
        VirtualScreenManager.key(2, "home") as CommandOutcome.Ok
        VirtualScreenManager.key(2, "enter") as CommandOutcome.Ok
        VirtualScreenManager.key(2, "61") as CommandOutcome.Ok
        assertEquals(
            listOf(
                listOf("input", "-d", "2", "keyevent", "4"),
                listOf("input", "-d", "2", "keyevent", "3"),
                listOf("input", "-d", "2", "keyevent", "66"),
                listOf("input", "-d", "2", "keyevent", "61"),
            ),
            shell.argvOf("input").filter { it.contains("keyevent") },
        )
    }

    @Test
    fun `key rejects names it cannot map and text escapes spaces`() {
        seedDisplay(2)
        shell.respond = { ShizukuManager.ProcessResult(0, "", "") }
        assertTrue(VirtualScreenManager.key(2, "menu") is CommandOutcome.Failed)
        VirtualScreenManager.text(2, "hello world") as CommandOutcome.Ok
        assertEquals(
            listOf("input", "-d", "2", "text", "hello%sworld"),
            shell.argvOf("input").filter { it.contains("text") }.single(),
        )
    }

    @Test
    fun `swipe passes duration and out-of-range coordinates are refused`() {
        seedDisplay(2)
        shell.respond = { ShizukuManager.ProcessResult(0, "", "") }
        VirtualScreenManager.swipe(2, 0, 0, 1080, 1920, 300) as CommandOutcome.Ok
        assertEquals(
            listOf("input", "-d", "2", "swipe", "0", "0", "1080", "1920", "300"),
            shell.argvOf("input").filter { it.contains("swipe") }.single(),
        )
        val r = VirtualScreenManager.tap(2, 9999, 10) as CommandOutcome.Failed
        assertTrue(r.message.contains("outside virtual screen"))
    }

    // ── screenshot: layered degradation ───────────────────────────────────

    @Test
    fun `screenshot reports notFound for an unknown display`() {
        assertTrue(VirtualScreenManager.screenshot(999, tmp.newFolder()) is CommandOutcome.NotFound)
    }

    @Test
    fun `screenshot fails fast with no app context`() {
        seedDisplay(2)
        assertTrue(VirtualScreenManager.screenshot(2, null) is CommandOutcome.Failed)
    }

    @Test
    fun `screenshot layer 2 - screencap plus cp writes the png and returns the guest path`() {
        seedDisplay(2)
        val outDir = tmp.newFolder()
        val pngBytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 1, 2, 3, 4)
        shell.respond = { argv ->
            val joined = argv.joinToString(" ")
            when {
                argv[0] == "screencap" -> ShizukuManager.ProcessResult(0, "screencap [-hp] [-d display-id] [FILENAME]", "")
                joined.startsWith("sh -c screencap -d") -> ShizukuManager.ProcessResult(0, "", "")
                joined.startsWith("sh -c cp ") -> {
                    val dst = Regex("'(.+)'").find(joined)!!.groupValues[1]
                    File(dst).writeBytes(pngBytes)
                    ShizukuManager.ProcessResult(0, "", "")
                }
                else -> ShizukuManager.ProcessResult(0, "", "")
            }
        }
        val r = VirtualScreenManager.screenshot(2, outDir) as CommandOutcome.Ok
        val path = r.guestPath!!
        assertTrue(path.startsWith("/var/minis/offloads/vscreen_2_"))
        assertTrue(path.endsWith(".png"))
        assertEquals(pngBytes.toList(), File(outDir, path.removePrefix("/var/minis/offloads/")).readBytes().toList())
    }

    @Test
    fun `screenshot layer 3 - chunked base64 fallback concatenates the chunks`() {
        seedDisplay(2)
        val outDir = tmp.newFolder()
        val pngBytes = ByteArray(60_000) { (it % 251).toByte() }
        shell.respond = { argv ->
            val joined = argv.joinToString(" ")
            when {
                argv[0] == "screencap" -> ShizukuManager.ProcessResult(0, "screencap [-hp] [-d display-id] [FILENAME]", "")
                joined.startsWith("sh -c screencap -d") -> ShizukuManager.ProcessResult(0, "", "")
                joined.startsWith("sh -c cp ") -> ShizukuManager.ProcessResult(1, "", "Permission denied")
                joined.startsWith("sh -c wc -c") -> ShizukuManager.ProcessResult(0, "${pngBytes.size}\n", "")
                joined.startsWith("sh -c tail -c +") -> {
                    val offset = Regex("tail -c \\+(\\d+)").find(joined)!!.groupValues[1].toInt()
                    val slice = pngBytes.copyOfRange(offset - 1, minOf(offset - 1 + 48_000, pngBytes.size))
                    ShizukuManager.ProcessResult(0, Base64.getEncoder().encodeToString(slice), "")
                }
                else -> ShizukuManager.ProcessResult(0, "", "")
            }
        }
        val r = VirtualScreenManager.screenshot(2, outDir) as CommandOutcome.Ok
        assertTrue(r.detail.contains("chunked base64"))
        assertEquals(
            pngBytes.toList(),
            File(outDir, r.guestPath!!.removePrefix("/var/minis/offloads/")).readBytes().toList(),
        )
        // Two chunks for 60KB at 48KB per chunk.
        assertEquals(2, shell.argvOf("sh").count { it.joinToString(" ").startsWith("sh -c tail -c +") })
    }

    @Test
    fun `screenshot reports honestly when every layer fails`() {
        seedDisplay(2)
        val outDir = tmp.newFolder()
        shell.respond = { argv ->
            val joined = argv.joinToString(" ")
            when {
                argv[0] == "screencap" -> ShizukuManager.ProcessResult(0, "screencap [-hp] [-d display-id] [FILENAME]", "")
                joined.startsWith("sh -c screencap -d") -> ShizukuManager.ProcessResult(0, "", "")
                else -> ShizukuManager.ProcessResult(1, "", "Permission denied")
            }
        }
        val r = VirtualScreenManager.screenshot(2, outDir) as CommandOutcome.Failed
        assertTrue(r.message.contains("no app-readable retrieval path worked"))
    }

    @Test
    fun `screencap probe caches and reports unsupported without -d`() {
        seedDisplay(2)
        var probeCalls = 0
        shell.respond = { argv ->
            if (argv[0] == "screencap") {
                probeCalls++
                ShizukuManager.ProcessResult(0, "screencap [-hp] [FILENAME]", "")
            } else {
                ShizukuManager.ProcessResult(0, "", "")
            }
        }
        // No -d in the usage text and the capture never ran for the probe — but
        // the screenshot path returns NotSupported BEFORE attempting screencap.
        assertTrue(VirtualScreenManager.screenshot(2, tmp.newFolder()) is CommandOutcome.NotSupported)
        assertEquals(1, probeCalls)
    }

    // ── Shizuku guard ─────────────────────────────────────────────────────

    @Test
    fun `drive and screenshot report ShizukuUnavailable when the guard refuses`() {
        seedDisplay(2, guard = { "Minis is not authorized for Shizuku." })
        assertTrue(VirtualScreenManager.launch(2, "com.example", null) is CommandOutcome.ShizukuUnavailable)
        assertTrue(VirtualScreenManager.tap(2, 1, 1) is CommandOutcome.ShizukuUnavailable)
        assertTrue(VirtualScreenManager.screenshot(2, tmp.newFolder()) is CommandOutcome.ShizukuUnavailable)
    }

    @Test
    fun `create list destroy work without Shizuku`() {
        val factory = FakeFactory()
        VirtualScreenManager.resetForTest(VirtualScreenManager.DisplayHandleFactory { _, _, _, _ -> factory.create() }, shell, { "no shizuku at all" })
        val s = (VirtualScreenManager.create(0, 0, 0, null, null) as CreateOutcome.Created).screen
        assertEquals(1, VirtualScreenManager.list().size)
        assertNotNull(VirtualScreenManager.destroy(s.displayId))
    }

    // ── component parser ──────────────────────────────────────────────────

    @Test
    fun `resolveComponentFromOutput takes the last usable component line`() {
        assertEquals(
            "com.android.settings/.Settings",
            VirtualScreenManager.resolveComponentFromOutput(
                "priority=0 preferredOrder=0 match=0x108000\n" +
                    "  com.android.settings/.Settings\n",
            ),
        )
        assertEquals("com.foo/Main", VirtualScreenManager.resolveComponentFromOutput("com.foo/Main"))
        assertNull(VirtualScreenManager.resolveComponentFromOutput(""))
        assertNull(VirtualScreenManager.resolveComponentFromOutput("no component here"))
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun seedDisplay(id: Int, guard: (() -> String?)? = { null }): VirtualScreenManager.VirtualScreen {
        VirtualScreenManager.resetForTest(VirtualScreenManager.DisplayHandleFactory { _, _, _, _ -> FakeDisplay(id) }, shell, guard)
        return (VirtualScreenManager.create(0, 0, 0, "seed", null) as CreateOutcome.Created).screen
    }
}
