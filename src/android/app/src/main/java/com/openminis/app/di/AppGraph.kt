package com.openminis.app.di

import com.openminis.app.config.confirm.PendingConfigChange
import com.openminis.app.data.MountedFoldersStore
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.evolution.EvolutionEngine
import com.openminis.app.session.ChatSessionBinder

/** Startup wiring boundary between Application and containerized repositories. */
object AppGraph {
    fun create(scopes: AppCoroutineScopes): AppContainer = AppContainer(scopes)

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
