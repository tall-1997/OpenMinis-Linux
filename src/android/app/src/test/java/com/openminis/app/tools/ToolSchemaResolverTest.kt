package com.openminis.app.tools

import com.openminis.app.ui.chat.normalizedExecArgs
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-schema-validation-wiring] 派发入口校验判定的验收：
 * 放行 / 缺必填 / 类型错 / 无 schema 跳过 / 非法 JSON 跳过。
 * 判定函数是纯的（schema 来自静态工具定义），不需要 ViewModel。
 */
class ToolSchemaResolverTest {

    @Test
    fun `schema universe covers the built-in dispatch names`() {
        assertTrue(ToolSchemaResolver.schemas.containsKey("shell_execute"))
        assertTrue(ToolSchemaResolver.schemas.containsKey("file_read"))
        assertTrue(ToolSchemaResolver.schemas.containsKey("file_edit"))
        assertTrue(ToolSchemaResolver.schemas.containsKey("spawn_agent"))
    }

    @Test
    fun `well-formed arguments pass`() {
        val problems = ToolSchemaResolver.problemsFor(
            "shell_execute",
            """{"tool_title":"List files","command":"ls -la","timeout":30}""",
        )
        assertTrue("expected no problems: $problems", problems.isEmpty())
    }

    @Test
    fun `missing required parameter is reported by name`() {
        val problems = ToolSchemaResolver.problemsFor("shell_execute", """{"tool_title":"x"}""")
        assertTrue("should complain about the missing required arg: $problems", problems.isNotEmpty())
        assertTrue(problems.joinToString().contains("command"))
    }

    @Test
    fun `wrongly typed parameter is reported`() {
        // timeout 声明为 integer，给一个对象必被拦
        val problems = ToolSchemaResolver.problemsFor(
            "shell_execute",
            """{"tool_title":"x","command":"ls","timeout":{"oops":true}}""",
        )
        assertTrue(problems.isNotEmpty())
        assertTrue(problems.joinToString().contains("timeout"))
    }

    @Test
    fun `numeric strings still pass integer parameters`() {
        // matchesType 对 integer/number 容忍数字字符串（模型常见漂移），不能误伤
        val problems = ToolSchemaResolver.problemsFor(
            "shell_execute",
            """{"tool_title":"x","command":"ls","timeout":"900"}""",
        )
        assertTrue("expected no problems: $problems", problems.isEmpty())
    }

    @Test
    fun `aliases that the schema declares still help the model`() {
        // cmd 是 command 的别名且 shell_execute schema 声明了 command → 补键保留、校验通过
        val problems = ToolSchemaResolver.problemsFor("shell_execute", """{"tool_title":"x","cmd":"ls"}""")
        assertTrue("alias-filled required arg should pass: $problems", problems.isEmpty())
    }

    @Test
    fun `tools without a schema are skipped not blocked`() {
        // MCP 与任何未登记工具：resolver 返回 null → 宽容放行
        assertTrue(ToolSchemaResolver.problemsFor("mcp__whatever", """{"any":1}""").isEmpty())
        assertTrue(ToolSchemaResolver.problemsFor("not_a_tool", """{}""").isEmpty())
    }

    @Test
    fun `malformed args json falls through to the executors own error path`() {
        assertTrue(ToolSchemaResolver.problemsFor("shell_execute", "{not json").isEmpty())
        assertTrue(ToolSchemaResolver.problemsFor("shell_execute", "[1,2]").isEmpty())
    }

    @Test
    fun `executor side normalization completes aliases the validator approved`() {
        // [T-schema-exec-args-sync] P2：校验面补出来的键（cmd→command）执行器也
        // 要看得到，否则「校验通过、执行缺参」。ChatViewModelExecuteToolExt 的
        // 执行器现在读 normalizedExecArgs 的输出。
        val aliased = JSONObject(
            normalizedExecArgs("shell_execute", """{"cmd":"ls"}"""),
        )
        assertEquals("ls", aliased.optString("command"))
    }

    @Test
    fun `executor side normalization unwraps wrappers and unflattens keys`() {
        val unwrapped = JSONObject(
            normalizedExecArgs("shell_execute", """{"params":{"command":"ls"}}"""),
        )
        assertEquals("ls", unwrapped.optString("command"))

        val unflattened = JSONObject(
            normalizedExecArgs("shell_execute", """{"command":"ls","options__cwd":"/tmp"}"""),
        )
        assertEquals("/tmp", unflattened.optJSONObject("options")?.optString("cwd"))
    }

    @Test
    fun `executor side normalization falls back to raw on invalid json and mcp`() {
        // 非法 JSON 回退原始 argsJson（各执行器自有错误路径）；MCP 的别名与
        // 解包整体关闭，raw 即权威。
        assertEquals("{not json", normalizedExecArgs("shell_execute", "{not json"))
        assertEquals("""{"params":{"a":1}}""", normalizedExecArgs("mcp__x", """{"params":{"a":1}}"""))
    }
}
