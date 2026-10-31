package com.openminis.app.di

import android.content.Context
import com.openminis.app.BuildConfig
import com.openminis.app.config.confirm.PendingConfigChange
import com.openminis.app.data.MountedFoldersStore
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.evolution.EvolutionEngine
import com.openminis.app.sandbox.NativeOffloadServer
import com.openminis.app.sandbox.offload.AccessibilityOffloadHandler
import com.openminis.app.sandbox.offload.AlarmOffloadHandler
import com.openminis.app.sandbox.offload.BrowserUseOffloadHandler
import com.openminis.app.sandbox.offload.CalendarOffloadHandler
import com.openminis.app.sandbox.offload.ClipboardOffloadHandler
import com.openminis.app.sandbox.offload.ConfigOffloadHandler
import com.openminis.app.sandbox.offload.ContactsOffloadHandler
import com.openminis.app.sandbox.offload.DeviceOffloadHandler
import com.openminis.app.sandbox.offload.DozeOffloadHandler
import com.openminis.app.sandbox.offload.FirewallOffloadHandler
import com.openminis.app.sandbox.offload.HostEventOffloadHandler
import com.openminis.app.sandbox.offload.LocationOffloadHandler
import com.openminis.app.sandbox.offload.MinisNotifyOffloadHandler
import com.openminis.app.sandbox.offload.ModelUseOffloadHandler
import com.openminis.app.sandbox.offload.NotificationOffloadHandler
import com.openminis.app.sandbox.offload.OpenOffloadHandler
import com.openminis.app.sandbox.offload.PhotosOffloadHandler
import com.openminis.app.sandbox.offload.PlayerOffloadHandler
import com.openminis.app.sandbox.offload.ProcfsOffloadHandler
import com.openminis.app.sandbox.offload.ScheduledTaskOffloadHandler
import com.openminis.app.sandbox.offload.SessionsOffloadHandler
import com.openminis.app.sandbox.offload.ShizukuOffloadHandler
import com.openminis.app.sandbox.offload.SpeakOffloadHandler
import com.openminis.app.sandbox.offload.SpeechOffloadHandler
import com.openminis.app.sandbox.offload.SuOffloadHandler
import com.openminis.app.sandbox.offload.ToastOffloadHandler
import com.openminis.app.sandbox.offload.WeatherOffloadHandler
import com.openminis.app.session.ChatSessionBinder

/** Startup wiring boundary between Application and containerized repositories. */
object AppGraph {
    fun create(scopes: AppCoroutineScopes): AppContainer = AppContainer(scopes)

    /**
     * [T-android-offload-lazy-handlers] The single place the native_offload
     * registry is wired — moved verbatim out of [com.openminis.app.MinisApp]
     * `onCreate`, which now spends one line here. Same names, same order, same
     * conditional (debug-only minis-debug); the ONLY behavioral difference is
     * that handlers are passed as FACTORIES, so the 28 handler objects are
     * constructed on first dispatch instead of on the onCreate critical path
     * (see [NativeOffloadServer.register]).
     *
     * Requires [com.openminis.app.MinisApp.subsystemsInitialized] — the model
     * /sessions handlers close over the repositories.
     */
    fun registerOffloadHandlers(app: Context) {
        // Only MinisApp.onCreate calls this (see the doc): the Context IS the
        // MinisApp, and the repositories below are guaranteed assigned.
        val minisApp = app as com.openminis.app.MinisApp
        NativeOffloadServer.register("android-alarm") { AlarmOffloadHandler(app) }
        NativeOffloadServer.register("android-calendar") { CalendarOffloadHandler(app) }
        // One clipboard handler instance serves two names (aliases, not
        // copies) — shared exactly-once via lazy.
        val clipboardOffload by lazy { ClipboardOffloadHandler(app) }
        NativeOffloadServer.register("android-clipboard") { clipboardOffload }
        NativeOffloadServer.register("minis-clipboard") { clipboardOffload }
        NativeOffloadServer.register("minis-toast") { ToastOffloadHandler(app) }
        NativeOffloadServer.register("minis-firewall") { FirewallOffloadHandler(app) }
        NativeOffloadServer.register("minis-doze") { DozeOffloadHandler(app) }
        NativeOffloadServer.register("minis-ps") { ProcfsOffloadHandler() }
        NativeOffloadServer.register("minis-notify") { MinisNotifyOffloadHandler(app) }
        NativeOffloadServer.register("minis-on-event") { HostEventOffloadHandler() }
        NativeOffloadServer.register("android-contacts") { ContactsOffloadHandler(app) }
        NativeOffloadServer.register("android-device") { DeviceOffloadHandler(app) }
        NativeOffloadServer.register("android-location") { LocationOffloadHandler(app) }
        NativeOffloadServer.register("android-notification") { NotificationOffloadHandler(app) }
        NativeOffloadServer.register("android-open") { OpenOffloadHandler(app) }
        NativeOffloadServer.register("android-photos") { PhotosOffloadHandler(app) }
        NativeOffloadServer.register("android-player") { PlayerOffloadHandler() }
        NativeOffloadServer.register("android-speak") { SpeakOffloadHandler(app) }
        NativeOffloadServer.register("android-speech") { SpeechOffloadHandler(app) }
        NativeOffloadServer.register("android-weather") { WeatherOffloadHandler(app) }
        // T323: UI-layer automation backed by MinisAccessibilityService.
        NativeOffloadServer.register("android-a11y-cli") { AccessibilityOffloadHandler(app) }
        NativeOffloadServer.register("minis-model-use") {
            ModelUseOffloadHandler(app, minisApp.providerRepository)
        }
        // T-config: minis-config — agent-facing settings management
        // (read/write registered ConfigFields with audit + revert).
        // Mirrors iOS `config_offload_register()` in ISHKernel.m.
        NativeOffloadServer.register("minis-config") { ConfigOffloadHandler() }
        NativeOffloadServer.register("minis-browser-use") { BrowserUseOffloadHandler(app) }
        // T188: minis-sessions-cli — agent-side query of chat history.
        // Registers next to the other minis-* tools so PRootKernel.
        // installHandlerStubs() picks it up on the next rootfs boot
        // (writes a 17-byte exit-0 stub at /usr/local/bin/minis-sessions-cli
        // so PATH lookup succeeds; PRoot intercepts the execve before
        // the stub runs and routes to this handler).
        NativeOffloadServer.register("minis-sessions-cli") {
            SessionsOffloadHandler(minisApp.chatRepository)
        }
        // [T-android-session-read-boundary] Cross-session reads are audited
        // regardless of scope; point the audit at app storage so the trail
        // survives a logcat flush. Only ids/actions are written, never bodies.
        com.openminis.app.sandbox.SessionAccessAudit.dir = app.filesDir.resolve("session-access")
        // The gate has no Context, but its guest-path policy has to know whether
        // /sdcard is really bind-mounted before refusing a command that names it.
        // shellSharedStorageBinds is the single source of truth for that plan, so
        // ask it instead of re-deriving the All Files Access state here.
        com.openminis.app.security.SecurityGateHolder.gate.sdcardMounted = {
            com.openminis.app.sandbox.PRootKernel
                .shellSharedStorageBinds(app, publish = false)
                .containsKey(com.openminis.app.sandbox.SharedStorageBindPlan.SDCARD)
        }
        // [T-android-scheduled-tasks-full] minis-scheduled — create/list/run
        // timed AI tasks (new chat / follow-up / re-run), mirroring the in-app
        // Scheduled Tasks editor and the iOS Shortcuts intent set.
        NativeOffloadServer.register("minis-scheduled") {
            ScheduledTaskOffloadHandler(app)
        }
        // T322: android-shizuku-cli — privileged Android control via Shizuku.
        // The handler short-circuits with a typed error envelope when the
        // user hasn't installed / started / authorized Shizuku, so we
        // can register unconditionally; ShizukuManager.init below wires
        // up the binder lifecycle listeners + StateFlow.
        NativeOffloadServer.register("android-shizuku-cli") { ShizukuOffloadHandler(app) }
        com.openminis.app.offload.ShizukuManager.init(app)
        // Host Magisk/KernelSU su passthrough (no Shizuku). Registered as
        // both `su` and `android-su` so PATH lookup and the android-* CLI
        // convention both work; installHandlerStubs writes both stubs. One
        // handler instance behind both names, exactly as before.
        val suHandler by lazy { SuOffloadHandler() }
        NativeOffloadServer.register("android-su") { suHandler }
        NativeOffloadServer.register("su") { suHandler }

        // T-android-minis-debug-cli: shell-side CLI wrapper around the in-app
        // DebugServer (127.0.0.1:5322) JSON-RPC. DEBUG-only — Release builds
        // ship neither the DebugServer nor this handler, so the
        // `/usr/local/bin/minis-debug` stub is also absent (PRootKernel.
        // installHandlerStubs enumerates currently-registered handlers).
        if (BuildConfig.DEBUG) {
            NativeOffloadServer.register(
                "minis-debug",
            ) { com.openminis.app.sandbox.offload.DebugOffloadHandler(app) }
        }
    }

    /**
     * Process-wide static-injection registry.
     *
     * The cross-layer singletons below used to live as public static fields
     * on their own consumer objects (ChatRuntime.binder, EvolutionHooks.engine,
     * ExecutionCoordinator.envVarRepository, EnvVarRedactor.envVarRepository,
     * PRootKernel.mountedFoldersStore, ConfigConfirmationGate.backgroundNotifier
     * and .cancelNotification) — each writable by anyone, from anywhere.
     * They now live here so the whole app has exactly ONE place that wires
     * them: [com.openminis.app.MinisApp.onCreate], via the install* entry
     * points, in the same order as before. The static fields on the consumer
     * objects are gone; their readers call the matching *OrNull accessor.
     *
     * Happens-before: installs run once on the main thread during onCreate,
     * while readers routinely sit on binder / IO threads. Every slot's
     * storage is @Volatile, preserving the visibility guarantee the old
     * per-object @Volatile fields gave. "Fully wired" for the app layer is
     * still signalled by MinisApp.subsystemsInitialized, unchanged.
     *
     * Slots created with `once = true` reject a second install (guards
     * against duplicate or out-of-order wiring re-running onCreate logic).
     * The config-confirm hooks are `once = false`: the unit suite legitimately
     * installs per-test stubs and unhooks them in tearDown.
     */
    private class Slot<T : Any>(private val name: String, private val once: Boolean = true) {
        @Volatile
        private var installed = false

        @Volatile
        private var value: T? = null

        fun install(v: T?) {
            if (once) check(!installed) { "AppGraph.$name installed twice" }
            installed = true
            value = v
        }

        fun orNull(): T? = value
    }

    /** UI-agnostic chat bridge for debug/headless RPC (was ChatRuntime.binder). */
    private val chatBinder = Slot<ChatSessionBinder>("chatBinder")
    fun installChatBinder(binder: ChatSessionBinder) = chatBinder.install(binder)
    fun chatBinderOrNull(): ChatSessionBinder? = chatBinder.orNull()

    /** Evolution engine handoff; nullable — construction is runCatching-guarded (was EvolutionHooks.engine). */
    private val evolutionEngine = Slot<EvolutionEngine>("evolutionEngine")
    fun installEvolutionEngine(engine: EvolutionEngine?) = evolutionEngine.install(engine)
    fun evolutionEngineOrNull(): EvolutionEngine? = evolutionEngine.orNull()

    /** Env-var source for sandbox shells (was ExecutionCoordinator.envVarRepository). */
    private val executionEnvVarRepository = Slot<EnvVarRepository>("executionEnvVarRepository")
    fun installExecutionEnvVarRepository(repo: EnvVarRepository) = executionEnvVarRepository.install(repo)
    fun executionEnvVarRepositoryOrNull(): EnvVarRepository? = executionEnvVarRepository.orNull()

    /** Env-var source for privacy-mode redaction (was EnvVarRedactor.envVarRepository). */
    private val redactorEnvVarRepository = Slot<EnvVarRepository>("redactorEnvVarRepository")
    fun installRedactorEnvVarRepository(repo: EnvVarRepository) = redactorEnvVarRepository.install(repo)
    fun redactorEnvVarRepositoryOrNull(): EnvVarRepository? = redactorEnvVarRepository.orNull()

    /** User bind-mount store; null tolerated as "no mounts" (was PRootKernel.mountedFoldersStore). */
    private val mountedFoldersStore = Slot<MountedFoldersStore>("mountedFoldersStore")
    fun installMountedFoldersStore(store: MountedFoldersStore) = mountedFoldersStore.install(store)
    fun mountedFoldersStoreOrNull(): MountedFoldersStore? = mountedFoldersStore.orNull()

    /** Background nudge for pending config approvals (was ConfigConfirmationGate.backgroundNotifier). */
    private val configConfirmBackgroundNotifier =
        Slot<(PendingConfigChange) -> Boolean>("configConfirmBackgroundNotifier", once = false)
    fun installConfigConfirmBackgroundNotifier(notifier: ((PendingConfigChange) -> Boolean)?) =
        configConfirmBackgroundNotifier.install(notifier)
    fun configConfirmBackgroundNotifierOrNull(): ((PendingConfigChange) -> Boolean)? =
        configConfirmBackgroundNotifier.orNull()

    /** Cancels the pending-approval notification once a change resolves (was .cancelNotification). */
    private val configConfirmCancelNotification =
        Slot<(String) -> Unit>("configConfirmCancelNotification", once = false)
    fun installConfigConfirmCancelNotification(cancel: ((String) -> Unit)?) =
        configConfirmCancelNotification.install(cancel)
    fun configConfirmCancelNotificationOrNull(): ((String) -> Unit)? =
        configConfirmCancelNotification.orNull()
}
