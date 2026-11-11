package com.openminis.app

import android.app.Activity
import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.util.Log
import androidx.core.content.ContextCompat
import org.acra.ACRA
import org.acra.ReportField
import org.acra.config.CoreConfigurationBuilder
import org.acra.data.StringFormat
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.decode.SvgDecoder
import com.openminis.app.browser.BrowserTabPool
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.db.DatabaseVersionGuard
import com.openminis.app.data.repository.BackgroundSettingsRepository
import com.openminis.app.data.repository.MultiAgentSettingsRepository
import com.openminis.app.data.repository.*
import com.openminis.app.data.MountedFoldersStore
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.WebAppShortcutRepository
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.di.AppGraph
import com.openminis.app.evolution.EvolutionEngine
import com.openminis.app.evolution.EvolutionHooks
import com.openminis.app.notification.BackgroundTaskNotifier
import com.openminis.app.platform.ProcessChecks
import com.openminis.app.logging.AppLogger
import com.openminis.app.network.NetworkMonitor
import com.openminis.app.offload.OffloadPermissionManager
import com.openminis.app.provider.ModelsDevApi
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.sandbox.NativeOffloadServer
import com.openminis.app.sandbox.PRootKernel
import com.openminis.app.sandbox.RootfsManager
import com.openminis.app.sandbox.HostEventHooks
import com.openminis.app.service.SessionActivityTracker
import com.openminis.app.ui.MinisImageFetcher
import kotlinx.coroutines.launch

class MinisApp : Application(), ImageLoaderFactory {
    /** Process-owned scopes; all application background work is cancelled together in tests. */
    val appCoroutineScopes = com.openminis.app.di.AppCoroutineScopes()
    internal val appContainer = com.openminis.app.di.AppGraph.create(appCoroutineScopes)
    // T-android-safemode-lateinit-crash: true only after the heavy subsystem
    // block in [onCreate] fully ran (DB + every repository assigned).
    @Volatile
    var subsystemsInitialized: Boolean = false

    /** [T-android-startup-splash-hang] Why the subsystem block failed, for the recovery screen in MainActivity. */
    var subsystemInitFailure: String? = null
        private set

    /** [T-android-downgrade-compat] Why init was skipped: newer-build DB vs genuine init failure, for accurate MainActivity guidance. */
    @Volatile
    var dbVersionDecision: DatabaseVersionGuard.Decision =
        DatabaseVersionGuard.Decision.PROCEED
        private set

    // [T-android-safemode-lateinit-crash-147] Null-safe [chatRepository] for code
    // that runs without MainActivity (alarms, notification taps). Checks [subsystemsInitialized]
    // (not ::chatRepository.isInitialized) so a mid-onCreate read can't trip the next lateinit.
    val chatRepositoryOrNull: ChatRepository?
        get() = if (subsystemsInitialized) chatRepository else null

    // [T-android-share-launch-crash] Same contract as [chatRepositoryOrNull], for the share-import path.
    val providerRepositoryOrNull: ProviderRepository?
        get() = if (subsystemsInitialized) providerRepository else null

    /** [T-android-safemode-lateinit-crash-147] True when app-layer deps are usable; prefer over isSafeMode() which flips false on dismiss. */
    fun subsystemsReady(): Boolean = subsystemsInitialized

    val database: AppDatabase get() = checkNotNull(appContainer.database)
    val chatRepository: ChatRepository get() = appContainer.requireChatRepository()
    val providerRepository: ProviderRepository get() = appContainer.requireProviderRepository()
    val envVarRepository: EnvVarRepository get() = appContainer.requireEnvVarRepository()
    val skillRepository: SkillRepository get() = appContainer.requireSkillRepository()
    val mcpRepository: MCPRepository get() = appContainer.requireMcpRepository()
    val memoryRepository: MemoryRepository get() = appContainer.requireMemoryRepository()
    val evolutionEngine: EvolutionEngine? get() = appContainer.evolutionEngine
    val webAppShortcutRepository: WebAppShortcutRepository
        get() = checkNotNull(appContainer.webAppShortcutRepository)
    val backgroundSettingsRepository: BackgroundSettingsRepository
        get() = appContainer.requireBackgroundSettingsRepository()
    val multiAgentSettingsRepository: MultiAgentSettingsRepository
        get() = appContainer.requireMultiAgentSettingsRepository()
    val backgroundTaskNotifier: BackgroundTaskNotifier get() = checkNotNull(appContainer.backgroundTaskNotifier)
    val mountedFoldersStore: MountedFoldersStore get() = appContainer.requireMountedFoldersStore()

    // T180-bg-notif: foreground-Activity counter, read by [BackgroundTaskNotifier] to suppress completion notifications while the user is in-app.
    @Volatile
    private var foregroundActivityCount: Int = 0

    fun isAppForeground(): Boolean = foregroundActivityCount > 0

    // T-bg-overlay phase 2: foreground StateFlow for AgentForegroundService. Initial true avoids overlay flash before first onStart (T-overlay-startup-flash).
    private val _isAppForegroundFlow = kotlinx.coroutines.flow.MutableStateFlow(true)
    val isAppForegroundFlow: kotlinx.coroutines.flow.StateFlow<Boolean>
        get() = _isAppForegroundFlow

    /** App-wide network monitor (mirrors iOS NetworkMonitor.shared). */
    val networkMonitor: NetworkMonitor = NetworkMonitor()

    /** App-scoped BrowserTabPool for shell-invoked `minis-browser-use`, separate from the per-ChatViewModel pool. */
    val sharedBrowserTabPool: BrowserTabPool by lazy {
        BrowserTabPool(this).also { it.setSession("minis-browser-use") }
    }

    override fun attachBaseContext(base: Context) {
        super.attachBaseContext(base)
        // T283: install ACRA before any app-level singleton runs; reports surface in LogManagementScreen.
        ACRA.init(
            this,
            CoreConfigurationBuilder()
                .withBuildConfigClass(BuildConfig::class.java)
                .withReportFormat(StringFormat.JSON)
                .withLogcatArguments(listOf("-t", "200", "-v", "time"))
                .withReportContent(
                    ReportField.APP_VERSION_NAME,
                    ReportField.APP_VERSION_CODE,
                    ReportField.ANDROID_VERSION,
                    ReportField.BUILD,
                    ReportField.PHONE_MODEL,
                    ReportField.BRAND,
                    ReportField.STACK_TRACE,
                    ReportField.LOGCAT,
                ),
        )
    }

    override fun onCreate() {
        super.onCreate()

        // T287-followup: ACRA's :acra reporter must not bind the socket / boot the DB (EADDRINUSE loop).
        if (ACRA.isACRASenderServiceProcess()) {
            Log.i("MinisApp", "skipping app init in :acra reporter process")
            return
        }

        // T-android-safemode-lateinit-crash: prime AppLogger's Context before any early-return skips init.
        AppLogger.primeContext(this)

        // Application subclasses run in EVERY process: sub-processes get minimal boot (rest is main-only).
        if (ProcessChecks.shouldSkipMainInit(this)) return

        // [GH#258][GH#287] App-level trust store for user-imported CAs. Init
        // only creates the directory; the provider clients pick the material
        // up when they are built (Settings → Network → Proxies can import one).
        com.openminis.app.network.AppTrustStore.init(this)

        // [GH#423][GH#301] Claude CLI fingerprint version: load the cached npm
        // dist-tag now, refresh it in the background at most once per 24h. A
        // network failure leaves the pinned fallback in place.
        com.openminis.app.auth.ClaudeCliVersion.init(this)
        appCoroutineScopes.io.launch {
            com.openminis.app.auth.ClaudeCliVersion.refreshIfStale(this@MinisApp)
        }

        com.openminis.app.crash.LaunchPhaseProbes.startLaunchScan(this, appCoroutineScopes.io)
        com.openminis.app.crash.LaunchPhaseProbes.startDbVersionProbe(this, appCoroutineScopes.io)

        com.openminis.app.platform.OptionalPackages.probe(this)
        // Chat bridge for debug/headless RPC — via the AppGraph registry.
        AppGraph.installChatBinder(com.openminis.app.ui.chat.ChatViewModelBinder(this))

        // [Wave3 #4/#16] Live chat/session permission facade via com.openminis.app.di.ChatBinder so ConfigBuiltins reads only the di slot.
        AppGraph.installChatStateBinder(object : com.openminis.app.di.ChatBinder {
            override fun activeSessionId(): String? =
                AppGraph.chatBinderOrNull()?.activeSessionId
            override fun sessionMode(id: String?): String? =
                com.openminis.app.security.effectivePermissionMode(
                    com.openminis.app.security.SecurityGateHolder.activeSessionMode(id),
                    com.openminis.app.service.ApprovalGate.isSessionAllowAll(id),
                ).name
            override fun sessionAllowAll(id: String?): Boolean =
                com.openminis.app.service.ApprovalGate.isSessionAllowAll(id)
        })

        // [T-codex-fast-mode] Warm the Fast Mode flag cache for Context-free provider reads.
        com.openminis.app.data.FastModePrefs.prime(this)

        // The pre-send context check and the in-chat opt-in read this with no Activity context.
        com.openminis.app.data.AutoCompactPrefs.prime(this)
        // [GH#221] Per-provider custom request headers are read by
        // ProviderFactory.create() from call sites without a Context.
        com.openminis.app.provider.ProviderHeaderStore.prime(this)
        // [GH#114] Per-app accessibility allow/block list, read from the ui
        // tools which have no Activity context.
        com.openminis.app.accessibility.A11yAppPolicy.prime(this)
        // [GH#131] Lifecycle hook config (event → sandbox script), read from
        // ChatRepository without a Context.
        com.openminis.app.agent.lifecycle.LifecycleHooks.prime(this)
        com.openminis.app.data.PlanDiscussionPrefs.prime(this)
        com.openminis.app.data.ToolLimitPrefs.prime(this)
        com.openminis.app.data.ContextSaverPrefs.prime(this) // [T-tool-result-aging] send path reads it per request
        com.openminis.app.security.SecurityGateHolder.load(this)

        // T283: NDK signal handler for native crashes; writes a report then re-raises so the system tombstone is also generated.
        try {
            com.openminis.app.crash.NativeCrashHandler.install(
                java.io.File(filesDir, "logs"),
            )
        } catch (t: Throwable) {
            Log.w("MinisApp", "NativeCrashHandler install failed: ${t.message}")
        }

        // T-android-fgs-timeout-crash: intercept RemoteServiceException$ForegroundServiceDidNotStopInTimeException — stop the FGS cleanly, then delegate to ACRA.
        try {
            val priorHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                try {
                    val isFgsTimeout = throwable.javaClass.name.endsWith(
                        "RemoteServiceException\$ForegroundServiceDidNotStopInTimeException",
                    ) || (throwable.message?.contains("foreground service of type") == true &&
                        throwable.message?.contains("did not stop within its timeout") == true)
                    if (isFgsTimeout) {
                        Log.w(
                            "MinisApp",
                            "FGS timeout caught; stopping service before deferring to ACRA: ${throwable.message}",
                        )
                        // Stop the service so the system tears the
                        // sticky binding down cleanly instead of
                        // re-spawning into the same trap.
                        runCatching {
                            val intent = Intent(this, com.openminis.app.service.AgentForegroundService::class.java)
                            stopService(intent)
                        }
                    }
                } catch (t: Throwable) {
                    Log.w("MinisApp", "FGS-timeout handler internal failure: ${t.message}")
                }
                // Always defer to the prior handler so ACRA's
                // dump-and-relaunch flow runs intact.
                priorHandler?.uncaughtException(thread, throwable)
            }
        } catch (t: Throwable) {
            Log.w("MinisApp", "install FGS-timeout handler failed: ${t.message}")
        }

        // [T-orphan-notification-sweep] Sweep stuck Question/approval notifications (process-local gates) before the safe-mode early-return below.
        try {
            com.openminis.app.notification.AskUserNotifier.sweepOrphans(this)
            com.openminis.app.notification.ApprovalNotifier.sweepOrphans(this)
        } catch (t: Throwable) {
            Log.w("MinisApp", "orphan notification sweep failed: ${t.message}")
        }

        // Safe-mode short-circuits before another segfault rewrites the log files.
        com.openminis.app.crash.LaunchPhaseProbes.awaitLaunchScan()
        if (com.openminis.app.crash.CrashFrequencyDetector.isSafeMode()) {
            Log.w("MinisApp", "safe-mode ON — skipping app subsystem init")
            return
        }

        // Init the daily-rotating file logger first (mirrors iOS LoggingManager.startIfEnabled()).
        AppLogger.init(this)

        // [T-generation-run] Mark generation runs abandoned by a previous process death as ABANDONED (traceable).
        runCatching {
            com.openminis.app.agent.GenerationRunStore.abandoned(this).forEach { run ->
                com.openminis.app.agent.GenerationRunStore.markAbandoned(this, run.runId)
                Log.w(
                    "MinisApp",
                    "[GenerationRun] abandoned run recovered: session=${run.sessionId} " +
                        "model=${run.modelId} anchor=${run.turnAnchor} startedAtMs=${run.startedAtMs}",
                )
            }
        }
        // [T-tool-approval-removed][T-stream-trace-live] Restore stream-trace recording state.
        com.openminis.app.provider.StreamTraceRecorder.restore(this)

        // Bug 2 (MIUI silent kill) diagnostic: write a launch-cycle beacon read on next launch by LaunchCycleBeacon.
        try {
            com.openminis.app.diagnostics.LaunchCycleBeacon.recordLaunch(this)
        } catch (t: Throwable) {
            Log.w("MinisApp", "LaunchCycleBeacon.recordLaunch failed: ${t.message}")
        }

        // Start the main-thread hang watchdog before the heavier subsystems; AppNavigation overrides launch to home after 3 hangs.
        com.openminis.app.diagnostics.HangDetector.start(this)
        com.openminis.app.diagnostics.HangDetector.hasLiveWorkload = {
            com.openminis.app.sandbox.SandboxWorkload.hasLiveWork()
        }
        com.openminis.app.diagnostics.HangDetector.onCountedHang = { _, _ -> }
        com.openminis.app.sandbox.SandboxWorkload.attach(this)
        com.openminis.app.sandbox.kernel.remediation.RemediationLoop.start(filesDir) { reason ->
            com.openminis.app.sandbox.SandboxWorkload.stopUnprotected(reason)
            com.openminis.app.sandbox.ExecutionCoordinator.stopCurrentCommand()
        }

        // [T-android-safemode-lateinit-crash-147] Structural backstop: a throw mid-block must leave subsystemsInitialized false (recoverable) rather than rethrow and lock the user out.
        // [T-android-downgrade-compat] Probe the on-disk schema version before Room opens the file, so a newer-build DB shows recoverable guidance instead of dying.
        dbVersionDecision = com.openminis.app.crash.LaunchPhaseProbes.awaitDbVersionProbe()

        try {
        // Guard INSIDE the try: a newer-schema DB takes the degraded-mode path instead of an early return that would skip unrelated startup.
        if (dbVersionDecision == DatabaseVersionGuard.Decision.SHOW_NEWER_DB_GUIDANCE) {
            error(
                "on-disk chat schema is newer than this build " +
                    "(code=${DatabaseVersionGuard.CODE_DB_VERSION}); refusing to open it. " +
                    "The database file is left completely untouched — upgrading restores everything."
            )
        }
        appContainer.providerRepository = ProviderRepository(this)
        // [port-upstream-1.16] Upstream provider quota / balance cache
        // (T-provider-quota): foreground-only refresh scheduler over the
        // provider repository. Fails soft — a quota problem must never block
        // normal chat, so it is started unguarded by design and all of its
        // fetch paths already contain their own runCatching / gate logic.
        com.openminis.app.quota.QuotaStore.start(this, providerRepository)
        AppGraph.installQuotaStore(com.openminis.app.quota.QuotaStore)
        appContainer.envVarRepository = EnvVarRepository(this)
        // [T-android-safemode-lateinit-crash-147] SkillRepository parses third-party content; its init is guarded so construction cannot escape here.
        appContainer.skillRepository = SkillRepository(this)
        appContainer.mcpRepository = MCPRepository(this)
        appContainer.memoryRepository = MemoryRepository(java.io.File(filesDir, "minis-global/memory"))

        // [T-perf-async-db-init-v2] Construction stays synchronous (cheap); only the expensive first open is pulled off-main by the IO warmup below.
        appContainer.database = AppDatabase.getInstance(this)
        appContainer.chatRepository = ChatRepository(
            database.chatDao(), database.goalDao(), filesDir, database.messageVersionDao(), database,
        )
        // [Wave3 #17] AppGraph is the single source of truth for the evolution engine (appContainer.evolutionEngine is a compat getter).
        val evolutionEngineInstance = runCatching {
            EvolutionEngine(
                context = this,
                memoryRepository = memoryRepository,
                evolutionDir = java.io.File(filesDir, "minis-global/evolution"),
                chatRepository = chatRepository,
                providerRepository = providerRepository,
                skillRepository = skillRepository,
            )
        }.onFailure { Log.e("MinisApp", "evolution init failed", it) }.getOrNull()
        // Possibly null (runCatching-guarded); readers tolerate null.
        AppGraph.installEvolutionEngine(evolutionEngineInstance)
        appContainer.webAppShortcutRepository = WebAppShortcutRepository(database.webAppShortcutDao())
        MinisAppAsyncInit.startWarmup(appContainer)

        // T-android-safemode-lateinit-crash: UI deps are ready, so set the flag early — a late non-UI failure must not lock the user out.
        subsystemsInitialized = true
            // backfillBlankPermissionModesToAsk moved to MinisAppAsyncInit post-init hook
            try {
                Log.i("WebViewEngine", com.openminis.app.browser.WebViewEngine.snapshot(this).summary)
            } catch (t: Throwable) {
                Log.w("WebViewEngine", "probe failed", t)
            }
        } catch (t: Throwable) {
            // Keep subsystemsInitialized false (recoverable); do NOT rethrow.
            Log.e("MinisApp", "subsystem init failed — app will start in degraded mode", t)
            // Short, user-presentable cause (class name + message, no stack).
            subsystemInitFailure = buildString {
                append(t.javaClass.simpleName)
                t.message?.take(300)?.let { append(": ").append(it) }
            }
            return
        }

        // [Wave3 #12] SoulStore chain + GLOBAL.md seeding moved off the critical path (readers collect reactively or read files on demand).
        appCoroutineScopes.io.launch {
            // Load assets first (DEFAULT_CONTENT shipped persona); do not force-overwrite SOUL.md.
            runCatching {
                com.openminis.app.agent.SoulStore.loadDefaultFromAssets(this@MinisApp)
                com.openminis.app.agent.SoulStore.forceOverwriteOnce(this@MinisApp)
                com.openminis.app.agent.SoulStore.ensureExists(this@MinisApp)
                com.openminis.app.agent.SoulStore.upgradeStaleDefault(this@MinisApp)
                com.openminis.app.agent.SoulStore.renameStockProduct(this@MinisApp)
                com.openminis.app.agent.SoulStore.refreshCache(this@MinisApp)

                // [T-global-md-seed + T-default-assets] Seed GLOBAL.md starter template on first launch (create-only).
                com.openminis.app.agent.SystemPromptBuilder.loadIdentityFromAssets(this@MinisApp)
                com.openminis.app.data.repository.MemoryRepository.loadGlobalDefaultFromAssets(this@MinisApp)
                com.openminis.app.data.repository.MemoryRepository.ensureGlobalExists(this@MinisApp)
            }.onFailure {
                android.util.Log.e("MinisApp", "soul/memory asset seeding failed", it)
            }
        }

        appCoroutineScopes.io.launch {
            runCatching { chatRepository.warmupWorkspaceOwners() }.onFailure {
                android.util.Log.e("MinisApp", "workspace owner warmup failed", it)
            }
            runCatching {
                com.openminis.app.sandbox.WorkspaceMover.recoverInterrupted(filesDir)
            }.onFailure {
                android.util.Log.e("MinisApp", "workspace staging recovery failed", it)
            }
            runCatching { chatRepository.reconcileWorkspaceFiles() }.onFailure {
                android.util.Log.e("MinisApp", "workspace reconcile failed", it)
            }
            runCatching {
                com.openminis.app.sandbox.LegacyWorkspaceMigrator.run(this@MinisApp, chatRepository)
            }.onFailure {
                android.util.Log.e("MinisApp", "legacy workspace migrate failed", it)
            }
            // [T268] One-shot migration of pre-T266 ghost alarms into the system Clock app, off the critical path.
            runCatching { migrateGhostAlarms() }
                .onFailure { Log.w("MinisApp", "ghost alarm migration failed: ${it.message}") }
        }

        // [skill-startup-refresh] Re-scan minis-global/skills/ and default-enable the library on IO.
        appCoroutineScopes.io.launch {
            runCatching { skillRepository.refreshOnStartup() }.onFailure {
                android.util.Log.e("MinisApp", "skill startup refresh failed", it)
            }
        }

        appCoroutineScopes.io.launch {
            kotlinx.coroutines.delay(5_000)
            runCatching {
                com.openminis.app.data.repository.SkillSubscriptionSync.refreshAuto(
                    this@MinisApp,
                    skillRepository,
                )
            }
        }

        // T-config: init registry / audit log / master-switch store so ConfigRegistry.get() is thread-safe.
        com.openminis.app.config.MinisConfigPermissionStore.init(this)
        com.openminis.app.config.audit.ConfigAuditLog.init(this)
        com.openminis.app.config.ConfigRegistry.init(
            this, providerRepository, envVarRepository, chatRepository,
        )

        // Initialize models.dev registry (loads from bundled asset, refreshes in background)
        ModelsDevApi.init(this)

        // Initialize sandbox singletons (does not trigger extraction)
        RootfsManager.getInstance(this)
        ExecutionCoordinator.init(this)
        com.openminis.app.sandbox.jobs.JobSpawner.install(this)
        HostEventHooks.init(this)
        AppGraph.installExecutionEnvVarRepository(envVarRepository)

        // Privacy Mode store + redactor wiring (repo handoff via AppGraph; mirrors iOS).
        com.openminis.app.data.EnvVarPrivacyStore.init(this)
        AppGraph.installRedactorEnvVarRepository(envVarRepository)

        // Start network monitoring (mirrors iOS); refreshes /etc/resolv.conf on connectivity changes.
        networkMonitor.start(this)

        // [port-upstream-1.16] Settings > Network (Tailscale + proxies), the
        // same wiring as upstream MinisApp.onCreate: init does nothing — no Go
        // call, no listener — unless the user turned something on; on each
        // return to the foreground, and every 30 s while in front, a published
        // local entry is checked and restarted if it died (clients go direct
        // meanwhile), and the Tailscale status is refreshed. The lifecycle
        // observer is installed through AppGraph like the other startup
        // handoffs, so double wiring is a hard error instead of a silent
        // double-registration.
        com.openminis.app.network.NetworkHub.init(this)
        AppGraph.installNetworkRuntime(
            object : androidx.lifecycle.DefaultLifecycleObserver {
                override fun onStart(owner: androidx.lifecycle.LifecycleOwner) {
                    com.openminis.app.network.NetworkHub.onForeground()
                }

                override fun onStop(owner: androidx.lifecycle.LifecycleOwner) {
                    com.openminis.app.network.NetworkHub.onBackground()
                }
            }.also {
                androidx.lifecycle.ProcessLifecycleOwner.get().lifecycle.addObserver(it)
            },
        )

        // Register global /var/minis/{memory,skills,shared} bind mounts so file tools resolve them pre-boot.
        PRootKernel.registerGlobalBindMounts(this)

        // T219-1: seed PRoot's bindMounts before the first proot invocation; unresolved SAF URIs are skipped.
        appContainer.mountedFoldersStore = MountedFoldersStore(this)
        // T219-5/T277: register the store in AppGraph and re-apply on change; kill live shells so the next execute() rebuilds with the updated bind set.
        AppGraph.installMountedFoldersStore(appContainer.requireMountedFoldersStore())
        mountedFoldersStore.onChange = {
            PRootKernel.applyMountedFoldersSnapshot(this)
            ExecutionCoordinator.stopCurrentCommand()
        }
        // T219-6: seed via applyMountedFoldersSnapshot (runs pre-boot; boot re-applies once rootfs is on disk).
        PRootKernel.applyMountedFoldersSnapshot(this)

        // Register native_offload handlers + start the server eagerly.
        // [T-android-offload-lazy-handlers] Handlers are factories built on first dispatch (moved verbatim into AppGraph.registerOffloadHandlers).
        AppGraph.registerOffloadHandlers(this)

        NativeOffloadServer.start(RootfsManager.getInstance(this).rootfsDir)

        // Initialize session activity tracker for foreground service management
        SessionActivityTracker.init(this)

        // [T-android-session-paused-badge] Init the per-session badge-state queue early for first compose.
        com.openminis.app.service.SessionBadgeStore.init(this)

        // [T-android-session-paused-badge-hardkill] Reconcile PAUSED badges from the durable DB tail (also covers hard kills).
        appCoroutineScopes.io.launch {
            com.openminis.app.service.SessionBadgeStore.reconcileFromDb(this@MinisApp)
        }

        // T180-bg-notif: wire the task-completion notifier into SessionActivityTracker's completion hook.
        appContainer.backgroundSettingsRepository = BackgroundSettingsRepository(this)
        appContainer.multiAgentSettingsRepository = MultiAgentSettingsRepository(this)
        appCoroutineScopes.io.launch {
            kotlinx.coroutines.flow.combine(
                providerRepository.configLoaded,
                providerRepository.config,
            ) { loaded, cfg -> loaded to cfg }.collect { (loaded, cfg) ->
                if (!loaded) return@collect
                val enabled = cfg.instances.associate { it.id to it.isEnabled }
                val live = cfg.modelEntries
                    .filter { !it.isHidden && enabled[it.providerInstanceId] == true }.map { it.id }
                    .toSet()
                multiAgentSettingsRepository.retainLiveEntries(live)
            }
        }
        appContainer.backgroundTaskNotifier = BackgroundTaskNotifier(
            context = this,
            chatRepository = chatRepository,
            backgroundSettings = backgroundSettingsRepository,
            isAppForeground = ::isAppForeground,
        )
        SessionActivityTracker.setCompletionListener { sessionId, isError ->
            backgroundTaskNotifier.notifyTaskCompleted(sessionId, isError)
            EvolutionHooks.onSessionFinished(sessionId, isError)
        }

        // [T-scheduled-active-session-android, port upstream 1.16] Chats a
        // scheduled task keeps active between its fires: hold them in the
        // tracker (foreground service continuation, never a start) and keep
        // their cached ChatViewModels alive across idle trims, so a follow-up
        // run lands back into the same live context instead of a cold one.
        appCoroutineScopes.io.launch {
            com.openminis.app.scheduled.ScheduledSessionHold.observe(this@MinisApp)
                .collect { held -> SessionActivityTracker.setScheduledSessions(held) }
        }

        // [T-sched-inapp-timer, port upstream 1.16] Fire timed tasks at their
        // exact second while the process is alive / Minis is in front; the
        // AlarmManager alarm stays the fallback for a dead or frozen process.
        com.openminis.app.scheduled.ScheduledInAppTimer.start(this)

        // [T-android-config-confirm-timeout] Nudge the user before the 120s timeout when a config-confirm approval waits in background.
        val configConfirmNotifier = com.openminis.app.notification.ConfigConfirmNotifier(
            context = this,
            backgroundSettings = backgroundSettingsRepository,
            isAppForeground = ::isAppForeground,
        )
        // Both hooks are reinstallable AppGraph slots; the notifier is constructed once here.
        AppGraph.installConfigConfirmBackgroundNotifier(configConfirmNotifier::notifyIfBackgrounded)
        AppGraph.installConfigConfirmCancelNotification(configConfirmNotifier::cancel)

        // Track foreground state via started/stopped (balanced across config changes; conservative when partially visible).
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
            override fun onActivityStarted(activity: Activity) {
                val wasBackgrounded = foregroundActivityCount == 0
                foregroundActivityCount++
                if (wasBackgrounded) _isAppForegroundFlow.value = true
                // T298: clear task-completed notifications on background → foreground.
                if (wasBackgrounded && appContainer.backgroundTaskNotifier != null) {
                    backgroundTaskNotifier.cancelAllCompletedNotifications()
                }
                // [T-android-session-paused-badge-hardkill] Reconcile PAUSED badges from the DB tail (excludes currently-active sessions).
                if (wasBackgrounded) {
                    appCoroutineScopes.io.launch {
                        com.openminis.app.service.SessionBadgeStore.reconcileFromDb(this@MinisApp)
                    }
                }
            }
            override fun onActivityResumed(activity: Activity) {
                // T-android-crash-freq-share: one-shot share-logs dialog on first resume after a crash burst.
                com.openminis.app.crash.CrashFrequencyDetector.maybeShowOnActivity(activity)
                // T219-1: re-probe mounted-folder writability on resume so OS revocations propagate.
                appCoroutineScopes.io.launch {
                    runCatching { mountedFoldersStore.refreshWritability() }
                }
            }
            override fun onActivityPaused(activity: Activity) {}
            override fun onActivityStopped(activity: Activity) {
                foregroundActivityCount = (foregroundActivityCount - 1).coerceAtLeast(0)
                if (foregroundActivityCount == 0) {
                    _isAppForegroundFlow.value = false
                    // [T-android-config-confirm-timeout] Nudge for a pending config-confirm before the 120s timeout.
                    com.openminis.app.config.confirm.ConfigConfirmationGate.notifyPending()
                    EvolutionHooks.maybeHarvestIdle()
                }
            }
            override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
            override fun onActivityDestroyed(activity: Activity) {}
        })

        // Initialize offload permission manager
        OffloadPermissionManager.init(this)

        // Initialize speech-recognition adapter layer (system + provider engines).
        com.openminis.app.speech.SpeechRecognitionManager.init(this)

        // Refresh model lists once per calendar day (mirrors iOS MinisApp.swift).
        // Runs per-instance in parallel; `autoRefreshModels` skips instances with custom models.
        providerRepository.refreshAllModelsIfNeeded(
            appCoroutineScopes.io
        )

        // Propagate system timezone and HTTP-proxy changes into the sandbox (live shells pick up changes without restart).
        val sandboxSystemReceiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val scope = appCoroutineScopes.io
                when (intent.action) {
                    Intent.ACTION_TIMEZONE_CHANGED -> scope.launch {
                        try {
                            ExecutionCoordinator.broadcastTimezoneChange()
                        } catch (t: Throwable) {
                            Log.w("MinisApp", "broadcastTimezoneChange failed: ${t.message}")
                        }
                    }
                    android.net.Proxy.PROXY_CHANGE_ACTION -> scope.launch {
                        try {
                            ExecutionCoordinator.broadcastProxyChange()
                        } catch (t: Throwable) {
                            Log.w("MinisApp", "broadcastProxyChange failed: ${t.message}")
                        }
                    }
                }
            }
        }
        ContextCompat.registerReceiver(
            this,
            sandboxSystemReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
                addAction(android.net.Proxy.PROXY_CHANGE_ACTION)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        // Debug server: only start in debug builds (NEVER in release)
        if (BuildConfig.DEBUG) {
            try {
                com.openminis.app.debug.DebugServer(this).start()
            } catch (e: Exception) {
                Log.w("MinisApp", "Failed to start debug server: ${e.message}")
            }
        }

        // Built-in MCP server (loopback 127.0.0.1:8765); boots when enabled in Settings.
        runCatching {
            com.openminis.app.mcp.server.McpServerManager.init(this)
            com.openminis.app.mcp.server.McpServerManager.bootIfEnabled()
        }.onFailure { Log.w("MinisApp", "MCP server init failed: ${it.message}") }

        // [T268] Ghost-alarm migration call moved into the startup IO block above.
    }

    // T268: replay pre-T266 alarms/timers from prefs through SET_ALARM/SET_TIMER, then clear the blob (idempotent, past-dated dropped).
    private fun migrateGhostAlarms() {
        val prefs = getSharedPreferences("minis_alarms_prefs", Context.MODE_PRIVATE)
        val raw = prefs.getString("alarms_json", null) ?: return
        if (raw.isBlank() || raw == "[]") return
        val arr = org.json.JSONArray(raw)
        if (arr.length() == 0) {
            prefs.edit().remove("alarms_json").apply()
            return
        }
        val now = System.currentTimeMillis()
        var migrated = 0
        var skipped = 0
        for (i in 0 until arr.length()) {
            val entry = arr.optJSONObject(i) ?: continue
            val triggerAt = entry.optLong("triggerAtMs", 0L)
            if (triggerAt in 1L..now && entry.optString("type") == "timer") {
                skipped++; continue  // Past timer — nothing to recover.
            }
            if (triggerAt in 1L..now && entry.optString("repeatMode", "ONCE") == "ONCE") {
                skipped++; continue  // Past one-shot alarm.
            }
            val migrationOk = runCatching {
                if (entry.optString("type") == "timer") {
                    val secs = entry.optInt("durationSec", -1)
                    val remaining = ((triggerAt - now) / 1000L).toInt()
                    if (remaining <= 0 && secs <= 0) return@runCatching false
                    val intent = android.content.Intent(android.provider.AlarmClock.ACTION_SET_TIMER).apply {
                        putExtra(android.provider.AlarmClock.EXTRA_LENGTH, if (remaining > 0) remaining else secs)
                        putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, entry.optString("label", "Timer"))
                        putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    true
                } else {
                    val intent = android.content.Intent(android.provider.AlarmClock.ACTION_SET_ALARM).apply {
                        putExtra(android.provider.AlarmClock.EXTRA_HOUR, entry.optInt("hour", 0))
                        putExtra(android.provider.AlarmClock.EXTRA_MINUTES, entry.optInt("minute", 0))
                        putExtra(android.provider.AlarmClock.EXTRA_MESSAGE, entry.optString("label", "Alarm"))
                        putExtra(android.provider.AlarmClock.EXTRA_SKIP_UI, true)
                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    startActivity(intent)
                    true
                }
            }.getOrDefault(false)
            if (migrationOk) migrated++ else skipped++
        }
        // Clear the blob unconditionally — entries we couldn't replay are
        // still useless ghosts, and leaving the blob would re-trigger
        // migration on every launch.
        prefs.edit().remove("alarms_json").apply()
        Log.i("MinisApp", "T268 ghost alarm migration: migrated=$migrated skipped=$skipped (prefs cleared)")
    }

    /** Coil ImageLoader: MinisImageFetcher resolves `minis://`; SvgDecoder is required for res/raw vendor logos. */
    override fun newImageLoader(): ImageLoader =
        ImageLoader.Builder(this)
            // [port-upstream-1.16] Remote images follow the default route in
            // Settings > Network (upstream newImageLoader): Coil's own default
            // is a plain OkHttpClient(); that same client sends every request
            // while nothing is configured.
            .callFactory {
                val base = okhttp3.OkHttpClient()
                okhttp3.Call.Factory { request ->
                    com.openminis.app.network.NetworkHttp.client(
                        com.openminis.app.network.NetworkHttp.DEFAULT_TAG,
                        base,
                    ).newCall(request)
                }
            }
            .components {
                add(SvgDecoder.Factory())
                add(MinisImageFetcher.Factory())
                add(MinisImageFetcher.UriFactory())
                // T-image-cache-mtime-35133: include mtime in cache keys so in-place rewrites invalidate.
                add(MinisImageFetcher.MtimeKeyer())
                add(MinisImageFetcher.StringMtimeKeyer())
            }
            .build()

    // [GH#206] Respond to memory pressure by dropping reconstructible caches:
    // RUNNING_LOW+ drops formula bitmaps; COMPLETE also tears down the offscreen KaTeX WebView. RUNNING_MODERATE is not acted on.
    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)

        val dropFormulaCaches = when (level) {
            TRIM_MEMORY_UI_HIDDEN,
            TRIM_MEMORY_RUNNING_LOW,
            TRIM_MEMORY_RUNNING_CRITICAL,
            TRIM_MEMORY_BACKGROUND,
            TRIM_MEMORY_MODERATE,
            TRIM_MEMORY_COMPLETE,
            -> true
            else -> false
        }
        if (!dropFormulaCaches) return

        com.openminis.app.data.body.Admission.onTrim()
        com.openminis.app.ui.chat.ChatViewModelStore.trimIdle(pressure = level != TRIM_MEMORY_UI_HIDDEN)
        Log.i("MinisApp", "onTrimMemory(level=$level): releasing formula bitmap caches")
        runCatching { com.openminis.app.ui.chat.KatexWebViewPool.evictAll() }
            .onFailure { Log.w("MinisApp", "KatexWebViewPool.evictAll failed: ${it.message}") }
        runCatching { com.openminis.app.ui.markdown.KaTeXRendererCache.evictAll() }
            .onFailure { Log.w("MinisApp", "KaTeXRendererCache.evictAll failed: ${it.message}") }

        if (level == TRIM_MEMORY_RUNNING_CRITICAL || level >= TRIM_MEMORY_COMPLETE) {
            com.openminis.app.sandbox.SandboxWorkload.stopUnprotected("trim-memory level=$level")
        }
        if (level >= TRIM_MEMORY_COMPLETE) {
            Log.i("MinisApp", "onTrimMemory(level=$level): tearing down the offscreen KaTeX WebView")
            runCatching { com.openminis.app.ui.chat.KatexWebViewPool.releaseWebView() }
                .onFailure { Log.w("MinisApp", "KatexWebViewPool.releaseWebView failed: ${it.message}") }
        }
    }

    override fun onTerminate() {
        // onTerminate is emulator/teardown-only; still mark the clean_exit beacon for [LaunchCycleBeacon].
        try {
            com.openminis.app.diagnostics.LaunchCycleBeacon.recordCleanExit(this)
        } catch (t: Throwable) {
            Log.w("MinisApp", "LaunchCycleBeacon.recordCleanExit failed: ${t.message}")
        }
        super.onTerminate()
    }

}
