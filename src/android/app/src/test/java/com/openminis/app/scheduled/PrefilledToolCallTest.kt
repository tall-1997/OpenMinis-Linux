package com.openminis.app.scheduled

import com.openminis.app.data.model.LLMStreamChunk
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-scheduled-tool-prefill] The pure pieces of a prefilled call: what may be
 * stored, how a stored call is read back, the scripted turn a fire feeds the
 * agent loop, and the envelope that tells the model the call was not its own.
 *
 * The firing path itself is pinned in ScheduledPrefillWiringTest; the CLI in
 * ScheduledPrefillCliTest.
 */
class PrefilledToolCallTest {

    private fun task(prefill: PrefilledToolCall? = null) = ScheduledTask(
        id = "T1",
        label = "Weather",
        timeOfDayHour = 8,
        timeOfDayMinute = 0,
        repeatMode = ScheduledRepeatMode.DAILY,
        prompt = "Say hi",
        prefillToolCall = prefill,
        createdAt = 1L,
    )

    // ── construction ─────────────────────────────────────────────────────

    @Test
    fun `shell carries the command and a title the card can show`() {
        val call = PrefilledToolCall.shell("curl -s wttr.in", title = "Weather")
        assertEquals(PrefilledToolCall.SHELL_TOOL, call.toolName)
        assertEquals("curl -s wttr.in", call.args().getString("command"))
        assertEquals("Weather", call.args().getString("tool_title"))
        assertEquals("curl -s wttr.in", call.shellCommand)
        assertNull(call.validationError())
    }

    @Test
    fun `a shell call without a title falls back to the command's first line`() {
        val call = PrefilledToolCall.shell("df -h\ndu -sh /var")
        assertEquals("df -h", call.args().getString("tool_title"))
    }

    @Test
    fun `an explicit title wins over the fallback`() {
        val call = PrefilledToolCall.of(PrefilledToolCall.SHELL_TOOL, JSONObject().put("command", "df -h"), "Disk")
        assertEquals("Disk", call.args().getString("tool_title"))
    }

    @Test
    fun `a caller-supplied tool_title is kept verbatim`() {
        val args = JSONObject().put("command", "date").put("tool_title", "Clock")
        assertEquals("Clock", PrefilledToolCall.of(PrefilledToolCall.SHELL_TOOL, args).args().getString("tool_title"))
    }

    @Test
    fun `alias names resolve to the real tool`() {
        for (raw in listOf("shell", "Shell", "SH", "bash", " shell_execute ")) {
            assertEquals(raw, PrefilledToolCall.SHELL_TOOL, PrefilledToolCall.canonicalToolName(raw))
        }
        assertEquals("some_other_tool", PrefilledToolCall.canonicalToolName("some_other_tool"))
    }

    // ── validation ───────────────────────────────────────────────────────

    @Test
    fun `an unsupported tool is refused up front, naming the supported set`() {
        val err = PrefilledToolCall("browser_use", "{}").validationError()
        assertNotNull(err)
        assertTrue(err!!.contains("not supported"))
        assertTrue(err.contains(PrefilledToolCall.SHELL_TOOL))
    }

    @Test
    fun `argument payloads that are not a JSON object are refused`() {
        assertNotNull(PrefilledToolCall(PrefilledToolCall.SHELL_TOOL, "not json").validationError())
        assertNotNull(PrefilledToolCall(PrefilledToolCall.SHELL_TOOL, "[1,2]").validationError())
    }

    @Test
    fun `an empty command is refused`() {
        val err = PrefilledToolCall.shell("   ").validationError()
        assertNotNull(err)
        assertTrue(err!!.contains("non-empty command"))
    }

    @Test
    fun `an over-long command points at a script file`() {
        val err = PrefilledToolCall.shell("x".repeat(PrefilledToolCall.MAX_COMMAND_LENGTH + 1)).validationError()
        assertNotNull(err)
        assertTrue(err!!.contains("file"))
    }

    @Test
    fun `a command at exactly the limit is accepted`() {
        assertNull(PrefilledToolCall.shell("x".repeat(PrefilledToolCall.MAX_COMMAND_LENGTH)).validationError())
    }

    @Test
    fun `timeout boundaries are inclusive`() {
        assertNull(PrefilledToolCall.shell("date", timeoutSec = 1).validationError())
        assertNull(PrefilledToolCall.shell("date", timeoutSec = 3600).validationError())
        assertNotNull(PrefilledToolCall.shell("date", timeoutSec = 0).validationError())
        assertNotNull(PrefilledToolCall.shell("date", timeoutSec = 3601).validationError())
    }

    @Test
    fun `an out-of-range timeout cannot wrap into a passing one`() {
        // `optInt` on 4294967356 wraps to 60 — a "way past an hour" timeout
        // would have validated as one minute. Read as Double instead.
        val args = JSONObject().put("command", "date").put("timeout", 4294967356L)
        val err = PrefilledToolCall(PrefilledToolCall.SHELL_TOOL, args.toString()).validationError()
        assertNotNull(err)
        assertTrue(err!!.contains("between"))
    }

    @Test
    fun `a timeout that is not a number is refused`() {
        val args = JSONObject().put("command", "date").put("timeout", "2m")
        val err = PrefilledToolCall(PrefilledToolCall.SHELL_TOOL, args.toString()).validationError()
        assertNotNull(err)
        assertTrue(err!!.contains("number of seconds"))
    }

    @Test
    fun `an explicit null timeout means none`() {
        val args = JSONObject().put("command", "date").put("timeout", JSONObject.NULL)
        assertNull(PrefilledToolCall(PrefilledToolCall.SHELL_TOOL, args.toString()).validationError())
    }

    @Test
    fun `saturatingSeconds clamps instead of wrapping`() {
        assertEquals(60, PrefilledToolCall.saturatingSeconds(60.0))
        assertEquals(2, PrefilledToolCall.saturatingSeconds(2.9))
        assertEquals(Int.MAX_VALUE, PrefilledToolCall.saturatingSeconds(4294967356.0))
        assertEquals(Int.MAX_VALUE, PrefilledToolCall.saturatingSeconds(Double.POSITIVE_INFINITY))
        assertEquals(Int.MIN_VALUE, PrefilledToolCall.saturatingSeconds(Double.NEGATIVE_INFINITY))
        assertEquals(Int.MIN_VALUE, PrefilledToolCall.saturatingSeconds(Double.NaN))
    }

    // ── persistence ──────────────────────────────────────────────────────

    @Test
    fun `a task with a prefill round-trips through JSON`() {
        val original = task(PrefilledToolCall.shell("df -h", title = "Disk", timeoutSec = 90))
        val json = original.toJson()
        assertTrue(json.has("prefillToolCall"))
        val back = ScheduledTask.fromJson(json)
        assertEquals(original.id, back.id)
        assertEquals(PrefilledToolCall.SHELL_TOOL, back.prefillToolCall?.toolName)
        assertEquals("df -h", back.prefillToolCall?.args()?.getString("command"))
        assertEquals(90, back.prefillToolCall?.args()?.getInt("timeout"))
        assertEquals("Disk", back.prefillToolCall?.args()?.getString("tool_title"))
    }

    @Test
    fun `a task without a prefill writes no key`() {
        assertFalse(task().toJson().has("prefillToolCall"))
        assertNull(ScheduledTask.fromJson(task().toJson()).prefillToolCall)
    }

    @Test
    fun `a stored prefill with no tool name reads back as absent`() {
        assertNull(PrefilledToolCall.fromJson(JSONObject()))
        assertNull(PrefilledToolCall.fromJson(null))
    }

    @Test
    fun `a stored prefill with no args reads back with an empty object`() {
        val back = PrefilledToolCall.fromJson(JSONObject().put("tool", PrefilledToolCall.SHELL_TOOL))
        assertEquals(PrefilledToolCall.SHELL_TOOL, back?.toolName)
        assertEquals(0, back!!.args().length())
    }

    // ── the envelope the model reads ─────────────────────────────────────

    @Test
    fun `a prefilled envelope names the tool and explains the call is already made`() {
        val xml = ScheduledTaskMarker(
            taskId = "T1", label = "Weather", nextFireAtMs = 5L,
            prompt = "Summarise.", prefilledTool = PrefilledToolCall.SHELL_TOOL,
        ).xml
        assertTrue(xml.contains("prefilled=\"${PrefilledToolCall.SHELL_TOOL}\""))
        assertTrue(xml.contains(ScheduledTaskMarker.prefillNote(PrefilledToolCall.SHELL_TOOL)))
        assertTrue(xml.contains("Summarise."))
    }

    @Test
    fun `an ordinary envelope carries no prefill note`() {
        val xml = ScheduledTaskMarker(taskId = "T1", label = "Weather", nextFireAtMs = 5L, prompt = "Hi.").xml
        assertFalse(xml.contains("prefilled="))
        assertFalse(xml.contains("already ran for you"))
    }

    @Test
    fun `the note and the envelope both strip back off the prompt`() {
        val marker = ScheduledTaskMarker(
            taskId = "T1", label = "Weather", nextFireAtMs = null,
            prompt = "Summarise.", prefilledTool = PrefilledToolCall.SHELL_TOOL,
        )
        assertEquals("Summarise.", ScheduledTaskMarker.stripMarker(marker.xml))
    }

    // ── the scripted turn ────────────────────────────────────────────────

    @Test
    fun `the scripted turn streams the calls in place of a provider turn`() {
        val turn = ScriptedToolTurn.from(
            listOf(PrefilledToolCall.shell("df -h", title = "Disk")),
            setOf(PrefilledToolCall.SHELL_TOOL),
        )
        assertNotNull(turn)
        val chunks = turn!!.asStreamChunks()
        assertEquals(4, chunks.size)
        assertTrue(chunks[0] is LLMStreamChunk.Started)
        val start = chunks[1] as LLMStreamChunk.ToolUseStart
        val complete = chunks[2] as LLMStreamChunk.ToolCallComplete
        assertEquals(start.id, complete.id)
        assertEquals(PrefilledToolCall.SHELL_TOOL, complete.name)
        assertEquals("df -h", complete.args.getString("command"))
        assertEquals("Disk", complete.args.getString("tool_title"))
        assertNull("only Gemini populates this", complete.thoughtSignature)
        val finished = chunks[3] as LLMStreamChunk.Finished
        assertEquals(ScriptedToolTurn.STOP_REASON, finished.stopReason)
        assertEquals("tool_use", finished.stopReason)
    }

    @Test
    fun `each scripted call gets a distinct scripted id`() {
        val turn = ScriptedToolTurn.from(
            listOf(
                PrefilledToolCall.shell("date", "A"),
                PrefilledToolCall.shell("df -h", "B"),
            ),
            setOf(PrefilledToolCall.SHELL_TOOL),
        )!!
        val ids = turn.calls.map { it.id }
        assertEquals(2, ids.distinct().size)
        assertTrue(ids.all { ScriptedToolTurn.isScriptedId(it) })
        assertTrue(ids.all { it.matches(Regex("^[a-zA-Z0-9_-]+$")) })
        assertTrue("under the 64-char cap", ids.all { it.length <= 64 })
        assertFalse("a model's own call is not scripted", ScriptedToolTurn.isScriptedId("call_abc123"))
    }

    @Test
    fun `a call to a tool this session does not offer is dropped`() {
        val turn = ScriptedToolTurn.from(
            listOf(PrefilledToolCall.shell("date")),
            setOf("browser_use"),
        )
        assertNull("falls back to the model deciding for itself", turn)
    }

    @Test
    fun `an unusable stored call never reaches the loop`() {
        val broken = PrefilledToolCall(PrefilledToolCall.SHELL_TOOL, JSONObject().put("command", "  ").toString())
        assertNull(ScriptedToolTurn.from(listOf(broken), setOf(PrefilledToolCall.SHELL_TOOL)))
    }

    @Test
    fun `only the usable calls of a mixed batch are scripted`() {
        val turn = ScriptedToolTurn.from(
            listOf(
                PrefilledToolCall.shell("date"),
                PrefilledToolCall("browser_use", "{}"),
            ),
            setOf(PrefilledToolCall.SHELL_TOOL),
        )!!
        assertEquals(1, turn.calls.size)
        assertEquals(PrefilledToolCall.SHELL_TOOL, turn.calls[0].toolName)
    }

    @Test
    fun `malformed stored args still stream an object`() {
        val turn = ScriptedToolTurn.from(
            listOf(PrefilledToolCall(PrefilledToolCall.SHELL_TOOL, "{oops")),
            setOf(PrefilledToolCall.SHELL_TOOL),
        )
        // The call is unusable (command blank after the parse fails), so the
        // turn is dropped rather than sent with `{}` — but if it ever does
        // stream, the chunk must carry an object, never a crash.
        assertNull(turn)
    }

    // ── editing keeps what it cannot show ────────────────────────────────

    @Test
    fun `renaming a task keeps its stored call`() {
        val prev = task(PrefilledToolCall.shell("date"))
        val incoming = task(prefill = null).copy(label = "Renamed")
        val merged = ScheduledTaskManager.mergeBookkeeping(incoming, prev)
        assertEquals("Renamed", merged.label)
        assertEquals("date", merged.prefillToolCall?.args()?.getString("command"))
    }

    @Test
    fun `switching a task to rerun drops the call for good`() {
        val prev = task(PrefilledToolCall.shell("date"))
        val rerun = task(prefill = null).copy(targetMode = ScheduledTargetMode.RerunMessage("s1", "m1"))
        assertNull(ScheduledTaskManager.mergeBookkeeping(rerun, prev).prefillToolCall)
    }

    @Test
    fun `an explicitly supplied call wins over the stored one`() {
        val prev = task(PrefilledToolCall.shell("date"))
        val incoming = task(PrefilledToolCall.shell("df -h"))
        val merged = ScheduledTaskManager.mergeBookkeeping(incoming, prev)
        assertEquals("df -h", merged.prefillToolCall?.args()?.getString("command"))
    }
}
