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

/** Long-running PRoot shell: commands via stdin, output captured via unique end-of-command markers, so env/cwd/packages/services persist across commands in a session. */
class PersistentShell(
    private val context: Context,
    private val sessionId: String,
    internal val sessionBindMounts: Map<String, String>,  // linuxPath -> hostPath
) {

    companion object {
        private const val MAX_OUTPUT_CHARS = 128 * 1024
        private const val TAG = "PersistentShell"

        /** [T-android-shell-death-diagnosability] Head holds proot's pre-dump error line(s); tail shows how output ended. */
        private const val OUTPUT_HEAD_MAX = 1024
        private const val OUTPUT_TAIL_MAX = 2048

    }

    @Volatile
    private var process: Process? = null

    @Volatile
    private var stdinWriter: BufferedWriter? = null
    /** [T-shell-env-writer] Writer access for the extracted env/PATH helpers. */
    internal fun stdinWriterOrNull(): BufferedWriter? = stdinWriter

    /** Serializes stdin writes: concurrent writers would splice two commands into one line and corrupt both. */
    private val stdinLock = Any()
    /** [T-shell-env-writer] Lock access for the extracted env/PATH helpers. */
    internal val stdinWriteLock: Any get() = stdinLock

    private val isStarting = AtomicBoolean(false)

    /** Pending command callback — only one command at a time. */
    @Volatile
    private var pendingCallback: ShellCommandCallback? = null

    /**
     * [GH#358] Channel generation. Bumped on every spawn / timeout reset / stop.
     *
     * 中文：旧 reader 线程在 reset 后仍可能活着，并把旧命令的字节投递到新命令的
     * pendingCallback（新 framer 之前没有 BEGIN 就能收下这些字节），或者把新
     * 回调当成自己进程的“shell died”结束掉。generation 让 reader 只能投递给
     * 同一代创建的回调：跨代字节一律丢弃，跨代完成一律跳过。
     */
    private val channelGeneration = java.util.concurrent.atomic.AtomicLong(0L)

    /**
     * [GH#358] True when the LAST command was stopped by THIS shell's watchdog.
     *
     * 中文：`exit 124` 是 timeout 工具的通用退出码，用户命令自己 exit 124 并不是
     * 工具超时；执行入口只认这个内部标志来丢弃通道 / 标 TIMEOUT，而不是裸看
     * 124，也不解析命令输出里的 `[Command timed out after …]` 文本（用户命令
     * 可以自己 echo 它，见 [ShellChannelRecovery]）。由 [executeCommand] 清理、
     * [consumeTimedOut] 消费。
     */
    @Volatile
    private var lastCommandTimedOut = false

    /**
     * [GH#358] Read-and-clear the internal timeout verdict of the command that
     * just returned. Must be called by the executor immediately after
     * [executeCommand] so the next command cannot inherit a stale verdict.
     */
    fun consumeTimedOut(): Boolean {
        val value = lastCommandTimedOut
        lastCommandTimedOut = false
        return value
    }

    val isAlive: Boolean
        get() = process?.isAlive == true

    /** 行回调的限速/CR 处理（拆分自本文件，见 [ShellLineFeeder]）。 */
    private val lineFeeder = ShellLineFeeder()

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
                // [T-android-seccomp-selfheal / GH#186] The dynamically linked guest /bin/sh
                // can die during dynamic linking on an affected kernel - the shell never
                // comes up. One retry with PROOT_NO_SECCOMP=1, same narrow conditions as
                // one-shot commands. Timed around startProcess() (sleeps 200ms): a shell
                // already dead then died essentially instantly.
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
            // [T-android-shell-spawn-outcome] A throw out of startProcess() (ENOENT on
            // proot, EACCES, EAGAIN) used to unwind with nothing written anywhere,
            // leaving the caller's generic "[Shell not running]". Record it and rethrow
            // - swallowing the cause made this failure class undiagnosable.
            com.openminis.app.logging.AppLogger.error(
                TAG,
                "spawn threw: ${t.javaClass.simpleName}: ${t.message}",
            )
            throw t
        } finally {
            isStarting.set(false)
        }
    }

    /** [T-android-seccomp-selfheal / GH#186] Sticky workaround for later respawns; without it the shell crashes-and-retries every restart, doubling startup latency. */
    @Volatile
    private var useNoSeccomp = false

    private fun startProcess() {
        Log.i(TAG, "Starting persistent shell process")

        val rootfsManager = RootfsManager.getInstance(context)

        // [T-android-shell-death-diagnosability] One-line spawn context in the FILE
        // log. "[Shell not running]" reports have had two distinct causes already;
        // loader presence is the first thing to rule out (without PROOT_LOADER,
        // SELinux kills proot on 10+, a25d93f7).
        com.openminis.app.logging.AppLogger.info(
            TAG,
            "spawn ctx: proot=${rootfsManager.prootBinary.exists()} " +
                "rootfs=${File(rootfsManager.rootfsDir, "bin/busybox").exists()} " +
                "loader64=${PRootKernel.prootLoaderPath.isNotEmpty()} " +
                "loader32=${PRootKernel.prootLoader32Path.isNotEmpty()}",
        )
        // Fresh capture per process incarnation — a respawned shell must not
        // report its predecessor's dying output as its own.
        deathTailStore.reset()
        lastExitCode = null
        utf8 = Utf8ChunkDecoder()
        // [GH#358] New process = new reader generation. Any reader thread still
        // draining the previous process is now stale and cannot deliver its
        // bytes to callbacks created for this incarnation.
        val generation = channelGeneration.incrementAndGet()
        // [T-offload-prelude-source-once] A brand-new bash has no offload
        // functions defined; force the next command to re-source the prelude.
        sourcedOffloadSignature = null

        // Shared proot prefix (formerly a hand-rolled copy — see
        // PRootKernel.commonArgv). Same flags, same order; the per-session
        // bind snapshot and the native-offload socket are included.
        val cmd = commonArgv(
            prootPath = rootfsManager.prootBinary.absolutePath,
            rootfsDir = rootfsManager.rootfsDir.absolutePath,
            bindMounts = sessionBindMounts,
            includeSys = true,
            includeNativeOffload = true,
        )

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

        // [T-shell-stall-yes-head] SIGPIPE unblocked in the child; see SpawnSignalMask.
        val p = SpawnSignalMask.start(processBuilder)
        process = p
        SandboxWorkload.track(p, "shell:$sessionId")
        com.openminis.app.service.ActiveRunRegistry.registerProcess(sessionId, p)
        val activeRun = com.openminis.app.service.ActiveRunContext.current()
        activeRun?.registerProcess(p) { stop() }
        stdinWriter = BufferedWriter(OutputStreamWriter(p.outputStream, StandardCharsets.UTF_8))

        // Start background reader thread
        Thread({
            readLoop(p, generation)
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

        // [T-android-shell-spawn-outcome] Record the spawn OUTCOME, not just the
        // attempt: "starting" followed by silence cannot distinguish a healthy
        // proot from one that died in the 200ms window. FILE log, not logcat -
        // that is what a user can actually send us.
        val alive = isAlive
        val exit = runCatching { process?.exitValue() }.getOrNull()
        val early = deathTailStore.headPreview(300)
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

    /** [T-android-shell-death-diagnosability] premature-death capture（拆分：见 [ShellDeathTail]）。 */
    private val deathTailStore = ShellDeathTail()

    private fun appendTail(text: String) = deathTailStore.append(text)

    fun deathTail(): String = deathTailStore.capture()

    /** Exit code of the dead shell process, when known. */
    @Volatile
    var lastExitCode: Int? = null
        private set

    /** One decoder per process so a UTF-8 sequence split across reads survives. */
    private var utf8 = Utf8ChunkDecoder()

    private fun dispatchDecoded(text: String, generation: Long, endOfInput: Boolean = false) {
        // [GH#358] Cross-generation byte gate. After a timeout/reset/stop the old
        // reader thread can still be draining the dead process; those bytes must
        // never enter the NEW command's framer (they carry the OLD marker and
        // command output). The generation is captured by the reader when its
        // process was started, so a mismatch means "you are reading a shell the
        // channel has already abandoned".
        val currentGeneration = channelGeneration.get()
        if (generation != currentGeneration) {
            Log.d(
                TAG,
                "dropping stale reader bytes generation=$generation current=$currentGeneration",
            )
            return
        }
        val cb = pendingCallback ?: return
        if (cb.generation != generation) return
        if (text.isEmpty() && !endOfInput) return
        val step = cb.framer.push(text, endOfInput)
        if (step.output.isNotEmpty()) {
            cb.appendOutput(step.output)
            cb.lineCallback?.let { lineFeeder.feed(step.output, it) }
        }
        if (step.completed && pendingCallback === cb) {
            cb.onComplete?.invoke(cb.output.toString(), step.exitCode)
            if (pendingCallback === cb) pendingCallback = null
        }
    }

    private fun readLoop(p: Process, generation: Long) {
        val decoder = utf8
        try {
            val buffer = ByteArray(4096)
            val stream = p.inputStream
            while (true) {
                val n = stream.read(buffer)
                if (n < 0) break
                val text = decoder.decode(buffer, n)
                if (text.isNotEmpty()) appendTail(text)
                dispatchDecoded(text, generation = generation)
            }
            val tail = decoder.finish()
            if (tail.isNotEmpty()) appendTail(tail)
            dispatchDecoded(tail, generation = generation, endOfInput = true)
        } catch (e: Exception) {
            Log.d(TAG, "Reader loop ended: ${e.message}")
        }

        // Process exited. Only complete a callback that belongs to THIS reader's
        // generation — a reset may have installed a callback for the replacement
        // shell, and completing it here would end the new command with the old
        // process's death notice ("shell died before the command finished").
        val staleReader = generation != channelGeneration.get()
        if (staleReader) {
            Log.d(TAG, "stale reader for generation=$generation ended; not touching pending state")
        } else {
            val cb = pendingCallback
            if (cb != null && cb.generation == generation && pendingCallback === cb) {
                cb.onComplete?.invoke(
                    cb.output.toString() + "\n[shell died before the command finished; later clauses did not run]",
                    -1,
                )
                if (pendingCallback === cb) pendingCallback = null
            }
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

    /**
     * Execute a command in the persistent shell and wait for completion.
     *
     * Wraps the command with a unique marker to detect output boundaries:
     *   {command}; echo "__MINIS_DONE_{marker}_EXIT_$?__"
     *
     * @return Pair of (output, exitCode)
     */
    /**
     * [T-offload-prelude-source-once] Offload reply-parsing shell functions,
     * sourced into the persistent bash. This used to run `file.readText()` on
     * the rootfs and re-source ~25 functions on EVERY command. The shell is
     * long-lived and reused, so the functions persist once loaded — the only
     * thing that invalidates them is a change in the registered handler set
     * (app update / new handler). So the body is built, the script file written,
     * and the `. source` emitted ONLY when the signature changes; every other
     * command returns "" and prepends nothing.
     *
     * [sourcedOffloadSignature] resets on respawn (a new bash has no functions),
     * so the next command after a restart re-sources. The offload-exit reporting
     * the prelude exists for (the `__MINIS_OFFLOAD_RC` / handler_timeout mapping)
     * keeps working unchanged: the wrapper functions are in the shell exactly as
     * before, just installed once instead of per command.
     */
    @Volatile
    private var sourcedOffloadSignature: String? = null

    private fun offloadExitPrelude(): String {
        val outcome = OffloadShellPrelude.ensure(
            rootfsDir = RootfsManager.getInstance(context).rootfsDir,
            lastSignature = sourcedOffloadSignature,
        )
        if (outcome == null) {
            // 没有已注册 handler：清空签名，下次有 handler 时重新 source。
            if (NativeOffloadServer.registeredHandlers.isEmpty()) sourcedOffloadSignature = null
            return ""
        }
        sourcedOffloadSignature = outcome.second
        return outcome.first
    }

    suspend fun executeCommand(
        command: String,
        // [T-timeout-honored] May SHORTEN the armed wall clock, never
        // extend it: armed = min(timeout, class wall). The class ceiling
        // stays the authority; the caller gets to bound its own wait.
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
        // [GH#358] Each command starts with a clean verdict; only THIS command's
        // watchdog branch (or resetAfterTimeout) may set it.
        lastCommandTimedOut = false

        val writer = stdinWriter
        if (writer == null || !isAlive) {
            // [stop=kill] No live channel: never let a stale user-kill flag
            // label the eventual replacement shell's first result.
            killedByUser = false
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
        // [T-timeout-honored] The caller may shorten the class wall but never
        // extend it, and the setup floor survives. The guest watchdog below
        // gets the SAME effective wall so the in-guest `sleep` and the host
        // withTimeoutOrNull agree on when to fire.
        val armed = BudgetClassifier.armMs(budget, command, timeout)
        if (timeout > 0L && armed < budget.wallMs) {
            Log.i(TAG, "caller timeout ${timeout}ms shortens class ${budget.workClass} wall ${budget.wallMs}ms → ${armed}ms")
        }
        val marker = UUID.randomUUID().toString().take(8)
        // [T-android-ghost-cwd] A long-lived shell whose cwd was deleted stays on
        // the dead inode for the rest of the session; heal it before the next
        // command. `$PWD` (a shell variable) cannot itself fail with ENOENT in the
        // very state it detects.
        val wrappedCommand = buildString {
            append(offloadExitPrelude())
            // A previous command, or the user's own script, may have turned on
            // `set -e`. That would abort the rest of a compound command and
            // skip the done marker, so the caller sees a cut-off with no code.
            append("set +e\n")
            // [T-android-stale-stream-gate] First thing the wrapper writes. Bytes the
            // pty still holds from BEFORE this command arrive in this read window; the
            // framer drops everything before BEGIN (one command's result once
            // re-delivered under four later tool-call ids because those bytes were
            // never gated off).
            append("echo \"__MINIS_GO_${marker}__\"\n")
            // Supervisor re-arm. Not a subshell around the command, not a trap,
            // not a second ulimit. On expiry `kill -TERM -$$` kills the group.
            append(GuardianScript.persistentCommand(command, (armed / 1000L).toInt().coerceAtLeast(1)))
            append("__minis_rc=\$?\n")
            append("kill -KILL \${__minis_cmd_wd:-} 2>/dev/null\n")
            append("if [ ! -d \"\$PWD\" ]; then cd /var/minis/workspace 2>/dev/null || cd / ; fi\n")
            append("echo \"__MINIS_DONE_${marker}_EXIT_\${__minis_rc}__\"\n")
        }

        return withContext(Dispatchers.IO) {
            SandboxWorkload.armDeadline(process, armed)
            try {
            val result = withTimeoutOrNull(armed) {
                coroutineScope {
                val cb = ShellCommandCallback(
                    marker = marker,
                    // [GH#358] Bind the callback to the current channel generation;
                    // the reader validates it before delivering any bytes.
                    generation = channelGeneration.get(),
                    lineCallback = lineCallback,
                )
                pendingCallback = cb
                val beat = if (lineCallback != null) {
                    launchSilentHeartbeat(
                        cb.heartbeat,
                        { pendingCallback === cb },
                    ) { line -> lineFeeder.emitLine(lineCallback, line) }
                } else {
                    null
                }
                try {
                suspendCancellableCoroutine { cont ->
                    cb.onComplete = { output, exitCode ->
                        beat?.cancel()
                        if (cb.output.truncated) {
                            Log.w(TAG, "output truncated, dropped ${cb.output.dropped} chars")
                        }
                        // [T-android-stale-stream-gate] Make dropped pre-BEGIN
                        // bytes observable: the sample is the fingerprint of
                        // whatever emitted stale bytes into the stream.
                        if (cb.framer.preBeginDroppedChars() > 0) {
                            Log.w(
                                TAG,
                                "pre-begin bytes dropped: ${cb.framer.preBeginDroppedChars()} " +
                                    "sample=${cb.framer.preBeginDroppedSample().take(160).replace('\n', '|')}",
                            )
                        }
                        if (cont.isActive) {
                            cont.resume(Pair(output, exitCode))
                        }
                    }

                    cont.invokeOnCancellation {
                        beat?.cancel()
                        if (pendingCallback === cb) pendingCallback = null
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

            if (result == null) {
                // The guest shell reads the next command only after the previous
                // one exits. Leaving it alive makes the timeout a no-op and lets
                // the old marker/output land on the next command.
                Log.w(TAG, "command timed out after ${armed / 1000}s; killing shell tree")
                // [stop=kill] A timeout is never a user kill: a Stop that landed
                // right at the wall must not leak its flag onto this verdict.
                killedByUser = false
                // [GH#358] Set the internal verdict BEFORE the reset clears the
                // callback: callers must distinguish this real watchdog timeout
                // from a user command that merely exited 124. The marker shown to
                // the model stays the same; the flag is what drives channel
                // discard and the tool's TIMEOUT status.
                lastCommandTimedOut = true
                // [GH#358] Reset the channel, not just the process: the reader
                // thread may still hold a pending callback, stdin may still be
                // buffered, and a half-started replacement must not make
                // ensureStarted() wait on a stale isStarting flag. Fields reset:
                // pendingCallback / stdinWriter / process / isStarting /
                // sourcedOffloadSignature / lastExitCode (logged in the method).
                resetAfterTimeout()
                Pair("[Command timed out after ${armed / 1000}s; guest process group killed]", 124)
            } else {
                // [stop=kill] Head-label the killed command's result for the
                // model; a normally-exiting command in the stop race consumes
                // the flag without being labelled.
                applyKilledByUserMarker(result)
            }
            } finally {
                SandboxWorkload.clearDeadline(process)
            }
        }
    }

    /** Apply a full environment snapshot to the running shell. */
    /**
     * [GH#358] Reclaim the per-session shell channel after a command timeout.
     *
     * 中文：命令超时后显式重置挂起回调、stdin、进程、启动标志等状态，
     * 让下一次 shell_execute 能重建并正常执行，而不是永久排队。
     *
     * The reported failure was that one timed-out command permanently wedged
     * that session: all later `shell_execute` calls hung while a parallel
     * session kept working. The per-session mutex is released by
     * `ExecutionCoordinator` either way, so the stuck state has to be the
     * shell object itself. This resets every field that can hold a stale
     * command:
     *
     *  - [pendingCallback] — the reader/framer may not have observed the
     *    cancellation yet; leaving it set makes the next command's output
     *    match the old marker.
     *  - [stdinWriter] — closed so a blocked `write()` in the timed-out caller
     *    unwedges and a later command can never splice onto the dead pipe.
     *  - [process] — killed and cleared so `isAlive` is false and
     *    `ensureStarted()` must spawn a fresh shell.
     *  - [isStarting] — a killed spawn must not leave waiters spinning in
     *    `ensureStarted()`'s `while (isStarting && !isAlive)` loop.
     *  - [sourcedOffloadSignature] — the replacement bash has none of the
     *    offload wrapper functions; force the next command to re-source them.
     *  - [lastExitCode] — recorded as 124 so a dead-channel report names the
     *    timeout instead of an unrelated earlier exit.
     *  - [channelGeneration] — bumped FIRST, so any reader still draining the
     *    killed process is stale and cannot feed its bytes to the next command's
     *    callback or complete it as "shell died" ([GH#358] reader race).
     *
     * Idempotent and safe to call after [stop].
     */
    fun resetAfterTimeout() {
        // [GH#358] Invalidate the old reader BEFORE clearing pendingCallback: a
        // reader that already passed its generation check is still stopped from
        // touching the next command's state because the check is re-read at
        // dispatch time.
        channelGeneration.incrementAndGet()
        pendingCallback = null
        runCatching { stdinWriter?.close() }
        stdinWriter = null
        runCatching { process?.destroyForcibly() }
        process = null
        isStarting.set(false)
        sourcedOffloadSignature = null
        lastExitCode = 124
        // [GH#358] Internal verdict for the executor; consumeTimedOut() clears it.
        // Kept after the generation bump so a caller that reads it right after
        // executeCommand() returns sees "true", while the next executeCommand()
        // starts by clearing it.
        lastCommandTimedOut = true
        com.openminis.app.logging.AppLogger.warning(
            TAG,
            "[GH#358] shell channel reset after timeout: pendingCallback=null " +
                "stdinWriter=null process=null isStarting=false sourcedOffload=nil exit=124 " +
                "generation=${channelGeneration.get()}",
        )
    }

    /**
     * Stop the persistent shell.
     *
     * [T-android-stop-jank] The caller is the Stop button's thread: the old
     * order did the whole harvest inline — SandboxWorkload.release walks /proc
     * for the process tree (3 passes), then `waitFor(500ms)` blocked on the
     * dying process — so a tap on Stop froze the UI for the better part of a
     * second per live shell. Now the waiting command is completed FIRST (the
     * UI unblocks at once), the kill signal goes out synchronously, and the
     * expensive reaping runs on a daemon thread. Nothing after the signal
     * needs the caller to wait: the shell object is already unregistered by
     * the coordinator before stop() runs, so no new command can reach it.
     */
    fun stop() {
        val p = process
        val cb = pendingCallback
        // [GH#358] Invalidate this incarnation's reader before completing the
        // callback: a reader that is still draining must not race the "stopped"
        // completion or deliver its remaining bytes to a later callback.
        channelGeneration.incrementAndGet()
        // 1. Complete the pending command so the caller's coroutine resumes
        //    immediately — the user-visible "stopped" feedback.
        if (cb != null && pendingCallback === cb) {
            cb.onComplete?.invoke(
                cb.output.toString() + "\n[shell died before the command finished; later clauses did not run]",
                -1,
            )
            if (pendingCallback === cb) pendingCallback = null
        }
        // 2. Signal the death synchronously: closing stdin wakes the guest
        //    shell, destroyForcibly() delivers SIGKILL without waiting.
        try { stdinWriter?.close() } catch (_: Exception) {}
        if (process === p) stdinWriter = null
        p?.destroyForcibly()
        if (process === p) process = null
        if (p == null) {
            Log.i(TAG, "Persistent shell stopped")
            return
        }
        // 3. Reap off the caller thread: tree kill, registry unregistration and
        //    the exit-code wait. Daemon so an app teardown never blocks on it.
        Thread({
            SandboxWorkload.release(p, kill = true, reason = "shell-stop:$sessionId")
            com.openminis.app.service.ActiveRunRegistry.unregisterProcess(sessionId, p)
            com.openminis.app.service.ActiveRunContext.current()?.unregisterProcess(p)
            val exit = runCatching { p.waitFor(2, java.util.concurrent.TimeUnit.SECONDS) }
                .getOrDefault(false)
            if (exit) lastExitCode = runCatching { p.exitValue() }.getOrNull()
            Log.i(TAG, "Persistent shell stopped")
        }, "PersistentShell-reap").apply { isDaemon = true; start() }
    }

    /** [stop=kill] Armed by [interruptRunningCommand]; consumed on the next result. */
    @Volatile
    private var killedByUser = false

    /**
     * [stop=kill] Kill the RUNNING foreground command's tree from the host
     * side while this shell stays alive. The pending callback completes on
     * its own (guest bash falls through the `__minis_rc=$?` tail, DONE marker
     * echoes the signal exit 137) — no channel reset, cwd/env/packages
     * survive. Returns true when a command was actually pending.
     */
    fun interruptRunningCommand(): Boolean {
        val root = process
        val hadPending = pendingCallback != null
        if (root == null || !hadPending) return false // stop epoch alone gates the next command
        // Flag BEFORE the kill: the completion must not race ahead of the
        // marker it is supposed to explain.
        killedByUser = true
        val victims = SandboxWorkload.killCommandTree(root, "user-stop")
        Log.w(TAG, "[stop-tool] running command killed by user: victims=${victims.size}")
        return true
    }

    /**
     * Read-and-clear the user-kill verdict for the command that just
     * returned. Only a signal-range exit counts (guest SIGKILL 137, shell
     * death -1): a command that exited normally in the stop race must not
     * be labelled, and 124 stays the timeout's own code.
     */
    private fun consumeKilledByUser(exitCode: Int): Boolean {
        val flagged = killedByUser
        killedByUser = false
        return flagged && ShellStopKill.isSignalExit(exitCode)
    }

    private fun applyKilledByUserMarker(result: Pair<String, Int>): Pair<String, Int> =
        Pair(ShellStopKill.markedOutput(consumeKilledByUser(result.second), result.first), result.second)
}
