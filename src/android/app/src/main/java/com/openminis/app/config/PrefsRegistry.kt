package com.openminis.app.config

/*
 * 自动生成 —— 不要手改。
 * 生成器: scripts/prefs/gen_prefs_registry.py
 * 源目录: src/android/app/src/main/java/com/openminis/app
 * 规模:   105 个 prefs 文件（加密 10）/ 已归属键常量 206 / 184 处使用
 *
 * 目的: 把「SharedPreferences 文件名 + 键名」从 93 个文件里的裸字符串收敛为一处
 * 可枚举、可校验、可审计的数据表。
 */

/** 存储性质，决定迁移策略（不决定读写 API）。 */
enum class PrefsScope {
    /** 明文 SharedPreferences，可迁移到 DataStore。 */
    PLAIN,

    /** AES 加密（EncryptedPrefsFactory），只登记不迁移。 */
    ENCRYPTED,

    /** 一次性标记位（安装提示 / 重试计数 / 健康缓存），迁移无收益。 */
    EPHEMERAL,
}

/**
 * @param name   SharedPreferences 文件名字面量
 * @param module 主要使用方（app 内顶层包名）
 * @param keys   已归集的键常量（常量名 -> 键名字面量）
 * @param dynamicPrefixes 允许动态拼接的键前缀。全仓实测有 8 处这种写法，例如
 *        UnreadRegistry 的 `"lastReadAt:" + sessionId`、BackgroundSettingsRepository
 *        的 `KEY_BG_OVERLAY_X + suffix`、ConnectorNetworkPolicy 的
 *        `"allow_private_" + configurationKey()`。这些键无法被静态扫描归集，
 *        但它们是合法写入 —— 所以要在 spec 里显式声明前缀，否则严格校验会锁死
 *        一个仍在正常工作的文件。
 */
data class PrefsFileSpec(
    val name: String,
    val module: String,
    val scope: PrefsScope,
    val keys: Map<String, String> = emptyMap(),
    val dynamicPrefixes: List<String> = emptyList(),
    /**
     * 键空间是否开放：键名本身就是数据（插件 id、组件 id、session id），
     * 无法静态枚举。这类文件只能整体放行 —— 但必须在注册表里显式声明，
     * 不能靠 keys 为空来推断（keys 为空更可能是扫描器没抓到，那是 bug）。
     */
    val openKeySpace: Boolean = false,
    val note: String = "",
) {
    fun isEncrypted(): Boolean = scope == PrefsScope.ENCRYPTED

    /** 该键是否可以出现在本文件里（静态登记 or 命中动态前缀 or 开放键空间）。 */
    fun accepts(key: String): Boolean =
        openKeySpace || keys.containsValue(key) || dynamicPrefixes.any { key.startsWith(it) }
}

object PrefsRegistry {

    val files: List<PrefsFileSpec> = listOf(
        PrefsFileSpec(name = "a11y_app_policy", module = "accessibility", scope = PrefsScope.PLAIN, keys = mapOf("KEY" to "policy_json"), note = "2 usages across 1 file(s)"),
        PrefsFileSpec(name = "a11y_recovery", module = "accessibility", scope = PrefsScope.PLAIN, keys = mapOf("KEY_EVER_GRANTED" to "ever_granted"), note = "2 usages across 1 file(s)"),
        PrefsFileSpec(name = "a2a_gateway", module = "data", scope = PrefsScope.PLAIN, openKeySpace = true, note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "agent_fgs", module = "service", scope = PrefsScope.PLAIN, keys = mapOf("KEY_CHANNEL_RESTORED" to "channel_visible_restored"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "app_icon_prefs", module = "data", scope = PrefsScope.PLAIN, keys = mapOf("KEY_SELECTED_ID" to "selected_icon_id"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "appearance_prefs", module = ".", scope = PrefsScope.PLAIN, keys = mapOf("KEY" to "chat.confirmBeforeStop", "KEY_AUTO_EXPAND_THINKING" to "chat.autoExpandThinking", "KEY_AUTO_FOCUS_AFTER_REPLY" to "chat.autoFocusAfterReply", "KEY_AUTO_GROUPING" to "autoGroupingEnabled", "KEY_COMPOSER_CONTEXT_GLOW" to "chat.composerContextGlow", "KEY_CURRENT_CHAT_SESSION_ID" to "minis.current_chat_session_id", "KEY_DYNAMIC_COLOR" to "chat.dynamicColor", "KEY_FOLD_AI_PROCESS" to "chat.foldAiProcess", "KEY_FONT_APP_BASE" to "font_app_base", "KEY_FONT_CHAT_INPUT" to "font_chat_input", "KEY_FONT_MESSAGE" to "font_message", "KEY_KEEP_SCREEN_AWAKE" to "keepScreenAwakeDuringTasks", "KEY_LANGUAGE" to "app_language", "KEY_LAUNCH_SESSION" to "launch_session", "KEY_RETURN_KEY_BEHAVIOR" to "returnKeyBehavior", "KEY_SHOW_CHAT_TITLE" to "appearance.show_chat_title", "KEY_SHOW_COMPLETED_TOOL_CARDS" to "chat.showCompletedToolCards", "KEY_SHOW_FLOATING_TOOL_BAR" to "chat.showFloatingToolBar", "KEY_SHOW_FULL_THINKING" to "chat.showFullThinking", "KEY_SHOW_MESSAGE_TOKEN_USAGE" to "chat.showMessageTokenUsage", "KEY_SHOW_MIC_BUTTON" to "chat.showMicButton", "KEY_SHOW_SUBAGENT_BAR" to "chat.showSubAgentBar", "KEY_SHOW_TURN_CHANGES_CARD" to "chat.showTurnChangesCard", "KEY_THEME_MODE" to "theme_mode", "KEY_TOOL_PREVIEW" to "tool_preview", "THEME_MODE_KEY" to "theme_mode"), note = "7 usages across 6 file(s)"),
        PrefsFileSpec(name = "background_settings", module = "config", scope = PrefsScope.PLAIN, keys = mapOf("KEY_BG_NOTIFICATIONS" to "background_notifications_enabled", "KEY_BG_OVERLAY_ENABLED" to "backgroundOverlayEnabled", "KEY_BG_OVERLAY_X" to "backgroundOverlayX", "KEY_BG_OVERLAY_Y" to "backgroundOverlayY", "KEY_DYNAMIC_ISLAND_ENABLED" to "dynamicIslandEnabled", "KEY_ENHANCED_BG" to "enhanced_background_execution", "KEY_TASK_NOTIFICATIONS" to "taskNotificationsEnabled", "PRESENCE_KEEP_ALIVE_KEY" to "presenceKeepAliveEnabled"), dynamicPrefixes = listOf("backgroundOverlayX", "backgroundOverlayY"), note = "2 usages across 2 file(s)"),
        PrefsFileSpec(name = "backup_rclone_remotes", module = "backup", scope = PrefsScope.PLAIN, openKeySpace = true, note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "backup_rclone_secrets", module = "backup", scope = PrefsScope.ENCRYPTED, openKeySpace = true, note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "backup_ui", module = "ui", scope = PrefsScope.PLAIN, keys = mapOf("KEY_CATEGORIES" to "selectedCategories", "KEY_ENCRYPT" to "encrypt", "KEY_MAX_FILE_MB" to "maxFileSizeMB"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "bash_install", module = "agent", scope = PrefsScope.PLAIN, keys = mapOf("KEY_FAIL_COUNT" to "failCount", "KEY_LAST_FAIL" to "lastFailAt"), note = "3 usages across 1 file(s)"),
        PrefsFileSpec(name = "boot_hook", module = "sandbox", scope = PrefsScope.PLAIN, keys = mapOf("KEY_ENABLED" to "enabled", "KEY_FIRST_SEEN_AT" to "first_seen_at", "KEY_LAST_FINGERPRINT" to "last_script_fingerprint"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "browser_prefs", module = "browser", scope = PrefsScope.PLAIN, keys = mapOf("LITERAL_custom_user_agent" to "custom_user_agent", "LITERAL_user_agent_profile" to "user_agent_profile", "PREF_GLOBAL_VIEWPORT_HEIGHT" to "browser_custom_viewport_height", "PREF_GLOBAL_VIEWPORT_WIDTH" to "browser_custom_viewport_width", "PREF_IDLE_TIMEOUT_MINUTES" to "idle_timeout_minutes"), dynamicPrefixes = listOf("engine_stale_seen_"), note = "6 usages across 3 file(s)"),
        PrefsFileSpec(name = "claude_cli_version", module = "auth", scope = PrefsScope.PLAIN, keys = mapOf("KEY_FETCHED_AT" to "fetched_at", "KEY_VERSION" to "version"), note = "2 usages across 1 file(s)"),
        PrefsFileSpec(name = "collab_roles", module = "tools", scope = PrefsScope.PLAIN, keys = mapOf("KEY" to "roles_json"), note = "2 usages across 1 file(s)"),
        PrefsFileSpec(name = "composer_input_prefs", module = "ui", scope = PrefsScope.PLAIN, keys = mapOf("KEY_MODE" to "composer_input_mode_pref"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "cot_translation", module = "cottranslate", scope = PrefsScope.PLAIN, keys = mapOf("KEY_ENABLED" to "enabled", "KEY_LANGUAGE" to "language", "KEY_PRIVACY_ACK" to "privacy_ack", "KEY_PROVIDER" to "provider"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "crash_freq_prefs", module = "crash", scope = PrefsScope.PLAIN, keys = mapOf("KEY_DISMISSED_AT" to "dismissed_at", "KEY_FORCE_HOME_UNTIL" to "force_home_until", "KEY_SUPPRESS_UNTIL" to "suppress_until"), note = "6 usages across 1 file(s)"),
        PrefsFileSpec(name = "debug_server_prefs", module = "debug", scope = PrefsScope.PLAIN, keys = mapOf("LAN_LISTEN_KEY" to "lan_listen"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "dev_suite_state", module = "plugins", scope = PrefsScope.PLAIN, openKeySpace = true, note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "display_prefs", module = "ui", scope = PrefsScope.PLAIN, keys = mapOf("KEY" to "high_refresh"), note = "2 usages across 1 file(s)"),
        PrefsFileSpec(name = "dpkg_world_retry", module = "sandbox", scope = PrefsScope.EPHEMERAL, keys = mapOf("KEY_STRIKES" to "strikes"), note = "2 usages across 1 file(s)"),
        PrefsFileSpec(name = "dummy", module = "auth", scope = PrefsScope.PLAIN, note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "env_var_values", module = "data", scope = PrefsScope.ENCRYPTED, note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "envvar_privacy", module = "data", scope = PrefsScope.PLAIN, keys = mapOf("KEY" to "privacy_mode_enabled"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "file_browser_prefs", module = "ui", scope = PrefsScope.PLAIN, keys = mapOf("PREF_KEY_SHOW_HIDDEN" to "file_browser_show_hidden"), note = "2 usages across 1 file(s)"),
        PrefsFileSpec(name = "free_lane_health", module = "provider", scope = PrefsScope.PLAIN, keys = mapOf("KEY_DEAD" to "dead_ids"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "hang_detector_prefs", module = "diagnostics", scope = PrefsScope.PLAIN, keys = mapOf("KEY_HANG_COUNT" to "hang_count", "KEY_LAST_HANG_AT" to "last_hang_at_ms"), note = "4 usages across 1 file(s)"),
        PrefsFileSpec(name = "host_event_hooks", module = "sandbox", scope = PrefsScope.PLAIN, keys = mapOf("KEY" to "hooks_json"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "lifecycle_hooks", module = "agent", scope = PrefsScope.PLAIN, keys = mapOf("KEY" to "config_json"), note = "2 usages across 1 file(s)"),
        PrefsFileSpec(name = "local_tool_plugins", module = "plugins", scope = PrefsScope.PLAIN, keys = mapOf("KEY" to "tools_json"), note = "3 usages across 1 file(s)"),
        PrefsFileSpec(name = "logging_prefs", module = "logging", scope = PrefsScope.PLAIN, keys = mapOf("KEY_ENABLED" to "logging_enabled"), note = "3 usages across 1 file(s)"),
        PrefsFileSpec(name = "mcp_approvals", module = "mcp", scope = PrefsScope.PLAIN, keys = mapOf("KEY_STATE" to "state"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "mcp_oauth_secrets", module = "mcp", scope = PrefsScope.ENCRYPTED, dynamicPrefixes = listOf("secret::", "tokens::"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "mcp_server", module = "mcp", scope = PrefsScope.PLAIN, keys = mapOf("KEY_ENABLED" to "enabled", "KEY_PORT" to "port", "KEY_SHELL_POLICY" to "shell_policy", "KEY_TOKEN" to "token"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "mcp_server_secrets", module = "mcp", scope = PrefsScope.ENCRYPTED, note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "minis_alarms_prefs", module = ".", scope = PrefsScope.PLAIN, keys = mapOf("KEY_ALARMS" to "alarms_json", "KEY_NEXT_REQUEST_CODE" to "next_request_code"), note = "2 usages across 2 file(s)"),
        PrefsFileSpec(name = "minis_auto_compact_prefs", module = "data", scope = PrefsScope.PLAIN, keys = mapOf("KEY_ENABLED" to "autoCompactOnThreshold", "KEY_THRESHOLD" to "autoCompactThresholdPercent"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "minis_cache_warming", module = "data", scope = PrefsScope.PLAIN, keys = mapOf("KEY_ENABLED" to "cacheWarmingEnabled"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "minis_config_permission", module = "config", scope = PrefsScope.PLAIN, keys = mapOf("KEY" to "minis_config_enabled"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "minis_context_saver", module = "data", scope = PrefsScope.PLAIN, keys = mapOf("KEY_MODE" to "mode"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "minis_embedding_prefs", module = "data", scope = PrefsScope.PLAIN, keys = mapOf("KEY_API_KEY" to "embedding.api_key", "KEY_BASE_URL" to "embedding.base_url", "KEY_ENABLED" to "embedding.enabled", "KEY_MODEL" to "embedding.model"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "minis_enhanced_cache_prefs", module = "data", scope = PrefsScope.PLAIN, keys = mapOf("KEY_CONFIRMED" to "enhancedCacheConfirmed"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "minis_evolution_prefs", module = "evolution", scope = PrefsScope.PLAIN, keys = mapOf("KEY_DAILY_CAP" to "evolution.daily_cap", "KEY_ENABLED" to "evolution.enabled", "KEY_LAST_HARVEST" to "evolution.last_harvest_at", "KEY_LAST_REFLECT" to "evolution.last_reflect_at", "KEY_LLM_COUNT" to "evolution.llm_count", "KEY_LLM_DAY" to "evolution.llm_day", "KEY_LLM_FAILS" to "evolution.llm_fails"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "minis_fast_mode_prefs", module = "data", scope = PrefsScope.PLAIN, keys = mapOf("KEY_ENABLED" to "codexFastModeEnabled"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "minis_goal_drift_prefs", module = "data", scope = PrefsScope.PLAIN, keys = mapOf("KEY_STALL_TURNS" to "goalDriftStallTurns"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "minis_group_chat", module = "ui", scope = PrefsScope.PLAIN, dynamicPrefixes = listOf("closed:", "enabled:", "opening:", "round:"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "minis_guard_prefs", module = "data", scope = PrefsScope.PLAIN, keys = mapOf("KEY_STOP_ENABLED" to "loopGuardStopEnabled"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "minis_memory_prefs", module = "config", scope = PrefsScope.PLAIN, keys = mapOf("KEY_GLOBAL_ENABLED" to "memory.global.enabled"), note = "2 usages across 2 file(s)"),
        PrefsFileSpec(name = "minis_network", module = "network", scope = PrefsScope.PLAIN, keys = mapOf("KEY" to "network.settings.v1"), note = "2 usages across 1 file(s)"),
        PrefsFileSpec(name = "minis_network_secrets", module = "network", scope = PrefsScope.ENCRYPTED, dynamicPrefixes = listOf("proxy."), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "minis_phone_backup_folders", module = "backup", scope = PrefsScope.PLAIN, keys = mapOf("KEY" to "folders", "KEY_CONFIG_DECODE_FAILED" to "config_decode_failed"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "minis_plan_discussion_prefs", module = "data", scope = PrefsScope.PLAIN, keys = mapOf("KEY_ENABLED" to "planDiscussionEnabled", "KEY_MODE" to "planDiscussionMode"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "minis_quick", module = "quick", scope = PrefsScope.PLAIN, keys = mapOf("KEY_PANEL_AT" to "panel_session_at", "KEY_PANEL_SESSION" to "panel_session_id"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "minis_scheduled_notifications", module = "offload", scope = PrefsScope.PLAIN, keys = mapOf("KEY" to "scheduled"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "minis_scheduled_tasks_prefs", module = "scheduled", scope = PrefsScope.PLAIN, keys = mapOf("KEY_TASKS" to "tasks_json"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "minis_storage_scan", module = "data", scope = PrefsScope.PLAIN, keys = mapOf("KEY_ROOTFS_BYTES" to "ubuntu_rootfs_bytes"), note = "2 usages across 1 file(s)"),
        PrefsFileSpec(name = "minis_tool_limits", module = "data", scope = PrefsScope.PLAIN, keys = mapOf("KEY_AUTO_CONCURRENCY" to "autoConcurrency", "KEY_CHARS" to "fileReadMaxChars", "KEY_COMMAND_CONCURRENCY" to "commandConcurrency", "KEY_HEAVY_CONCURRENCY" to "heavyConcurrency", "KEY_IDLE_SHELL_COUNT" to "idleShellCount", "KEY_IDLE_SHELL_MINUTES" to "idleShellMinutes", "KEY_LINES" to "fileReadMaxLines", "KEY_MAIN_TURNS" to "mainSessionMaxTurns", "KEY_QUEUE_TIMEOUT_SEC" to "queueTimeoutSec", "KEY_SHELL" to "shellTimeoutSec", "KEY_TOOL_CALL_CONCURRENCY" to "toolCallConcurrency", "KEY_TURNS" to "subagentMaxTurns", "LITERAL_autoConcurrency" to "autoConcurrency", "LITERAL_toolCallConcurrency" to "toolCallConcurrency"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "minis_unread_registry", module = "data", scope = PrefsScope.PLAIN, dynamicPrefixes = listOf("lastReadAt:"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "mirror_settings", module = "sandbox", scope = PrefsScope.PLAIN, keys = mapOf("KEY_FRESH_INSTALL" to "rootfs.freshInstall"), dynamicPrefixes = listOf("mirror.selected.", "mirror.useCustom."), note = "2 usages across 2 file(s)"),
        PrefsFileSpec(name = "multi_agent_settings", module = "data", scope = PrefsScope.PLAIN, keys = mapOf("KEY_ENABLED" to "enabled", "KEY_MAX_CONCURRENT" to "max_concurrent", "KEY_MODEL_IDS" to "model_entry_ids", "KEY_SUBAGENT_MAX_ATTEMPTS" to "subagent_max_attempts", "KEY_SUBAGENT_MAX_TURNS" to "subagent_max_turns", "KEY_SUPERVISOR_MAX_WORKER_TURNS" to "supervisor_max_worker_turns", "KEY_WORKER_MODEL_ID" to "worker_model_entry_id"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "oauth_prefs", module = "auth", scope = PrefsScope.ENCRYPTED, keys = mapOf("KEY_MANUAL_BEARER" to "manual_bearer_token"), dynamicPrefixes = listOf("oauth_", "oauth_manual_bearer_token_", "oauth_tokens_"), note = "5 usages across 3 file(s)"),
        PrefsFileSpec(name = "offload_permissions", module = "offload", scope = PrefsScope.PLAIN, keys = mapOf("KEY" to "privacy_global_level"), dynamicPrefixes = listOf("level_"), note = "3 usages across 2 file(s)"),
        PrefsFileSpec(name = "offload_permissions_asked", module = "offload", scope = PrefsScope.EPHEMERAL, openKeySpace = true, note = "2 usages across 1 file(s)"),
        PrefsFileSpec(name = "outbound_mask", module = "data", scope = PrefsScope.PLAIN, keys = mapOf("KEY" to "enabled"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "pending_update", module = "data", scope = PrefsScope.PLAIN, keys = mapOf("KEY" to "pending", "KEY_INTENT" to "pending_intent"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "pip_world_retry", module = "sandbox", scope = PrefsScope.EPHEMERAL, keys = mapOf("KEY_STRIKES" to "strikes"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "plugin_network_policy", module = "plugins", scope = PrefsScope.PLAIN, note = "3 usages across 1 file(s)"),
        PrefsFileSpec(name = "plugin_online_flags", module = "plugins", scope = PrefsScope.PLAIN, openKeySpace = true, note = "3 usages across 1 file(s)"),
        PrefsFileSpec(name = "plugin_online_keys", module = "plugins", scope = PrefsScope.ENCRYPTED, openKeySpace = true, note = "3 usages across 1 file(s)"),
        PrefsFileSpec(name = "plugin_registry", module = "plugins", scope = PrefsScope.PLAIN, keys = mapOf("CACHE_KEY" to "plugin_registry_cache", "OVERRIDE_KEY" to "registry_url_override"), note = "3 usages across 2 file(s)"),
        PrefsFileSpec(name = "provider_config", module = "data", scope = PrefsScope.PLAIN, keys = mapOf("KEY_LAST_MODELS_REFRESH_DATE" to "lastModelsRefreshDate", "LITERAL_config" to "config", "LITERAL_last_used" to "lastUsedModelEntryId", "LITERAL_voice_in" to "voice.input.overrideEntryId", "LITERAL_voice_out" to "voice.output.overrideEntryId"), dynamicPrefixes = listOf("keypool_", "modelsLastFetchAt_", "voiceShadowDisabled."), note = "8 usages across 1 file(s)"),
        PrefsFileSpec(name = "provider_extra_headers", module = "provider", scope = PrefsScope.ENCRYPTED, keys = mapOf("KEY" to "headers_json", "OPENCODE_SESSION_KEY" to "opencode_session_json"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "provider_refresh_marks", module = "data", scope = PrefsScope.EPHEMERAL, openKeySpace = true, note = "3 usages across 1 file(s)"),
        PrefsFileSpec(name = "provider_secrets", module = "data", scope = PrefsScope.ENCRYPTED, note = "2 usages across 2 file(s)"),
        PrefsFileSpec(name = "sandbox_firewall", module = "sandbox", scope = PrefsScope.PLAIN, keys = mapOf("KEY_POLICY" to "policy"), note = "2 usages across 1 file(s)"),
        PrefsFileSpec(name = "sandbox_job_keepalive", module = "sandbox", scope = PrefsScope.PLAIN, keys = mapOf("KEY_SESSIONS" to "sessions"), note = "2 usages across 1 file(s)"),
        PrefsFileSpec(name = "sandbox_notify_dyn", module = "notification", scope = PrefsScope.PLAIN, dynamicPrefixes = listOf("dyn:"), note = "2 usages across 1 file(s)"),
        PrefsFileSpec(name = "security_gate", module = "security", scope = PrefsScope.PLAIN, keys = mapOf("KEY_MODE" to "permission_mode"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "session-permission", module = "data", scope = PrefsScope.PLAIN, keys = mapOf("KEY_MODE" to "lastMode"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "session_badge_store", module = "service", scope = PrefsScope.PLAIN, keys = mapOf("KEY" to "badge_state_by_session", "KEY_V2" to "badge_state_by_session_v2"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "session_list_ui", module = "ui", scope = PrefsScope.PLAIN, openKeySpace = true, note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "share_prefs", module = "share", scope = PrefsScope.PLAIN, keys = mapOf("KEY_PENDING_SHARE" to "pending_share"), note = "3 usages across 1 file(s)"),
        PrefsFileSpec(name = "skill_feeds_prefs", module = "data", scope = PrefsScope.PLAIN, keys = mapOf("KEY_FEEDS" to "feeds_json"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "skill_prompt_prefs", module = "data", scope = PrefsScope.PLAIN, keys = mapOf("KEY_PROGRESSIVE" to "skills.progressive_loading"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "skill_user_disabled", module = "data", scope = PrefsScope.PLAIN, keys = mapOf("LITERAL_ids" to "ids", "LITERAL_seeded_from_db" to "seeded_from_db"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "skills_prefs", module = "ui", scope = PrefsScope.PLAIN, openKeySpace = true, note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "soul_store", module = "agent", scope = PrefsScope.PLAIN, keys = mapOf("FORCE_OVERWRITE_KEY" to "force_overwrite_1_36_13"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "speech_recognition", module = "speech", scope = PrefsScope.PLAIN, keys = mapOf("KEY_ENGINE_ID" to "engineId", "KEY_LOCALE" to "locale"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "ssh_host_secrets", module = "ssh", scope = PrefsScope.ENCRYPTED, note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "ssh_workspace_prefs", module = "ssh", scope = PrefsScope.PLAIN, note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "stream_trace", module = "provider", scope = PrefsScope.PLAIN, keys = mapOf("PREFS_ENABLED" to "enabled"), note = "2 usages across 1 file(s)"),
        PrefsFileSpec(name = "sub_agent_types", module = "tools", scope = PrefsScope.PLAIN, keys = mapOf("KEY" to "types_json"), note = "2 usages across 1 file(s)"),
        PrefsFileSpec(name = "text_segmenter", module = "shared", scope = PrefsScope.PLAIN, keys = mapOf("KEY_DICT_VERSION" to "jieba_dict_version"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "translate_settings", module = "i18n", scope = PrefsScope.PLAIN, keys = mapOf("KEY_ENABLED" to "enabled", "KEY_ENTRY" to "entry_id", "KEY_LANG" to "lang"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "ui_prefs", module = "ui", scope = PrefsScope.PLAIN, keys = mapOf("KEY_LIST_COLLAPSED" to "tablet_list_pane_collapsed", "KEY_LIST_PANE_WIDTH" to "tablet_list_pane_width_dp", "PREF_FAB_SWAPPED" to "fab_swapped"), note = "2 usages across 2 file(s)"),
        PrefsFileSpec(name = "update_download_prefs", module = "data", scope = PrefsScope.EPHEMERAL, keys = mapOf("KEY_WIFI_ONLY" to "wifi_only"), note = "2 usages across 1 file(s)"),
        PrefsFileSpec(name = "update_sources", module = "data", scope = PrefsScope.PLAIN, keys = mapOf("KEY_INCLUDE_ROLLING" to "include_rolling", "KEY_PREFERRED" to "preferred_source_id"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "voice_announcement_prefs", module = "speech", scope = PrefsScope.PLAIN, dynamicPrefixes = listOf("scene."), note = "3 usages across 1 file(s)"),
        PrefsFileSpec(name = "voice_correction_prefs", module = "speech", scope = PrefsScope.PLAIN, keys = mapOf("KEY_ENABLED" to "voiceCorrectionCollectionEnabled", "KEY_PROMPTED" to "voiceCorrectionCollectionPrompted"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "voice_prefs", module = "speech", scope = PrefsScope.PLAIN, keys = mapOf("KEY_DRAG_X_DP" to "readReplies.capsuleDragXDp", "KEY_DRAG_Y_DP" to "readReplies.capsuleDragYDp", "KEY_ENABLED" to "handsFree.enabled", "KEY_EXPANDED" to "voice.panel.expanded", "KEY_MUTED" to "readReplies.muted", "KEY_READ_REPLIES" to "readReplies", "KEY_SPEED" to "readReplies.speed", "KEY_SYSTEM_VOICE" to "readReplies.systemVoice", "KEY_SYSTEM_VOICE_LABEL" to "readReplies.systemVoiceLabel", "KEY_VOLUME" to "readReplies.volume"), note = "5 usages across 3 file(s)"),
        PrefsFileSpec(name = "vscreen", module = "vscreen", scope = PrefsScope.PLAIN, openKeySpace = true, note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "web_search_prefs", module = "tools", scope = PrefsScope.PLAIN, keys = mapOf("KEY_BING_KEY" to "bing_key", "KEY_BOCHA" to "bocha_key", "KEY_BRAVE" to "brave_key", "KEY_CUSTOM_KEY" to "custom_key", "KEY_CUSTOM_KEY_HEADER" to "custom_key_header", "KEY_CUSTOM_URL" to "custom_url", "KEY_ENGINE" to "engine", "KEY_ENGINE_EXPLICIT" to "engine_explicit", "KEY_EXA" to "exa_key", "KEY_FALLBACK" to "fallback_ddg", "KEY_JINA" to "jina_key", "KEY_KEYLESS_CN" to "keyless_cn", "KEY_SEARXNG_URL" to "searxng_url", "KEY_TAVILY" to "tavily_key", "KEY_TOOL_ENABLED" to "tool_enabled", "KEY_ZHIPU" to "zhipu_key"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "workspace_layout", module = "sandbox", scope = PrefsScope.PLAIN, keys = mapOf("KEY_DONE" to "legacy_v1_done", "KEY_DONE_V2" to "legacy_v2_filed"), note = "1 usages across 1 file(s)"),
        PrefsFileSpec(name = "zen_free_lane_health", module = "provider", scope = PrefsScope.PLAIN, keys = mapOf("KEY_DEAD" to "dead_ids"), note = "1 usages across 1 file(s)"),
    )

    private val byName: Map<String, PrefsFileSpec> = files.associateBy { it.name }

    fun spec(name: String): PrefsFileSpec? = byName[name]

    fun require(name: String): PrefsFileSpec = byName[name]
        ?: error("unregistered SharedPreferences file: $name - run gen_prefs_registry.py")

    fun ofModule(module: String): List<PrefsFileSpec> = files.filter { it.module == module }

    /** 键名 -> 声明它的 prefs 文件。同名键在不同文件里是两回事，故返回列表。 */
    val keyIndex: Map<String, List<PrefsFileSpec>> by lazy {
        val m = LinkedHashMap<String, MutableList<PrefsFileSpec>>()
        for (f in files) for (k in f.keys.values) m.getOrPut(k) { mutableListOf() }.add(f)
        m
    }

    fun filesDeclaring(key: String): List<PrefsFileSpec> = keyIndex[key].orEmpty()

    data class Coverage(
        val totalFiles: Int,
        val encryptedFiles: Int,
        val ephemeralFiles: Int,
        val plainFiles: Int,
        val attributedKeys: Int,
    )

    fun coverage(): Coverage = Coverage(
        totalFiles = files.size,
        encryptedFiles = files.count { it.isEncrypted() },
        ephemeralFiles = files.count { it.scope == PrefsScope.EPHEMERAL },
        plainFiles = files.count { it.scope == PrefsScope.PLAIN },
        attributedKeys = files.sumOf { it.keys.size },
    )
}
