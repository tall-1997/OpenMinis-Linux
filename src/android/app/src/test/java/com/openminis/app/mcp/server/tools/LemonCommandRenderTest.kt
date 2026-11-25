package com.openminis.app.mcp.server.tools

import com.openminis.app.security.PermissionMode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-lemon-render] `buildCommand` 的渲染契约。
 *
 * 这个函数是柠檬工具的**唯一**生产命令路径：catalog 里的 `cmd` 模板被填充成
 * 真实 shell 命令，再交给 `SecurityGate` 判级、最后由 shell 执行。填错一个字符
 * 就是三种后果之一：命令注入、bash 语法错误、或者参数静默失效。
 *
 * 三个已知的真实失效点都在这里钉住：
 *  1. 值未转义 → 命令注入（上游面板用 `str.format`，`;` 就能链命令）
 *  2. `${name:-default}` 被裸替换打断 → bash `bad substitution`
 *     （实测：`${'40':-100}` 运行时报错，而 `bash -n` 查不出来）
 *  3. `$path` 的替换吃掉 `$paths` → 变量名残渣
 */
class LemonCommandRenderTest {

    private fun tool(cmd: String, params: String = "", required: String = "") =
        LemonCatalog.Tool(
            name = "probe",
            desc = "probe",
            cmd = cmd,
            params = params,
            required = required,
            timeoutMs = 5000,
            domain = "D1-系统",
            risk = "low",
            readOnly = true,
        )

    // ── 注入防护 ────────────────────────────────────────────────────────

    @Test
    fun `semicolon in a value cannot chain a command`() {
        val out = buildCommand(tool("echo \$msg"), JSONObject().put("msg", "a; reboot"))
        assertEquals("echo 'a; reboot'", out)
    }

    @Test
    fun `command substitution in a value is neutralised`() {
        val out = buildCommand(tool("echo \$msg"), JSONObject().put("msg", "\$(id)"))
        assertEquals("echo '\$(id)'", out)
    }

    @Test
    fun `embedded single quote escapes the POSIX way`() {
        val out = buildCommand(tool("echo \$msg"), JSONObject().put("msg", "it's"))
        assertEquals("echo 'it'\\''s'", out)
    }

    // ── 花括号默认值展开（68 个上游插件依赖）──────────────────────────────

    @Test
    fun `braced default is preserved when no value is supplied`() {
        // 没有值时必须原样保留，让 bash 用它自己的默认值 ——
        // 这正是 ${name:-default} 存在的意义。旧实现会打断成 ${'..':-..}。
        val out = buildCommand(
            tool("head -\${lines:-100}", params = "lines:int"),
            JSONObject(),
        )
        assertEquals("head -\${lines:-100}", out)
    }

    @Test
    fun `braced default is replaced entirely when a value is supplied`() {
        val out = buildCommand(
            tool("head -\${lines:-100}", params = "lines:int"),
            JSONObject().put("lines", "40"),
        )
        assertEquals("head -'40'", out)
    }

    @Test
    fun `braced form without default expands to empty when absent`() {
        val out = buildCommand(tool("echo \${msg}", params = "msg:string"), JSONObject())
        assertEquals("echo ", out)
    }

    @Test
    fun `dash-only default form is also handled`() {
        // ${args:--h} 的默认值是 "-h"，前缀是 ":-"；${args--h} 的前缀是 "-"。
        val out = buildCommand(
            tool("readelf \${args:--h}", params = "args:string"),
            JSONObject(),
        )
        assertEquals("readelf \${args:--h}", out)
    }

    // ── 变量名边界 ──────────────────────────────────────────────────────

    @Test
    fun `short parameter name does not swallow a longer variable`() {
        // $path 不该吃掉 $paths —— 裸 replace 会留下 ";' 这种残渣。
        val out = buildCommand(
            tool("echo \$path \$paths", params = "path:string"),
            JSONObject().put("path", "/a"),
        )
        assertEquals("echo '/a' \$paths", out)
    }

    // ── 审计参数 ────────────────────────────────────────────────────────

    @Test
    fun `reason argument never reaches the command line`() {
        val out = buildCommand(
            tool("echo \$msg", params = "msg:string"),
            JSONObject().put("msg", "hi").put("_reason", "because"),
        )
        assertFalse("_reason 只进审计，不进命令行", out.contains("because"))
    }

    // ── 缺失必填参数 ────────────────────────────────────────────────────

    @Test
    fun `missing required parameter throws instead of expanding to empty`() {
        val thrown = runCatching {
            buildCommand(tool("am force-stop \$pkg", params = "pkg:string", required = "pkg"), JSONObject())
        }.exceptionOrNull()
        assertTrue("缺必填必须抛错", thrown is IllegalArgumentException)
        assertTrue(thrown!!.message!!.contains("pkg"))
    }

    // ── 工具注册形状（原本在 LemonToolsTest，合并到这里保持一处）──────────

    @Test
    fun `both catalog tools are registered with object schemas`() {
        val tools = lemonTools(
            McpToolDeps(
                context = null,
                gate = com.openminis.app.security.SecurityGateImpl(),
                shellMode = { PermissionMode.ALLOW_ALL },
            ),
        )
        assertEquals(listOf("lemon_list", "lemon_call"), tools.map { it.name })
        tools.forEach {
            assertEquals("object", it.schema.getString("type"))
            assertTrue(it.description.length > 20)
        }
    }
}
