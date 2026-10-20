package com.openminis.app.sandbox

import com.openminis.app.data.repository.MCPToolPolicy

import android.content.Context
import android.util.Log
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.notification.SandboxNotifyActions
import com.openminis.app.sandbox.SandboxResourceGate
import com.openminis.app.sandbox.kernel.BudgetClassifier
import com.openminis.app.util.LogRedaction
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Manages per-session persistent shell processes.
 *
 * Architecture:
 * - PRootKernel (rootfs + proot binary): global singleton, booted once
 * - PersistentShell: one per sessionId, owns its bind mounts and /bin/sh process
 * - Per-session Mutex: different sessions can run commands concurrently
 * - ConcurrentHashMap: thread-safe shell/mutex registry
 *
 * Concurrency guarantees:
 * - Same session: commands are serialized by the per-session Mutex
 * - Different sessions: run concurrently (each has its own Mutex)
 * - Shell creation: protected by globalLock to prevent duplicate shells
 * - Shell death: detected on next command, shell is recreated with same bind mounts
 */
object ExecutionCoordinator {

    private const val TAG = "ExecutionCoordinator"
    private const val LANE_PREFIX = "subagent:"

    data class CommandResult(
        val output: String,
        val exitCode: Int,
        val durationMs: Long,
        /**
         * [T-android-shell-process-budget] A <system-reminder> for the model
         * when the command waited in [GlobalShellThrottle]'s queue; null when
         * it started at once. Kept apart from [output] so the caller can put
         * it after everything else it appends (exit status, redaction).
         */
        val queueNote: String? = null,
    )

    private var appContextRef: Context? = null
    private val appContext: Context get() = checkNotNull(appContextRef) { "ExecutionCoordinator.init must be called first" }
    var envVarRepository: EnvVarRepository? = null

    /** Thread-safe per-session shell registry. */
    private val shells = ConcurrentHashMap<String, PersistentShell>()

    /** [T-android-shell-fresh-process] Live fresh-process commands per session. */
    private val freshShells = ConcurrentHashMap<String, MutableSet<FreshProcessShell>>()

    /** [T-android-fresh-shells-concurrent] Thread-safe per-session set for [freshShells]. */
    private fun newFreshShellSet(): MutableSet<FreshProcessShell> = ConcurrentHashMap.newKeySet()

    /** Thread-safe per-session mutex registry. */
    private val mutexes = ConcurrentHashMap<String, Mutex>()

    /**
     * [T-android-a11y-helper-mount] dispatch session id -> the session whose
     * `/var/minis` directories its shell binds (fsSessionId), recorded only
     * when they differ (a helper / sub agent sharing its parent's workspace).
     */
    private val mountedSessionIds = ConcurrentHashMap<String, String>()

    /** Which mechanism runs a shell_execute. */
    internal var executionStrategy: ShellExecutionStrategy = ShellExecutionStrategy.DEFAULT

    /**
     * Per-session snapshot of the env-var keys injected on the previous
     * `applyEnvironment` call. Used to issue `unset` for keys the user has
     * since deleted from EnvVarRepository. Long-lived shells would otherwise
     * keep the stale value around indefinitely.
     */
    private val lastInjectedKeys = ConcurrentHashMap<String, Set<String>>()

    /**
     * Global lock used only for shell creation to prevent duplicate shells
     * when the same session's first command arrives concurrently.
     */
    private val globalLock = Mutex()
    private val lastIdle = ConcurrentHashMap<String, Long>()
    private val evictionScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var evictionJob: Job? = null

    @Synchronized
    private fun scheduleIdleReap() {
        if (evictionJob?.isActive == true) return
        evictionJob = evictionScope.launch {
            while (shells.isNotEmpty()) {
                delay(30_000)
                reapIdleShells()
            }
        }
    }

    private suspend fun reapIdleShells() {
        // Conservative process-wide guard. A background job in ANY chat pins
        // shells until it exits. Never terminate a service to meet a cache cap.
        if (!ShellIdleSafety.canReap()) return
        val now = android.os.SystemClock.elapsedRealtime()
        val prefs = com.openminis.app.data.ToolLimitPrefs
        val idle = lastIdle.entries.sortedBy { it.value }
        var excess = (idle.size - prefs.idleShellCount()).coerceAtLeast(0)
        for ((id, timestamp) in idle) {
            if (excess <= 0 && now - timestamp < prefs.idleShellMinutes() * 60_000L) continue
            val mutex = mutexes[id] ?: continue
            if (!mutex.tryLock()) continue
            try {
                if (lastIdle[id] != timestamp || !ShellIdleSafety.canReap()) continue
                // Keep the per-session mutex: queued callers already reference it.
                globalLock.withLock {
                    shells.remove(id)?.stop()
                    lastInjectedKeys.remove(id)
                    lastIdle.remove(id)
                }
                excess--
            } finally { mutex.unlock() }
        }
    }

    fun init(context: Context) {
        appContextRef = context.applicationContext
    }

    /**
     * [T-android-a11y-helper-mount] The session id whose per-session dirs are
     * mounted at `/var/minis/...` for a command dispatched as [sessionId].
     * Defaults to [sessionId] when no helper mount was recorded.
     */
    fun mountedSessionIdFor(sessionId: String): String = mountedSessionIds[sessionId] ?: sessionId

    /**
     * A fresh process that could not be spawned ran nothing and is safe to
     * retry. Everything else - a warm shell that died, a command killed
     * mid-run - may have had effects.
     */
    internal fun isStartFailure(result: CommandResult): Boolean =
        result.exitCode == -1 && result.output.startsWith(FreshProcessShell.SPAWN_FAILED_PREFIX)

    /**
     * [T-android-shell-process-budget] Tell the model a command sat in the
     * queue, so a slow result is not read as a slow command.
     */
    internal fun queueNote(queuedMs: Long, startRetries: Int): String {
        val secs = String.format(java.util.Locale.US, "%.1f", queuedMs / 1000.0)
        val retried = if (startRetries > 0) " Its process failed to start $startRetries time(s) and was retried." else ""
        return "<system-reminder>This command was queued for ${secs}s before it started, because Android " +
            "limits how many processes an app may run at once.$retried It then ran to completion and its output " +
            "is complete, but its measured duration may be inaccurate.</system-reminder>"
    }

    /**
     * Execute a command in the session's persistent shell.
     *
     * Flow:
     * 1. Get or create per-session Mutex (thread-safe via ConcurrentHashMap)
     * 2. Acquire per-session Mutex (serializes commands within same session)
     * 3. Get or create PersistentShell (protected by globalLock on creation)
     * 4. Execute command
     */
    suspend fun execute(
        sessionId: String,
        command: String,
        // [T-timeout-honored] May SHORTEN the armed wall clock, never
        // extend it: armed = min(timeout, class wall). The class ceiling
        // stays the authority; the caller gets to bound its own wait.
        timeout: Long = 600_000L,
        lineCallback: ((String) -> Unit)? = null,
        resourceClass: SandboxResourceGate.ResourceClass = SandboxResourceGate.ResourceClass.AUTO,
        /**
         * [T-android-a11y-helper-mount] Session whose `/var/minis` dirs the
         * shell binds (a helper's PARENT). The shell process itself stays
         * keyed by [sessionId], so stop / cleanup never reach the parent's
         * shell.
         */
        fsSessionId: String? = null,
    ): CommandResult {
        MCPToolPolicy.blockedMessage(appContext, command)?.let { msg ->
            lineCallback?.invoke(msg)
            return CommandResult(output = msg, exitCode = 1, durationMs = 0)
        }
        SandboxJobKeepAlive.onStart(appContext, sessionId, command)
        try {
        // [T-android-a11y-helper-mount] Remember whose /var/minis dirs this
        // dispatch id is mounted with, so native offloads (which only learn
        // the MINIS_CHAT_SESSION_ID = dispatch id) resolve guest paths
        // against the directory the guest actually sees. See
        // [mountedSessionIdFor].
        if (fsSessionId != null && fsSessionId != sessionId) {
            mountedSessionIds[sessionId] = fsSessionId
        }

        // [T-android-shell-fresh-process] Route through the strategy.
        // FRESH_PROCESS needs no lease, no pool slot and no mutex — a command
        // that owns its own process cannot collide with another one, so the
        // serialisation the warm path requires is simply absent here.
        if (executionStrategy == ShellExecutionStrategy.FRESH_PROCESS) {
            return executeFresh(sessionId, command, timeout, lineCallback, fsSessionId)
        }
        // ConcurrentHashMap.getOrPut (the Kotlin extension) is NOT atomic — it
        // does get→compute→put, so two first-touching coroutines can both
        // construct a Mutex and the loser's callers serialise on an orphan.
        // computeIfAbsent is atomic; use it and say so (matches the same
        // pattern in SandboxResourceGate.withNamedLock).
        val mutex = mutexes.computeIfAbsent(sessionId) { Mutex() }

        return mutex.withLock {
        lastIdle.remove(sessionId)
        try {
        // [T-yoyo-root-deletion] Under YOYO the gate has already shown a
        // mustPrompt confirmation for a root deletion before the command reaches
        // this executor, so refusing it again here made that approval a dead
        // end: the user tapped through and the command still died with exit 126.
        // The guest rootfs is also rebuildable (`minis-dev-setup`), unlike a host
        // partition. Block-device writes stay refused in every mode.
        val yoyo = com.openminis.app.security.SecurityGateHolder
            .activeSessionMode(sessionId)
            .isYoyo()
        val refused = GuestWorkloadPolicy.hostRefusal(command, allowRootDeletion = yoyo)
            ?: DiskPressure.refusal(appContext, command)
            ?: run {
                // [T-memory-poison-guard] 工具路径之外的同一配额：/var/minis/memory
                // bind 的宿主目录就是本会话的 memory 目录，直接 shell 写语法在
                // 当日配额耗尽后一并拒绝（详见 GuestWorkloadPolicy KDoc）。
                val memoryHost = com.openminis.app.sandbox.SessionWorkspace.memoryDir(
                    appContext.filesDir,
                    ownerSessionId(sessionId),
                )
                GuestWorkloadPolicy.memoryQuotaRefusal(
                    command,
                    com.openminis.app.data.repository.MemoryRepository.dailyEntryCount(memoryHost),
                    com.openminis.app.data.repository.MemoryRepository.MAX_DAILY_ENTRIES,
                )
            }
        if (refused != null) {
            Log.w(TAG, "[$sessionId] refused before start: $refused")
            lineCallback?.invoke(refused)
            return@withLock CommandResult(output = refused, exitCode = 126, durationMs = 0)
        }
        SandboxResourceGate.withCommandLock(
            command,
            resourceClass = resourceClass,
            pressure = { SandboxMemoryPressure.reason(appContext) },
            limits = { SandboxMemoryPressure.executionLimits(appContext) },
            onWaiting = { lineCallback?.invoke(it) },
        ) {
            val budget = BudgetClassifier.classify(command, resourceClass)
            // [T-timeout-honored] The caller may shorten the class wall but
            // never extend it, and the setup floor always survives (a killed
            // minis-dev-setup-full leaves a broken rootfs). The old code
            // logged "caller timeout ignored" — a 60s request then rode the
            // full 1200s BATCH wall while the user watched the shell "hang".
            val armed = BudgetClassifier.armMs(budget, command, timeout)
            if (timeout > 0L && armed < budget.wallMs) {
                Log.i(
                    TAG,
                    "[$sessionId] caller timeout ${timeout}ms shortens class " +
                        "${budget.workClass} wall ${budget.wallMs}ms → ${armed}ms",
                )
            }
            val startTime = System.currentTimeMillis()

            // Route: fresh process vs. warm shell.
            // Fresh path already handled before withCommandLock.
            // This block is warm-shell execution only.
            val (rawOutput, exitCode, durationMs) = executeWarmRaw(
                sessionId = sessionId,
                command = command,
                timeout = armed,
                lineCallback = lineCallback,
                budget = budget,
            )

            // Common post-processing (shared with fresh path)
            val sanitized = TerminalSanitizer.sanitize(rawOutput)
            val (stripped, loggedExit) = TerminalSanitizer.dropOffloadTrace(sanitized)
            var effectiveExit = exitCode
            if (effectiveExit == 0 && loggedExit != null && loggedExit != 0) effectiveExit = loggedExit
            if (effectiveExit == 0 && stripped.contains("handler_timeout")) effectiveExit = 124
            val truncated = TerminalSanitizer.truncateIfNeeded(stripped)
            val output = if (effectiveExit != 0 && effectiveExit != 124) {
                "$truncated\n(exit code: $effectiveExit)"
            } else {
                truncated
            }

            CommandResult(
                output = MCPToolPolicy.filterToolsOutput(appContext, command, output),
                exitCode = effectiveExit,
                durationMs = durationMs,
            )
        }
        } finally {
            if (shells.containsKey(sessionId)) lastIdle[sessionId] = android.os.SystemClock.elapsedRealtime()
            scheduleIdleReap()
        }
        }
        } finally {
            SandboxJobKeepAlive.onEnd(appContext, sessionId)
        }
    }

    /**
     * [T-android-shell-fresh-process] Run [command] in a throwaway process
     * that owns its own pipe — no pool slot, no mutex, no warm shell. A
     * command that owns its own process cannot collide with another one, so
     * the serialisation the warm path requires is simply absent here.
     *
     * The process budget ([SandboxResourceGate.withCommandLock]) is NOT taken
     * here: a fresh process is admitted directly by [GlobalShellThrottle]
     * inside [FreshProcessShell] via the strategy's own spawn path. This
     * matches upstream, where the budget gate wraps the whole [execute] and
     * the fresh path is a leaf that the budget already admitted.
     *
     * Seccomp self-heal ([SeccompFallbackPolicy]): if the child dies on an
     * early fatal signal with no output, retry once with
     * PROOT_NO_SECCOMP=1 — the same narrow gate the warm path uses.
     */
    private suspend fun executeFresh(
        sessionId: String,
        command: String,
        timeout: Long,
        lineCallback: ((String) -> Unit)?,
        fsSessionId: String?,
    ): CommandResult {
        val startTime = System.currentTimeMillis()
        val bindMounts = buildSessionBindMounts(fsSessionId ?: sessionId)
        val envVars = envVarRepository?.allAsDict() ?: emptyMap()

        suspend fun attempt(noSeccomp: Boolean): Pair<String, Int> {
            val shell = FreshProcessShell(
                context = appContext,
                sessionId = sessionId,
                sessionBindMounts = bindMounts,
                useNoSeccomp = noSeccomp,
            )
            // [T-android-shell-fresh-process] Register before running so
            // stopCurrentCommand() can reach this command. A fresh shell is
            // owned by this call frame, unlike a pooled PersistentShell, so
            // without the registry the Stop button would have nothing to
            // kill and a running command would ignore it.
            freshShells.compute(sessionId) { _, existing ->
                (existing ?: newFreshShellSet()).also { it.add(shell) }
            }
            return try {
                shell.execute(
                    command = command,
                    timeout = timeout,
                    envVars = envVars,
                    lineCallback = lineCallback,
                )
            } finally {
                freshShells.computeIfPresent(sessionId) { _, set ->
                    set.remove(shell)
                    if (set.isEmpty()) null else set
                }
            }
        }

        var (rawOutput, exitCode) = attempt(noSeccomp = false)
        // [T-android-fresh-seccomp-selfheal / GH#186] The warm path retries
        // once with PROOT_NO_SECCOMP=1 when the child dies on an early fatal
        // signal with no output. The fresh path needs the same self-heal or
        // a GH#186 device sees every shell_execute die with 135/139.
        val firstDurationMs = System.currentTimeMillis() - startTime
        if (SeccompFallbackPolicy.shouldRetryWithoutSeccomp(
                exitCode = exitCode,
                durationMs = firstDurationMs,
                producedOutput = rawOutput.isNotEmpty(),
                alreadyRetried = false,
            )
        ) {
            com.openminis.app.logging.AppLogger.warning(
                TAG,
                SeccompFallbackPolicy.retryLogLine(exitCode, firstDurationMs, "fresh shell command"),
            )
            // No output came out of the first attempt (a precondition of the
            // retry), so reusing lineCallback cannot duplicate anything.
            val retried = attempt(noSeccomp = true)
            rawOutput = retried.first
            exitCode = retried.second
        }
        val durationMs = System.currentTimeMillis() - startTime
        val sanitized = TerminalSanitizer.sanitize(rawOutput)
        val (stripped, loggedExit) = TerminalSanitizer.dropOffloadTrace(sanitized)
        var effectiveExit = exitCode
        if (effectiveExit == 0 && loggedExit != null && loggedExit != 0) effectiveExit = loggedExit
        if (effectiveExit == 0 && stripped.contains("handler_timeout")) effectiveExit = 124
        val truncated = TerminalSanitizer.truncateIfNeeded(stripped)
        val output = if (effectiveExit != 0 && effectiveExit != 124) {
            "$truncated\n(exit code: $effectiveExit)"
        } else {
            truncated
        }
        return CommandResult(
            output = MCPToolPolicy.filterToolsOutput(appContext, command, output),
            exitCode = effectiveExit,
            durationMs = durationMs,
        )
    }

    /**
     * Warm-shell execution core — the body that runs inside the per-session
     * mutex and the process-budget gate. Returns the raw (output, exitCode,
     * durationMs) triple; the caller applies the shared post-processing.
     */
    private suspend fun executeWarmRaw(
        sessionId: String,
        command: String,
        timeout: Long,
        lineCallback: ((String) -> Unit)?,
        budget: com.openminis.app.sandbox.kernel.ProcessBudget,
    ): Triple<String, Int, Long> {
        val startTime = System.currentTimeMillis()

        // Auto-boot PRoot if not already booted
        if (!PRootKernel.isBooted) {
            Log.i(TAG, "[$sessionId] Auto-booting PRootKernel")
            PRootKernel.boot(appContext)
        }

        // [diag] trace the sessionId that shell_execute is dispatched with —
        // suspected source of the Chinese-emoji filename vanishing bug.
        // S-14: only a redacted summary (program/len/hash), never the line.
        Log.w(TAG, "[diag] execute sessionId=$sessionId ${LogRedaction.command(command)}")

        // Get or create shell — protected by globalLock to avoid duplicate creation
        val shell = getOrCreateShell(sessionId)
        recordLastCommand(sessionId, command)

        // Inject user-defined environment variables as a *full snapshot*
        // (T124a). Pass the previously-injected key set so applyEnvironment
        // can `unset` anything the user has since removed from settings;
        // otherwise the long-lived shell would keep stale values.
        val envVars = envVarRepository?.allAsDict() ?: emptyMap()
        val previousKeys = lastInjectedKeys[sessionId] ?: emptySet()
        if (envVars.isNotEmpty() || previousKeys.isNotEmpty()) {
            shell.applyEnvironment(envVars, previousKeys = previousKeys)
            lastInjectedKeys[sessionId] = envVars.keys.toSet()
        }
        // Plugin installers add command links to the guest PATH while this
        // persistent shell remains alive. Refresh both PATH and bash's
        // command hash before every command so the next command sees it.
        shell.refreshPath()

        val (rawOutput, exitCode) = shell.executeCommand(
            command = command,
            timeout = timeout,
            lineCallback = lineCallback,
            // [T-resource-class-honoured] Pass the budget this function
            // already computed. Re-deriving it downstream from the command
            // text alone silently dropped `resourceClass`.
            callerBudget = budget,
        )

        val durationMs = System.currentTimeMillis() - startTime
        return Triple(rawOutput, exitCode, durationMs)
    }

    private fun recordLastCommand(sessionId: String, command: String) {
        if (sessionId.startsWith("__") || sessionId == "self-build") return
        if (!SandboxNotifyActions.shouldRecordLastCommand(command)) return
        // [T-per-command-file-write] The only consumer of this file is the
        // background task-completion notification's RETRY action
        // (`sh /var/minis/workspace/.minis-last-cmd.sh`), which
        // BackgroundTaskNotifier posts only while "Task Notifications" is
        // enabled. With that toggle off nothing reads the file, so skip the
        // per-command write + chmod entirely. Defaults to recording when the
        // setting cannot be resolved (feature ships ON; conservative).
        val notifyEnabled = runCatching {
            (appContext.applicationContext as? com.openminis.app.MinisApp)
                ?.backgroundSettingsRepository?.taskNotificationsEnabled?.value
        }.getOrNull() ?: true
        if (!notifyEnabled) return
        val host = PRootKernel.resolveHostPath(SandboxNotifyActions.LAST_CMD_GUEST_PATH) ?: return
        val content = "#!/bin/sh\n# recorded by ExecutionCoordinator\n$command\n"
        try {
            // Skip the truncate-rewrite + setExecutable when the exact same
            // command is already recorded — a no-op rewrite is not consumed by
            // anything and the exec bit is already set on an existing file.
            if (host.isFile && host.readText() == content) return
            host.parentFile?.mkdirs()
            host.writeText(content)
            host.setExecutable(true, false)
        } catch (t: Throwable) {
            Log.w(TAG, "recordLastCommand failed: ${t.message}")
        }
    }

    /**
     * Get the existing shell for this session, or create a new one.
     * Uses globalLock to prevent two coroutines from simultaneously creating
     * a shell for the same session (e.g. if the old shell just died).
     */
    private suspend fun getOrCreateShell(sessionId: String): PersistentShell {
        // Fast path: existing alive shell
        val existing = shells[sessionId]
        if (existing != null && existing.isAlive && !shellMountsStale(existing, sessionId)) {
            Log.w(TAG, "[diag] reuse existing shell for sessionId=$sessionId attachmentsMount=${existing.debugBindMount("/var/minis/attachments")}")
            return existing
        }

        // Slow path: need to create (or recreate after crash)
        return globalLock.withLock {
            // Double-check after acquiring lock
            val recheck = shells[sessionId]
            if (recheck != null && recheck.isAlive && !shellMountsStale(recheck, sessionId)) {
                Log.w(TAG, "[diag] reuse existing shell (post-lock) for sessionId=$sessionId attachmentsMount=${recheck.debugBindMount("/var/minis/attachments")}")
                return@withLock recheck
            }

            // Shell is dead or missing — clean up and create fresh
            if (recheck != null) {
                Log.w(TAG, "[$sessionId] Shell ${if (recheck.isAlive) "mounts stale" else "died"}, recreating")
                recheck.stop()
            }

            val bindMounts = buildSessionBindMounts(sessionId)
            val shell = PersistentShell(appContext, sessionId, bindMounts)
            shells[sessionId] = shell
            shell.ensureStarted()
            Log.i(TAG, "[$sessionId] Shell created with ${bindMounts.size} bind mounts")
            Log.w(TAG, "[diag] new shell created sessionId=$sessionId attachmentsMount=${bindMounts["/var/minis/attachments"]}")
            shell
        }
    }

    fun sessionBindMounts(sessionId: String): Map<String, String> = buildSessionBindMounts(sessionId)

    internal fun hasLiveShell(sessionId: String): Boolean = shells[sessionId]?.isAlive == true

    private fun shellMountsStale(shell: PersistentShell, sessionId: String): Boolean {
        // Compare the whole argv, not only /sdcard. A user mount named sdcard,
        // a new /var/minis/mounts entry, or a filed-session hostDir change is
        // otherwise stuck on the shell that already started.
        return shell.bindSnapshot() != buildSessionBindMounts(sessionId, publish = false)
    }

    /**
     * Build bind mounts for a session:
     * - Session-level: workspace, attachments, offloads, browser → per-session dirs
     * - Global: memory, skills, shared → shared dirs across all sessions
     */
    private fun buildSessionBindMounts(sessionId: String, publish: Boolean = true): Map<String, String> {
        val filesDir = appContext.filesDir
        val mounts = linkedMapOf<String, String>()

        // [diag] previous attachments mount target — exposes cross-session
        // overwrite of the global bindMounts map (the suspected cause of
        // the "file disappears after first download" bug)
        val prevAttachments = PRootKernel.bindMounts["/var/minis/attachments"]

        // Session / project directories. Sub-agent lanes share the parent
        // session tree so files written by a teammate stay visible here.
        // Filed sessions mount attachments/workspace/offloads/browser from the
        // project folder; memory stays per-session.
        val owner = ownerSessionId(sessionId)
        SessionWorkspace.SESSION_SUBDIRS.forEach { subdir ->
            val hostDir = SessionWorkspace.hostDir(filesDir, owner, subdir).also {
                if (publish) it.mkdirs()
            }
            val linuxPath = "/var/minis/$subdir"
            mounts[linuxPath] = hostDir.absolutePath
            if (publish) PRootKernel.addBindMount(linuxPath, hostDir.absolutePath)
        }

        Log.w(TAG, "[diag] buildSessionBindMounts sessionId=$sessionId " +
            "attachments: prev=$prevAttachments new=${mounts["/var/minis/attachments"]}")

        // Global shared directories.
        // [T-android-mcp-bind-mount] mcp-servers MUST be here, not only in
        // PRootKernel.registerGlobalBindMounts: PersistentShell builds PRoot's
        // `-b` argv from THIS map, so a subdir missing here is invisible to the
        // shell that runs minis-mcp-cli — /var/minis/mcp-servers/servers.json
        // then resolves to the empty rootfs placeholder and `minis-mcp-cli list`
        // returns {"servers": [], "count": 0} even though the UI wrote the
        // server (the UI / debug.ls read via resolveHostPath, a separate map,
        // which is why they disagreed). Same trap as the external-mounts note
        // below.
        val globalBase = File(filesDir, SessionWorkspace.GLOBAL_DIR)
        SessionWorkspace.GLOBAL_BIND_SUBDIRS.forEach { subdir ->
            val hostDir = File(globalBase, subdir).also {
                if (publish) it.mkdirs()
            }
            val linuxPath = "/var/minis/$subdir"
            mounts[linuxPath] = hostDir.absolutePath
            if (publish) PRootKernel.addBindMount(linuxPath, hostDir.absolutePath)
        }

        // T277: user-mounted external folders (SAF-picked trees). PersistentShell
        // uses this map verbatim as PRoot's `-b` argv, so any mount missing here
        // is invisible to the shell — `ls /var/minis/mounts/<name>/` then shows
        // only the empty rootfs placeholder. PRootKernel.bindMounts is kept in
        // sync separately by applyMountedFoldersSnapshot for the resolveHostPath
        // path (debug.ls, file_read, …) but does NOT feed the live PRoot argv.
        // Skip entries whose SAF tree URI didn't decode to a POSIX path
        // (cloud providers, unmounted removable storage).
        PRootKernel.mountedFoldersStore?.entries?.value?.forEach { entry ->
            val host = entry.resolvedHostPath ?: return@forEach
            val linuxPath = "/var/minis/mounts/${entry.name}"
            mounts[linuxPath] = host
        }

        // Shared storage is not a MountedFoldersStore entry. Without this,
        // shell_execute and the interactive PTY never see /sdcard even when
        // All Files Access is granted (PRootKernel.bindMounts is a different map).
        for ((linuxPath, host) in PRootKernel.shellSharedStorageBinds(appContext, publish)) {
            if (linuxPath == SharedStorageBindPlan.MOUNTS_SDCARD && linuxPath in mounts) continue
            mounts[linuxPath] = host
        }

        return mounts
    }

    /**
     * Called when a session is closed. Stops and removes the shell.
     */
    fun sessionDidTerminate(sessionId: String) {
        terminateOne(sessionId)
        if (!isLaneSession(sessionId)) {
            val prefix = lanePrefix(sessionId)
            shells.keys.filter { it.startsWith(prefix) }.forEach { terminateOne(it) }
        }
        // [T-android-shell-fresh-process] Drop the mount mapping too.
        mountedSessionIds.remove(sessionId)
    }

    private fun terminateOne(sessionId: String) {
        val shell = shells.remove(sessionId)
        lastIdle.remove(sessionId)
        // T124a: drop the snapshot too — a future shell for the same id
        // restarts from a clean baseline, so the next applyEnvironment
        // shouldn't try to `unset` keys that don't exist in the new shell.
        lastInjectedKeys.remove(sessionId)
        shell?.stop()
        // [T-android-shell-fresh-process] Stop any fresh-process commands
        // still running for this session (a session can run several
        // concurrently). Safe if none are live.
        stopFreshShells(sessionId)
        if (shell != null) Log.i(TAG, "[$sessionId] Shell terminated")
    }

    /**
     * Stop the shell for a specific session (e.g. user tapped cancel).
     * The shell process is killed; next command will recreate it.
     */
    fun stopCurrentCommand(sessionId: String? = null) {
        if (sessionId != null) {
            stopOne(sessionId)
            if (!isLaneSession(sessionId)) {
                val prefix = lanePrefix(sessionId)
                shells.keys.filter { it.startsWith(prefix) }.forEach { stopOne(it) }
            }
            // [T-android-shell-fresh-process] Stop fresh-process commands too.
            stopFreshShells(sessionId)
        } else {
            // Stop all sessions (legacy/fallback)
            shells.values.forEach { it.stop() }
            shells.clear()
            lastInjectedKeys.clear()
            // [T-android-shell-fresh-process] Stop all fresh shells across
            // all sessions.
            freshShells.values.flatten().forEach { runCatching { it.stop() } }
            freshShells.clear()
            ShellExecutor.destroyCurrent()
        }
    }

    /** Legacy overload for callers without sessionId. */
    fun stopCurrentCommand() = stopCurrentCommand(sessionId = null)

    /**
     * Propagate a system-timezone change to every live shell.
     *
     * - Updates [PRootKernel.customEnvironment]["TZ"] so future shells inherit
     *   the new value at spawn time.
     * - Exports the new TZ into every already-running [PersistentShell] via
     *   `export TZ=...` on stdin.
     * - Asks [TerminalSession] to do the same for every live interactive PTY.
     * - Rewrites guest `/etc/localtime`. Processes that ignore `TZ` still read it.
     *
     * Safe to call before PRoot has booted — it's a no-op in that case.
     */
    suspend fun broadcastTimezoneChange() {
        if (!PRootKernel.isBooted) return
        val tz = PRootKernel.updateTimezone()
        runCatching { PRootKernel.syncHostTimezoneFiles() }
            .onFailure { Log.w(TAG, "syncHostTimezoneFiles failed: ${it.message}") }
        val tzMap = mapOf("TZ" to tz)
        for ((_, shell) in shells) {
            if (shell.isAlive) shell.applyEnvironment(tzMap)
        }
        TerminalSession.broadcastTimezone(tz)
    }

    /**
     * Propagate a system-proxy change to every live shell. Exports all six
     * proxy keys as a block — empty strings when no proxy is configured, so
     * a disable transition clears the old values in-place without needing
     * a separate `unset`.
     *
     * Safe to call before PRoot has booted — it's a no-op in that case.
     */
    suspend fun broadcastProxyChange() {
        if (!PRootKernel.isBooted) return
        val env = PRootKernel.updateProxy(appContext)
        for ((_, shell) in shells) {
            if (shell.isAlive) shell.applyEnvironment(env)
        }
        TerminalSession.broadcastProxy(env)
    }

    private fun stopOne(sessionId: String) {
        val shell = shells.remove(sessionId)
        lastInjectedKeys.remove(sessionId)
        shell?.stop()
        // [T-android-shell-fresh-process] Also stop fresh-process commands
        // for this session — Stop means "stop everything this conversation
        // is running", not just the warm shell.
        stopFreshShells(sessionId)
        Log.i(TAG, "[$sessionId] Shell stopped by user")
    }

    /**
     * [T-android-shell-fresh-process] Stop every live [FreshProcessShell]
     * for [sessionId]. Safe to call when none are running.
     */
    private fun stopFreshShells(sessionId: String) {
        val set = freshShells.remove(sessionId) ?: return
        for (shell in set) {
            runCatching { shell.stop() }
        }
    }

    fun ownerSessionId(sessionId: String): String {
        if (!isLaneSession(sessionId)) return sessionId
        val rest = sessionId.substring(LANE_PREFIX.length)
        val cut = rest.lastIndexOf(':')
        return if (cut > 0) rest.substring(0, cut) else sessionId
    }

    fun isLaneSession(sessionId: String): Boolean = sessionId.startsWith(LANE_PREFIX)

    private fun lanePrefix(ownerSessionId: String): String = "$LANE_PREFIX$ownerSessionId:"
}
