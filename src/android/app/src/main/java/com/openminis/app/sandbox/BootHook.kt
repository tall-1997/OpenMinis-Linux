package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import com.openminis.app.MinisApp
import com.openminis.app.logging.AppLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * [GH#511] 沙箱 boot hook：rootfs 就绪后异步执行用户脚本 `/var/minis/boot.sh`。
 *
 * ## 语义
 *
 * - **只执行一次 / 进程**：PRoot 在 Minis 里是“每条命令一个进程”，没有常驻
 *   内核；[PRootKernel.boot] 成功（[PRootKernel.isBooted] = true）就是“rootfs
 *   可用”的唯一信号。hook 在这个点之后异步拉起，绝不阻塞 boot 本身。
 * - **超时不拖启动**：脚本在独立协程里跑，默认 30s 上限；超时/非零退出只写
 *   日志与 [lastStatus]，不影响聊天 / shell 的可用性。
 * - **失败可见**：结果写入 AppLogger（设置 → 日志可见）与 [lastStatus]；
 *   boot 失败本身由 boot 日志负责，hook 失败不会再往 UI 抛异常。
 * - **[GH#511] 显式开启**：master switch 默认关闭（[BootHookSettings]）。关闭
 *   时 hook 完全不执行，只记一行“disabled by user”日志。脚本本身会被保留；
 *   用户可在 Settings → Agent Runtime → Lifecycle Hooks 打开。
 * - **[GH#511] 来源审计**：首次观察时间与内容指纹记入 AppLogger，脚本被
 *   agent/用户改写时下次启动留下 warning。
 * - **路径**：脚本必须位于 rootfs 内的 `/var/minis/boot.sh`。`/var/minis` 下
 *   的 `skills` / `shared` / `mcp-servers` 是 host bind mount，但 `/var/minis`
 *   根目录本身不是 —— 所以 boot.sh 是 rootfs 里的真实文件，随 rootfs 持久化。
 *
 * ## 为什么脚本里不要用 flock / 不要依赖 /proc（重要）
 *
 * 1. **/proc 跨会话不可见**：每次 `shell_execute` 都是一次新的 proot 进程，
 *    看到的是宿主内核的 /proc，而不是“上一次命令”的 /proc。脚本里
 *    `pgrep` / `ls /proc/<pid>` 找不到另一条命令启动的进程，也无法通过
 *    /proc/<pid>/fd 与别的会话共享状态。需要跨调用协调时，用文件锁之外的
 *    显式信号（例如写一个状态文件并检查内容），不要假定能看到对方的进程。
 * 2. **flock 不可靠**：proot 的 ptrace 注入会改变 fd 关闭/继承时机，flock 在
 *    某些 proot 版本上会被静默忽略或立即释放；多个会话并发执行 boot hook
 *    时它并不能保证互斥。真正的互斥点应在 Kotlin 侧（本对象的
 *    [AtomicBoolean] / 单协程），脚本内部不要依赖 flock 做关键区。
 *
 * 扩展点：需要多个 hook 时，在这里按行读取一个清单文件（例如
 * `/var/minis/boot.d/*.sh` 排序执行），执行器与日志契约保持不变。
 */
object BootHook {

    private const val TAG = "BootHook"

    /** rootfs 内的约定路径。 */
    const val GUEST_PATH = "/var/minis/boot.sh"

    /** hook 的墙钟上限；boot hook 是“锦上添花”，不允许长期占用沙箱资源。 */
    private const val TIMEOUT_MS = 30_000L

    /** 每个进程只跑一次（boot 本身有 mutex，这里再兜一层重复调用）。 */
    private val started = java.util.concurrent.atomic.AtomicBoolean(false)

    /** 最近一次 hook 的结果，供诊断/未来设置页展示；null = 尚未运行。 */
    @Volatile
    var lastStatus: String? = null
        private set

    /**
     * 由 [PRootKernel.boot] 在 `isBooted = true` 之后调用。非 suspend：立刻返回，
     * 脚本在独立 IO 协程里执行。
     *
     * [GH#511] 提权面收口：
     *  - `/var/minis/boot.sh` 在 agent 可写的 rootfs 内，执行它等于把“写文件”
     *    升级成“跨启动静默执行”。因此必须有 [BootHookSettings] 的显式 master
     *    switch（默认关）；关闭时只记日志，不碰脚本。
     *  - 执行前重新检查文件存在 / 开关仍开着，避免 probe 与 exec 之间被替换。
     *  - 首次观察时间 + 内容指纹写入 AppLogger，脚本被改写时留下 warning。
     */
    fun launch(context: Context, rootfsDir: File) {
        if (!started.compareAndSet(false, true)) {
            Log.d(TAG, "boot hook already launched for this process")
            return
        }
        val appContext = context.applicationContext
        if (!BootHookSettings.isEnabled(appContext)) {
            lastStatus = "skipped: disabled in Settings"
            AppLogger.info(
                TAG,
                "boot hook skipped: disabled by user (Settings → Agent Runtime → Lifecycle Hooks); " +
                    "$GUEST_PATH was not executed",
            )
            return
        }
        val script = File(rootfsDir, "var/minis/boot.sh")
        if (!script.isFile) {
            lastStatus = "skipped: $GUEST_PATH not present"
            Log.d(TAG, lastStatus!!)
            return
        }
        auditScript(script, appContext)
        val app = appContext as? MinisApp
        val scope: CoroutineScope = app?.appCoroutineScopes?.io
            ?: CoroutineScope(SupervisorJob() + Dispatchers.IO)
        scope.launch {
            // [GH#511] Re-check the switch and the file immediately before the
            // exec: the state can change between launch() and this coroutine,
            // and "user just disabled it" must win over a queued run.
            if (!BootHookSettings.isEnabled(appContext)) {
                lastStatus = "skipped: disabled before execution"
                AppLogger.info(TAG, "boot hook cancelled: disabled before execution")
                return@launch
            }
            if (!script.isFile) {
                lastStatus = "skipped: $GUEST_PATH disappeared before execution"
                AppLogger.warning(TAG, "boot hook cancelled: $GUEST_PATH no longer exists")
                return@launch
            }
            AppLogger.info(
                TAG,
                "boot hook starting: $GUEST_PATH size=${script.length()} " +
                    "modified=${script.lastModified()} (enabled by user)",
            )
            // Guard against a hook that deletes/replaces the script between the
            // isFile probe and execution: the guest command is the contract.
            val result = try {
                ShellExecutor.execute(
                    context = appContext,
                    command = "bash $GUEST_PATH",
                    timeout = TIMEOUT_MS,
                    // boot hooks are lightweight setup scripts; AUTO keeps them
                    // out of the heavy-build budget unless the command really is.
                    resourceClass = SandboxResourceGate.ResourceClass.AUTO,
                    ownerKey = "boot-hook",
                )
            } catch (t: Throwable) {
                lastStatus = "failed: ${t.javaClass.simpleName}: ${t.message}"
                AppLogger.error(TAG, "boot hook crashed: ${t.message}")
                return@launch
            }
            val output = result.output.trim()
            val tail = if (output.length > 4000) output.takeLast(4000) else output
            if (result.exitCode == 0) {
                lastStatus = "ok (${result.durationMs}ms)"
                AppLogger.info(TAG, "boot hook finished: $lastStatus")
            } else {
                lastStatus = "exit=${result.exitCode} (${result.durationMs}ms)"
                AppLogger.warning(
                    TAG,
                    "boot hook failed: $lastStatus\n${tail.ifBlank { "(no output)" }}",
                )
            }
            if (tail.isNotEmpty()) {
                // Output is diagnostic, not state: keep it at debug level so a
                // chatty hook cannot flood the visible log page.
                Log.d(TAG, "boot hook output:\n$tail")
            }
        }
    }

    /**
     * [GH#511] Record first-seen time / fingerprint changes for the boot script.
     * Not a security boundary (same app uid can rewrite the prefs), but it puts
     * "this script appeared / changed at boot N" into the visible log instead
     * of leaving silent cross-launch execution.
     */
    private fun auditScript(script: File, context: Context) {
        val fingerprint = "${script.length()}|${script.lastModified()}"
        val now = System.currentTimeMillis()
        val firstSeen = BootHookSettings.firstSeenAt(context)
        if (firstSeen == null) {
            BootHookSettings.setFirstSeenAt(context, now)
            AppLogger.info(TAG, "boot hook script first observed at $now (fingerprint=$fingerprint)")
        }
        val previous = BootHookSettings.lastScriptFingerprint(context)
        if (previous != null && previous != fingerprint) {
            AppLogger.warning(
                TAG,
                "boot hook script changed since the last run: $previous -> $fingerprint; " +
                    "it will still run because the user enabled the hook",
            )
        }
        BootHookSettings.setLastScriptFingerprint(context, fingerprint)
    }

    /** 测试入口：允许同一个 JVM 里重复验证。 */
    internal fun resetForTest() {
        started.set(false)
        lastStatus = null
    }
}
