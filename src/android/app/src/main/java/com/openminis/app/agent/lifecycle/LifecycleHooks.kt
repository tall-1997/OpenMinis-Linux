package com.openminis.app.agent.lifecycle

import android.content.Context
import android.util.Log
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.util.concurrent.CopyOnWriteArrayList

/**
 * [GH#131] 生命周期 Hook 系统（最小版）。
 *
 * ## 三个事件点
 *
 *  - [LifecycleEvent.SESSION_START]：新会话行写入数据库后（用户首条消息 /
 *    定时任务 / 自动化创建的会话都算）。
 *  - [LifecycleEvent.RESPONSE]：一轮 agent 回复结束（`runAgentLoop` 正常退出，
 *    含达到轮次上限的正常收尾）。
 *  - [LifecycleEvent.SESSION_END]：会话被删除时（含多选删除）。
 *
 * ### [GH#131] RESPONSE 的边界（第三轮复审明确）
 *
 * **取消（用户 Stop / 协程 cancel）不触发 OnResponse。** 被取消的一轮没有
 * 完整回复：如果为了“有始有终”而触发，用户脚本会把半截文本当成已完成结果
 * （发通知、把内容同步到外部、触发下游自动化），代价远大于收益。触发点因此
 * 在 `runAgentLoopBody` 的收尾处，并显式检查协程仍 active；取消路径在到达
 * 之前就以 CancellationException 退出。
 *
 * **群聊一轮触发一次 OnResponse。** 群聊（`ChatViewModelGroupChatExt`）不走
 * `runAgentLoop`，而是主管 + 成员的多次 `speakModel`。按“每个成员一条回复”
 * 逐次触发会让同一条用户消息执行 N 次脚本、且脚本无法区分成员发言与回合
 * 结束；按“一个用户可见回合”触发一次才与其余事件（会话开始/结束）的粒度
 * 一致。关闭群聊的总结轮同样算一轮，照常触发。detail 是该轮最后一条
 * assistant 文本的尾部（与主循环的 500 字符上限一致）。
 *
 * ## 两层扩展点
 *
 * 1. **进程内事件总线**：[LifecycleListener] 可注册任意 Kotlin 处理器（未来
 *    的通知 / 统计 / 技能钩子）。同步回调，必须自己保证不阻塞——核心流程
 *    只是 `fire()`，不会等待处理器。
 * 2. **沙箱脚本**：设置页为每个事件配置一条 guest 命令（如
 *    `bash /var/minis/hooks/on-response.sh`），通过 [ExecutionCoordinator]
 *    在独立协程执行，带 20s 超时；失败只记日志，不影响会话。
 *
 * ## 声明式开关
 *
 * [Config.enabled] 默认 **false**：升级用户不会因为本功能突然开始执行脚本。
 * 开关与脚本一起存在 SharedPreferences（`lifecycle_hooks`，JSON），保存即
 * 生效，无需重启。
 *
 * ## 与 SKILL.md frontmatter 的关系（未做，留扩展点）
 *
 * 上游的双轨方案允许在 SKILL.md 的 frontmatter 里声明 `on_session_start: …`，
 * 让技能自带 hook。本版只做“全局配置”一轨：技能是用户可安装的第三方内容，
 * 让未启用的技能获得执行沙箱命令的能力需要额外的信任/审批模型，不适合塞进
 * 最小实现。若要做，落点：
 *  - 在 `SkillRepository` 解析 frontmatter 时收集 hook 声明；
 *  - 合并进 [Config] 时标记来源 `skill:<id>`，并在设置页显示来源、默认关闭；
 *  - `fire()` 里对来源为 skill 的命令先走一次用户确认（复用 ApprovalGate）。
 */
object LifecycleHooks {

    private const val TAG = "LifecycleHooks"
    private const val PREFS = "lifecycle_hooks"
    private const val KEY = "config_json"
    private const val MAX_COMMAND_CHARS = 2000
    private const val TIMEOUT_MS = 20_000L

    /** 脚本在哪个沙箱会话里跑；固定 lane，避免污染聊天会话的 shell。 */
    const val GUEST_SESSION_ID = "__lifecycle-hooks__"

    enum class LifecycleEvent(val wire: String, val displayName: String) {
        SESSION_START("session_start", "OnSessionStart"),
        RESPONSE("response", "OnResponse"),
        SESSION_END("session_end", "OnSessionEnd"),
        ;

        companion object {
            fun fromWire(raw: String?): LifecycleEvent? =
                entries.firstOrNull { it.wire.equals(raw, ignoreCase = true) }
        }
    }

    /** 进程内监听器；[onEvent] 在 [fire] 的调用线程同步执行，必须快速返回。 */
    interface LifecycleListener {
        fun onEvent(event: LifecycleEvent, sessionId: String, detail: String)
    }

    data class Config(
        val enabled: Boolean = false,
        val commands: Map<LifecycleEvent, String> = emptyMap(),
    )

    private val listeners = CopyOnWriteArrayList<LifecycleListener>()

    @Volatile
    private var app: Context? = null

    @Volatile
    private var config: Config = Config()

    /** 最近一次脚本执行结果，供诊断；键为事件。 */
    private val lastRun = java.util.concurrent.ConcurrentHashMap<LifecycleEvent, String>()

    private val fallbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * [GH#131] Test seam: replaces the sandbox script runner. Signature is
     * (script, timeoutMs) → (exitCode, combinedOutput); null = the production
     * [ExecutionCoordinator] path (a one-shot PRoot process).
     *
     * 中文：脚本投递契约（环境变量前缀、20s 超时、退出码记账）不该只有真机
     * 才能验证；注入 fake runner 后 JVM 测试可以钉住 fire() 的整条脚本分支。
     */
    internal var scriptRunnerForTest: (suspend (String, Long) -> Pair<Int, String>)? = null

    fun prime(context: Context) {
        app = context.applicationContext
        config = load(context)
    }

    fun config(): Config = config

    fun isEnabled(): Boolean = config.enabled

    fun commandFor(event: LifecycleEvent): String = config.commands[event].orEmpty()

    fun lastRunStatus(event: LifecycleEvent): String? = lastRun[event]

    /** 保存开关；保留已配置脚本。返回错误信息，null = 成功。 */
    fun setEnabled(context: Context, enabled: Boolean): String? =
        persist(context, config.copy(enabled = enabled))

    /**
     * 保存某个事件的脚本；空字符串 = 删除该事件的 hook。
     *
     * 只做最基本的长度/控制字符校验：脚本内容由用户在设置页亲手输入，属于
     * 用户显式授权的能力，不再过 `minis-notify` 那套“仅允许已知命令”的白名单
     * （那会直接排除用户自己的脚本路径）。
     */
    fun setCommand(context: Context, event: LifecycleEvent, command: String): String? {
        val cmd = command.trim()
        if (cmd.length > MAX_COMMAND_CHARS) return "Command is too long (max $MAX_COMMAND_CHARS characters)"
        if (cmd.any { it == '\r' } || cmd.count { it == '\n' } > 1) {
            return "Command must be a single line (use a script path for multi-line logic)"
        }
        val next = config.commands.toMutableMap()
        if (cmd.isEmpty()) next.remove(event) else next[event] = cmd
        return persist(context, config.copy(commands = next))
    }

    fun registerListener(listener: LifecycleListener) {
        listeners.addIfAbsent(listener)
    }

    fun unregisterListener(listener: LifecycleListener) {
        listeners.remove(listener)
    }

    /**
     * 触发一个生命周期事件。永不抛异常、永不阻塞：监听器同步回调（各自
     * runCatching），脚本投递到 IO 协程。
     *
     * @param detail 事件的短上下文（会话标题 / 回复尾部 / 结束原因），会以
     *   `MINIS_HOOK_DETAIL` 环境变量传给脚本，并原样转给 Kotlin 监听器。
     */
    fun fire(event: LifecycleEvent, sessionId: String, detail: String = "") {
        for (listener in listeners) {
            try {
                listener.onEvent(event, sessionId, detail)
            } catch (t: Throwable) {
                Log.w(TAG, "listener failed for ${event.wire}: ${t.message}")
            }
        }
        val snapshot = config
        if (!snapshot.enabled) return
        val command = snapshot.commands[event]?.trim().orEmpty()
        if (command.isEmpty()) return
        val appContext = app
        if (appContext == null) {
            lastRun[event] = "skipped: not initialized"
            return
        }
        val scope = (appContext as? com.openminis.app.MinisApp)?.appCoroutineScopes?.io ?: fallbackScope
        val runner = scriptRunnerForTest
        scope.launch {
            val script = buildScript(event, sessionId, detail, command)
            val startedAt = System.currentTimeMillis()
            try {
                // [GH#131] Production runs the script as a one-shot sandbox
                // process; the injected runner exists only for JVM tests.
                val (exitCode, output) = if (runner != null) {
                    runner(script, TIMEOUT_MS)
                } else {
                    val result = com.openminis.app.sandbox.ExecutionCoordinator.execute(
                        sessionId = GUEST_SESSION_ID,
                        command = script,
                        timeout = TIMEOUT_MS,
                    )
                    result.exitCode to result.output
                }
                val duration = System.currentTimeMillis() - startedAt
                val tail = output.trim().takeLast(2000)
                if (exitCode == 0) {
                    lastRun[event] = "ok (${duration}ms)"
                    AppLogger.info(TAG, "${event.wire} hook ok (${duration}ms) session=$sessionId")
                } else {
                    lastRun[event] = "exit=$exitCode (${duration}ms)"
                    AppLogger.warning(
                        TAG,
                        "${event.wire} hook failed: exit=$exitCode session=$sessionId\n" +
                            tail.ifBlank { "(no output)" },
                    )
                }
            } catch (t: Throwable) {
                lastRun[event] = "failed: ${t.javaClass.simpleName}: ${t.message}"
                AppLogger.warning(TAG, "${event.wire} hook crashed: ${t.message}")
            }
        }
    }

    /**
     * 前置环境变量后执行用户命令。值用单引号包裹并转义，避免事件/会话 id 里
     * 的字符被 shell 解释（会话 id 是 UUID，但 detail 可能是任意文本）。
     */
    internal fun buildScript(
        event: LifecycleEvent,
        sessionId: String,
        detail: String,
        command: String,
    ): String = buildString {
        append("export MINIS_HOOK_EVENT=").append(shellQuote(event.wire)).append('\n')
        append("export MINIS_HOOK_SESSION_ID=").append(shellQuote(sessionId)).append('\n')
        append("export MINIS_HOOK_DETAIL=").append(shellQuote(detail)).append('\n')
        append(command.trim())
    }

    private fun shellQuote(value: String): String =
        "'" + value.replace("'", "'\\''") + "'"

    private fun persist(context: Context, next: Config): String? {
        val obj = JSONObject().put("enabled", next.enabled)
        for ((event, command) in next.commands) {
            if (command.isBlank()) continue
            obj.put(event.wire, command)
        }
        val ok = context.applicationContext
            .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY, obj.toString())
            .commit()
        if (!ok) return "Failed to persist lifecycle hook config"
        config = next
        return null
    }

    private fun load(context: Context): Config {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY, "") ?: ""
        if (raw.isBlank()) return Config()
        return runCatching {
            val obj = JSONObject(raw)
            val commands = LinkedHashMap<LifecycleEvent, String>()
            for (event in LifecycleEvent.entries) {
                val command = obj.optString(event.wire, "").trim()
                if (command.isNotEmpty()) commands[event] = command
            }
            Config(enabled = obj.optBoolean("enabled", false), commands = commands)
        }.getOrElse { Config() }
    }
}
