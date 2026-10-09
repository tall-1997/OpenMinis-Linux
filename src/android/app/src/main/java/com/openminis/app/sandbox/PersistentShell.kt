package com.openminis.app.sandbox

import android.content.Context
import android.util.Log
import com.openminis.app.sandbox.kernel.BudgetClassifier
import com.openminis.app.sandbox.kernel.GuardianScript
import com.openminis.app.sandbox.kernel.ProcessBudget
import com.openminis.app.sandbox.kernel.TokenBucket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedWriter
import java.io.File
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

/**
 * A long-running PRoot shell process that persists across commands.
 * Agent commands are sent via stdin and output is captured using unique
 * end-of-command markers, similar to iOS ISHKernel.executeCommandAndWait.
 *
 * This ensures that environment variables, working directory, installed
 * packages, and running services all persist between commands within the
 * same session.
 */
class PersistentShell(
    private val context: Context,
    private val sessionId: String,
    private val sessionBindMounts: Map<String, String>,  // linuxPath -> hostPath
) {

    companion object {
        private const val MAX_OUTPUT_CHARS = 128 * 1024
        private const val TAG = "PersistentShell"

        /**
         * [T-android-shell-death-diagnosability] Death-capture windows. The
         * head must comfortably hold proot's error line(s) printed BEFORE the
         * multi-KB talloc leak dump; the tail shows how the output ended.
         */
        private const val OUTPUT_HEAD_MAX = 1024
        private const val OUTPUT_TAIL_MAX = 2048
    }

    @Volatile
    private var process: Process? = null

    @Volatile
    private var stdinWriter: BufferedWriter? = null

    private val isStarting = AtomicBoolean(false)

    /** Pending command callback — only one command at a time. */
    @Volatile
    private var pendingCallback: CommandCallback? = null

    /**
     * [T-p1-3-shell-auth-frames] 会话帧认证秘密：guest 侧**未导出**的 shell 变量，
     * 首条命令的 bootstrap 行生成（`$RANDOM`×4 ≈ 60bit），并以
     * `__MINIS_AUTH_<hex>__` 回显一次。它从不过 stdin 之外的通道，也从不进入
     * 子进程 environ（未 export）——竞争读取 stdin 的后台进程（`cat &` 继承
     * fd 0）偷得到命令文本，偷不到秘密，因此**伪造不出合法的 GO/DONE 帧**。
     * null = 尚未握手；握手失败一率降级 legacyFraming（保持旧行为，不破坏可用性）。
     */
    @Volatile
    private var sessionAuth: String? = null

    /** [T-p1-3-shell-auth-frames] 握手失败后的永久降级：帧退回无标签旧格式。 */
    @Volatile
    private var legacyFraming = false

    /**
     * [T-android-apply-env-race] stdin 写互斥。executeCommand 的写走
     * ExecutionCoordinator 的 per-session mutex，applyEnvironment 原本不经过它——
     * TZ/proxy 广播恰逢命令执行时，两个非线程安全的 BufferedWriter 写交错会损坏
     * 命令流（wrappedCommand 可超 PIPE_BUF，写不是原子的）。shell 自身的写锁兜住
     * 所有直接写 stdin 的路径。用对象监视器而非协程 Mutex：executeCommand 的写
     * 发生在 suspendCancellableCoroutine 的非挂起 lambda 里，那里进不了挂起锁。
     */
    private val stdinLock = Any()

    val isAlive: Boolean
        get() = process?.isAlive == true

    /** [diag] Read back the mount this shell was started with (frozen at boot). */
    fun debugBindMount(linuxPath: String): String? = sessionBindMounts[linuxPath]

    /** Copy of the binds this process was started with. */
    fun bindSnapshot(): Map<String, String> = sessionBindMounts.toMap()

    private class CommandCallback(
        val marker: String,
        val output: BoundedOutputBuffer = BoundedOutputBuffer(),
        val lineCallback: ((String) -> Unit)?,
        var onComplete: ((String, Int) -> Unit)? = null,
    ) {
        val framer = MarkerFramer(marker)
        val heartbeat = SilentOutputHeartbeat()
        fun appendOutput(text: String) {
            if (text.isNotEmpty()) heartbeat.onOutput()
            output.append(text)
        }
    }

    /** lineCallback may come from the reader thread and the heartbeat job. */
    private val lineCallbackLock = Any()

    private fun emitLine(callback: (String) -> Unit, line: String) {
        synchronized(lineCallbackLock) { callback(line) }
    }

    /**
     * Ensure the persistent shell process is running.
     * Idempotent — returns immediately if already alive.
     */
    suspend fun ensureStarted() {
        if (isAlive) return
        if (!isStarting.compareAndSet(false, true)) {
            // Another coroutine is already starting — wait for it
            while (isStarting.get() && !isAlive) {
                kotlinx.coroutines.delay(50)
            }
            return
        }

        try {
            withContext(Dispatchers.IO) {
                // [T-android-seccomp-selfheal / GH#186] The guest /bin/sh is
                // itself a dynamically linked binary, so on an affected kernel
                // it can die during dynamic linking before it ever reads a
                // command — the shell simply never comes up, which surfaces to
                // the user as "[Shell not running] (exit code: -1)". Give it
                // one retry with PROOT_NO_SECCOMP=1 under the same narrow
                // conditions used for one-shot commands.
                //
                // Timed around startProcess() (which sleeps 200ms before
                // returning): if the shell is already dead when it returns, it
                // died during that window, i.e. essentially instantly.
                val spawnedAt = System.currentTimeMillis()
                startProcess()
                val aliveMs = System.currentTimeMillis() - spawnedAt

                if (!useNoSeccomp && !isAlive) {
                    val exit = runCatching { process?.exitValue() }.getOrNull() ?: lastExitCode
                    if (SeccompFallbackPolicy.shouldRetryWithoutSeccomp(
                            exitCode = exit,
                            durationMs = aliveMs,
                            producedOutput = false,
                            alreadyRetried = false,
                        )
                    ) {
                        com.openminis.app.logging.AppLogger.warning(
                            TAG,
                            SeccompFallbackPolicy.retryLogLine(exit, aliveMs, "persistent shell startup"),
                        )
                        // Sticky for this shell's lifetime: every later respawn
                        // keeps the workaround instead of rediscovering it.
                        useNoSeccomp = true
                        startProcess()
                        if (isAlive) {
                            com.openminis.app.logging.AppLogger.warning(
                                TAG,
                                "[proot-retry] persistent shell came up with " +
                                    "${SeccompFallbackPolicy.NO_SECCOMP_ENV}=1 — this device " +
                                    "needs the seccomp workaround (GH#186)",
                            )
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            // [T-android-shell-spawn-outcome] Previously swallowed: the only
            // handler here was `finally { isStarting.set(false) }`, so a throw
            // out of startProcess() (ProcessBuilder.start() IOException —
            // ENOENT on the proot binary, EACCES, EAGAIN under memory
            // pressure) unwound with NOTHING written anywhere. The caller then
            // saw isAlive == false and reported the generic "[Shell not
            // running]", which is exactly the dead end the field reports keep
            // hitting. Record it and rethrow — swallowing the cause is what
            // made this class of failure undiagnosable.
            com.openminis.app.logging.AppLogger.error(
                TAG,
                "spawn threw: ${t.javaClass.simpleName}: ${t.message}",
            )
            throw t
        } finally {
            isStarting.set(false)
        }
    }

    /**
     * [T-android-seccomp-selfheal / GH#186] Once the seccomp workaround proves
     * necessary on this device, keep it for every subsequent respawn of this
     * shell. Without stickiness the shell would crash-and-retry on every
     * restart, doubling startup latency for exactly the users already hitting
     * the bug.
     */
    @Volatile
    private var useNoSeccomp = false

    private fun startProcess() {
        Log.i(TAG, "Starting persistent shell process")

        val rootfsManager = RootfsManager.getInstance(context)

        // [T-android-shell-death-diagnosability] One-line spawn context in the
        // FILE log. Field reports of "[Shell not running] (exit code: -1)" have
        // had two distinct causes already (missing proot ELF loaders after
        // bc2566b2, and at least one custom-ROM death we couldn't classify),
        // and a user log that only says "process exited" cannot tell them
        // apart. Loader presence is the first thing to rule out: without
        // PROOT_LOADER, proot bare-execve's rootfs busybox and SELinux kills
        // it on Android 10+ (see a25d93f7).
        com.openminis.app.logging.AppLogger.info(
            TAG,
            "spawn ctx: proot=${rootfsManager.prootBinary.exists()} " +
                "rootfs=${File(rootfsManager.rootfsDir, "bin/busybox").exists()} " +
                "loader64=${PRootKernel.prootLoaderPath.isNotEmpty()} " +
                "loader32=${PRootKernel.prootLoader32Path.isNotEmpty()}",
        )
        // Fresh capture per process incarnation — a respawned shell must not
        // report its predecessor's dying output as its own.
        synchronized(outputTail) {
            outputHead.setLength(0)
            outputTail.setLength(0)
            outputTotal = 0
        }
        lastExitCode = null
        utf8 = Utf8ChunkDecoder()

        val cmd = mutableListOf<String>()
        cmd.add(rootfsManager.prootBinary.absolutePath)
        cmd.add("-0")
        // T141: see PRootKernel.buildProotCommand for rationale — translates
        // hardlinks to symlinks so apk install of binutils/gcc works.
        cmd.add("--link2symlink")
        // So destroyForcibly on timeout also reaps the guest command, not only proot.
        cmd.add("--kill-on-exit")
        cmd.add("-r")
        cmd.add(rootfsManager.rootfsDir.absolutePath)
        cmd.add("-b"); cmd.add("/dev")
        cmd.add("-b"); cmd.add("/proc")
        cmd.add("-b"); cmd.add("/sys")
        cmd.add("-w"); cmd.add("/root")

        // Apply this session's bind mounts (session-specific, not global)
        for ((linuxPath, hostPath) in sessionBindMounts) {
            cmd.add("-b")
            cmd.add("$hostPath:$linuxPath")
        }

        val handlers = NativeOffloadServer.registeredHandlers
        if (handlers.isNotEmpty()) {
            cmd.add("--native-offload=${NativeOffloadServer.socketName}:${handlers.joinToString(",")}")
        }

        cmd.add("/bin/bash")
        cmd.add("-c")
        // Ulimits and the session watchdog are injected once, then exec
        // replaces this bash with the long-lived shell. Later commands are
        // not wrapped: a subshell would drop cwd and exports, and an EXIT
        // trap would kill the pipe reader.
        cmd.add(
            GuardianScript.persistentBoot(
                BudgetClassifier.setup(),
                (com.openminis.app.sandbox.ShellTimeoutPolicy.SETUP_WALL_MS / 1000L).toInt(),
            ),
        )

        val debugOffload = com.openminis.app.BuildConfig.DEBUG

        val processBuilder = ProcessBuilder(cmd)
        // In debug builds we want proot's native_offload stderr logs in
        // logcat, not merged into shell stdout (which would break the
        // __MINIS_DONE__ marker detection). Release keeps the original
        // merged behavior so no stderr output is lost silently.
        processBuilder.redirectErrorStream(!debugOffload)

        val env = processBuilder.environment()
        env["PROOT_TMP_DIR"] = PRootKernel.getProotTmpDir(context).absolutePath
        if (PRootKernel.nativeLibDir.isNotEmpty()) {
            env["LD_LIBRARY_PATH"] = PRootKernel.nativeLibDir
        }
        if (PRootKernel.prootLoaderPath.isNotEmpty()) {
            env["PROOT_LOADER"] = PRootKernel.prootLoaderPath
        }
        if (PRootKernel.prootLoader32Path.isNotEmpty()) {
            env["PROOT_LOADER_32"] = PRootKernel.prootLoader32Path
        }
        env["TERM"] = "dumb"
        env["PS1"] = ""  // Suppress prompt to avoid polluting output
        // Timezone: customEnvironment["TZ"] is seeded at PRootKernel.boot(),
        // but refresh it here in case the system timezone changed between boot
        // and now.
        env["TZ"] = PRootKernel.guestTz()
        if (debugOffload) env["MINIS_NOFF_DEBUG"] = "1"
        // T340: forward the chat session id to native_offload handlers via
        // proot env. NativeOffloadServer reads this off `request.env` and
        // hands it to OffloadPermissionManager so ASK_ONCE grants/denials
        // are scoped per chat session, not globally.
        env["MINIS_CHAT_SESSION_ID"] = ExecutionCoordinator.ownerSessionId(sessionId)

        for ((key, value) in PRootKernel.customEnvironment) {
            env[key] = value
        }

        // [T-android-seccomp-selfheal / GH#186] Applied last so nothing above
        // can clobber it once this device is known to need it.
        if (useNoSeccomp) {
            env[SeccompFallbackPolicy.NO_SECCOMP_ENV] = SeccompFallbackPolicy.NO_SECCOMP_VALUE
        }

        val p = processBuilder.start()
        process = p
        SandboxWorkload.track(p, "shell:$sessionId")
        com.openminis.app.service.ActiveRunRegistry.registerProcess(sessionId, p)
        val activeRun = com.openminis.app.service.ActiveRunContext.current()
        activeRun?.registerProcess(p) { stop() }
        stdinWriter = BufferedWriter(OutputStreamWriter(p.outputStream, StandardCharsets.UTF_8))
        // [T-p1-3-shell-auth-frames] 新进程 = 新 shell = 旧的未导出秘密已死。
        // 不重置的话，respawn 后的帧标签仍按旧秘密计算，新 shell 永远配不上帧，
        // 每条命令都超时自杀——这是本机制最危险的失效模式，必须在 spawn 点清零。
        sessionAuth = null
        legacyFraming = false

        // Start background reader thread
        Thread({
            readLoop(p)
        }, "PersistentShell-reader").apply {
            isDaemon = true
            start()
        }

        // In debug, drain stderr separately into logcat.
        if (debugOffload) {
            Thread({
                val br = p.errorStream.bufferedReader(StandardCharsets.UTF_8)
                try {
                    for (line in br.lineSequence()) Log.d("PRootStderr", line)
                } catch (_: Exception) {}
            }, "PersistentShell-stderr").apply {
                isDaemon = true
                start()
            }
        }

        // Wait briefly for shell to initialize
        try {
            Thread.sleep(200)
        } catch (_: InterruptedException) {}

        // [T-android-shell-spawn-outcome] Record the OUTCOME of the spawn, not
        // just that one was attempted. "Starting persistent shell process"
        // followed by silence is the signature every field report has, and it
        // cannot distinguish "proot is running fine" from "proot exited during
        // the 200ms window" — the two cases that lead to completely different
        // investigations. Logged to the FILE log (AppLogger, not Log.i) because
        // that is what a user can actually send us; logcat is gone by then.
        val alive = isAlive
        val exit = runCatching { process?.exitValue() }.getOrNull()
        val early = synchronized(outputTail) { outputHead.toString().take(300) }
        com.openminis.app.logging.AppLogger.info(
            TAG,
            "spawn outcome: alive=$alive" +
                (if (exit != null) " exit=$exit" else "") +
                " loader=${PRootKernel.prootLoaderPath.isNotEmpty()}" +
                " noSeccomp=$useNoSeccomp" +
                (if (early.isNotEmpty()) " early=${early.replace('\n', '|')}" else ""),
        )
        Log.i(TAG, "Persistent shell started")
    }

    /**
     * [T-android-shell-death-diagnosability] Captured shell output for
     * premature-death reporting, including chunks that arrive with no pending
     * callback — which readLoop previously discarded outright. In release
     * builds stderr is merged into stdout (redirectErrorStream), so proot's
     * dying words land exactly in that discarded window.
     *
     * HEAD + rolling TAIL, not tail-only. The first crDroid field log proved
     * tail-only insufficient: proot prints its one-line cause FIRST ("proot
     * error: …") and then talloc_enable_leak_report() dumps a multi-KB
     * allocation tree at exit — which evicted the cause and left us staring at
     * HandlerEntry leak rows. The head is where the answer lives; the tail
     * still shows how it ended.
     */
    private val outputHead = StringBuilder()
    private val outputTail = StringBuilder()
    private var outputTotal = 0

    private fun appendTail(text: String) {
        synchronized(outputTail) {
            outputTotal += text.length
            if (outputHead.length < OUTPUT_HEAD_MAX) {
                outputHead.append(text.take(OUTPUT_HEAD_MAX - outputHead.length))
            }
            outputTail.append(text)
            val over = outputTail.length - OUTPUT_TAIL_MAX
            if (over > 0) outputTail.delete(0, over)
        }
    }

    /**
     * Captured output (trimmed) for premature-death reporting: the head (where
     * proot's error line lives), plus the tail when output outgrew the head
     * window, with the elided middle marked.
     */
    fun deathTail(): String = synchronized(outputTail) {
        val head = outputHead.toString().trim()
        if (outputTotal <= OUTPUT_HEAD_MAX) return head // head holds everything
        // Tail buffer starts at byte (outputTotal - tail.length); the head
        // covers [0, OUTPUT_HEAD_MAX). Drop any overlap from the tail so the
        // two windows concatenate without duplication.
        val overlap = OUTPUT_HEAD_MAX - (outputTotal - outputTail.length)
        val tail = outputTail.substring(overlap.coerceIn(0, outputTail.length)).trim()
        if (tail.isEmpty()) return head
        val elided = (-overlap).coerceAtLeast(0)
        val sep = if (elided > 0) "\n…[$elided bytes elided]…\n" else "\n"
        "$head$sep$tail"
    }

    /** Exit code of the dead shell process, when known. */
    @Volatile
    var lastExitCode: Int? = null
        private set

    /** One decoder per process so a UTF-8 sequence split across reads survives. */
    private var utf8 = Utf8ChunkDecoder()

    private fun dispatchDecoded(text: String, endOfInput: Boolean = false) {
        val cb = pendingCallback ?: return
        if (text.isEmpty() && !endOfInput) return
        val step = cb.framer.push(text, endOfInput)
        if (step.output.isNotEmpty()) {
            cb.appendOutput(step.output)
            cb.lineCallback?.let { feedLines(step.output, it) }
        }
        if (step.completed && pendingCallback === cb) {
            cb.onComplete?.invoke(cb.output.toString(), step.exitCode)
            if (pendingCallback === cb) pendingCallback = null
        }
    }

    private fun readLoop(p: Process) {
        val decoder = utf8
        try {
            val buffer = ByteArray(4096)
            val stream = p.inputStream
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                val text = decoder.decode(buffer, n)
                if (text.isNotEmpty()) appendTail(text)
                dispatchDecoded(text)
            }
            val tail = decoder.finish()
            if (tail.isNotEmpty()) appendTail(tail)
            dispatchDecoded(tail, endOfInput = true)
        } catch (e: Exception) {
            Log.d(TAG, "Reader loop ended: ${e.message}")
        }

        // Process exited. Only complete the callback this loop still owns —
        // a timeout may already have cleared it and started a new shell.
        val cb = pendingCallback
        if (cb != null && pendingCallback === cb) {
            cb.onComplete?.invoke(
                cb.output.toString() + "\n[shell died before the command finished; later clauses did not run]",
                -1,
            )
            if (pendingCallback === cb) pendingCallback = null
        }

        // [T-android-shell-death-diagnosability] Name the cause in the FILE
        // log. exitValue distinguishes death classes (signal deaths are
        // 128+n: 132=SIGILL, 139=SIGSEGV, 159=SIGSYS/seccomp), and the tail
        // carries proot's own error line in release builds. Without these,
        // a field log reads "started → exited" 25ms apart and is
        // unactionable — precisely the shape of the crDroid report.
        val exit = runCatching { p.waitFor() }.getOrNull()
        val tail = deathTail()
        com.openminis.app.logging.AppLogger.error(
            TAG,
            "shell process exited code=$exit" +
                (if (tail.isNotEmpty()) " capture=${tail.take(1200)}" else " capture=(no output)"),
        )

        // A timeout may already have spawned the replacement shell. Do not
        // clear that process just because this reader thread is exiting.
        if (process === p) {
            lastExitCode = exit
            process = null
            stdinWriter = null
        }
        Log.i(TAG, "Persistent shell process exited")
    }

    // [T-line-budget-sync] The byte budget was raised 64→512 KiB/s
    // (61a2d77) but this line bucket stayed at 20 lines/s, so build logs'
    // REAL-TIME progress was still throttled ~25x below the byte budget
    // (final tool output is unaffected — StreamSink.snapshot() keeps the
    // full buffer to its cap). 250 lines/s ≈ 200 KiB/s of typical log text,
    // inside the byte budget, with burst headroom for progress meters.
    private val lineBudget = TokenBucket(ratePerSec = 250.0, burst = 500)

    private fun feedLines(text: String, callback: (String) -> Unit) {
        if (!lineBudget.tryTake(System.currentTimeMillis())) return
        val lines = text.split('\n')
        for (i in lines.indices) {
            // CR overwrites a progress meter. Keep the latest non-empty
            // segment so "50%\r51%\r" becomes "51%", not "5051".
            val line = lines[i].split('\r').lastOrNull { it.isNotEmpty() }.orEmpty()
            if (line.isNotEmpty() && (i < lines.size - 1 || text.endsWith('\n'))) {
                emitLine(callback, line)
            } else if (line.isNotEmpty() && i == lines.size - 1) {
                // Partial line — still feed it for real-time updates
                emitLine(callback, line)
            }
        }
    }

    /**
     * Execute a command in the persistent shell and wait for completion.
     *
     * Wraps the command with a unique marker to detect output boundaries:
     *   {command}; echo "__MINIS_DONE_{marker}_EXIT_$?__"
     *
     * @return Pair of (output, exitCode)
     */
    private fun offloadExitPrelude(): String {
        val names = NativeOffloadServer.registeredHandlers
            .filter { it.matches(Regex("[A-Za-z0-9._+-]+")) }
            .sorted()
        if (names.isEmpty()) return ""
        val body = buildString {
            appendLine("minis_offload_wrap() {")
            appendLine("  local name=\"\$1\"")
            appendLine("  shift")
            appendLine("  local out rc first")
            appendLine("  out=\$(command \"\$name\" \"\$@\" 2>&1)")
            appendLine("  rc=\$?")
            appendLine("  first=\$(printf '%s\\n' \"\$out\" | head -n 1)")
            appendLine("  case \"\$first\" in")
            appendLine("    __MINIS_OFFLOAD_RC=*__)")
            appendLine("      rc=\${first#__MINIS_OFFLOAD_RC=}")
            appendLine("      rc=\${rc%__}")
            appendLine("      out=\$(printf '%s\\n' \"\$out\" | tail -n +2)")
            appendLine("      ;;")
            appendLine("  esac")
            appendLine("  printf '%s\\n' \"\$out\"")
            appendLine("  case \"\$out\" in")
            appendLine("    *handler_timeout*) return 124 ;;")
            appendLine("  esac")
            appendLine("  return \$rc")
            appendLine("}")
            for (name in names) {
                append(name).append("() { minis_offload_wrap ").append(name).appendLine(" \"\$@\"; }")
            }
        }
        val file = File(RootfsManager.getInstance(context).rootfsDir, "etc/minis-offload-exit.sh")
        runCatching {
            if (!file.isFile || file.readText() != body) {
                file.parentFile?.mkdirs()
                file.writeText(body)
            }
        }.onFailure { return "" }
        return ". /etc/minis-offload-exit.sh 2>/dev/null || true\n"
    }

    suspend fun executeCommand(
        command: String,
        // Ignored. Armed timeout comes from [callerBudget] (or, when absent,
        // from BudgetClassifier.classify(command)).
        timeout: Long = 600_000L,
        lineCallback: ((String) -> Unit)? = null,
        // [T-resource-class-threaded] The budget the CALLER already computed,
        // including the tool's `resource_class`. Re-deriving it here from the
        // command text alone used the 1-arg classify overload, whose
        // `resourceClass` defaults to AUTO — so `heavy` was silently dropped,
        // this layer armed the NORMAL wall, and because the tighter of two
        // disagreeing layers is what actually fires, `resource_class = heavy`
        // had no effect on the timeout at all. Two log lines per command
        // recorded the disagreement ("caller timeout … ignored") from both
        // sides. Defaulted to null so every existing caller still compiles.
        callerBudget: ProcessBudget? = null,
    ): Pair<String, Int> {
        ensureStarted()

        val writer = stdinWriter
        if (writer == null || !isAlive) {
            // [T-android-shell-death-diagnosability] Surface WHY the shell is
            // down, not just that it is. The exit code and proot's own last
            // words (captured by outputTail; stderr is merged into stdout in
            // release) turn "[Shell not running]" from a dead end into a
            // self-describing report — both for the agent reading the tool
            // result and for the screenshot a user sends us.
            val exit = lastExitCode
            val tail = deathTail()
            val detail = buildString {
                append("[Shell not running]")
                if (exit != null) append(" proot exit=$exit")
                if (tail.isNotEmpty()) append("\n${tail.take(300)}")
            }
            return Pair(detail, -1)
        }

        val budget = callerBudget ?: BudgetClassifier.classify(command)
        val armed = budget.wallMs
        if (timeout != armed) {
            Log.w(TAG, "caller timeout ${timeout}ms ignored; armed ${armed}ms class=${budget.workClass}")
        }
        // [T-p1-3-shell-auth-frames] 帧标签：认证建立后 marker = "<nonce>-<tag>"，
        // tag = cksum(nonce + 会话秘密)。秘密只存在于 guest 的未导出变量里，wrapper
        // 在 guest 侧现算标签——竞争读者（`cat &` 偷 stdin）偷不到秘密，伪造的
        // GO/DONE 永远配不上 framer 的字面匹配。握手完成前 / 降级时保持旧格式。
        val auth = if (legacyFraming) null else sessionAuth
        val nonce = UUID.randomUUID().toString().take(8)
        val tagCompute: String
        val marker: String
        if (auth != null) {
            val tag = PosixCksum.cksum(nonce + auth).toString()
            // 标签在 guest 侧重算一次（nonce 是本条命令的新值；秘密不出现在
            // 命令文本里，出现在未导出变量的引用中）。
            tagCompute = "__minis_tag=\$(printf '%s' '$nonce'\"\$__minis_auth\" | cksum | cut -d' ' -f1)\n"
            marker = "$nonce-$tag"
        } else {
            tagCompute = ""
            marker = nonce
        }
        // [T-p1-3-shell-auth-frames] 首条命令先握手：guest 生成未导出的
        // __minis_auth 并回显一次（字节落在 GO 之前的 pre-BEGIN 窗口，由
        // MarkerFramer 捕获）。秘密从不过 stdin 之外的通道；握手结果在命令
        // 结束后回填 sessionAuth。
        val bootstrap = if (sessionAuth == null && !legacyFraming) {
            // printf %05d 定宽拼接：恰好 20 位数字，长度确定（framer 的 AUTH
            // 正则与跨读 hold 都依赖可预期形状；$RANDOM 裸拼可能短于正则下限）。
            "if [ -z \"\${__minis_auth:-}\" ]; then " +
                "__minis_auth=\$(printf '%05d%05d%05d%05d' \$RANDOM \$RANDOM \$RANDOM \$RANDOM); " +
                "echo \"__MINIS_AUTH_\${__minis_auth}__\"; fi\n"
        } else {
            ""
        }
        // [T-android-ghost-cwd] Heal the shell's cwd before handing control back
        // to the next command. This shell is long-lived, so a `cd` into a
        // directory that a later command deletes leaves EVERY following command
        // running inside a deleted inode: `pwd` prints the dead path and
        // `ls`/`os.getcwd()` fail, and it never repairs itself for the rest of
        // the session (the bug report had to kill the app). `$PWD` is used
        // instead of `pwd` on purpose: it is a shell variable, so the probe
        // cannot itself fail with ENOENT in the very state it detects.
        val wrappedCommand = buildString {
            append(bootstrap)
            append(offloadExitPrelude())
            // A previous command, or the user's own script, may have turned on
            // `set -e`. That would abort the rest of a compound command and
            // skip the done marker, so the caller sees a cut-off with no code.
            append("set +e\n")
            // [T-android-stale-stream-gate] First thing the wrapper writes.
            // Everything the pty still holds from BEFORE this command — the
            // previous command's late watchdog-kill notification, a late
            // offload response, a replayed payload — arrives in THIS command's
            // read window; the framer drops it all before BEGIN and keeps a
            // bounded sample for ShellExecDiag. Field evidence (2026-10-04):
            // one command's full result re-delivered under four different
            // later tool-call ids because those bytes were never gated off.
            //
            // [T-p1-3-shell-auth-frames] 认证建立后 GO/DONE 的 marker 携带
            // guest 侧现算的帧标签（__minis_tag），字面匹配天然拒绝伪造帧。
            append(tagCompute)
            append("echo \"__MINIS_GO_${marker}__\"\n")
            // Supervisor re-arm. Not a subshell around the command, not a trap,
            // not a second ulimit. On expiry `kill -TERM -$$` kills the group.
            append(GuardianScript.persistentCommand(command, budget.wallSeconds))
            append("__minis_rc=\$?\n")
            append("kill -KILL \${__minis_cmd_wd:-} 2>/dev/null\n")
            append("if [ ! -d \"\$PWD\" ]; then cd /var/minis/workspace 2>/dev/null || cd / ; fi\n")
            append("echo \"__MINIS_DONE_${marker}_EXIT_\${__minis_rc}__\"\n")
        }

        return withContext(Dispatchers.IO) {
            SandboxWorkload.armDeadline(process, armed)
            // [T-p1-3-shell-auth-frames] 握手对账：本条命令若带了 bootstrap，命令
            // 结束（或超时——AUTH 字节先于 GO 到达，超时也可能已捕获）后把 framer
            // 看到的秘密回填 sessionAuth；始终握不上则永久降级 legacy 帧。
            val expectedAuth = bootstrap.isNotEmpty()
            var authSeen: String? = null
            var cb: CommandCallback? = null
            try {
            val result = withTimeoutOrNull(armed) {
                coroutineScope {
                val callback = CommandCallback(
                    marker = marker,
                    lineCallback = lineCallback,
                )
                cb = callback
                pendingCallback = callback
                val beat = if (lineCallback != null) {
                    launchSilentHeartbeat(
                        callback.heartbeat,
                        { pendingCallback === callback },
                    ) { line -> emitLine(lineCallback, line) }
                } else {
                    null
                }
                try {
                suspendCancellableCoroutine { cont ->
                    callback.onComplete = { output, exitCode ->
                        beat?.cancel()
                        if (callback.output.truncated) {
                            Log.w(TAG, "output truncated, dropped ${callback.output.dropped} chars")
                        }
                        // [T-android-stale-stream-gate] Make dropped pre-BEGIN
                        // bytes observable: the sample is the fingerprint of
                        // whatever emitted stale bytes into the stream.
                        if (callback.framer.preBeginDroppedChars() > 0) {
                            Log.w(
                                TAG,
                                "pre-begin bytes dropped: ${callback.framer.preBeginDroppedChars()} " +
                                    "sample=${callback.framer.preBeginDroppedSample().take(160).replace('\n', '|')}",
                            )
                        }
                        callback.framer.authTag?.let { authSeen = it }
                        if (cont.isActive) {
                            cont.resume(Pair(output, exitCode))
                        }
                    }

                    cont.invokeOnCancellation {
                        beat?.cancel()
                        if (pendingCallback === callback) pendingCallback = null
                        // Parent cancel has the same serialization bug as timeout.
                        stop()
                    }

                    try {
                        synchronized(stdinLock) {
                            writer.write(wrappedCommand)
                            writer.flush()
                        }
                    } catch (e: Exception) {
                        beat?.cancel()
                        pendingCallback = null
                        if (cont.isActive) {
                            cont.resume(Pair("[Write error: ${e.message}]", -1))
                        }
                    }
                }
                } finally {
                    beat?.cancel()
                }
                }
            }
            authSeen = authSeen ?: cb?.framer?.authTag

            if (result == null) {
                // The guest shell reads the next command only after the previous
                // one exits. Leaving it alive makes the timeout a no-op and lets
                // the old marker/output land on the next command.
                Log.w(TAG, "command timed out after ${armed / 1000}s; killing shell tree")
                stop()
                Pair("[Command timed out after ${armed / 1000}s; guest process group killed]", 124)
            } else {
                result
            }
            } finally {
                // [T-p1-3-shell-auth-frames] 握手回填/降级判定在两条出路（完成/超时）
                // 上都要走：AUTH 字节先于 GO 到达，超时命令也可能已完成握手。
                if (expectedAuth) {
                    val seen = authSeen
                    if (seen != null && sessionAuth == null) {
                        sessionAuth = seen
                        com.openminis.app.logging.AppLogger.info(
                            TAG,
                            "shell frame auth established (session=$sessionId)",
                        )
                    } else if (seen == null && sessionAuth == null && !legacyFraming) {
                        legacyFraming = true
                        com.openminis.app.logging.AppLogger.warning(
                            TAG,
                            "shell frame auth handshake failed — falling back to legacy unauthenticated framing (session=$sessionId)",
                        )
                    }
                }
                SandboxWorkload.clearDeadline(process)
            }
        }
    }

    /**
     * Apply environment variables to the running shell.
     *
     * The shell is long-lived and reused across commands, so a stale `export`
     * from a previous turn lingers until something overwrites it. Caller can
     * supply [previousKeys] — the set of variable names injected on the prior
     * call — so any name absent from the new [envVars] is `unset` first. That
     * gives whole-snapshot semantics matching the per-command isolation iOS
     * gets for free with one `/bin/sh` process per command.
     *
     * Empty default for [previousKeys] preserves the original behaviour for
     * system broadcasts (TZ, proxy) that are only meant to overlay specific
     * keys, never wipe the user's env-var snapshot.
     */
    suspend fun applyEnvironment(
        envVars: Map<String, String>,
        previousKeys: Set<String> = emptySet(),
    ) {
        if (!isAlive) return
        val writer = stdinWriter ?: return
        withContext(Dispatchers.IO) {
            try {
                // [T-android-apply-env-race] 与 executeCommand 的 stdin 写共用
                // shell 自身写锁：广播恰逢命令执行时两个非线程安全的写交错会
                // 损坏命令流（wrappedCommand 可超 PIPE_BUF）。
                synchronized(stdinLock) {
                    for (key in previousKeys - envVars.keys) {
                        writer.write("unset $key\n")
                    }
                    for ((key, value) in envVars) {
                        // Escape single quotes in values
                        val escaped = value.replace("'", "'\\''")
                        writer.write("export $key='$escaped'\n")
                    }
                    writer.flush()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to apply env vars: ${e.message}")
            }
        }
    }

    /**
     * Stop the persistent shell.
     */
    fun stop() {
        val p = process
        val cb = pendingCallback
        try { stdinWriter?.close() } catch (_: Exception) {}
        if (process === p) stdinWriter = null
        SandboxWorkload.release(p, kill = true, reason = "shell-stop:$sessionId")
        if (p != null) com.openminis.app.service.ActiveRunRegistry.unregisterProcess(sessionId, p)
        if (p != null) com.openminis.app.service.ActiveRunContext.current()?.unregisterProcess(p)
        p?.destroyForcibly()
        runCatching { p?.waitFor(500, java.util.concurrent.TimeUnit.MILLISECONDS) }
        if (process === p) process = null
        if (cb != null && pendingCallback === cb) {
            cb.onComplete?.invoke(
                cb.output.toString() + "\n[shell died before the command finished; later clauses did not run]",
                -1,
            )
            if (pendingCallback === cb) pendingCallback = null
        }
        Log.i(TAG, "Persistent shell stopped")
    }
}
