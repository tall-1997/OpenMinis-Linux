package com.openminis.app.scheduled

import com.openminis.app.sandbox.offload.OffloadArgs
import com.openminis.app.sandbox.offload.ScheduledTaskOffloadHandler
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-scheduled-tool-prefill] The `minis-scheduled create` side of a prefilled
 * call: `--command` / `--shell` / `--tool` / `--tool-args` /
 * `--command-timeout` parsing. A wrong flag must fail the create with a
 * sentence naming the fix — the alternative is a task that saves cleanly and
 * then misbehaves on every unattended fire.
 *
 * [ScheduledTaskOffloadHandler.parsePrefill] is static for exactly this test;
 * the firing path is pinned in [ScheduledPrefillWiringTest].
 */
class ScheduledPrefillCliTest {

    private fun args(vararg tokens: String) = OffloadArgs(tokens.toList())

    private fun refuse(tokens: Array<out String>, target: ScheduledTargetMode = ScheduledTargetMode.NewSession): String {
        val e = assertThrows(IllegalArgumentException::class.java) {
            ScheduledTaskOffloadHandler.parsePrefill(args(*tokens), target, "Disk")
        }
        return e.message ?: ""
    }

    // ── the happy shapes ─────────────────────────────────────────────────

    @Test
    fun `--command becomes a shell call with the task's label as its title`() {
        val call = ScheduledTaskOffloadHandler.parsePrefill(
            args("--command", "df -h", "--command-timeout", "2m"),
            ScheduledTargetMode.NewSession,
            title = "Disk",
        )
        assertNotNull(call)
        assertEquals(PrefilledToolCall.SHELL_TOOL, call!!.toolName)
        assertEquals("df -h", call.args().getString("command"))
        assertEquals(120, call.args().getInt("timeout"))
        assertEquals("Disk", call.args().getString("tool_title"))
    }

    @Test
    fun `--shell is the same flag and a bare command still gets a title`() {
        val call = ScheduledTaskOffloadHandler.parsePrefill(args("--shell", "uptime"), ScheduledTargetMode.NewSession)
        assertEquals("uptime", call?.args()?.getString("command"))
        assertEquals("uptime", call?.args()?.getString("tool_title"))
    }

    @Test
    fun `--tool keeps the caller's arguments verbatim`() {
        val call = ScheduledTaskOffloadHandler.parsePrefill(
            args("--tool", "shell_execute", "--tool-args", """{"command":"uptime","tool_title":"Up"}"""),
            ScheduledTargetMode.AppendToSession("s1"),
        )
        assertEquals(PrefilledToolCall.SHELL_TOOL, call?.toolName)
        assertEquals("uptime", call?.args()?.getString("command"))
        assertEquals("Up", call?.args()?.getString("tool_title"))
    }

    @Test
    fun `a flag timeout wins over one inside --tool-args, as on iOS`() {
        val call = ScheduledTaskOffloadHandler.parsePrefill(
            args("--tool", "shell_execute", "--tool-args", """{"command":"uptime","timeout":5}""", "--command-timeout", "90s"),
            ScheduledTargetMode.NewSession,
        )
        assertEquals(90, call?.args()?.getInt("timeout"))
    }

    @Test
    fun `no prefill flag means no call, whatever else was passed`() {
        assertNull(ScheduledTaskOffloadHandler.parsePrefill(args("--label", "X"), ScheduledTargetMode.NewSession))
    }

    // ── the refusals ─────────────────────────────────────────────────────

    @Test
    fun `--command and --tool together are refused`() {
        assertTrue(
            refuse(arrayOf("--command", "date", "--tool", "shell_execute")).contains("pass one of the two, not both"),
        )
    }

    @Test
    fun `a timeout with nothing to time out is a typo, not a default`() {
        assertTrue(refuse(arrayOf("--command-timeout", "2m")).contains("only applies with --command"))
    }

    @Test
    fun `a rerun target cannot carry a call`() {
        assertTrue(
            refuse(arrayOf("--command", "date"), ScheduledTargetMode.RerunMessage("s1", "m1"))
                .contains("replays an existing turn"),
        )
    }

    @Test
    fun `an unsupported tool is refused at create time`() {
        assertTrue(refuse(arrayOf("--tool", "browser_use")).contains("not supported"))
    }

    @Test
    fun `--tool-args that is not an object is refused`() {
        assertTrue(refuse(arrayOf("--tool", "shell_execute", "--tool-args", "{oops")).contains("must be a JSON object"))
    }

    @Test
    fun `an empty command is refused instead of saved`() {
        assertTrue(refuse(arrayOf("--command", "")).contains("non-empty command"))
    }

    @Test
    fun `a timeout past an hour names the range`() {
        assertTrue(refuse(arrayOf("--command", "date", "--command-timeout", "2h")).contains("between"))
    }

    @Test
    fun `a timeout that is not a duration is refused`() {
        assertTrue(
            refuse(arrayOf("--command", "date", "--command-timeout", "soon")).contains("like 90s, 2m or 1h"),
        )
    }

    // ── durations ────────────────────────────────────────────────────────

    @Test
    fun `durations read as seconds, minutes and hours`() {
        assertEquals(90, ScheduledTaskOffloadHandler.parseDurationSec("90s"))
        assertEquals(120, ScheduledTaskOffloadHandler.parseDurationSec("2m"))
        assertEquals(150, ScheduledTaskOffloadHandler.parseDurationSec("2.5m"))
        assertEquals(45, ScheduledTaskOffloadHandler.parseDurationSec("45"))
        assertEquals(7200, ScheduledTaskOffloadHandler.parseDurationSec("2H"))
        assertEquals(3600, ScheduledTaskOffloadHandler.parseDurationSec("1h"))
    }

    @Test
    fun `nonsense durations read as absent, not as a number`() {
        assertNull(ScheduledTaskOffloadHandler.parseDurationSec("ms"))
        assertNull(ScheduledTaskOffloadHandler.parseDurationSec("abc"))
        assertNull(ScheduledTaskOffloadHandler.parseDurationSec(""))
        assertNull(ScheduledTaskOffloadHandler.parseDurationSec("h"))
    }

    @Test
    fun `an absurd duration saturates instead of wrapping into a legal one`() {
        // Past Int range, so a plain toInt() would land somewhere arbitrary
        // (3000000000 → 1295421448 with `%`), not helpfully out of bounds.
        assertEquals(
            Int.MAX_VALUE,
            ScheduledTaskOffloadHandler.parseDurationSec("3000000000s"),
        )
        assertTrue(refuse(arrayOf("--command", "date", "--command-timeout", "3000000000s")).contains("between"))
    }

    @Test
    fun `a stored call writes the same shape it reads back`() {
        val call = ScheduledTaskOffloadHandler.parsePrefill(
            args("--command", "date", "--command-timeout", "30s"),
            ScheduledTargetMode.NewSession,
            title = "Clock",
        )!!
        val json = JSONObject().put("tool", call.toolName).put("args", call.args())
        val back = PrefilledToolCall.fromJson(json)
        assertEquals(call.toolName, back?.toolName)
        assertEquals("date", back?.args()?.getString("command"))
        assertEquals(30, back?.args()?.getInt("timeout"))
    }
}
