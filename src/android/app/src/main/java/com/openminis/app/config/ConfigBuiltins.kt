package com.openminis.app.config

import android.content.Context
import com.openminis.app.config.collections.EnvVarsCollection
import com.openminis.app.service.DynamicIslandSupport
import com.openminis.app.config.collections.GroupsCollection
import com.openminis.app.config.collections.ModelsCollection
import com.openminis.app.config.collections.ProvidersCollection
import com.openminis.app.config.fields.ClosureField
import com.openminis.app.config.fields.PrefsBoolField
import com.openminis.app.config.fields.PrefsDoubleField
import com.openminis.app.config.fields.PrefsEnumField
import com.openminis.app.config.fields.PrefsIntCodedEnumField
import com.openminis.app.config.fields.PrefsIntField
import com.openminis.app.config.fields.PrefsLongField
import com.openminis.app.config.fields.PrefsStringField
import com.openminis.app.config.fields.ReadOnlyField
import com.openminis.app.config.readable.ConfigReadable
import com.openminis.app.config.readable.ConfigReadableRenderer
import com.openminis.app.config.readable.ConfigReadableStrings
import com.openminis.app.config.readable.ConfigRefKind
import com.openminis.app.config.readable.ConfigRefResolver
import com.openminis.app.config.readable.ConfigValueFormatter
import com.openminis.app.config.readable.ReadableArg
import com.openminis.app.config.readable.ReadableThinkingLabels
import com.openminis.app.data.repository.BackgroundSettingsRepository
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.sandbox.ContainerDns
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.ui.chat.ChatViewModelStore
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * Built-in field registrations. Single place to add or remove a
 * setting from the agent-facing surface. Mirrors iOS
 * `ConfigRegistry+Builtins.swift`.
 */
internal object ConfigBuiltins {

    fun registerInto(
        r: ConfigRegistry,
        context: Context,
        providerRepo: ProviderRepository,
        envVarRepo: EnvVarRepository,
        chatRepo: ChatRepository,
    ) {
        installReadable(context, providerRepo)
        registerSelfMeta(r)
        registerSession(r, providerRepo, chatRepo)
        registerAppearance(r, context)
        registerChat(r, context)
        registerBackground(r, context)
        registerLogs(r, context)
        registerProviderCollections(r, context, providerRepo, envVarRepo)
        registerDefaults(r, providerRepo)
        registerSoul(r, context)
        registerMemory(r, context)
        registerNetwork(r, context, envVarRepo)
        registerAgentTools(r, context)
        registerSpeech(r, context)
        registerScheduled(r, context)
        registerUpdates(r, context)
        registerBackup(r, context)
        registerFiles(r, context)
    }

    // -- [T-config-readable] Readable descriptions --

    /**
     * Points the renderer at the live stores (id → name) and at the in-app
     * UI language (LocaleWrap), so the text frozen with an audit row is in
     * the language the user reads the app in.
     */
    private fun installReadable(context: Context, providerRepo: ProviderRepository) {
        val app = context.applicationContext
        ConfigRefResolver.names = com.openminis.app.config.readable.ProviderRepositoryNames(providerRepo)
        ConfigReadableRenderer.localizerFactory = {
            val ctx = com.openminis.app.i18n.LocaleWrap.wrap(app)
            val lookup: (String) -> String? = { key -> ConfigReadableStrings.lookup(ctx, key) }
            lookup
        }
        ConfigReadableRenderer.currentLocale = {
            com.openminis.app.i18n.LocaleWrap.wrap(app).resources.configuration.locales[0] ?: Locale.getDefault()
        }
        ConfigReadableRenderer.currentLanguage = { ConfigReadableRenderer.currentLocale().toLanguageTag() }
    }

    /** Token → label key; tokens as in [fontScaleField]. */
    private val fontScaleLabels: Map<String, String> = mapOf(
        "xSmall" to "Extra small", "small" to "Small", "default" to "Default",
        "medium" to "Medium", "large" to "Large", "extraLarge" to "Extra large",
    )

    /** BCP-47 code → the language's name in the UI language; "" = system. */
    private fun languagePhrase(v: ConfigValue): ReadableArg {
        val code = (v as? ConfigValue.Str)?.value ?: return ConfigReadableRenderer.genericPhrase(v)
        if (code.isEmpty()) return ReadableArg.Key("System")
        val name = Locale.forLanguageTag(code.replace('_', '-')).getDisplayName(ConfigReadableRenderer.currentLocale())
        return ReadableArg.Text(name.ifBlank { code })
    }

    // -- Network — the sandbox's DNS (Container Network) --
    //
    // [T-container-network-dns] (OpenMinis#396) Same prefs + keys as the
    // Settings > Rootfs > Container Network section (ContainerDns), so the
    // agent and the UI read and write one source of truth. Both writers
    // re-apply the sandbox resolv.conf at once: waiting for the next network
    // change or reboot would leave a "set but not in effect" gap.

    private fun registerNetwork(r: ConfigRegistry, context: Context, envVarRepo: EnvVarRepository) {
        val fields = com.openminis.app.config.fields.ContainerDnsFields.build(ContainerDns.PrefsStore(context)) {
            runCatching { com.openminis.app.sandbox.RootfsManager.getInstance(context).refreshDns() }
        }
        fields.forEach { r.register(it) }
        // [FEAT-261021-03] Settings > Network: routing, Tailscale and proxies.
        com.openminis.app.config.fields.NetworkConfigFields.build(envVarRepo).forEach { r.register(it) }
        r.register(com.openminis.app.config.collections.ProxiesCollection(envVarRepo))
    }

    // -- [T-minis-config-coverage] Settings added to the app that the
    // registry did not cover yet. Each one binds to the store its Settings
    // row writes, so the UI and minis-config read one value. --

    /**
     * `agentTools.userAsk.*` - the user_ask tool and its timeout /
     * notification settings (Settings > Agent Tools); same paths, defaults
     * and range as iOS. Nothing here can answer or approve for the user.
     */
    private fun registerAgentTools(r: ConfigRegistry, context: Context) {
        val spec = com.openminis.app.agent.userask.UserAskSettingsSpec
        val prefs = context.getSharedPreferences(
            com.openminis.app.tools.AgentToolSwitch.PREFS,
            Context.MODE_PRIVATE,
        )
        r.register(
            PrefsBoolField(
                path = "agentTools.userAsk.enabled",
                displayName = "User interaction tool (user_ask)",
                description = "Whether the assistant may ask you structured questions (choices, or a confirmation before a risky action) on an in-chat card. Never offered to sub agents. In a run you are not watching, the card stays open and your answer is sent as your next message. When off, the tool is removed.",
                prefs = prefs,
                key = spec.ENABLED_KEY,
                defaultValue = spec.DEFAULT_ENABLED,
            )
        )
        r.register(
            PrefsIntField(
                path = "agentTools.userAsk.timeoutMinutes",
                displayName = "Question timeout (minutes)",
                description = "Minutes the assistant waits for an answer. After that the question is returned to the assistant as timed out - nothing is applied, approved or cancelled on your behalf. 0 = wait indefinitely. Default ${spec.DEFAULT_TIMEOUT_MINUTES}.",
                prefs = prefs,
                key = spec.TIMEOUT_MINUTES_KEY,
                defaultValue = spec.DEFAULT_TIMEOUT_MINUTES,
                minValue = 0,
                maxValue = spec.MAX_TIMEOUT_MINUTES,
                zeroLabel = "Wait indefinitely",
            )
        )
        r.register(
            PrefsBoolField(
                path = "agentTools.userAsk.notifyInBackground",
                displayName = "Notify about questions in the background",
                description = "Post a notification when the assistant asks while Minis is in the background. The question can be answered from the notification.",
                prefs = prefs,
                key = spec.NOTIFY_IN_BACKGROUND_KEY,
                defaultValue = spec.DEFAULT_NOTIFY_IN_BACKGROUND,
            )
        )
    }

    /**
     * `speech.volume` - the read-aloud volume slider. Written through
     * VoiceOutputState so a reply being read follows the change at once, as
     * with the slider. The other iOS `speech.*` keys have no Android
     * counterpart with the same meaning (speed is a multiplier here, voices
     * are engine-specific), so they are not registered.
     */
    private fun registerSpeech(r: ConfigRegistry, context: Context) {
        val state = com.openminis.app.speech.VoiceOutputState
        r.register(
            ClosureField(
                path = "speech.volume",
                displayName = "Speech volume",
                description = "Read-aloud volume, 0.0 (mute) to 2.0 (200%); 1.0 (default) is the system media volume as is. Applied to both the system voice and provider TTS; above 1.0 a limiter-backed loudness boost makes quiet voices louder.",
                valueSchema = ConfigSchema.Double(min = 0.0, max = 2.0),
                risk = ConfigRisk.NORMAL,
                revertable = true,
                readable = ConfigReadable(ConfigValueFormatter.Number()),
                reader = {
                    state.init(context)
                    // Two decimals: the stored float reads back as 0.3, not 0.30000001.
                    ConfigValue.Double(Math.round(state.volume.value * 100.0) / 100.0)
                },
                writer = { v ->
                    val d = when (v) {
                        is ConfigValue.Double -> v.value
                        is ConfigValue.Int -> v.value.toDouble()
                        else -> throw ConfigError.TypeMismatch("double")
                    }
                    state.init(context)
                    state.setVolume(d.toFloat())
                },
            )
        )
    }

    /** `scheduled.showHomeBadge` - the switch in the Scheduled Tasks screen's menu. */
    private fun registerScheduled(r: ConfigRegistry, context: Context) {
        val store = com.openminis.app.scheduled.ScheduledTaskStore(context.applicationContext)
        r.register(
            ClosureField(
                path = "scheduled.showHomeBadge",
                displayName = "Show scheduled task count on Home",
                description = "Whether the clock icon on the home screen shows the number of enabled scheduled tasks. Default true. It only hides the count; tasks keep running. Tasks themselves are managed with the minis-scheduled command, not here.",
                valueSchema = ConfigSchema.Bool,
                risk = ConfigRisk.NORMAL,
                revertable = true,
                readable = ConfigReadable(ConfigValueFormatter.Bool()),
                reader = { ConfigValue.Bool(store.showHomeBadge()) },
                writer = { v ->
                    val b = (v as? ConfigValue.Bool)?.value ?: throw ConfigError.TypeMismatch("bool")
                    store.setShowHomeBadge(b)
                },
            )
        )
    }

    /**
     * `updates.*` - Settings > About > Check for Updates. Both writes go
     * through UpdateStateStore, so the cached result of the old channel is
     * dropped and the badge follows, exactly as with the picker; a channel
     * change then starts the same check the picker starts. Nothing is
     * downloaded or installed from here.
     */
    private fun registerUpdates(r: ConfigRegistry, context: Context) {
        val app = context.applicationContext
        val store = com.openminis.app.data.UpdateStateStore
        val channels = com.openminis.app.data.UpdatePolicy.Channel.entries
        r.register(
            ClosureField(
                path = "updates.channel",
                displayName = "Update channel",
                description = "Which releases the in-app updater offers: stable (tested releases, recommended) or beta (earlier builds with new features; may be less stable). Switching back to stable keeps the installed beta until a newer stable release exists. Changing it only changes what is OFFERED - installing an update is always the user's own tap.",
                valueSchema = ConfigSchema.StrEnum(channels.map { it.id }),
                // Sensitive: beta builds are less stable, and Settings asks before switching to them.
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                readable = ConfigReadable(ConfigValueFormatter.EnumLabels(mapOf("stable" to "Stable", "beta" to "Beta"))),
                reader = {
                    store.init(app)
                    ConfigValue.Str(store.channel.value.id)
                },
                writer = { v ->
                    val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                    val ch = channels.firstOrNull { it.id == s }
                        ?: throw ConfigError.InvalidValue("must be one of: ${channels.joinToString(", ") { it.id }}")
                    store.setChannel(app, ch)
                    runCatching { com.openminis.app.data.UpdateAutoChecker.maybeCheck(app, "channel") }
                },
            )
        )
        val autoCheck = ClosureField(
            path = "updates.autoCheck",
            displayName = "Check for updates automatically",
            description = "Whether Minis looks for a new version by itself when it comes to the foreground (at most once a day on stable, every 6 hours on beta). false stops those requests and hides the update badges; Check for Updates in Settings still works.",
            valueSchema = ConfigSchema.Bool,
            risk = ConfigRisk.NORMAL,
            revertable = true,
            readable = ConfigReadable(ConfigValueFormatter.Bool()),
            reader = {
                store.init(app)
                ConfigValue.Bool(store.autoEnabled.value)
            },
            writer = { v ->
                val b = (v as? ConfigValue.Bool)?.value ?: throw ConfigError.TypeMismatch("bool")
                store.setAutoEnabled(app, b)
            },
        )
        // Settings hides this row on a build without automatic checks, so the
        // path stays documented there but refuses reads and writes.
        r.register(
            if (com.openminis.app.BuildConfig.AUTO_UPDATE_CHECK) {
                autoCheck
            } else {
                UnavailableField(autoCheck, "This build of Minis does not check for updates by itself.")
            }
        )
    }

    /** `files.showHidden` - the file browser's "Show Hidden Files" menu item; same path as iOS. */
    private fun registerFiles(r: ConfigRegistry, context: Context) {
        val vm = com.openminis.app.ui.sandbox.FileBrowserViewModel
        r.register(
            PrefsBoolField(
                path = "files.showHidden",
                displayName = "Show Hidden Files",
                description = "Whether the in-app file browser lists dot-files and dot-folders (.git, .env, …). Default false. Only changes what the browser shows; the shell always sees them. A file browser that is already open picks it up the next time it is opened.",
                prefs = context.getSharedPreferences(vm.PREFS_NAME, Context.MODE_PRIVATE),
                key = vm.PREF_KEY_SHOW_HIDDEN,
                defaultValue = false,
            )
        )
    }

    /** `backup.deviceName` - Settings > Backup > Device Name. */
    private fun registerBackup(r: ConfigRegistry, context: Context) {
        val app = context.applicationContext
        val identity = com.openminis.app.data.DeviceIdentity
        r.register(
            ClosureField(
                path = "backup.deviceName",
                displayName = "Backup device name",
                description = "The name that leads every backup file name and is recorded in the backup, so two devices backing up into one folder can be told apart. Empty string (default) = automatic: the device's own name. At most ${identity.CUSTOM_NAME_MAX_LENGTH} characters. Backup destinations and their credentials are not configurable here.",
                valueSchema = ConfigSchema.Str(maxLength = identity.CUSTOM_NAME_MAX_LENGTH),
                risk = ConfigRisk.NORMAL,
                revertable = true,
                readable = ConfigReadable(ConfigValueFormatter.Text(empty = "Automatic")),
                reader = { ConfigValue.Str(identity.customName(app) ?: "") },
                writer = { v ->
                    val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                    identity.setCustomName(app, s)
                },
            )
        )
    }

    // -- Memory — global default toggle for the persistent memory feature --
    //
    // [T-android-memory-enabled-minisconfig] `memory.enabled` mirrors the
    // Settings → Memory switch (MemoryManagementScreen, backed by
    // MemoryGlobalPrefs: SharedPreferences `minis_memory_prefs`, key
    // `memory.global.enabled`, default true). It is the global DEFAULT
    // applied to newly-created sessions: a new chat seeds its per-session
    // memoryEnabled from this value. It does NOT retroactively flip
    // already-open sessions — those carry their own per-session toggle,
    // changed via the SessionMemorySheet / `/memory`. Registering the same
    // prefs+key as MemoryGlobalPrefs keeps the CLI/agent and the Settings
    // switch reading/writing one source of truth. Topic auto-surfaces in
    // list-topics since topics derive from field-path prefixes.

    private fun registerMemory(r: ConfigRegistry, context: Context) {
        val prefs = context.getSharedPreferences("minis_memory_prefs", Context.MODE_PRIVATE)
        r.register(
            PrefsBoolField(
                path = "memory.enabled",
                displayName = "Memory enabled (global default)",
                description = "Default for whether NEW chat sessions start with memory on. When on, a new session auto-injects GLOBAL.md + recent daily logs into the system prompt and exposes the memory_get / memory_write tools. When off, new sessions get neither. Already-open sessions keep their own per-session setting (toggle in the session memory sheet) and are not changed by this.",
                prefs = prefs,
                key = "memory.global.enabled",
                defaultValue = true,
            )
        )
    }

    // -- Master switch surface (read-only via the registry; UI toggles it) --

    private fun registerSelfMeta(r: ConfigRegistry) {
        r.register(
            ClosureField(
                path = "permissions.minisConfig.enabled",
                displayName = "Allow minis-config",
                description = "Master switch. Read-only here — toggle via Settings → Permissions.",
                valueSchema = ConfigSchema.Bool,
                access = ConfigAccess.READONLY,
                risk = ConfigRisk.DESTRUCTIVE,
                revertable = false,
                readable = ConfigReadable.notWritable,
                reader = { ConfigValue.Bool(MinisConfigPermissionStore.isEnabled) },
                writer = { _ ->
                    throw ConfigError.PermissionDenied(
                        "Master switch — toggle via Settings → Permissions only"
                    )
                },
            )
        )
    }

    // -- Session (current chat) --
    //
    // Mirrors iOS `session.*` keys in ConfigRegistry+Builtins.swift. The
    // "current session" is whatever ChatScreen is composed with right now,
    // tracked via `ChatViewModelStore.activeSessionId`. Reads return empty
    // / writes throw "No active session" when no chat is foregrounded.

    private fun registerSession(
        r: ConfigRegistry,
        providerRepo: ProviderRepository,
        chatRepo: ChatRepository,
    ) {
        r.register(
            ClosureField(
                path = "session.thinkingLevel",
                displayName = "Thinking level (current session)",
                description = "off / low / medium / high / xhigh. Applied to the active chat.",
                valueSchema = ConfigSchema.StrEnum(
                    listOf("off", "low", "medium", "high", "xhigh")
                ),
                risk = ConfigRisk.NORMAL,
                revertable = true,
                readable = ConfigReadable(ConfigValueFormatter.EnumLabels(ReadableThinkingLabels.levels)),
                reader = {
                    val sid = ChatViewModelStore.activeSessionId
                    if (sid == null) ConfigValue.Null
                    else {
                        val session = runBlocking { chatRepo.dao.getSession(sid) }
                        val raw = session?.thinkingOverride
                        val token = if (raw == null) "off" else thinkingLevelToToken(
                            ThinkingLevel.parseOrNull(raw) ?: ThinkingLevel.OFF
                        )
                        ConfigValue.Str(token)
                    }
                },
                writer = { v ->
                    val token = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    val level = thinkingLevelFromToken(token)
                        ?: throw ConfigError.InvalidValue("Unknown thinking level: $token")
                    val sid = ChatViewModelStore.activeSessionId
                        ?: throw ConfigError.InvalidValue("No active session — open a chat first")
                    runBlocking { chatRepo.dao.updateThinkingOverride(sid, level.name) }
                },
            )
        )
        r.register(
            ClosureField(
                path = "session.primaryModel",
                displayName = "Primary model (current session)",
                description = "Set as `entry:<uuid>` or `group:<id>` (use `models` / `groups` topics to discover ids). Empty clears the binding.",
                valueSchema = ConfigSchema.Str(maxLength = 200),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                readable = ConfigReadable(ConfigValueFormatter.Ref(ConfigRefKind.ModelSource)),
                reader = {
                    val sid = ChatViewModelStore.activeSessionId
                    if (sid == null) ConfigValue.Str("")
                    else {
                        val session = runBlocking { chatRepo.dao.getSession(sid) }
                        ConfigValue.Str(formatBinding(session?.modelBinding))
                    }
                },
                writer = { v ->
                    val s = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    val sid = ChatViewModelStore.activeSessionId
                        ?: throw ConfigError.InvalidValue("No active session — open a chat first")
                    if (s.isEmpty()) {
                        // Clear the binding — fall back to default group/entry on next load.
                        runBlocking { chatRepo.dao.updateSessionBinding(sid, "", "") }
                        return@ClosureField
                    }
                    val cfg = providerRepo.config.value
                    val (bindingJson, modelId) = when {
                        s.startsWith("entry:") -> {
                            val uuid = s.removePrefix("entry:")
                            if (uuid.isEmpty()) {
                                throw ConfigError.InvalidValue("entry uuid is empty")
                            }
                            val entry = cfg.modelEntries.find { it.id == uuid }
                                ?: throw ConfigError.InvalidValue("Unknown model entry uuid: $uuid")
                            """{"type":"entry","entryId":"$uuid"}""" to entry.baseModel.id
                        }
                        s.startsWith("group:") -> {
                            val gid = s.removePrefix("group:")
                            if (gid.isEmpty()) {
                                throw ConfigError.InvalidValue("group id is empty")
                            }
                            val group = cfg.modelGroups.find { it.id == gid }
                                ?: throw ConfigError.InvalidValue("Unknown group id: $gid")
                            val firstEntryId = group.memberEntryIds.firstOrNull()
                                ?: throw ConfigError.InvalidValue("Group $gid has no member entries")
                            val firstEntry = cfg.modelEntries.find { it.id == firstEntryId }
                                ?: throw ConfigError.InvalidValue("Group $gid references missing entry $firstEntryId")
                            """{"type":"group","groupId":"$gid"}""" to firstEntry.baseModel.id
                        }
                        else -> throw ConfigError.InvalidValue(
                            "Expected `entry:<uuid>` or `group:<id>`, got '$s'"
                        )
                    }
                    runBlocking {
                        chatRepo.dao.updateSessionBinding(sid, bindingJson, modelId)
                    }
                },
            )
        )
        // T311: iOS `session.subModel` is not registered on Android — the
        // platform has no per-session sub/fallback-model concept (no
        // `subModelSource` field in `model_binding` JSON, no agent-loop
        // fallback path). Reported up at the spec level rather than
        // adding a no-op key that silently throws on every write.
    }

    /** iOS uses lowercase tokens (`off`/`low`/…); Android `ThinkingLevel.name`
     *  is uppercase. Centralised so reader/writer round-trip identical strings. */
    private fun thinkingLevelToToken(level: ThinkingLevel): String = when (level) {
        ThinkingLevel.OFF -> "off"
        ThinkingLevel.LOW -> "low"
        ThinkingLevel.MEDIUM -> "medium"
        ThinkingLevel.HIGH -> "high"
        ThinkingLevel.XHIGH -> "xhigh"
        // [T-android-thinking-level-arch] GPT-5.6 higher tiers.
        ThinkingLevel.MAX -> "max"
        ThinkingLevel.ULTRA -> "ultra"
    }

    private fun thinkingLevelFromToken(token: String): ThinkingLevel? = when (token) {
        "off" -> ThinkingLevel.OFF
        "low" -> ThinkingLevel.LOW
        "medium" -> ThinkingLevel.MEDIUM
        "high" -> ThinkingLevel.HIGH
        "xhigh" -> ThinkingLevel.XHIGH
        "max" -> ThinkingLevel.MAX
        "ultra" -> ThinkingLevel.ULTRA
        else -> null
    }

    /** Convert the `model_binding` JSON the chat layer writes into the
     *  `entry:<uuid>` / `group:<id>` form the iOS minis-config surface uses.
     *  Returns empty string when the session has no explicit binding (the
     *  default group/entry fallback applies on next load). */
    private fun formatBinding(bindingJson: String?): String {
        if (bindingJson.isNullOrEmpty()) return ""
        return try {
            val obj = org.json.JSONObject(bindingJson)
            when (obj.optString("type")) {
                "entry" -> {
                    val id = obj.optString("entryId")
                    if (id.isEmpty()) "" else "entry:$id"
                }
                "group" -> {
                    val id = obj.optString("groupId")
                    if (id.isEmpty()) "" else "group:$id"
                }
                else -> ""
            }
        } catch (_: Exception) {
            ""
        }
    }

    // -- Appearance & display --

    private fun registerAppearance(r: ConfigRegistry, context: Context) {
        // Source-of-truth lives in `appearance_prefs` (see
        // ui/settings/AppearanceScreen.kt PREF_APPEARANCE).
        val prefs = context.getSharedPreferences("appearance_prefs", Context.MODE_PRIVATE)
        r.register(
            PrefsIntCodedEnumField(
                path = "appearance.theme",
                displayName = "Theme",
                description = "Light / Dark / System color scheme.",
                prefs = prefs,
                key = "theme_mode",
                cases = listOf("system", "light", "dark"),
                valueLabels = mapOf("system" to "System", "light" to "Light", "dark" to "Dark"),
                defaultIndex = 0,
            )
        )
        // T-chat-title-pill: sticky session-title pill on the chat screen.
        // Default ON; key string deliberately matches iOS so a future
        // cross-platform settings sync round-trips identically.
        r.register(
            PrefsBoolField(
                path = "appearance.show_chat_title",
                displayName = "Show chat title pill",
                description = "Show a sticky title pill at the top of the chat while scrolling through history.",
                prefs = prefs,
                key = com.openminis.app.ui.settings.KEY_SHOW_CHAT_TITLE,
                defaultValue = true,
            )
        )
        r.register(
            PrefsStringField(
                path = "appearance.language",
                displayName = "App language",
                // T311: aligned with iOS appearance.language help wording.
                description = "BCP-47 code (zh-Hans, en, ja, …) or empty for system.",
                prefs = prefs,
                key = "app_language",
                defaultValue = "",
                maxLength = 16,
                readable = ConfigReadable(ConfigValueFormatter.Custom(::languagePhrase)),
            )
        )
        // [T-minis-config-coverage] Settings > Appearance > app font size; the
        // third font axis, same path and tokens as iOS.
        r.register(fontScaleField(
            path = "appearance.appFontSize",
            displayName = "App font size",
            description = "Base font size for the app's own UI (lists, settings, titles). The chat text has its own two settings: chat.messageFontSize and chat.inputFontSize.",
            prefs = prefs,
            key = com.openminis.app.ui.settings.KEY_FONT_APP_BASE,
        ))
    }

    // -- Chat / input --

    private fun registerChat(r: ConfigRegistry, context: Context) {
        // Default app prefs — Android persists most chat preferences in
        // the default SharedPreferences for the package.
        val prefs = context.getSharedPreferences("minis_settings", Context.MODE_PRIVATE)
        // [T-android-config-prefs-mismatch] The chat prefs the SETTINGS UI owns
        // live here, not in `minis_settings`. Hoisted above the registrations
        // so every field can bind to the same store the UI reads.
        val appearancePrefs = context.getSharedPreferences(
            com.openminis.app.ui.settings.PREF_APPEARANCE,
            Context.MODE_PRIVATE,
        )
        r.register(
            PrefsIntCodedEnumField(
                path = "chat.returnKey",
                displayName = "Return key behavior",
                description = "What the on-screen Return key does in the chat box.",
                // [T-android-config-prefs-mismatch] Must match
                // AppearanceScreen.returnKeySendsMessage exactly — both the
                // store AND the key. This field previously wrote
                // `minis_settings/return_key_behavior`, which nothing reads,
                // so `minis-config set chat.returnKey send` reported success
                // while the chat box kept inserting newlines.
                prefs = appearancePrefs,
                key = com.openminis.app.ui.settings.KEY_RETURN_KEY_BEHAVIOR,
                cases = listOf("newline", "send"),
                valueLabels = mapOf("newline" to "New line", "send" to "Send"),
                defaultIndex = 0,
            )
        )
        r.register(
            PrefsBoolField(
                path = "chat.keepScreenAwake",
                displayName = "Keep screen awake during tasks",
                description = "Prevents auto-lock while the agent is busy.",
                // Same mismatch as chat.returnKey above — see that comment.
                prefs = appearancePrefs,
                key = com.openminis.app.ui.settings.KEY_KEEP_SCREEN_AWAKE,
                defaultValue = false,
            )
        )
        // (No autoFocusAfterReply entry here — it lives in
        // PREF_APPEARANCE alongside the visible AppearanceScreen toggle.
        // Registered further down in this method where the
        // appearance-prefs section starts.)
        // T311: chat.toolPreview / inputFontSize / messageFontSize live in
        // `appearance_prefs` — same SharedPreferences AppearanceScreen.kt
        // reads/writes, so flipping via minis-config flows back into the
        // settings UI and into ChatScreen's `OnSharedPreferenceChangeListener`
        // (Compose recomposes immediately, no manual cache invalidation
        // needed on Android — there is no analogue to iOS
        // FontSettings.messageBaseScale .Published cache).
        r.register(
            PrefsBoolField(
                path = "chat.toolPreview",
                displayName = "Tool preview",
                description = "Show live preview during agent tool execution.",
                prefs = appearancePrefs,
                key = com.openminis.app.ui.settings.KEY_TOOL_PREVIEW,
                defaultValue = true,
            )
        )
        // [T-minis-config-coverage] Settings > Appearance > Expand Thinking
        // While Streaming; also toggled by long-pressing a thinking block.
        r.register(
            PrefsBoolField(
                path = "chat.autoExpandThinking",
                displayName = "Expand Thinking While Streaming",
                description = "true (default): a new thinking block opens while the model reasons and collapses when it finishes. false: thinking blocks stay collapsed until tapped. Long-pressing a thinking block switches it too.",
                prefs = appearancePrefs,
                key = com.openminis.app.ui.settings.KEY_AUTO_EXPAND_THINKING,
                defaultValue = true,
            )
        )
        // [T-minis-config-coverage] Settings > Appearance > Auto-Grouping.
        r.register(
            PrefsBoolField(
                path = "chat.autoGrouping",
                displayName = "Auto-Grouping",
                description = "When a chat's title is first generated, also file the chat into a matching existing group. Runs once per chat, only uses groups that already exist, and leaves the chat ungrouped when none fits. Default true.",
                prefs = appearancePrefs,
                key = com.openminis.app.ui.settings.KEY_AUTO_GROUPING,
                defaultValue = true,
            )
        )
        // [T-minis-config-coverage] The model menu's Speed setting (Standard /
        // Fast / Ultrafast). Through FastModePrefs, not the raw key: it keeps
        // the request builders' cache and the legacy fast-mode flag in step.
        // Tokens as on iOS; Android stores Standard as "off".
        val speedNames = linkedMapOf(
            com.openminis.app.data.SpeedTier.STANDARD to "standard",
            com.openminis.app.data.SpeedTier.FAST to "fast",
            com.openminis.app.data.SpeedTier.ULTRAFAST to "ultrafast",
        )
        r.register(
            ClosureField(
                path = "chat.speedTier",
                displayName = "Speed",
                description = "Response speed tier requested from models that offer one (ChatGPT sign-in / OpenAI and compatible providers). standard (default) = normal; fast = priority processing; ultrafast = fastest tier, only on models that support it - other models fall back to fast. Faster tiers use more of the account's quota. Applies from the next request.",
                valueSchema = ConfigSchema.StrEnum(speedNames.values.toList()),
                risk = ConfigRisk.NORMAL,
                revertable = true,
                readable = ConfigReadable(ConfigValueFormatter.EnumLabels(mapOf("standard" to "Standard", "fast" to "Fast", "ultrafast" to "Ultrafast"))),
                reader = { ConfigValue.Str(speedNames[com.openminis.app.data.FastModePrefs.tier()] ?: "standard") },
                writer = { v ->
                    val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                    val tier = speedNames.entries.firstOrNull { it.value == s }?.key
                        ?: throw ConfigError.InvalidValue("must be one of: ${speedNames.values.joinToString(", ")}")
                    com.openminis.app.data.FastModePrefs.setTier(context.applicationContext, tier)
                },
            )
        )
        // [T-minis-config-coverage] The two optional-tool switches of Settings
        // > Agent Tools, same paths as iOS. Bound to the store and keys
        // AgentToolSwitch reads at every request, so a write takes effect on
        // the next turn. user_ask's switch is agentTools.userAsk.enabled.
        val toolPrefs = context.getSharedPreferences(
            com.openminis.app.tools.AgentToolSwitch.PREFS,
            Context.MODE_PRIVATE,
        )
        r.register(
            PrefsBoolField(
                path = "chat.tools.browserUse",
                displayName = "Browser Use tool",
                description = "Whether the assistant is offered browser_use. When off the tool is removed from the schema and the system prompt no longer mentions it — the model never learns it exists. Each tool that is on adds its schema and its prompt section to every request.",
                prefs = toolPrefs,
                key = com.openminis.app.tools.AgentToolSwitch.BROWSER.key,
                defaultValue = com.openminis.app.tools.AgentToolSwitch.BROWSER.defaultValue,
            )
        )
        r.register(
            PrefsBoolField(
                path = "chat.tools.subAgents",
                displayName = "Sub Agents tool",
                description = "Whether the assistant is offered subagent_task. When off that tool and the sub agent roster both leave the request. Configure the agents themselves under `subagents`.",
                prefs = toolPrefs,
                key = com.openminis.app.tools.AgentToolSwitch.AGENTS.key,
                defaultValue = com.openminis.app.tools.AgentToolSwitch.AGENTS.defaultValue,
            )
        )
        // [T-port-fold-tools] Settings → Appearance → Tool Calls
        // (`chat.foldTools`, iOS userDefaults chat.foldConsecutiveTools).
        // [PAR-261022-05] On by default on every platform.
        r.register(
            PrefsBoolField(
                path = "chat.foldTools",
                displayName = "Fold Consecutive Tool Calls",
                description = "Show consecutive tool calls as one tappable line.",
                prefs = appearancePrefs,
                key = com.openminis.app.ui.settings.KEY_FOLD_TOOLS,
                defaultValue = com.openminis.app.ui.settings.FOLD_TOOLS_DEFAULT,
            )
        )
        // [T-stop-mode-longpress] Settings → Appearance → Confirm Before
        // Stopping; same store and key the stop button's long-press writes,
        // same path and default as iOS.
        r.register(
            PrefsBoolField(
                path = "chat.confirmBeforeStopping",
                displayName = "Confirm before stopping",
                description = "What tapping the composer's stop button does while a task runs. false (default) = stop immediately; true = ask \"Stop this task?\" first. Long-pressing the stop button switches it too. Stopping a conversation with scheduled tasks always asks, either way.",
                prefs = appearancePrefs,
                key = com.openminis.app.ui.chat.StopConfirmPolicy.SETTINGS_KEY,
                defaultValue = com.openminis.app.ui.chat.StopConfirmPolicy.DEFAULT_ENABLED,
            )
        )
        // [T-keyboard-auto-pop default flip] On by default — iOS gates
        // the existing post-stream auto-focus behind this. Android
        // currently does not auto-focus on stream end (it only
        // auto-focuses on brand-new sessions starting with "__new__"),
        // so on this platform the setting is registered for parity.
        // When a future Android change adds stream-end auto-focus, it
        // should read this same pref key.
        r.register(
            PrefsBoolField(
                path = "chat.autoFocusAfterReply",
                displayName = "Auto-focus input after reply",
                description = "When ON, the keyboard pops up automatically after the model finishes a reply so the input is ready for a follow-up. Turn OFF if you prefer to read the response without an unexpected keyboard.",
                prefs = appearancePrefs,
                key = com.openminis.app.ui.settings.KEY_AUTO_FOCUS_AFTER_REPLY,
                defaultValue = true,
            )
        )
        r.register(fontScaleField(
            path = "chat.inputFontSize",
            displayName = "Input font size",
            description = "Font size for the chat composer text editor.",
            prefs = appearancePrefs,
            key = com.openminis.app.ui.settings.KEY_FONT_CHAT_INPUT,
        ))
        r.register(fontScaleField(
            path = "chat.messageFontSize",
            displayName = "Message font size",
            description = "Base font size for assistant / user message bodies (markdown). Setting this invalidates rendered markdown caches.",
            prefs = appearancePrefs,
            key = com.openminis.app.ui.settings.KEY_FONT_MESSAGE,
        ))
    }

    /** Mirrors iOS `fontScaleField` — exposes the integer scale level
     *  (-2..3 in AppearanceScreen.kt) as a string token (xSmall / small /
     *  default / medium / large / extraLarge) so reads round-trip the same
     *  vocabulary as `chat.inputFontSize` / `appearance.appFontSize` on
     *  iOS. Centralised so both Android axes share one factory. */
    private fun fontScaleField(
        path: String,
        displayName: String,
        description: String,
        prefs: android.content.SharedPreferences,
        key: String,
    ): ConfigField {
        val cases = listOf("xSmall", "small", "default", "medium", "large", "extraLarge")
        return ClosureField(
            path = path,
            displayName = displayName,
            description = description,
            valueSchema = ConfigSchema.StrEnum(cases),
            risk = ConfigRisk.NORMAL,
            revertable = true,
            readable = ConfigReadable(ConfigValueFormatter.EnumLabels(fontScaleLabels)),
            reader = {
                val level = prefs.getInt(key, 0)
                val token = when (level) {
                    -2 -> "xSmall"
                    -1 -> "small"
                    0 -> "default"
                    1 -> "medium"
                    2 -> "large"
                    3 -> "extraLarge"
                    else -> "default"
                }
                ConfigValue.Str(token)
            },
            writer = { v ->
                val token = (v as? ConfigValue.Str)?.value
                    ?: throw ConfigError.TypeMismatch("string")
                val level = when (token) {
                    "xSmall" -> -2
                    "small" -> -1
                    "default" -> 0
                    "medium" -> 1
                    "large" -> 2
                    "extraLarge" -> 3
                    else -> throw ConfigError.InvalidValue("Unknown font scale: $token")
                }
                prefs.edit().putInt(key, level).apply()
            },
        )
    }

    // -- Background --

    private fun registerBackground(r: ConfigRegistry, context: Context) {
        val prefs = context.getSharedPreferences(BackgroundSettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
        r.register(
            PrefsBoolField(
                path = "background.enhanced",
                displayName = "Enhanced background execution",
                description = "Keep agent tasks running when the app is backgrounded.",
                prefs = prefs,
                key = "enhanced_background_execution",
                defaultValue = false,
                risk = ConfigRisk.SENSITIVE,
            )
        )
        r.register(
            PrefsBoolField(
                path = "background.notifications",
                displayName = "Background notifications",
                description = "Post a system notification when long-running tasks complete.",
                prefs = prefs,
                // [T-android-config-prefs-mismatch] The key Settings ->
                // Background's "Task Notifications" switch and the notifier
                // read. This wrote "background_notifications_enabled", which
                // nothing reads, so `minis-config set background.notifications`
                // reported ok and changed nothing (found verifying
                // FIX-261021-02 on a Pixel 4a).
                key = BackgroundSettingsRepository.KEY_TASK_NOTIFICATIONS,
                defaultValue = BackgroundSettingsRepository.DEFAULT_TASK_NOTIFICATIONS,
            )
        )
        // [T-android-config-feature-unavailable] Live Updates / "dynamic
        // island". Settings → Background renders this toggle DISABLED with an
        // "unsupported" footer unless the device is capable, so minis-config
        // must not be a side door around that gate: on an incapable device the
        // path stays registered (help/list still describe it) but every read
        // and write is refused with `feature_unavailable`.
        //
        // Capability = Android 16+ AND the per-app Live-Updates grant, probed
        // via the same DynamicIslandSupport the UI uses. The grant can be
        // revoked at runtime, so the probe runs at REGISTRATION time here and
        // the registry is rebuilt on the paths that re-register — matching how
        // the UI re-probes on resume. Mirrors iOS gating background.liveActivity
        // on isLiveActivitySupported (dba46d42).
        //
        // Writes go through the same SharedPreferences file+key the repository
        // reads (background_settings / dynamicIslandEnabled), so a change made
        // here is picked up by AgentForegroundService's overlay/promoted
        // mutual-exclusion logic exactly like a UI toggle.
        val dynamicIslandField = PrefsBoolField(
            path = "background.dynamicIsland",
            displayName = "Live Updates (dynamic island)",
            description = "Show agent progress in the Android 16 Live Updates status chip.",
            prefs = prefs,
            key = BackgroundSettingsRepository.KEY_DYNAMIC_ISLAND_ENABLED,
            defaultValue = false,
        )
        r.register(
            if (DynamicIslandSupport.isDynamicIslandCapable(context)) {
                dynamicIslandField
            } else {
                UnavailableField(
                    dynamicIslandField,
                    "Live Updates requires Android 16 or newer plus the per-app " +
                        "Live Updates permission; this device doesn't support it.",
                )
            }
        )
        // [FIX-261025-03] Settings > Background > Scheduled Task Countdown:
        // how long before a waiting scheduled task's next run its chat shows the
        // countdown and keeps the service. Same prefs file + key the repository
        // reads; its listener picks a write up at once.
        r.register(
            PrefsIntCodedEnumField(
                path = "background.scheduledCountdown",
                displayName = "Scheduled task countdown",
                description = "How long before a scheduled task's next run its chat shows the countdown in the notification and keeps running in the background.",
                prefs = prefs,
                key = BackgroundSettingsRepository.KEY_SCHEDULED_COUNTDOWN_LEAD,
                cases = BackgroundSettingsRepository.SCHEDULED_COUNTDOWN_CASES,
                valueLabels = mapOf(
                    "off" to "Off", "1m" to "1 minute before", "5m" to "5 minutes before",
                    "15m" to "15 minutes before", "always" to "Whole wait",
                ),
                defaultIndex = BackgroundSettingsRepository.DEFAULT_SCHEDULED_COUNTDOWN_LEAD,
            )
        )
    }

    // -- Logs --

    private fun registerLogs(r: ConfigRegistry, context: Context) {
        // T311: route through AppLogger so a write actually starts/stops the
        // capture pipeline. The previous PrefsBoolField wrote a sibling
        // `logging`/`logging_enabled` key that AppLogger never reads
        // (AppLogger uses `logging_prefs`/`logging_enabled`), so toggling via
        // minis-config did nothing. Default mirrors AppLogger's own default
        // (false — opt-in), aligning with iOS.
        r.register(
            ClosureField(
                path = "logs.enabled",
                displayName = "Logging enabled",
                description = "Capture stdout/stderr to daily log files for diagnostics.",
                valueSchema = ConfigSchema.Bool,
                risk = ConfigRisk.NORMAL,
                revertable = true,
                readable = ConfigReadable(ConfigValueFormatter.Bool()),
                reader = {
                    ConfigValue.Bool(com.openminis.app.logging.AppLogger.isEnabled(context))
                },
                writer = { v ->
                    val b = (v as? ConfigValue.Bool)?.value
                        ?: throw ConfigError.TypeMismatch("bool")
                    com.openminis.app.logging.AppLogger.setEnabled(context, b)
                },
            )
        )
    }

    // -- Provider / Models / Groups / EnvVars collections + aggregates --

    private fun registerProviderCollections(
        r: ConfigRegistry,
        context: Context,
        providerRepo: ProviderRepository,
        envVarRepo: EnvVarRepository,
    ) {
        r.register(ProvidersCollection(providerRepo, envVarRepo, context.applicationContext))
        // [T-minis-config-coverage] Sub agents are global, so the collection
        // keys its children by id and needs one flat `order` field. The
        // summary is what `get subagents` returns. Same surface as iOS.
        r.register(com.openminis.app.config.collections.SubAgentsCollection(providerRepo))
        r.register(com.openminis.app.config.collections.SubAgentsCollection.orderField(providerRepo))
        r.register(com.openminis.app.config.collections.SubAgentsCollection.summaryField(providerRepo))
        r.register(ModelsCollection(providerRepo))
        r.register(GroupsCollection(providerRepo))
        r.register(EnvVarsCollection(envVarRepo))
        // [T-android-thinking-rules-phase2] Custom thinking rules under
        // thinkingrules.<instanceId>:<ruleId>.<field>.
        r.register(com.openminis.app.config.collections.ThinkingRulesCollection(providerRepo))

        val isoFormatter = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply {
            timeZone = TimeZone.getTimeZone("UTC")
        }

        // Aggregate read-only summary so `minis-config get providers`
        // returns a useful list of configured instances. Credentials
        // (apiKey / oauthToken) are deliberately omitted.
        r.register(
            ReadOnlyField(
                path = "providers",
                displayName = "LLM provider instances (summary)",
                description = "Read-only list of configured providers (id, label, type, credential type, enabled, base URL). Credentials are not included. Use `minis-config add providers <json>` to create a new provider (payload mirrors a provider-export `config` block, with optional `apiKey` as literal or `\$\$ENV_VAR`).",
                valueSchema = ConfigSchema.Json,
                reader = {
                    val instances = providerRepo.config.value.instances
                    ConfigValue.Arr(
                        instances.map { inst ->
                            ConfigValue.Obj(
                                linkedMapOf(
                                    "id" to ConfigValue.Str(inst.id),
                                    "label" to ConfigValue.Str(inst.label),
                                    "providerType" to ConfigValue.Str(inst.providerType.name),
                                    "credentialType" to ConfigValue.Str(inst.credentialType.name),
                                    "isEnabled" to ConfigValue.Bool(inst.isEnabled),
                                    "customBaseURL" to (inst.customBaseURL?.let { ConfigValue.Str(it) } ?: ConfigValue.Null),
                                    "appendV1Suffix" to ConfigValue.Bool(inst.appendV1Suffix),
                                    "useResponsesAPI" to ConfigValue.Bool(inst.useResponsesAPI),
                                    "azureMode" to ConfigValue.Bool(inst.azureMode),
                                )
                            )
                        }
                    )
                },
            )
        )

        // Aggregate read-only summary so `minis-config get models`
        // returns every model entry along with its provider context.
        r.register(
            ReadOnlyField(
                path = "models",
                displayName = "Model entries (summary)",
                description = "Read-only list of every model entry (entry_id, display_name, model_id, provider).",
                valueSchema = ConfigSchema.Json,
                reader = {
                    val cfg = providerRepo.config.value
                    val providersById = HashMap<String, com.openminis.app.data.model.ProviderInstance>(cfg.instances.size)
                    for (inst in cfg.instances) providersById[inst.id] = inst
                    ConfigValue.Arr(
                        cfg.modelEntries.map { entry ->
                            val inst = providersById[entry.providerInstanceId]
                            ConfigValue.Obj(
                                linkedMapOf(
                                    "entry_id" to ConfigValue.Str(entry.id),
                                    "display_name" to ConfigValue.Str(entry.model.displayName),
                                    "model_id" to ConfigValue.Str(entry.baseModel.id),
                                    "provider_id" to ConfigValue.Str(entry.providerInstanceId),
                                    "provider_label" to (inst?.let { ConfigValue.Str(it.label) } ?: ConfigValue.Null),
                                    "provider_type" to (inst?.let { ConfigValue.Str(it.providerType.name) } ?: ConfigValue.Null),
                                    "is_custom" to ConfigValue.Bool(entry.isCustom),
                                    "is_hidden" to ConfigValue.Bool(entry.isHidden),
                                )
                            )
                        }
                    )
                },
            )
        )

        // Aggregate read-only summary so `minis-config get envvars`
        // returns every env var key with its non-secret metadata.
        r.register(
            ReadOnlyField(
                path = "envvars",
                displayName = "Environment variables (summary)",
                description = "Read-only list of env var keys with note, createdAt and enabled. Values are never exposed.",
                valueSchema = ConfigSchema.Json,
                reader = {
                    ConfigValue.Arr(
                        envVarRepo.entries.value.map { e ->
                            ConfigValue.Obj(
                                linkedMapOf(
                                    "key" to ConfigValue.Str(e.key),
                                    "note" to ConfigValue.Str(e.note),
                                    "created_at" to ConfigValue.Str(isoFormatter.format(Date(e.createdAt))),
                                    "enabled" to ConfigValue.Bool(e.enabled),
                                )
                            )
                        }
                    )
                },
            )
        )

        // Privacy Mode toggle. Read/write so the user can ask the
        // agent to flip it without diving into Settings ("turn off
        // privacy mode for the next command"). Routed through
        // EnvVarPrivacyStore.setEnabled so the StateFlow fires and
        // the Compose Switch stays in sync. Default ON; risk
        // SENSITIVE because turning it OFF means the next shell
        // execute can leak unmasked secrets to the model.
        r.register(
            ClosureField(
                path = "envvars.privacyMode",
                displayName = "Privacy Mode",
                description = "When ON, env-var values that appear in shell-execute output are masked before reaching the model. Turning OFF lets the model see raw values — only do this if the user explicitly asked.",
                valueSchema = ConfigSchema.Bool,
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                readable = ConfigReadable(ConfigValueFormatter.Bool()),
                reader = { ConfigValue.Bool(com.openminis.app.data.EnvVarPrivacyStore.isEnabled) },
                writer = { v ->
                    val b = (v as? ConfigValue.Bool)?.value
                        ?: throw ConfigError.TypeMismatch("bool")
                    com.openminis.app.data.EnvVarPrivacyStore.setEnabled(b)
                },
            )
        )

        // Aggregate read-only summary so `minis-config get groups`
        // returns every model group with its expanded entries.
        r.register(
            ReadOnlyField(
                path = "groups",
                displayName = "Model groups (summary)",
                description = "Read-only list of model groups with entries expanded.",
                valueSchema = ConfigSchema.Json,
                reader = {
                    val cfg = providerRepo.config.value
                    val entriesById =
                        HashMap<String, com.openminis.app.data.model.ModelEntry>(cfg.modelEntries.size)
                    for (e in cfg.modelEntries) entriesById[e.id] = e
                    val providersById =
                        HashMap<String, com.openminis.app.data.model.ProviderInstance>(cfg.instances.size)
                    for (inst in cfg.instances) providersById[inst.id] = inst
                    ConfigValue.Arr(
                        cfg.modelGroups.map { g ->
                            val entries = g.memberEntryIds.map { entryId ->
                                val e = entriesById[entryId]
                                if (e == null) {
                                    ConfigValue.Obj(
                                        linkedMapOf(
                                            "entry_id" to ConfigValue.Str(entryId),
                                            "missing" to ConfigValue.Bool(true),
                                        )
                                    )
                                } else {
                                    val inst = providersById[e.providerInstanceId]
                                    ConfigValue.Obj(
                                        linkedMapOf(
                                            "entry_id" to ConfigValue.Str(entryId),
                                            "display_name" to ConfigValue.Str(e.model.displayName),
                                            "model_id" to ConfigValue.Str(e.baseModel.id),
                                            "provider_id" to ConfigValue.Str(e.providerInstanceId),
                                            "provider_label" to (inst?.let { ConfigValue.Str(it.label) } ?: ConfigValue.Null),
                                        )
                                    )
                                }
                            }
                            ConfigValue.Obj(
                                linkedMapOf(
                                    "id" to ConfigValue.Str(g.id),
                                    "name" to ConfigValue.Str(g.name),
                                    "strategy" to ConfigValue.Str(g.strategy.name),
                                    "fallback_strategy" to ConfigValue.Str(g.fallbackStrategy.name),
                                    "entries" to ConfigValue.Arr(entries),
                                )
                            )
                        }
                    )
                },
            )
        )
    }

    // -- Defaults — agent loop entries / groups, default primary/sub group --

    private fun registerDefaults(r: ConfigRegistry, repo: ProviderRepository) {
        r.register(
            ClosureField(
                path = "defaults.primaryGroup",
                displayName = "Default primary group",
                description = "Group used by new sessions. Empty string clears the default.",
                valueSchema = ConfigSchema.Str(maxLength = 200),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                readable = ConfigReadable(ConfigValueFormatter.Ref(ConfigRefKind.Group)),
                reader = { ConfigValue.Str(repo.defaultPrimaryGroupId ?: "") },
                writer = { v ->
                    val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                    if (s.isEmpty()) {
                        repo.defaultPrimaryGroupId = null
                    } else {
                        if (repo.config.value.modelGroups.none { it.id == s }) {
                            throw ConfigError.InvalidValue("No group with id $s")
                        }
                        repo.defaultPrimaryGroupId = s
                    }
                },
            )
        )
        r.register(
            ClosureField(
                path = "defaults.subGroup",
                displayName = "Default sub group",
                description = "Secondary group for fallback. Empty string clears the default.",
                valueSchema = ConfigSchema.Str(maxLength = 200),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                readable = ConfigReadable(ConfigValueFormatter.Ref(ConfigRefKind.Group)),
                reader = { ConfigValue.Str(repo.defaultSubGroupId ?: "") },
                writer = { v ->
                    val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                    if (s.isEmpty()) {
                        repo.defaultSubGroupId = null
                    } else {
                        if (repo.config.value.modelGroups.none { it.id == s }) {
                            throw ConfigError.InvalidValue("No group with id $s")
                        }
                        repo.defaultSubGroupId = s
                    }
                },
            )
        )
        r.register(
            ClosureField(
                path = "defaults.agentLoopEntries",
                displayName = "Agent loop model entries",
                description = "Model entries available via minis-model-use. Replace with full list to set; use .append/.remove for single-element ops.",
                valueSchema = ConfigSchema.Array(ConfigSchema.Str()),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                readable = ConfigReadable(ConfigValueFormatter.RefList(ConfigRefKind.ModelEntry, ordered = false)),
                reader = {
                    // [T-android-agentloop-dirty-data-skip] Filter out ids that no
                    // longer match a real model entry (legacy bare-UUID / deleted
                    // rows). Surfacing them confuses minis-model-use and the
                    // round-trip set/append flow; skip silently.
                    val valid = repo.config.value.modelEntries.map { it.id }.toSet()
                    ConfigValue.Arr(
                        repo.config.value.agentLoopModelEntryIds
                            .filter { it in valid }
                            .map { ConfigValue.Str(it) }
                    )
                },
                writer = { v ->
                    val arr = (v as? ConfigValue.Arr)?.value ?: throw ConfigError.TypeMismatch("array")
                    val ids = arr.mapNotNull { (it as? ConfigValue.Str)?.value }
                    val valid = repo.config.value.modelEntries.map { it.id }.toSet()
                    // [T-android-agentloop-dirty-data-skip] Silent-skip unknown ids
                    // instead of hard-failing the whole write. `.append` reads the
                    // existing array (which may carry legacy bare-UUID dirt) and
                    // re-writes the full list; a hard fail rejected the valid new
                    // entry along with the historical dirt. Filtering cleans the
                    // dirt and lets the valid entries through; one diagnostic line
                    // per skipped id leaves a trace without blocking the op.
                    val cleanIds = ids.filter { it in valid }
                    ids.filterNot { it in valid }.forEach { bad ->
                        com.openminis.app.logging.AppLogger.warning(
                            "MinisConfig", "skipped unknown agentLoopEntries id: $bad"
                        )
                    }
                    repo.setAgentLoopEntryIds(cleanIds)
                },
            )
        )
        r.register(
            ClosureField(
                path = "defaults.agentLoopGroups",
                displayName = "Agent loop groups",
                description = "Whole groups exposed via minis-model-use.",
                valueSchema = ConfigSchema.Array(ConfigSchema.Str()),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                readable = ConfigReadable(ConfigValueFormatter.RefList(ConfigRefKind.Group, ordered = false)),
                reader = {
                    // [T-android-agentloop-dirty-data-skip] Skip ids that no
                    // longer match a real group (deleted / legacy bare UUID).
                    val valid = repo.config.value.modelGroups.map { it.id }.toSet()
                    ConfigValue.Arr(
                        repo.config.value.agentLoopGroupIds
                            .filter { it in valid }
                            .map { ConfigValue.Str(it) }
                    )
                },
                writer = { v ->
                    val arr = (v as? ConfigValue.Arr)?.value ?: throw ConfigError.TypeMismatch("array")
                    val ids = arr.mapNotNull { (it as? ConfigValue.Str)?.value }
                    val valid = repo.config.value.modelGroups.map { it.id }.toSet()
                    // [T-android-agentloop-dirty-data-skip] Silent-skip unknown
                    // group ids (same rationale as agentLoopEntries above).
                    val cleanIds = ids.filter { it in valid }
                    ids.filterNot { it in valid }.forEach { bad ->
                        com.openminis.app.logging.AppLogger.warning(
                            "MinisConfig", "skipped unknown agentLoopGroups id: $bad"
                        )
                    }
                    repo.setAgentLoopGroupIds(cleanIds)
                },
            )
        )
    }

    // -- Soul (SOUL.md personality) --
    //
    // [T-soul-md-minisconfig] Exposes the editable subset of SOUL.md
    // (name / style / lang / body) as `soul.*` config paths. The on-disk
    // YAML emoji field is intentionally NOT registered — matches the
    // iOS rollback where ✨ is locked as the identity icon.
    //
    // Writes go through the standard PendingConfigChange / user-confirm
    // gate (registered with [ConfigRisk.SENSITIVE]); only the *approved*
    // writer touches disk. Each writer reads the current SOUL.md so
    // unrelated fields (incl. the on-disk `emoji` value that survives
    // for round-trip) are preserved when one field changes.

    /**
     * [T-android-soul-icon-config-images] Resolve a config-supplied image
     * source to the stored inline data URI.
     *
     * Throws [ConfigError.InvalidValue] with actionable text on every failure
     * path — this runs on behalf of a model, and "invalid value" with no
     * detail just produces another wrong guess.
     */
    private fun resolveSoulIconImage(context: Context, raw: String): String {
        val icon = com.openminis.app.agent.SoulIcon
        val bytes: ByteArray = when (val src = icon.classifySource(raw)) {
            is com.openminis.app.agent.SoulIcon.Source.Unsupported ->
                throw ConfigError.InvalidValue(src.reason)

            is com.openminis.app.agent.SoulIcon.Source.Bytes -> src.data

            is com.openminis.app.agent.SoulIcon.Source.LinuxPath -> {
                val root = icon.ALLOWED_LINUX_ROOTS.firstOrNull {
                    src.path == it || src.path.startsWith("$it/")
                } ?: throw ConfigError.InvalidValue(
                    "path must be inside one of ${icon.ALLOWED_LINUX_ROOTS.joinToString(", ")}",
                )
                // resolveSessionHostPath, NOT resolveHostPath: the global
                // bindMounts map is only populated once a shell has booted
                // this session (and is last-writer-wins across sessions), so
                // resolving through it fails outright when the user hasn't
                // run a command yet — which is exactly the state a config
                // write from the UI arrives in.
                val sid = ChatViewModelStore.activeSessionId
                    ?: throw ConfigError.InvalidValue(
                        "no active session — open a chat first, or pass the image inline as a data URI",
                    )
                val kernel = com.openminis.app.sandbox.PRootKernel
                val hostRoot = kernel.resolveSessionHostPath(sid, root, context)
                    ?: throw ConfigError.InvalidValue("could not resolve $root on this device")
                val hostFile = kernel.resolveSessionHostPath(sid, src.path, context)
                    ?: throw ConfigError.InvalidValue("could not resolve ${src.path}")
                // Canonicalise BOTH sides before comparing, so a symlink
                // inside an allowed directory cannot point out of it and a
                // `..` segment cannot walk out of the sandbox.
                if (!icon.isContained(hostFile, hostRoot)) {
                    throw ConfigError.InvalidValue("${src.path} resolves outside $root")
                }
                if (!hostFile.isFile) {
                    throw ConfigError.InvalidValue("${src.path} does not exist")
                }
                runCatching { hostFile.readBytes() }.getOrElse {
                    throw ConfigError.InvalidValue("could not read ${src.path}")
                }
            }
        }

        val bitmap = runCatching {
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }.getOrNull() ?: throw ConfigError.InvalidValue(
            "that data isn't a decodable image (png / jpeg / webp / gif are supported; svg is not)",
        )

        // Same encoder the Settings picker uses, so crop / downscale / size
        // rules are shared by construction rather than by copy.
        return when (val r = icon.encode(bitmap)) {
            is com.openminis.app.agent.SoulIcon.EncodeResult.Success -> r.dataUri
            is com.openminis.app.agent.SoulIcon.EncodeResult.Failure -> when (r.reason) {
                com.openminis.app.agent.SoulIcon.Rejection.TOO_LARGE ->
                    throw ConfigError.InvalidValue("that image is too large to store inline")
                com.openminis.app.agent.SoulIcon.Rejection.UNREADABLE ->
                    throw ConfigError.InvalidValue("that image could not be processed")
            }
        }
    }

    private fun registerSoul(r: ConfigRegistry, context: Context) {
        // [persona-unlimited] No length cap — personality is user-authored
        // and the model's context window is the budget. isOverLimit() is a
        // pass-through Ok; the writer's OverLimit branches are unreachable
        // but kept for exhaustiveness.

        // Re-read SOUL.md every call so concurrent writes from Settings
        // UI don't race with minis-config writes. Parse falls back to
        // the canonical default content if the file was somehow deleted
        // between launches — same behavior as the Settings page.
        fun loadCurrent(): com.openminis.app.agent.SoulFile =
            com.openminis.app.agent.SoulStore.load(context)
                ?: com.openminis.app.agent.SoulMDParser.parse(
                    com.openminis.app.agent.SoulStore.DEFAULT_CONTENT
                )

        fun saveCurrent(file: com.openminis.app.agent.SoulFile) {
            com.openminis.app.agent.SoulStore.save(context, file)
        }

        r.register(
            ClosureField(
                path = "soul.name",
                displayName = "Soul name",
                description = "The agent's name as shown in chat bubble headers and as the system-prompt identity. Trimmed; empty falls back to \"Minis\".",
                valueSchema = ConfigSchema.Str(maxLength = 64),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                readable = ConfigReadable(ConfigValueFormatter.Text()),
                reader = {
                    val meta = loadCurrent().metadata
                    ConfigValue.Str(meta.name)
                },
                writer = { v ->
                    val raw = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    val name = raw.trim().ifBlank {
                        com.openminis.app.agent.SoulMetadata.DEFAULT.name
                    }
                    val cur = loadCurrent()
                    saveCurrent(cur.copy(metadata = cur.metadata.copy(name = name)))
                },
            )
        )

        // [T-android-soul-custom-icon][T-android-soul-icon-config-images]
        //
        // Accepts an emoji OR an image, matching iOS `fe2f3ae8b`. An address
        // (minis:// or a /var/minis path) is only an import source: it is
        // resolved, re-encoded through the SAME SoulIcon.encode the Settings
        // picker uses, and only the RESULT is stored inline — so the source
        // file can be deleted afterwards and the icon survives attachment
        // cleanup and syncing to another device.
        //
        // Reading is summarized, never dumped: a stored image reports as
        // "<image>" so `minis-config get` cannot flood the context with
        // base64. For the same reason the field is NOT revertable — the audit
        // log holds "<image>" rather than the bytes, so there is nothing to
        // restore from and offering a revert button would be a lie.
        r.register(
            ClosureField(
                path = "soul.icon",
                displayName = "Soul icon",
                // The description IS the API doc — `minis-config topic-help
                // soul` prints it verbatim. It leads with every word a user
                // might use for this (icon / avatar / persona image /
                // 角色形象 / 头像) because they are all this ONE field, and a
                // model that doesn't match the wording will otherwise report
                // the capability as missing.
                description = "The Soul's identity icon — also called its avatar, its persona image, or its " +
                    "character image (中文：角色形象 / 图标 / 头像). All of those refer to THIS one setting; " +
                    "there is no separate avatar field. Shown beside the assistant name in the chat header " +
                    "and on the Soul settings card.\n" +
                    "\n" +
                    "EMOJI\n" +
                    "  A single emoji, e.g. \"⚡\". An empty string \"\" restores the default sparkle.\n" +
                    "\n" +
                    "IMAGE — any of these forms:\n" +
                    "  • data URI: data:image/png;base64,iVBORw0KGgo...\n" +
                    "  • bare base64 (no data: prefix) — auto-detected\n" +
                    "  • minis:// resource, e.g. minis://attachments/icon.png\n" +
                    "  • a path inside the minis directories, e.g. /var/minis/attachments/icon.png\n" +
                    "  Remote http(s) URLs are NOT supported on Android — download the file first, " +
                    "then pass its path.\n" +
                    "\n" +
                    "TRANSPARENCY — not required. Opaque images (a JPEG, a flattened PNG) are fine; " +
                    "the icon renders with rounded corners, so it still reads as a normal small avatar. " +
                    "Transparency is preserved when the source has it.\n" +
                    "\n" +
                    "PROCESSING — identical to picking an image in Settings → Soul: centre-cropped to a " +
                    "square, downscaled to ${com.openminis.app.agent.SoulIcon.STORED_PIXELS}×" +
                    "${com.openminis.app.agent.SoulIcon.STORED_PIXELS}, re-encoded as PNG and stored inline.\n" +
                    "\n" +
                    "READING — `get soul.icon` returns \"<image>\" for an image, never the base64.\n" +
                    "\n" +
                    "EXAMPLES (the value is JSON, so the string needs its own quotes)\n" +
                    "  minis-config set soul.icon '\"⚡\"'\n" +
                    "  minis-config set soul.icon '\"minis://attachments/icon.png\"'\n" +
                    "  minis-config set soul.icon '\"\"'   # back to the default sparkle",
                // No maxLength: an inline data URI is far longer than any cap
                // that would make sense stated in characters. The real bound
                // is SoulIcon.MAX_DATA_URI_CHARS, enforced by the encoder.
                valueSchema = ConfigSchema.Str(),
                risk = ConfigRisk.SENSITIVE,
                revertable = false,
                readable = ConfigReadable(ConfigValueFormatter.Image),
                reader = {
                    val raw = loadCurrent().metadata.icon
                    ConfigValue.Str(
                        if (com.openminis.app.agent.SoulIcon.isDataUri(raw)) "<image>" else raw,
                    )
                },
                writer = { v ->
                    val raw = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    val trimmed = raw.trim()
                    val cur = loadCurrent()
                    val next = when {
                        // Empty clears back to the default sparkle.
                        trimmed.isEmpty() -> ""

                        // A single emoji stays verbatim.
                        com.openminis.app.agent.SoulIcon.graphemeClusters(trimmed).size == 1 &&
                            com.openminis.app.agent.SoulIcon.isEmojiGlyph(trimmed) -> trimmed

                        else -> resolveSoulIconImage(context, trimmed)
                    }
                    saveCurrent(cur.copy(metadata = cur.metadata.copy(icon = next)))
                },
            )
        )

        r.register(
            ClosureField(
                path = "soul.style",
                displayName = "Soul style",
                description = "Short style descriptor used as a hint in the system prompt (e.g. \"Warm, direct, opinionated\").",
                valueSchema = ConfigSchema.Str(maxLength = 256),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                readable = ConfigReadable(ConfigValueFormatter.Text(preview = 60)),
                reader = {
                    val meta = loadCurrent().metadata
                    ConfigValue.Str(meta.style)
                },
                writer = { v ->
                    val raw = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    val style = raw.trim()
                    val cur = loadCurrent()
                    saveCurrent(cur.copy(metadata = cur.metadata.copy(style = style)))
                },
            )
        )

        r.register(
            ClosureField(
                path = "soul.lang",
                displayName = "Soul language",
                description = "Preferred response language: auto / zh / en. Older SOUL.md files missing the field are upgraded on first write.",
                valueSchema = ConfigSchema.StrEnum(listOf("auto", "zh", "en")),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                // Label = the iOS key for the same field, so both read alike.
                readable = ConfigReadable(
                    ConfigValueFormatter.EnumLabels(mapOf("auto" to "Automatic", "zh" to "Chinese", "en" to "English")),
                    label = "Soul language preference",
                ),
                reader = {
                    val cur = loadCurrent()
                    // Older files may be missing the lang frontmatter
                    // key — parser already substitutes DEFAULT.lang in
                    // that case, so we just round-trip it.
                    ConfigValue.Str(cur.metadata.lang.ifBlank {
                        com.openminis.app.agent.SoulMetadata.DEFAULT.lang
                    })
                },
                writer = { v ->
                    val raw = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    val lang = raw.trim()
                    val cur = loadCurrent()
                    saveCurrent(cur.copy(metadata = cur.metadata.copy(lang = lang)))
                },
            )
        )

        r.register(
            ClosureField(
                path = "soul.body",
                displayName = "Soul personality prompt",
                description = "Personality / voice instructions injected as a block in the system prompt. No length cap — the model's context window is the budget. The writer still rejects prompt-injection patterns (\"ignore previous instructions\" etc.) — keep this to genuine character / tone guidance.",
                // No schema maxLength because either character or word
                // count is wrong for the *other* language. Both checks
                // (length + injection) run in the writer below.
                valueSchema = ConfigSchema.Str(),
                risk = ConfigRisk.SENSITIVE,
                revertable = true,
                readable = ConfigReadable(ConfigValueFormatter.LongText, label = "Soul personality body"),
                reader = {
                    ConfigValue.Str(loadCurrent().body)
                },
                writer = { v ->
                    val raw = (v as? ConfigValue.Str)?.value
                        ?: throw ConfigError.TypeMismatch("string")
                    if (com.openminis.app.agent.SystemPromptBuilder
                            .containsInjectionPattern(raw)
                    ) {
                        throw ConfigError.InvalidValue(
                            "Body contains a prompt-injection pattern (\"ignore/disregard/forget … previous/prior instructions\"). SOUL.md is for personality, not instructions to the model."
                        )
                    }
                    val check = com.openminis.app.agent.SoulStore.isOverLimit(raw)
                    when (check) {
                        is com.openminis.app.agent.SoulBodyLimitCheck.Ok -> Unit
                        is com.openminis.app.agent.SoulBodyLimitCheck.OverLimitChinese ->
                            throw ConfigError.InvalidValue(
                                "Over limit: ${check.chars} Chinese chars exceeds cap of ${check.cap}. Either trim to ≤ ${check.cap} chars, or rewrite mostly in English to use the ≤ ${com.openminis.app.agent.SoulStore.ENGLISH_WORD_LIMIT}-word cap."
                            )
                        is com.openminis.app.agent.SoulBodyLimitCheck.OverLimitEnglish ->
                            throw ConfigError.InvalidValue(
                                "Over limit: ${check.words} English words exceeds cap of ${check.cap}. Either trim to ≤ ${check.cap} words, or rewrite mostly in Chinese to use the ≤ ${com.openminis.app.agent.SoulStore.CHINESE_CHAR_LIMIT}-char cap."
                            )
                    }
                    val cur = loadCurrent()
                    saveCurrent(cur.copy(body = raw))
                },
            )
        )
    }
}
