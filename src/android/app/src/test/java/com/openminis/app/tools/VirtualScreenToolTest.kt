package com.openminis.app.tools

import com.openminis.app.offload.ShizukuManager
import com.openminis.app.vscreen.VirtualScreenManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-android-vscreen] JVM tests for the tool surface: action routing, arg
 * parsing (plain-integer coordinates, numeric-string display_id), the
 * honest-status error mapping (Shizuku state / missing display / ROM API
 * support) and the result JSON shape — driven through
 * [VirtualScreenManager.resetForTest] with fake factory / shell / guard
 * seams.
 *
 * The screenshot's host-side write (SessionWorkspace offloads dir) and the
 * real device behavior live behind the manager seams and are covered in
 * [com.openminis.app.vscreen.VirtualScreenManagerTest]; the full
 * read_image path needs an Android context and is not JVM-testable.
 */
class VirtualScreenToolTest {

    private class FakeShell : VirtualScreenManager.ShellRunner {
        val calls = mutableListOf<Array<String>>()
        var respond: (Array<String>) -> ShizukuManager.ProcessResult = {
            ShizukuManager.ProcessResult(0, "", "")
        }

        override fun run(argv: Array<String>, timeoutMs: Long): ShizukuManager.ProcessResult {
            calls.add(argv)
            return respond(argv)
        }
    }

    @Before
    fun reset() {
        VirtualScreenManager.resetForTest(
            factory = VirtualScreenManager.DisplayHandleFactory { _, _, _, _ -> object : VirtualScreenManager.DisplayHandle {
                override val displayId: Int = 2
                override fun acquireLatestFrame(): android.media.Image? = null
                override fun release() {}
            } },
            shell = FakeShell(),
            guard = { null },
        )
    }

    private fun run(args: String): ToolExecutionResult =
        VirtualScreenTool.execute(args, "session-1", context = null)

    // ── routing and parsing ───────────────────────────────────────────────

    @Test
    fun `invalid json is refused as invalid arguments`() {
        val r = run("{not json")
        assertFalse(r.success)
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, r.errorCode)
    }

    @Test
    fun `unknown action is refused with the action list`() {
        val r = run("""{"action":"wave","tool_title":"t"}""")
        assertFalse(r.success)
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, r.errorCode)
        assertTrue(r.output.contains("create"))
        assertTrue(r.output.contains("destroy"))
    }

    @Test
    fun `numeric-string display_id parses and reaches the manager`() {
        // No display 999 exists: the parse worked (NOT_FOUND), not a missing-arg error.
        val r = run("""{"action":"destroy","display_id":"999","tool_title":"t"}""")
        assertFalse(r.success)
        assertEquals(ToolErrorCode.NOT_FOUND, r.errorCode)
    }

    @Test
    fun `missing display_id is refused before any shell call`() {
        val r = run("""{"action":"screenshot","tool_title":"t"}""")
        assertFalse(r.success)
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, r.errorCode)
    }

    @Test
    fun `non-numeric tap coordinates are refused as invalid arguments`() {
        seedDisplay(2)
        val r = run("""{"action":"tap","display_id":2,"x":"0 0; touch /tmp/x","y":5,"tool_title":"t"}""")
        assertFalse(r.success)
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, r.errorCode)
    }

    @Test
    fun `create without a factory or context reports unsupported`() {
        VirtualScreenManager.resetForTest(factory = null, shell = FakeShell(), guard = { null })
        val r = run("""{"action":"create","tool_title":"t"}""")
        assertFalse(r.success)
        assertEquals(ToolErrorCode.UNSUPPORTED, r.errorCode)
    }

    // ── honest status mapping ─────────────────────────────────────────────

    @Test
    fun `shizuku-unavailable maps to permission denied with a grant hint`() {
        seedDisplay(2, guard = { "Minis is not authorized for Shizuku." })
        val r = run("""{"action":"tap","display_id":2,"x":1,"y":1,"tool_title":"t"}""")
        assertFalse(r.success)
        assertEquals(ToolErrorCode.PERMISSION_DENIED, r.errorCode)
        assertTrue(r.recoveryHint!!.contains("Shizuku"))
    }

    @Test
    fun `unknown display maps to not found with a list hint`() {
        val r = run("""{"action":"screenshot","display_id":42,"tool_title":"t"}""")
        assertFalse(r.success)
        assertEquals(ToolErrorCode.NOT_FOUND, r.errorCode)
        assertTrue(r.recoveryHint!!.contains("action=list"))
    }

    @Test
    fun `unsupported input rom maps to unsupported with the accessibility fallback`() {
        val shell = FakeShell()
        VirtualScreenManager.resetForTest(VirtualScreenManager.DisplayHandleFactory { _, _, _, _ -> seedDisplayHandle(2) }, shell, { null })
        VirtualScreenManager.create(0, 0, 0, "seed", null)
        shell.respond = { argv ->
            if (argv[0] == "input" && argv.size == 1) {
                ShizukuManager.ProcessResult(1, "", "usage: input [-source SOURCE] command [<arg>...]")
            } else {
                ShizukuManager.ProcessResult(0, "", "")
            }
        }
        val r = run("""{"action":"tap","display_id":2,"x":1,"y":1,"tool_title":"t"}""")
        assertFalse(r.success)
        assertEquals(ToolErrorCode.UNSUPPORTED, r.errorCode)
        assertTrue(r.output.contains("ui_action"))
    }

    @Test
    fun `execution failure carries the underlying command output`() {
        val shell = FakeShell()
        VirtualScreenManager.resetForTest(VirtualScreenManager.DisplayHandleFactory { _, _, _, _ -> seedDisplayHandle(2) }, shell, { null })
        VirtualScreenManager.create(0, 0, 0, "seed", null)
        shell.respond = { argv ->
            when {
                argv[0] == "input" && argv.size == 1 ->
                    ShizukuManager.ProcessResult(1, "", "usage: input [-d DISPLAY_ID] command")
                argv[0] == "input" -> ShizukuManager.ProcessResult(1, "", " injection failed: Bad display")
                else -> ShizukuManager.ProcessResult(0, "", "")
            }
        }
        val r = run("""{"action":"tap","display_id":2,"x":1,"y":1,"tool_title":"t"}""")
        assertFalse(r.success)
        assertEquals(ToolErrorCode.EXECUTION_FAILED, r.errorCode)
        assertTrue(r.output.contains("injection failed"))
    }

    @Test
    fun `screenshot with a valid display but no app context fails honestly`() {
        seedDisplay(2)
        val r = run("""{"action":"screenshot","display_id":2,"tool_title":"t"}""")
        assertFalse(r.success)
        assertEquals(ToolErrorCode.EXECUTION_FAILED, r.errorCode)
        assertTrue(r.output.contains("no app context"))
    }

    // ── result shapes ─────────────────────────────────────────────────────

    @Test
    fun `create returns display_id and the no-surface note`() {
        val r = run("""{"action":"create","tool_title":"t"}""")
        assertTrue(r.success)
        val json = org.json.JSONObject(r.output)
        assertEquals(2, json.getInt("display_id"))
        assertEquals(1080, json.getInt("width"))
        assertEquals(1920, json.getInt("height"))
        assertEquals(320, json.getInt("dpi"))
        assertTrue(json.getString("note").contains("renders nothing"))
    }

    @Test
    fun `list returns live displays with dimensions`() {
        run("""{"action":"create","tool_title":"t"}""")
        val r = run("""{"action":"list","tool_title":"t"}""")
        assertTrue(r.success)
        val json = org.json.JSONObject(r.output)
        assertEquals(1, json.getInt("count"))
        assertEquals(2, json.getJSONArray("displays").getJSONObject(0).getInt("display_id"))
    }

    @Test
    fun `destroy returns a destroyed json payload`() {
        run("""{"action":"create","tool_title":"t"}""")
        val r = run("""{"action":"destroy","display_id":2,"tool_title":"t"}""")
        assertTrue(r.success)
        val json = org.json.JSONObject(r.output)
        assertEquals(true, json.getBoolean("destroyed"))
    }

    @Test
    fun `key text and swipe reach the manager with display targeting`() {
        val shell = FakeShell()
        VirtualScreenManager.resetForTest(VirtualScreenManager.DisplayHandleFactory { _, _, _, _ -> seedDisplayHandle(2) }, shell, { null })
        VirtualScreenManager.create(0, 0, 0, "seed", null)
        shell.respond = { argv ->
            if (argv[0] == "input" && argv.size == 1) {
                ShizukuManager.ProcessResult(1, "", "usage: input [-d DISPLAY_ID] command")
            } else {
                ShizukuManager.ProcessResult(0, "", "")
            }
        }
        run("""{"action":"key","display_id":2,"key":"back","tool_title":"t"}""")
        run("""{"action":"text","display_id":2,"text":"hi there","tool_title":"t"}""")
        run("""{"action":"swipe","display_id":2,"x1":0,"y1":0,"x2":1080,"y2":1920,"duration_ms":250,"tool_title":"t"}""")
        val injected = shell.calls.filter { it.size > 1 }
        assertEquals(
            listOf(
                listOf("input", "-d", "2", "keyevent", "4"),
                listOf("input", "-d", "2", "text", "hi%sthere"),
                listOf("input", "-d", "2", "swipe", "0", "0", "1080", "1920", "250"),
            ),
            injected.map { it.toList() },
        )
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private fun seedDisplayHandle(id: Int): VirtualScreenManager.DisplayHandle =
        object : VirtualScreenManager.DisplayHandle {
            override val displayId: Int = id
            override fun acquireLatestFrame(): android.media.Image? = null
            override fun release() {}
        }

    private fun seedDisplay(id: Int, guard: (() -> String?)? = { null }) {
        VirtualScreenManager.resetForTest(VirtualScreenManager.DisplayHandleFactory { _, _, _, _ -> seedDisplayHandle(id) }, FakeShell(), guard)
        VirtualScreenManager.create(0, 0, 0, "seed", null)
    }
}
