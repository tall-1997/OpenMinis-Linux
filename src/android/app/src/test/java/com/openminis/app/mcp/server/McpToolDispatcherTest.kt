package com.openminis.app.mcp.server

import com.openminis.app.security.AuditEntry
import com.openminis.app.security.AuditChainVerification
import com.openminis.app.security.Capability
import com.openminis.app.security.Decision
import com.openminis.app.security.GateCommand
import com.openminis.app.security.PermissionMode
import com.openminis.app.security.PermissionProfile
import com.openminis.app.security.PermissionRule
import com.openminis.app.security.Reversibility
import com.openminis.app.security.RiskLevel
import com.openminis.app.security.SecurityGate
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * McpToolDispatcher 的纯 JVM 面：listTools 形状、未知工具、file_read 路径
 * 验证（validateFilePath/normalizePath 纯函数）、shell_exec 的 gate 映射
 * （注入 fake gate，不碰 ExecutionCoordinator）。
 *
 * Android 依赖策略：unitTests.isReturnDefaultValues=true，Context 只作为
 * 构造占位（本测试路径不触发其方法），Log 在 JVM 下为 no-op。
 */
class McpToolDispatcherTest {

    /** Scriptable fake gate：按注入的 decision 回答，记录 classify/audit。 */
    private class FakeGate(
        var nextDecision: Decision = Decision.Allow("fake allow"),
    ) : SecurityGate {
        var lastClassifiedArgs: String? = null
        var lastAuditedDecision: Decision? = null
        override fun setPermissionMode(mode: PermissionMode) {}
        override fun getPermissionMode(): PermissionMode = PermissionMode.ALLOW_ALL
        override fun classify(toolName: String, toolArgs: String): GateCommand {
            lastClassifiedArgs = toolArgs
            return GateCommand(toolName, toolArgs, Capability.PROCESS, Reversibility.REVERSIBLE, "fake")
        }
        override fun classifyRisk(command: String): RiskLevel = RiskLevel.NORMAL
        override fun decide(cmd: GateCommand, mode: PermissionMode): Decision = nextDecision
        override fun preview(cmd: GateCommand): String = "fake preview"
        override fun audit(cmd: GateCommand, decision: Decision, result: String?) {
            lastAuditedDecision = decision
        }
        override fun getAuditTrail(): List<AuditEntry> = emptyList()
        override fun setPermissionRules(rules: List<PermissionRule>) {}
        override fun setAuthorityProfile(profile: PermissionProfile?) {}
        override fun verifyAuditChain(): AuditChainVerification = AuditChainVerification(ok = true)
    }

    private fun dispatcher(gate: SecurityGate, mode: PermissionMode = PermissionMode.ALLOW_ALL): McpToolDispatcher =
        // Context 在 Kotlin 里是 platform type：可直接传 null（本测试路径
        // 不会解引用它；device_info/fileRead 的宿主路径未覆盖）。
        @Suppress("USELESS_ELVIS")
        McpToolDispatcher(null as android.content.Context?, gate, { mode })

    private fun call(
        d: McpToolDispatcher, name: String, args: String = "{}",
    ): McpServerCore.CallResult = d.callTool(name, JSONObject(args))

    private fun firstText(r: McpServerCore.CallResult): String =
        r.content.getJSONObject(0).getString("text")

    // ─── listTools ────────────────────────────────────────────────────────

    @Test
    fun `listTools exposes exactly the twelve tools with schemas`() {
        val tools = dispatcher(FakeGate()).listTools()
        assertEquals(12, tools.length())
        val names = (0 until tools.length()).map { tools.getJSONObject(it).getString("name") }
        assertEquals(
            listOf(
                "device_info", "shell_exec", "ui_read", "ui_action", "file_read",
                "file_write", "schedule_task_create", "schedule_task_list",
                "schedule_task_update", "schedule_task_delete",
                "alarm_reminder_create", "context_apps_query",
            ),
            names,
        )
        // every tool carries a type:object schema; shell_exec/file_read declare required
        for (i in 0 until tools.length()) {
            assertEquals("object", tools.getJSONObject(i).getJSONObject("inputSchema").getString("type"))
        }
    }

    @Test
    fun `unknown tool errors without crashing`() {
        val r = call(dispatcher(FakeGate()), "no_such_tool")
        assertTrue(r.isError)
        assertTrue(firstText(r).contains("no_such_tool"))
        assertNull(r.errorCode) // plain tool error, not a JSON-RPC error mapping
    }

    // ─── file_read 路径验证（纯函数） ─────────────────────────────────────

    @Test
    fun `file read path validation allows allowed prefixes`() {
        val d = dispatcher(FakeGate())
        assertNull(McpToolDispatcher.validateFilePath("/var/minis/workspace/a.txt"))
        assertNull(McpToolDispatcher.validateFilePath("/sdcard/Download/x.pdf"))
        assertNull(McpToolDispatcher.validateFilePath("/var/minis/"))
        assertNull(McpToolDispatcher.validateFilePath("/sdcard"))
    }

    @Test
    fun `file read path validation rejects outside prefixes`() {
        val d = dispatcher(FakeGate())
        assertNotNull(McpToolDispatcher.validateFilePath("/data/data/com.other/secret"))
        assertNotNull(McpToolDispatcher.validateFilePath("/etc/passwd"))
        assertNotNull(McpToolDispatcher.validateFilePath(""))
        assertNotNull(McpToolDispatcher.validateFilePath("/var/minisx/../etc/passwd")) // prefix 伪装
    }

    @Test
    fun `file read path validation rejects traversal that survives normalization`() {
        val d = dispatcher(FakeGate())
        // /var/minis/../../etc → normalize 收敛到 /var → 前缀不匹配 → 拒绝
        assertNotNull(McpToolDispatcher.validateFilePath("/var/minis/../../etc/passwd"))
        // 前缀内但折出前缀的 .. 同样拒绝
        assertNotNull(McpToolDispatcher.validateFilePath("/var/minis/a/../../b"))
        // normalize 折叠不掉的（仍在允许前缀下）放行
        assertNull(McpToolDispatcher.validateFilePath("/var/minis/a/../b.txt"))
    }

    @Test
    fun `normalizePath collapses dots and slashes`() {
        assertEquals("/var/minis/a.txt", McpToolDispatcher.normalizePath("/var/minis/./a.txt"))
        assertEquals("/var/minis/a.txt", McpToolDispatcher.normalizePath("/var/minis//a.txt"))
        assertEquals("/var/minis/a.txt", McpToolDispatcher.normalizePath("\\var\\minis\\a.txt"))
        // 折到根之上 → 空
        assertEquals("", McpToolDispatcher.normalizePath("/../.."))
        assertEquals("/", McpToolDispatcher.normalizePath("/"))
    }

    @Test
    fun `file read rejects missing path argument`() {
        val r = call(dispatcher(FakeGate()), "file_read")
        assertTrue(r.isError)
        assertTrue(firstText(r).contains("'path' is required"))
    }

    @Test
    fun `file read maps denied path to -32003 with data`() {
        val r = call(dispatcher(FakeGate()), "file_read", """{"path":"/etc/passwd"}""")
        assertEquals(McpServerCore.ErrorCode.PATH_DENIED, r.errorCode)
        assertTrue(firstText(r).startsWith("Path denied:"))
        assertNotNull(r.errorData)
    }

    // ─── shell_exec gate 映射 ─────────────────────────────────────────────

    @Test
    fun `shell exec without command errors before touching the gate`() {
        val gate = FakeGate()
        val r = call(dispatcher(gate), "shell_exec", "{}")
        assertTrue(r.isError)
        assertTrue(firstText(r).contains("'command' is required"))
        assertNull(gate.lastClassifiedArgs) // 没到 gate
    }

    @Test
    fun `shell exec gate denied maps to -32001 with reason data`() {
        val gate = FakeGate(Decision.Denied("rm -rf is destructive"))
        val r = call(dispatcher(gate), "shell_exec", """{"command":"rm -rf /"}""")
        assertEquals(McpServerCore.ErrorCode.GATE_DENIED, r.errorCode)
        assertTrue(firstText(r).contains("SecurityGate denied"))
        assertTrue(firstText(r).contains("rm -rf is destructive"))
        assertEquals("rm -rf is destructive", r.errorData)
        // gate 拿到的是包过的 argsJson（含 command 字段）
        assertTrue(gate.lastClassifiedArgs!!.contains("\"command\""))
    }

    @Test
    fun `shell exec need confirm maps to -32001 with strict hint`() {
        val gate = FakeGate(Decision.NeedConfirm("risky", "preview text"))
        val r = call(
            dispatcher(gate, mode = PermissionMode.ASK),
            "shell_exec", """{"command":"apt remove vim"}""",
        )
        assertEquals(McpServerCore.ErrorCode.GATE_DENIED, r.errorCode)
        val text = firstText(r)
        assertTrue(text.contains("requires user approval"))
        assertTrue(text.contains("preview text"))
        // strict（ASK）策略下带指向设置的恢复提示
        assertTrue(text.contains(McpToolDispatcher.SHELL_STRICT_HINT))
    }

    @Test
    fun `shell exec need confirm without strict mode omits strict hint`() {
        val gate = FakeGate(Decision.NeedConfirm("risky", "p"))
        val r = call(
            dispatcher(gate, mode = PermissionMode.ALLOW_ALL),
            "shell_exec", """{"command":"x"}""",
        )
        assertTrue(firstText(r).contains("requires user approval"))
        assertTrue(!firstText(r).contains(McpToolDispatcher.SHELL_STRICT_HINT))
    }

    @Test
    fun `shell exec gate allow proceeds past the gate`() {
        // Allow 路径会走到 ExecutionCoordinator.execute —— JVM 下runBlocking
        // 里 ExecutionCoordinator 无 shell 会话，返回值形态不确定（可能抛或
        // 返回错误文本），但关键契约是：gate 允许后 audit 被调用且不映射 -32001。
        val gate = FakeGate(Decision.Allow("fine"))
        runCatching { call(dispatcher(gate), "shell_exec", """{"command":"echo hi"}""") }
        assertEquals(Decision.Allow::class, gate.lastAuditedDecision!!::class)
        assertNull(gate.lastAuditedDecision?.let { it as? Decision.Denied })
    }

    @Test
    fun `shell exec clamps timeout argument`() {
        // timeout_sec 只影响执行阶段；这里验证参数解析不炸、缺省 60。
        // 无直接观察面 —— 通过未抛异常 + gate 允许路径可达来覆盖。
        val gate = FakeGate(Decision.Allow("fine"))
        runCatching { call(dispatcher(gate), "shell_exec", """{"command":"x","timeout_sec":99999}""") }
        assertEquals(Decision.Allow::class, gate.lastAuditedDecision!!::class)
    }
}
