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

/** Mutable assembly graph used only during Application startup; readers see require* accessors. */
class AppContainer(val scopes: AppCoroutineScopes) {
    var database: AppDatabase? = null
    var chatRepository: ChatRepository? = null
    var providerRepository: ProviderRepository? = null
    var envVarRepository: EnvVarRepository? = null
    var skillRepository: SkillRepository? = null
    var mcpRepository: MCPRepository? = null
    var memoryRepository: MemoryRepository? = null
    var evolutionEngine: EvolutionEngine? = null
    var webAppShortcutRepository: WebAppShortcutRepository? = null
    var backgroundSettingsRepository: BackgroundSettingsRepository? = null
    var multiAgentSettingsRepository: MultiAgentSettingsRepository? = null
    var backgroundTaskNotifier: BackgroundTaskNotifier? = null
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
