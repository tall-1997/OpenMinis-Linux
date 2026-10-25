package com.openminis.app.di

import com.openminis.app.data.MountedFoldersStore
import com.openminis.app.data.db.AppDatabase
import com.openminis.app.data.repository.BackgroundSettingsRepository
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.data.repository.MemoryRepository
import com.openminis.app.data.repository.MultiAgentSettingsRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.repository.SkillRepository
import com.openminis.app.data.repository.WebAppShortcutRepository
import com.openminis.app.evolution.EvolutionEngine
import com.openminis.app.notification.BackgroundTaskNotifier

/**
 * Mutable assembly graph used only during Application startup; readers see
 * require* accessors. Every slot is [Volatile]: assignments happen on the
 * main thread in [com.openminis.app.MinisApp.onCreate], while readers may sit
 * on binder / IO threads — the annotation guarantees each reader either sees
 * the assigned instance or null, never a half-published reference. Callers
 * that need "fully wired" semantics still gate on
 * [com.openminis.app.MinisApp.subsystemsInitialized].
 */
class AppContainer(val scopes: AppCoroutineScopes) {
    @Volatile
    var database: AppDatabase? = null

    @Volatile
    var chatRepository: ChatRepository? = null

    @Volatile
    var providerRepository: ProviderRepository? = null

    @Volatile
    var envVarRepository: EnvVarRepository? = null

    @Volatile
    var skillRepository: SkillRepository? = null

    @Volatile
    var mcpRepository: MCPRepository? = null

    @Volatile
    var memoryRepository: MemoryRepository? = null

    @Volatile
    var evolutionEngine: EvolutionEngine? = null

    @Volatile
    var webAppShortcutRepository: WebAppShortcutRepository? = null

    @Volatile
    var backgroundSettingsRepository: BackgroundSettingsRepository? = null

    @Volatile
    var multiAgentSettingsRepository: MultiAgentSettingsRepository? = null

    @Volatile
    var backgroundTaskNotifier: BackgroundTaskNotifier? = null

    @Volatile
    var mountedFoldersStore: MountedFoldersStore? = null

    fun requireChatRepository() = checkNotNull(chatRepository)
    fun requireProviderRepository() = checkNotNull(providerRepository)
    fun requireEnvVarRepository() = checkNotNull(envVarRepository)
    fun requireSkillRepository() = checkNotNull(skillRepository)
    fun requireMcpRepository() = checkNotNull(mcpRepository)
    fun requireMemoryRepository() = checkNotNull(memoryRepository)
    fun requireBackgroundSettingsRepository() = checkNotNull(backgroundSettingsRepository)
    fun requireMultiAgentSettingsRepository() = checkNotNull(multiAgentSettingsRepository)
    fun requireMountedFoldersStore() = checkNotNull(mountedFoldersStore)
}
