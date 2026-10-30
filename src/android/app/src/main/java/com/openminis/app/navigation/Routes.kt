package com.openminis.app.navigation

/**
 * Nav route constants. Lives outside ui/ so non-ui layers (deeplink,
 * MainActivity) can reference destinations without importing ui.*
 * (architecture-policy forbiddenImports). Moved verbatim from
 * ui/navigation/AppNavigation.kt; [navEnc] came with it from FilePreviewRoute.kt.
 */

internal fun navEnc(v: String): String =
    java.net.URLEncoder.encode(v, "UTF-8").replace("+", "%20")

object Routes {
    const val SESSION_LIST = "sessions"
    const val CHAT = "chat/{sessionId}"
    const val SETTINGS = "settings"
    const val PROVIDER_LIST = "providers"
    const val ADD_PROVIDER = "add_provider"
    const val PROVIDER_DETAIL = "provider/{instanceId}"
    /** [T-android-provider-voice] Read-only shadow Voice Service detail. */
    const val SHADOW_VOICE_DETAIL = "voice_service/{instanceId}"
    const val MODEL_GROUPS = "model_groups"
    const val MODEL_GROUP_DETAIL = "model_group/{groupId}"
    const val ADD_MODELS_TO_GROUP = "add_models_to_group/{groupId}"
    /** T185: picker that adds model *entries* to the agent-loop set. */
    const val ADD_MODELS_TO_AGENT_LOOP = "add_models_to_agent_loop"
    /** T185: picker that adds model *groups* to the agent-loop set. */
    const val ADD_GROUPS_TO_AGENT_LOOP = "add_groups_to_agent_loop"
    /** T171→T182: AGENT_LOOP_MODELS deprecated (the screen lived inside
     *  Settings, now the picker is a section inside ModelGroupsScreen).
     *  Route declared so any back-compat deep-link string from preview
     *  builds pops back instead of crashing. */
    const val AGENT_LOOP_MODELS = "agent_loop_models"
    const val MODEL_ENTRY_DETAIL = "model_entry/{instanceId}/{entryId}"
    const val ADD_CUSTOM_MODEL = "add_custom_model/{instanceId}"
    const val STORAGE = "storage"
    const val BACKUP = "backup"
    const val BACKUP_DESTINATIONS = "backup_destinations"
    const val BACKUP_HISTORY_DETAIL = "backup_history_detail"
    const val BACKUP_DESTINATION_BROWSE = "backup_destination_browse"
    const val RESTORE_BROWSE = "restore_browse"
    // [T-android-restore-server-list] The servers you can restore FROM, plus
    // an Add Server entry. Separate from BACKUP_DESTINATIONS, which is the
    // editable management screen for the same servers.
    const val RESTORE_SERVERS = "restore_servers"
    const val SESSION_STORAGE_DETAIL = "session_storage/{sessionId}"
    const val ROOTFS_MANAGEMENT = "rootfs_management"
    const val MIRROR_CATEGORY_DETAIL = "mirror_category/{categoryKey}"
    fun mirrorCategoryDetail(categoryKey: String) = "mirror_category/$categoryKey"
    const val FILE_BROWSER = "file_browser"
    // [T-android-filepreview-nav-args] The previewed file's absolute path is
    // carried as a nav argument so the destination survives process death.
    // Previously the hand-off was only the process-static FilePreviewHolder:
    // if the system killed the app while a SAF CreateDocument sheet was up
    // (the classic low-memory moment), the restored FILE_PREVIEW entry found
    // a null Holder, popped itself — and the SAF result then delivered to a
    // dead screen. With the path in the back stack entry the destination
    // rehydrates (FileItem.from) and the pending SAF result lands on a live
    // preview. Holder stays as the hot path (no re-stat on every navigate).
    const val FILE_PREVIEW = "file_preview?path={path}"
    // Encoding recipe: see navEnc() in FilePreviewRoute.kt.
    fun filePreview(path: String): String = "file_preview?path=${navEnc(path)}"
    const val ENV_VARS = "env_vars"
    const val SKILLS = "skills"
    const val MULTI_AGENT = "multi_agent"
    const val SKILL_DETAIL = "skill/{skillId}"
    const val SKILL_FILE = "skill_file/{skillId}/{relativePath}"
    const val MINIS_SKILLS_BROWSER = "minis_skills_browser"
    const val WEB_SEARCH = "web_search"
    const val CHARACTER_EXTRAS = "character_extras"
    const val TRANSLATE = "translate"
    const val TRANSLATE_PAD = "translate_pad"
    const val HOME_BROWSER = "home_browser"
    const val HOME_BROWSER_SETTINGS = "home_browser_settings"
    const val TOOL_LIMITS = "tool_limits"

    fun skillDetail(skillId: String) = "skill/$skillId"
    fun skillFile(skillId: String, relativePath: String = "SKILL.md"): String =
        // Path may contain `/`, which the nav library treats as a route
        // separator. URL-encode so subdirectory paths survive a round-trip.
        "skill_file/$skillId/${navEnc(relativePath)}"
    const val TERMINAL = "terminal?initCommand={initCommand}&sessionId={sessionId}"
    fun terminal(initCommand: String? = null, sessionId: String? = null): String {
        val params = buildList {
            if (initCommand != null) add("initCommand=${navEnc(initCommand)}")
            if (sessionId != null) add("sessionId=${navEnc(sessionId)}")
        }
        return if (params.isEmpty()) "terminal" else "terminal?${params.joinToString("&")}"
    }
    /** Chat-files browser: opens FileBrowser rooted at /var/minis for the session. */
    const val CHAT_FILES = "chat_files/{sessionId}"
    fun chatFiles(sessionId: String) = "chat_files/$sessionId"
    /** [P2-git-panel] Git panel over the session workspace (status / commit / history / diff). */
    const val GIT_PANEL = "git_panel/{sessionId}"
    fun gitPanel(sessionId: String) = "git_panel/${android.net.Uri.encode(sessionId)}"
    const val MEMORY = "memory"
    /** [T-mcp-integration-android] MCP Integrations management screen. */
    const val MCP = "mcp"
    const val MCP_TOOLS = "mcp_tools/{serverId}"
    fun mcpTools(serverId: String) = "mcp_tools/${android.net.Uri.encode(serverId)}"
    const val PLUGIN_MARKET = "plugin_market"
    // [T-builtin-gateway-native] Agent gateways are app infrastructure (the
    // payload ships in the APK, the service runs on loopback), so each has a
    // settings page instead of a market row. A2A's literal is load-bearing:
    // preview-build deep links from before the move still point at it.
    const val A2A = "settings/a2a"
    /** [T-soul-md] SOUL.md editor. */
    const val SOUL = "soul"
    const val EVOLUTION = "evolution"
    const val MEMORY_FILE_EDIT = "memory_file/{fileName}/{isGlobal}"
    const val PERMISSIONS = "permissions"
    /**
     * T322 / [T-android-privileged-backend]: Shizuku-protocol manager
     * walkthrough — handles both Shizuku and AXManager (they share the same
     * binder protocol + client SDK).
     */
    const val SHIZUKU = "shizuku"
    const val HOST_SU = "host_su"
    const val USAGE_STATS = "usage_stats"
    const val LOGS = "logs"
    const val LOG_DETAIL = "log_detail/{fileName}"
    const val APPEARANCE = "appearance"
    const val BACKGROUND = "background"
    const val ABOUT = "about"
    const val ONBOARDING_MODELS = "onboarding_models"
    /** T219-2: Mount external folders settings + detail. */
    const val MOUNTED_FOLDERS = "mounted_folders"
    const val MOUNTED_FOLDERS_DETAIL = "mounted_folders_detail/{mountId}"
    fun mountedFoldersDetail(mountId: String) = "mounted_folders_detail/$mountId"
    /** T235: Shared folders (Shared / Skills / Memory) — fixed list. */
    const val SHARED_FOLDERS = "shared_folders"
    const val SHARED_FOLDERS_DETAIL = "shared_folders_detail/{folderId}"
    fun sharedFoldersDetail(folderId: String) = "shared_folders_detail/$folderId"
    /** [T-android-scheduled-tasks-design] Scheduled tasks list + editor. */
    const val SCHEDULED_TASKS = "scheduled_tasks"
    const val SCHEDULED_TASK_EDIT = "scheduled_tasks/edit?taskId={taskId}"
    fun scheduledTaskEdit(taskId: String? = null): String =
        if (taskId == null) "scheduled_tasks/edit" else "scheduled_tasks/edit?taskId=$taskId"
    // [T-android-scheduled-tasks-run-records] per-task execution log.
    const val SCHEDULED_TASK_RUNS = "scheduled_tasks/runs/{taskId}"
    fun scheduledTaskRuns(taskId: String): String = "scheduled_tasks/runs/$taskId"

    fun logDetail(fileName: String) = "log_detail/$fileName"
    fun sessionStorageDetail(sessionId: String) = "session_storage/$sessionId"
    fun memoryFileEdit(fileName: String, isGlobal: Boolean) = "memory_file/$fileName/$isGlobal"
    fun chat(sessionId: String) = "chat/$sessionId"
    fun providerDetail(instanceId: String) = "provider/$instanceId"
    fun shadowVoiceDetail(instanceId: String) = "voice_service/$instanceId"
    fun modelGroupDetail(groupId: String) = "model_group/$groupId"
    fun addModelsToGroup(groupId: String) = "add_models_to_group/$groupId"
    // [T-android-model-entry-route-slash-crash] entryId is a composite key
    // "<instanceId>/<modelId>" (compositeEntryKey) — it CONTAINS a '/'. Left
    // raw, that slash splits the route into an extra path segment, so the
    // built route no longer matches the registered MODEL_ENTRY_DETAIL pattern
    // (model_entry/{instanceId}/{entryId}) and navigate() throws
    // IllegalArgumentException "destination … cannot be found" — a guaranteed
    // crash on tapping any model whose id carries a '/'. URL-encode it so the
    // slash becomes %2F (one segment); the receiver decodes it back.
    fun modelEntryDetail(instanceId: String, entryId: String) =
        "model_entry/${android.net.Uri.encode(instanceId)}/${android.net.Uri.encode(entryId)}"
    fun addCustomModel(instanceId: String) = "add_custom_model/$instanceId"
}
