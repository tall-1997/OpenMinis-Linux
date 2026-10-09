package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.AgentToolParam
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.mozilla.javascript.BaseFunction
import org.mozilla.javascript.ClassShutter
import org.mozilla.javascript.Context
import org.mozilla.javascript.ContextFactory
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject

/**
 * Rhino JS orchestration sandbox. Only print() returns to the model.
 *
 * The script has NO Java interop: the context installs a deny-all
 * [ClassShutter] and a safe standard scope, so `java.*`, `Packages` and
 * `new java.io.File(...)` are all undefined. Do not "fix" this by switching
 * back to initStandardObjects() — see the comment in execute().
 *
 * Adapted from XINCODE-Public CodeExecTool (GPL-3.0-or-later).
 */
object ExecuteCodeTool {
    const val NAME = "execute_code"
    private const val MAX_TOOL_CALLS = 50
    private const val MAX_STDOUT = 50_000
    private const val INSTRUCTION_BUDGET = 20_000_000

    /**
     * [T-p1-4-execute-code-wall-clock] 脚本墙钟上限。指令预算（observeInstructionCount）
     * 只统计解释执行的 JS 指令，而 java.util.regex 的灾难性回溯是 native 调用——不计数、
     * 不响应线程中断、也不响应协程取消，一个 `print(/((a+)+)+$/.test("a".repeat(30)))`
     * 就能把执行线程永久挂死，agent 循环只能杀 App。墙钟是最后的兜底：超时后**弃车**
     * （脚本线程留作 daemon 继续烧 CPU 直到指令预算/回溯自然结束——Thread.stop 已不可用），
     * 调用方立即拿到超时错误与已积累的 print 输出。取 5 分钟：覆盖 50 次本地只读工具
     * 编排的合法耗时，同时把最坏 CPU 燃烧限制在单核 5 分钟。
     */
    private const val WALL_CLOCK_MS = 5 * 60_000L

    val SANDBOX_TOOLS = setOf(
        "web_search", "web_fetch", "file_read", "list_dir", "grep", "glob",
        "memory_get", "grep_source",
    )

    fun definition(): AgentToolDefinition = AgentToolDefinition(
        name = NAME,
        description = "Run JavaScript (ES5, Rhino) to orchestrate multiple read-only tool calls. " +
            "Functions: ${SANDBOX_TOOLS.joinToString(", ")}(obj). Only print() output returns. " +
            "No file_write/shell. JavaScript only — not Python.",
        parameters = mapOf(
            "tool_title" to AgentToolParam("string", "A concise 5-10 word summary shown to the user."),
            "code" to AgentToolParam("string", "JavaScript (ES5) source. Use print(...) for output."),
        ),
        required = listOf("tool_title", "code"),
        propertyOrdering = listOf("tool_title", "code"),
    )

    class Bridge(private val invoke: suspend (String, String) -> ToolExecutionResult) {
        val out = StringBuilder()
        var toolCalls = 0
        fun print(v: Any?) {
            if (out.length < MAX_STDOUT) out.append(v?.toString() ?: "null").append('\n')
        }

        /**
         * [T-p1-4-execute-code-wall-clock] 上限中断必须携带已积累的 print 输出：
         * 旧实现裸抛 RuntimeException，被 execute() 外层 catch 兜住后模型只看到
         * "tool call cap 50"，此前 50 次编排的成果全部蒸发。自定义异常把快照带出
         * 沙箱，execute() 据此拼回部分结果。注意它必须在内层 try 之外抛——内层
         * catch 会把一切异常转成脚本可见的 ERROR 字符串，那就停不掉脚本了。
         */
        class ToolCallCapExceeded(val partialOutput: String) :
            RuntimeException("tool call cap $MAX_TOOL_CALLS reached; partial output preserved")

        fun call(toolName: String, argsJson: String): String {
            if (toolName !in SANDBOX_TOOLS) return "ERROR: '$toolName' not in sandbox"
            if (++toolCalls > MAX_TOOL_CALLS) throw ToolCallCapExceeded(out.toString())
            return try {
                runBlocking {
                    val r = invoke(toolName, argsJson)
                    if (r.success) r.output else "ERROR: ${r.output}"
                }
            } catch (t: Throwable) {
                "ERROR: ${t::class.java.simpleName}: ${t.message}"
            }
        }
    }

    suspend fun execute(
        argsJson: String,
        invoke: suspend (String, String) -> ToolExecutionResult,
    ): ToolExecutionResult {
        val args = try { JSONObject(argsJson) } catch (_: Exception) { JSONObject() }
        val toolTitle = args.optString("tool_title", NAME)
        val lang = args.optString("language").ifBlank { args.optString("lang") }.trim().lowercase()
        if (lang.isNotEmpty() && lang !in setOf("javascript", "js", "ecmascript", "node")) {
            return ToolExecutionResult(
                "execute_code only runs JavaScript (ES5), not $lang. Use shell_execute for Python.",
                false,
                toolTitle = toolTitle,
            )
        }
        val code = args.optString("code").ifBlank { args.optString("script") }.ifBlank { args.optString("source") }
        if (code.isBlank()) return ToolExecutionResult("code required", false, toolTitle = toolTitle)
        val bridge = Bridge(invoke)
        // [T-p1-4-execute-code-wall-clock] 沙箱跑在独立脚本线程上，调用方 join(墙钟)
        // 等结果。不用 withTimeout：evaluateString 是阻塞调用，灾难性回溯期间没有
        // 挂起点，协程取消根本递不进去。超时即弃车——脚本线程是 daemon，留在后台
        // 由指令预算兜底，调用方立刻返回超时错误 + 已积累输出。
        val outcome = java.util.concurrent.atomic.AtomicReference<ToolExecutionResult>()
        val failure = java.util.concurrent.atomic.AtomicReference<Throwable>()
        val script = Thread {
            try {
                outcome.set(runSandbox(bridge, toolTitle, code))
            } catch (t: Throwable) {
                failure.set(t)
            }
        }
        script.name = "minis-execute-code"
        script.isDaemon = true
        script.start()
        script.join(WALL_CLOCK_MS)
        if (script.isAlive) {
            val partial = bridge.out.toString().take(MAX_STDOUT)
            val text = buildString {
                append("execute_code timed out after ${WALL_CLOCK_MS / 1000}s — script abandoned")
                if (partial.isNotBlank()) append("\npartial output:\n").append(partial)
            }
            return ToolExecutionResult(text, false, toolTitle = toolTitle)
        }
        failure.get()?.let { t ->
            if (t is Bridge.ToolCallCapExceeded) {
                val partial = t.partialOutput.take(MAX_STDOUT)
                val text = buildString {
                    append("execute_code failed: ${t.message}")
                    if (partial.isNotBlank()) append("\noutput so far:\n").append(partial)
                }
                return ToolExecutionResult(text, false, toolTitle = toolTitle)
            }
            return ToolExecutionResult(
                "execute_code failed: ${t.javaClass.simpleName}: ${t.message}",
                false,
                toolTitle = toolTitle,
            )
        }
        return outcome.get() ?: ToolExecutionResult(
            "execute_code produced no result",
            false,
            toolTitle = toolTitle,
        )
    }

    /** 沙箱本体（在脚本线程上执行）。异常向上抛给 [execute] 的 failure 槽。 */
    private fun runSandbox(bridge: Bridge, toolTitle: String, code: String): ToolExecutionResult {
        val factory = object : ContextFactory() {
            private var instructions = 0
            override fun makeContext(): Context {
                val cx = super.makeContext()
                cx.optimizationLevel = -1
                cx.instructionObserverThreshold = 100_000
                return cx
            }
            override fun observeInstructionCount(cx: Context?, count: Int) {
                instructions += count
                if (instructions > INSTRUCTION_BUDGET) throw RuntimeException("instruction budget exceeded")
            }
        }
        val cx = factory.enterContext()
        try {
            // [T-execute-code-java-interop] Rhino's LiveConnect hands the
            // script `java.*` / `Packages` / `new java.io.File(...)` for
            // free — verified against rhino-1.7.14: `new java.io.
            // FileOutputStream(p)` writes and `Runtime.getRuntime().exec`
            // runs a shell. That made the "read-only JS orchestration"
            // claim false and, because execute_code sits in SubAgentKind's
            // READ_ONLY_ALLOW, let an explore/plan lane — the fence whose
            // whole point is that it cannot write — write anywhere the app
            // UID can reach, without any gate in between (the tool is
            // classified as a REVERSIBLE SYSTEM read).
            //
            // initSafeStandardObjects() + a deny-all ClassShutter remove
            // the interop bridge entirely while keeping the ES5 surface the
            // prelude needs (JSON, Array/String methods, closures). Deny-all
            // rather than an allow-list: the script has no legitimate Java
            // object to touch, so the smallest hole is no hole.
            cx.setClassShutter(ClassShutter { false })
            val scope = cx.initSafeStandardObjects()
            val printFn = object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<out Any>,
                ): Any? {
                    bridge.print(args.getOrNull(0))
                    return Context.getUndefinedValue()
                }
            }
            ScriptableObject.putProperty(scope, "print", printFn)
            val console = cx.newObject(scope)
            ScriptableObject.putProperty(console, "log", printFn)
            ScriptableObject.putProperty(scope, "console", console)
            val callFn = object : BaseFunction() {
                override fun call(
                    cx: Context,
                    scope: Scriptable,
                    thisObj: Scriptable,
                    args: Array<out Any>,
                ): Any? = bridge.call(
                    args.getOrNull(0)?.toString().orEmpty(),
                    args.getOrNull(1)?.toString() ?: "{}",
                )
            }
            ScriptableObject.putProperty(scope, "__minis_call", callFn)
            val prelude = buildString {
                for (t in SANDBOX_TOOLS) {
                    append("function $t(p){")
                    append("if(typeof p==='string'){var k={file_read:'path',list_dir:'path',grep:'pattern',glob:'pattern',web_search:'query',web_fetch:'url',memory_get:'name',grep_source:'pattern'};")
                    append("var o={};o[k['$t']||'query']=p;return __minis_call('$t', JSON.stringify(o));}")
                    append("if(p==null||typeof p!=='object'){return 'Error: $t expects an object like {path:\\\"...\\\"} or a string, got '+(typeof p);}")
                    append("return __minis_call('$t', JSON.stringify(p));}\n")
                }
            }
            cx.evaluateString(scope, prelude + "\n" + code, "execute_code", 1, null)
        } finally {
            Context.exit()
        }
        val text = bridge.out.toString().ifBlank { "(no print output, ${bridge.toolCalls} tool calls)" }
        return ToolExecutionResult(text.take(MAX_STDOUT), true, toolTitle = toolTitle)
    }
}
