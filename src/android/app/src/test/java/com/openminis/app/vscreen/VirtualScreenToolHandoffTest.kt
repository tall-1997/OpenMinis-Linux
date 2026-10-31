package com.openminis.app.vscreen

import com.openminis.app.offload.ShizukuManager
import com.openminis.app.tools.ToolErrorCode
import com.openminis.app.tools.VirtualScreenTool
import com.openminis.app.vscreen.VirtualScreenManager.CommandOutcome
import com.openminis.app.vscreen.VirtualScreenManager.DisplayHandle
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-android-vscreen2] JVM tests for the tool surface's hand_to_user action
 * and the handoff annotations on other results: argument validation (kind
 * enum, missing display_id), the no-context honest failure, the happy-path
 * result shape through an overridden controller, and ACTION registration
 * completeness (every action in the schema is dispatchable and vice versa).
 */
class VirtualScreenToolHandoffTest {

    private class ScriptedShell : VirtualScreenManager.ShellRunner {
        val calls = mutableListOf<Array<String>>()
        var respond: (Array<String>) -> ShizukuManager.ProcessResult = { ShizukuManager.ProcessResult(1, "", "refused") }

        override fun run(argv: Array<String>, timeoutMs: Long): ShizukuManager.ProcessResult {
            calls.add(argv)
            return respond(argv)
        }
    }

    private lateinit var shell: ScriptedShell

    @Before
    fun reset() {
        shell = ScriptedShell()
        VirtualScreenManager.resetForTest(
            factory = VirtualScreenManager.DisplayHandleFactory { _, _, _, _ ->
                object : DisplayHandle {
                    override val displayId: Int = 2
                    override fun acquireLatestFrame(): android.media.Image? = null
                    override fun release() {}
                }
            },
            shell = shell,
            guard = { null },
        )
        VirtualScreenManager.create(0, 0, 0, "seed", null)
    }

    private fun run(args: String): com.openminis.app.tools.ToolExecutionResult =
        VirtualScreenTool.execute(args, "session-1", context = null)

    private fun waitingController(): HandoffController {
        val prefs = MemoryPrefs()
        HandoffStore(prefs).put(
            HandoffRecord(
                displayId = 2,
                taskId = 42,
                packageName = "com.agent.app",
                topActivity = "",
                kind = "login",
                reason = "sign in",
                state = HandoffStateMachine.State.WAITING,
                startedAtMs = 1L,
                lastChangedAtMs = 1L,
            ),
        )
        return controller(prefs)
    }

    private fun freshController(): HandoffController = controller(MemoryPrefs())

    private fun controller(prefs: VscreenPrefs): HandoffController = HandoffController(
        store = HandoffStore(prefs),
        mover = TaskMover({ a, t -> shell.run(a, t) }, service = { null }),
        muter = AudioMuter({ a, t -> shell.run(a, t) }, MemoryPrefs()),
        notifier = { true },
    )

    // ── argument validation ───────────────────────────────────────────────

    @Test
    fun `hand_to_user rejects an unknown kind with the valid list`() {
        val r = run("""{"action":"hand_to_user","display_id":2,"kind":"root_my_phone","tool_title":"t"}""")
        assertFalse(r.success)
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, r.errorCode)
        assertTrue(r.output.contains("login"))
        assertTrue(r.output.contains("secure_page"))
        // Nothing moved: no move command was ever attempted.
        assertTrue(shell.calls.isEmpty())
    }

    @Test
    fun `hand_to_user requires display_id and defaults kind to other`() {
        val r = run("""{"action":"hand_to_user","kind":"login","tool_title":"t"}""")
        assertFalse(r.success)
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, r.errorCode)
        assertTrue(r.output.contains("display_id"))
    }

    @Test
    fun `hand_to_user without an app context fails honestly`() {
        val r = run("""{"action":"hand_to_user","display_id":2,"tool_title":"t"}""")
        assertFalse(r.success)
        assertEquals(ToolErrorCode.EXECUTION_FAILED, r.errorCode)
        assertTrue(r.output.contains("no app context"))
    }

    @Test
    fun `hand_to_user on an unknown display is not found`() {
        VirtualScreenManager.resetForTest(
            factory = null,
            shell = shell,
            guard = { null },
            controller = waitingController(),
        )
        val r = run("""{"action":"hand_to_user","display_id":777,"kind":"login","tool_title":"t"}""")
        assertFalse(r.success)
        assertEquals(ToolErrorCode.NOT_FOUND, r.errorCode)
    }

    // ── happy path through an overridden controller ───────────────────────

    @Test
    fun `hand_to_user moves the top task and reports the waiting state`() {
        VirtualScreenManager.resetForTest(
            factory = VirtualScreenManager.DisplayHandleFactory { _, _, _, _ ->
                object : DisplayHandle {
                    override val displayId: Int = 2
                    override fun acquireLatestFrame(): android.media.Image? = null
                    override fun release() {}
                }
            },
            shell = shell,
            guard = { null },
            controller = freshController(),
        )
        VirtualScreenManager.create(0, 0, 0, "seed", null)
        // Task listing: task 42 starts on display 2 and tracks the move so
        // the move candidate verifies (the ROM behaves the same way).
        var task42Display = 2
        shell.respond = { argv ->
            val joined = argv.joinToString(" ")
            when {
                joined.startsWith("am stack list") || joined.startsWith("cmd activity stack list") ||
                    joined.startsWith("dumpsys") ->
                    ShizukuManager.ProcessResult(
                        0,
                        "displayId=$task42Display\n*TaskRecord{abc #42 A=com.agent.app U=0 sz=1}",
                        "",
                    )
                joined.startsWith("appops get") -> ShizukuManager.ProcessResult(0, "PLAY_AUDIO: allow", "")
                joined.startsWith("appops set") -> ShizukuManager.ProcessResult(0, "", "")
                joined.startsWith("am stack move-task") -> {
                    task42Display = joined.split(" ").last().toIntOrNull() ?: task42Display
                    ShizukuManager.ProcessResult(0, "", "")
                }
                else -> ShizukuManager.ProcessResult(1, "", "unexpected: $joined")
            }
        }
        val r = run(
            """{"action":"hand_to_user","display_id":2,"kind":"payment","reason":"pay the order","tool_title":"t"}""",
        )
        assertTrue(r.success)
        val json = JSONObject(r.output)
        assertEquals("WAITING", json.getString("handoff_state"))
        assertEquals(42, json.getInt("task_id"))
        assertEquals("com.agent.app", json.getString("package"))
        assertTrue(json.getBoolean("notification_posted"))
        assertTrue(json.getString("note").contains("WAITING"))
        // The move actually went to the main display (0).
        assertTrue(
            shell.calls.any {
                it.joinToString(" ") == "am stack move-task 42 0"
            },
        )
    }

    @Test
    fun `hand_to_user reports task-move unavailability as unsupported`() {
        VirtualScreenManager.resetForTest(
            factory = VirtualScreenManager.DisplayHandleFactory { _, _, _, _ ->
                object : DisplayHandle {
                    override val displayId: Int = 2
                    override fun acquireLatestFrame(): android.media.Image? = null
                    override fun release() {}
                }
            },
            shell = shell,
            guard = { null },
            controller = freshController(),
        )
        VirtualScreenManager.create(0, 0, 0, "seed", null)
        // Listing fails everywhere → the controller cannot even see tasks.
        val r = run("""{"action":"hand_to_user","display_id":2,"kind":"login","tool_title":"t"}""")
        assertFalse(r.success)
        assertEquals(ToolErrorCode.UNSUPPORTED, r.errorCode)
        assertTrue(r.output.contains("task move unavailable on this ROM"))
    }

    @Test
    fun `list results annotate the waiting handoff and idle release`() {
        VirtualScreenManager.resetForTest(
            factory = VirtualScreenManager.DisplayHandleFactory { _, _, _, _ ->
                object : DisplayHandle {
                    override val displayId: Int = 2
                    override fun acquireLatestFrame(): android.media.Image? = null
                    override fun release() {}
                }
            },
            shell = shell,
            guard = { null },
            controller = waitingController(),
        )
        VirtualScreenManager.create(0, 0, 0, "seed", null)
        val r = run("""{"action":"list","tool_title":"t"}""")
        assertTrue(r.success)
        val json = JSONObject(r.output)
        val display = json.getJSONArray("displays").getJSONObject(0)
        assertTrue(display.getString("handoff").contains("WAITING"))
        assertTrue(display.getString("idle_release_note").contains("10"))
    }

    @Test
    fun `destroy results report the restored sound`() {
        val muter = AudioMuter({ a, t -> shell.run(a, t) }, MemoryPrefs())
        VirtualScreenManager.resetForTest(
            factory = VirtualScreenManager.DisplayHandleFactory { _, _, _, _ ->
                object : DisplayHandle {
                    override val displayId: Int = 2
                    override fun acquireLatestFrame(): android.media.Image? = null
                    override fun release() {}
                }
            },
            shell = shell,
            guard = { null },
            muter = muter,
        )
        VirtualScreenManager.create(0, 0, 0, "seed", null)
        shell.respond = { argv ->
            val joined = argv.joinToString(" ")
            when {
                joined.startsWith("appops get") -> ShizukuManager.ProcessResult(0, "PLAY_AUDIO: allow", "")
                else -> ShizukuManager.ProcessResult(0, "", "")
            }
        }
        muter.mute(2, "com.agent.app")
        val r = run("""{"action":"destroy","display_id":2,"tool_title":"t"}""")
        assertTrue(r.success)
        val json = JSONObject(r.output)
        assertTrue(json.getBoolean("destroyed"))
        assertEquals(1, json.getJSONArray("sound_restored").length())
        assertEquals("com.agent.app", json.getJSONArray("sound_restored").get(0))
    }

    // ── action registration completeness ──────────────────────────────────

    @Test
    fun `every action in the schema dispatches and the kind enum matches the machine`() {
        val def = VirtualScreenTool.definition()
        val actionEnum = def.parameters.getValue("action").enumValues.orEmpty()
        assertTrue("hand_to_user must be in the action enum", "hand_to_user" in actionEnum)
        // Each schema action must route past the unknown-action guard.
        for (action in actionEnum) {
            val r = run("""{"action":"$action","display_id":2,"tool_title":"t"}""")
            assertFalse("action $action must be dispatchable", r.output.contains("unknown action"))
        }
        // And the kind enum in the schema equals the state machine's KINDS.
        assertEquals(HandoffStateMachine.KINDS, def.parameters.getValue("kind").enumValues.orEmpty())
        // Arguments outside the enum are refused (spot check on kind).
        val r = run("""{"action":"hand_to_user","display_id":2,"kind":"weird","tool_title":"t"}""")
        assertEquals(ToolErrorCode.INVALID_ARGUMENTS, r.errorCode)
    }

    @Test
    fun `successful results carry the handoff annotation when a handoff is waiting`() {
        VirtualScreenManager.resetForTest(
            factory = VirtualScreenManager.DisplayHandleFactory { _, _, _, _ ->
                object : DisplayHandle {
                    override val displayId: Int = 2
                    override fun acquireLatestFrame(): android.media.Image? = null
                    override fun release() {}
                }
            },
            shell = shell,
            guard = { null },
            controller = waitingController(),
        )
        VirtualScreenManager.create(0, 0, 0, "seed", null)
        shell.respond = {
            ShizukuManager.ProcessResult(0, "usage: input [-d DISPLAY_ID] command", "")
        }
        // A tap is an Ok result → the handoff annotation must ride along.
        val r = run("""{"action":"tap","display_id":2,"x":5,"y":5,"tool_title":"t"}""")
        assertTrue(r.success)
        val json = JSONObject(r.output)
        assertTrue(json.getString("handoff").contains("WAITING"))
    }

    @Test
    fun `unknown actions still list hand_to_user in the choices`() {
        val r = run("""{"action":"wave","tool_title":"t"}""")
        assertFalse(r.success)
        assertTrue(r.output.contains("hand_to_user"))
        assertNotEquals(ToolErrorCode.UNKNOWN_TOOL, r.errorCode)
    }
}
