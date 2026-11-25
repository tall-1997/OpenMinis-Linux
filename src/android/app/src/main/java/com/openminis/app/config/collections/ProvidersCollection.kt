package com.openminis.app.config.collections

import com.openminis.app.config.ConfigCollection
import com.openminis.app.config.ConfigError
import com.openminis.app.config.ConfigField
import com.openminis.app.config.ConfigRisk
import com.openminis.app.config.ConfigSchema
import com.openminis.app.config.ConfigValue
import com.openminis.app.config.fields.ClosureField
import com.openminis.app.config.readable.ConfigReadable
import com.openminis.app.config.readable.ConfigRefResolver
import com.openminis.app.config.readable.ConfigValueFormatter
import com.openminis.app.config.readable.ReadableArg
import com.openminis.app.config.readable.ReadableCore
import com.openminis.app.config.readable.ReadableLine
import com.openminis.app.config.readable.RedactedConfigValue
import com.openminis.app.config.fields.HiddenField
import com.openminis.app.config.fields.ReadOnlyField
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.EnvVarRepository
import com.openminis.app.data.repository.ProviderRepository

/**
 * Exposes `ProviderInstance` fields under `providers.<id>.…`. Mirrors
 * iOS `ProvidersCollection`.
 *
 * [T-minis-config-provider-add] Add is OPEN (by design: writes
 * are agent-permitted, reads of credentials remain guarded). Remove
 * stays denied — yanking a provider may break tool calls already in
 * flight, and the user's revertable choice is to disable it instead
 * (`providers.<id>.isEnabled = false`).
 *
 * Credential safety contract:
 *   - apiKey is a write-only ClosureField — writes accept either a
 *     literal value or a `$$ENV_VAR` reference (resolved at write time
 *     via [envVars]); reads throw permission_denied (forwarded by the
 *     bridge as `error: permission_denied`, NOT `read_failed`).
 *   - oauthToken stays HiddenField on every axis — OAuth tokens come
 *     from a multi-step browser flow that minis-config cannot drive.
 */
class ProvidersCollection(
    private val repo: ProviderRepository,
    private val envVars: EnvVarRepository,
    /** For the per-device response idle timeout; null (tests) leaves that field out. */
    private val context: android.content.Context? = null,
) : ConfigCollection {
    override val basePath: String get() = "providers"
    override val displayName: String get() = "LLM provider instances"
    override val description: String get() =
        "Configured provider accounts (Anthropic, OpenAI, …). API keys may be written (literal or $\$ENV_VAR), but never read back."
    override val addable: Boolean get() = true
    override val removable: Boolean get() = false
    override val risk: ConfigRisk get() = ConfigRisk.SENSITIVE
    override val addPayloadSchema: ConfigSchema get() = ConfigSchema.Json

    override fun childIds(): List<String> = repo.instances.map { it.id }

    override fun fields(forId: String): List<ConfigField> {
        if (repo.instance(forId) == null) return emptyList()
        return listOf(
            providerType(forId),
            credentialType(forId),
            label(forId),
            enabled(forId),
            customBaseURL(forId),
            appendV1Suffix(forId),
            useResponsesAPI(forId),
            azureMode(forId),
            customUserAgent(forId),
            apiKeyField(forId),
            networkRoute(forId),
        ) + listOfNotNull(context?.let { responseIdleTimeout(forId, it) }) + listOf(
            // OAuth token stays fully hidden — the flow is browser-driven
            // and can't be expressed as a single value.
            HiddenField(
                path = "providers.$forId.oauthToken",
                displayName = "OAuth Token",
                description = "Hidden — completed via the in-app OAuth flow.",
                reason = "OAuth tokens cannot be set via minis-config — use Settings UI",
            ),
        )
    }

    /**
     * Create a new provider instance. JSON payload shape (mirrors iOS
     * Provider export envelope's `config` object so a single agent
     * surface covers both create-from-scratch and create-from-export):
     *
     *   {
     *     "providerType":     "openAI" | "anthropic" | "gemini" | ...,  // required
     *     "label":            "My Provider",                            // required, must be unique
     *     "credentialType":   "apiKey" | "oauth",                       // default apiKey
     *     "customBaseURL":    "https://api.x.ai",                       // optional
     *     "appendV1Suffix":   true,                                     // optional, default true
     *     "useResponsesAPI":  false,                                    // optional, default false
     *     "azureMode":        false,                                    // optional, default false (OpenAI api-key only)
     *     "apiKey":           "sk-…" | "$\$DEEPSEEK_KEY",                 // optional; literal OR env ref
     *   }
     *
     * `apiKey` accepts either a literal string (stored verbatim in the
     * encrypted credential store) or a `$\$ENV_NAME` reference. The
     * reference is RESOLVED AT WRITE TIME (looked up against the
     * EnvVarRepository now); missing keys fail loudly with
     * invalid_value rather than waiting for a 401 mid-stream. Future
     * env-var changes do NOT re-update the stored apiKey — the env var
     * acts as a one-shot value source, not a live binding.
     */
    override fun add(payload: ConfigValue): String {
        val obj = (payload as? ConfigValue.Obj)?.value
            ?: throw ConfigError.InvalidValue("Expected JSON object")

        val providerTypeRaw = (obj["providerType"] as? ConfigValue.Str)?.value
            ?: throw ConfigError.InvalidValue("`providerType` required (one of: ${ProviderType.values().joinToString { it.name }})")
        val providerType = runCatching { ProviderType.valueOf(providerTypeRaw) }.getOrNull()
            ?: throw ConfigError.InvalidValue("Unknown providerType '$providerTypeRaw'")

        val label = (obj["label"] as? ConfigValue.Str)?.value?.trim().orEmpty()
        if (label.isEmpty()) throw ConfigError.InvalidValue("`label` required (non-empty)")
        if (repo.instances.any { it.label.equals(label, ignoreCase = true) }) {
            throw ConfigError.InvalidValue("A provider with label '$label' already exists")
        }

        val credentialType = (obj["credentialType"] as? ConfigValue.Str)?.value?.let { raw ->
            runCatching { ProviderCredential.valueOf(raw) }.getOrNull()
                ?: throw ConfigError.InvalidValue("Unknown credentialType '$raw' (allowed: apiKey, oauth)")
        } ?: ProviderCredential.apiKey

        val customBaseURL = (obj["customBaseURL"] as? ConfigValue.Str)?.value?.takeIf { it.isNotBlank() }
        val appendV1Suffix = (obj["appendV1Suffix"] as? ConfigValue.Bool)?.value ?: true
        val useResponsesAPI = (obj["useResponsesAPI"] as? ConfigValue.Bool)?.value ?: false
        val azureMode = (obj["azureMode"] as? ConfigValue.Bool)?.value ?: false

        // Resolve apiKey BEFORE addInstance, so a bad $$ENV ref aborts
        // the whole operation without leaving a half-created instance.
        val apiKeyValue = (obj["apiKey"] as? ConfigValue.Str)?.value?.takeIf { it.isNotEmpty() }
            ?.let { resolveCredential(it, "apiKey") }

        val instance = ProviderInstance(
            id = java.util.UUID.randomUUID().toString(),
            label = label,
            providerType = providerType,
            credentialType = credentialType,
            customBaseURL = customBaseURL,
            appendV1Suffix = appendV1Suffix,
            useResponsesAPI = useResponsesAPI,
            azureMode = azureMode,
        )
        repo.addInstance(instance)
        if (apiKeyValue != null) repo.saveApiKey(instance.id, apiKeyValue)
        return instance.id
    }

    override fun remove(id: String) {
        throw ConfigError.PermissionDenied(
            "Removing a provider may break later tool calls — disable it instead (`providers.$id.isEnabled = false`) or use the Settings UI"
        )
    }

    // -- [T-config-readable] Readable descriptions (mirrors iOS ProvidersCollection) --

    override val readableSubjectFormat: String get() = "Provider “%1\$@” – %2\$@"

    override fun entityLabel(id: String): String? = ConfigRefResolver.names.instanceLabel(id)

    /**
     * The payload is already masked: `apiKey` is either "••• (hidden)" or a
     * `$$VAR` reference. Only the fact that a key was given (or the variable
     * name) is described — never a value.
     */
    override fun readableForAdd(payload: RedactedConfigValue): List<ReadableLine> {
        val d = (payload.value as? ConfigValue.Obj)?.value ?: return emptyList()
        val label = (d["label"] as? ConfigValue.Str)?.value ?: "?"
        val typeRaw = (d["providerType"] as? ConfigValue.Str)?.value
        val type = typeRaw?.let { raw -> runCatching { ProviderType.valueOf(raw).displayName }.getOrDefault(raw) } ?: "?"
        val lines = mutableListOf(
            ReadableLine("Add provider “%1\$@” (%2\$@)", listOf(ReadableArg.Text(label), ReadableArg.Text(type))),
        )
        val key = (d["apiKey"] as? ConfigValue.Str)?.value
        lines.add(
            when {
                key.isNullOrEmpty() -> ReadableLine("No API key yet")
                key.startsWith("\$\$") && key.length > 2 ->
                    ReadableLine("API key from the environment variable $%@", listOf(ReadableArg.Text(key.drop(2))))
                else -> ReadableLine("API key provided (value hidden)")
            },
        )
        (d["customBaseURL"] as? ConfigValue.Str)?.value?.takeIf { it.isNotEmpty() }?.let {
            lines.add(ReadableLine("Base URL: %@", listOf(ReadableArg.Text(ReadableCore.safeURL(it)))))
        }
        return lines
    }

    /**
     * Not reachable today ([removable] = false); kept so the text is right if
     * removal is ever allowed.
     */
    override fun readableForRemove(id: String): List<ReadableLine> {
        val label = ConfigRefResolver.names.instanceLabel(id) ?: return emptyList()
        val models = ConfigRefResolver.names.modelCountOf(id)
        return listOf(
            ReadableLine("Delete provider “%1\$@” and its %2\$@ models", listOf(ReadableArg.Text(label), ReadableArg.Text("$models"))),
        )
    }

    // -- Credential resolution ----------------------------------------
    //
    // `$$ENV_NAME` references are resolved at WRITE TIME against the
    // env var store. We deliberately don't intercept reads at provider
    // call time: doing so would mean rewriting 18+ `loadApiKey()` call
    // sites and lose the "credential was already validated when set"
    // invariant. A missing env var here surfaces immediately as an
    // invalid_value error, rather than as a 401 deep inside a stream.
    private fun resolveCredential(raw: String, fieldName: String): String =
        resolveEnvReference(envVars, raw, fieldName)

    // -- Field factories --

    private fun mutate(id: String, apply: (ProviderInstance) -> ProviderInstance) {
        val inst = repo.instance(id) ?: throw ConfigError.UnknownPath("providers.$id")
        repo.updateInstance(apply(inst))
    }

    private fun providerType(id: String): ConfigField =
        ReadOnlyField(
            path = "providers.$id.providerType",
            displayName = "Provider type",
            description = "Anthropic / OpenAI / Gemini / OpenRouter.",
            valueSchema = ConfigSchema.Str(),
            reader = {
                val inst = repo.instance(id) ?: return@ReadOnlyField ConfigValue.Null
                ConfigValue.Str(inst.providerType.name)
            },
        )

    private fun credentialType(id: String): ConfigField =
        ReadOnlyField(
            path = "providers.$id.credentialType",
            displayName = "Credential type",
            description = "API key vs OAuth.",
            valueSchema = ConfigSchema.Str(),
            reader = {
                val inst = repo.instance(id) ?: return@ReadOnlyField ConfigValue.Null
                ConfigValue.Str(inst.credentialType.name)
            },
        )

    private fun label(id: String): ConfigField =
        ClosureField(
            path = "providers.$id.label",
            displayName = "Label",
            description = "User-facing nickname for this instance.",
            valueSchema = ConfigSchema.Str(maxLength = 200),
            risk = ConfigRisk.NORMAL,
            revertable = true,
            readable = ConfigReadable(ConfigValueFormatter.Text()),
            reader = {
                val inst = repo.instance(id) ?: return@ClosureField ConfigValue.Null
                ConfigValue.Str(inst.label)
            },
            writer = { v ->
                val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                mutate(id) { it.copy(label = s) }
            },
        )

    private fun enabled(id: String): ConfigField =
        ClosureField(
            path = "providers.$id.isEnabled",
            displayName = "Enabled",
            description = "When false, the instance is excluded from agent calls.",
            valueSchema = ConfigSchema.Bool,
            risk = ConfigRisk.SENSITIVE,
            revertable = true,
            readable = ConfigReadable(ConfigValueFormatter.Bool(on = "Enabled", off = "Disabled"), label = "Status"),
            reader = {
                val inst = repo.instance(id) ?: return@ClosureField ConfigValue.Null
                ConfigValue.Bool(inst.isEnabled)
            },
            writer = { v ->
                val b = (v as? ConfigValue.Bool)?.value ?: throw ConfigError.TypeMismatch("bool")
                mutate(id) { it.copy(isEnabled = b) }
            },
        )

    private fun customBaseURL(id: String): ConfigField =
        ClosureField(
            path = "providers.$id.customBaseURL",
            displayName = "Custom base URL",
            description = "Override the default API endpoint. Empty string = use default.",
            valueSchema = ConfigSchema.Str(maxLength = 1000),
            risk = ConfigRisk.SENSITIVE,
            revertable = true,
            readable = ConfigReadable(ConfigValueFormatter.Url()),
            reader = {
                val inst = repo.instance(id) ?: return@ClosureField ConfigValue.Null
                ConfigValue.Str(inst.customBaseURL ?: "")
            },
            writer = { v ->
                val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                mutate(id) { it.copy(customBaseURL = s.ifEmpty { null }) }
            },
        )

    private fun appendV1Suffix(id: String): ConfigField =
        ClosureField(
            path = "providers.$id.appendV1Suffix",
            displayName = "Append /v1 suffix",
            description = "When false, the custom base URL is treated as already-versioned.",
            valueSchema = ConfigSchema.Bool,
            risk = ConfigRisk.NORMAL,
            revertable = true,
            readable = ConfigReadable(ConfigValueFormatter.Bool()),
            reader = {
                val inst = repo.instance(id) ?: return@ClosureField ConfigValue.Null
                ConfigValue.Bool(inst.appendV1Suffix)
            },
            writer = { v ->
                val b = (v as? ConfigValue.Bool)?.value ?: throw ConfigError.TypeMismatch("bool")
                mutate(id) { it.copy(appendV1Suffix = b) }
            },
        )

    private fun useResponsesAPI(id: String): ConfigField =
        ClosureField(
            path = "providers.$id.useResponsesAPI",
            displayName = "Use OpenAI Responses API",
            description = "OpenAI-only: when true, traffic goes through /v1/responses instead of /v1/chat/completions.",
            valueSchema = ConfigSchema.Bool,
            risk = ConfigRisk.NORMAL,
            revertable = true,
            readable = ConfigReadable(ConfigValueFormatter.Bool()),
            reader = {
                val inst = repo.instance(id) ?: return@ClosureField ConfigValue.Null
                ConfigValue.Bool(inst.useResponsesAPI)
            },
            writer = { v ->
                val b = (v as? ConfigValue.Bool)?.value ?: throw ConfigError.TypeMismatch("bool")
                mutate(id) { it.copy(useResponsesAPI = b) }
            },
        )

    // [T-android-azure-openai] Azure OpenAI mode. Mirrors useResponsesAPI above.
    private fun azureMode(id: String): ConfigField =
        ClosureField(
            path = "providers.$id.azureMode",
            displayName = "Azure OpenAI mode",
            description = "OpenAI API-key only: when true, auth uses the api-key header and the custom base URL is treated as an Azure endpoint ({endpoint}/openai/deployments/{model}/…?api-version=…).",
            valueSchema = ConfigSchema.Bool,
            risk = ConfigRisk.NORMAL,
            revertable = true,
            readable = ConfigReadable(ConfigValueFormatter.Bool()),
            reader = {
                val inst = repo.instance(id) ?: return@ClosureField ConfigValue.Null
                ConfigValue.Bool(inst.azureMode)
            },
            writer = { v ->
                val b = (v as? ConfigValue.Bool)?.value ?: throw ConfigError.TypeMismatch("bool")
                mutate(id) { it.copy(azureMode = b) }
            },
        )

    /**
     * [T-android-minisconfig-custom-useragent] Per-provider User-Agent
     * override. Empty string clears the override and falls back to the
     * branded default (Minis Ultra/<version> (Android <release>; <model>) — see
     * MinisUserAgent.DEFAULT) at request build time. Cloned from
     * [customBaseURL] in shape; the underlying field on ProviderInstance
     * has been wired through ProviderFactory + applyUserAgentOverride
     * since #802, so a write here takes effect on the very next outbound
     * call without any restart.
     */
    private fun customUserAgent(id: String): ConfigField =
        ClosureField(
            path = "providers.$id.customUserAgent",
            displayName = "Custom User-Agent",
            description = "Override the outbound User-Agent header. Empty string = use default.",
            valueSchema = ConfigSchema.Str(maxLength = 256),
            risk = ConfigRisk.NORMAL,
            revertable = true,
            readable = ConfigReadable(ConfigValueFormatter.Text(preview = 60, empty = "Default")),
            reader = {
                val inst = repo.instance(id) ?: return@ClosureField ConfigValue.Null
                ConfigValue.Str(inst.customUserAgent ?: "")
            },
            writer = { v ->
                val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                // [T-ua-block-oauth-provider] Custom UA only applies to
                // third-party API-key providers. OAuth providers (Anthropic /
                // Codex) ride their own authentication request chain with a
                // fixed client UA — overriding it can break the handshake or be
                // rejected upstream. Block ALL writes (including clearing to "")
                // for non-apiKey credentials; reads stay unblocked.
                val inst = repo.instance(id) ?: throw ConfigError.InvalidValue("Provider not found")
                if (inst.credentialType != ProviderCredential.apiKey) {
                    throw ConfigError.InvalidValue("Custom User-Agent is only supported for third-party API-key providers. OAuth providers (Anthropic/Codex) use their own authentication UA and cannot be overridden.")
                }
                mutate(id) { it.copy(customUserAgent = s.ifEmpty { null }) }
            },
        )

    /**
     * [T-minis-config-coverage] Settings > provider > Response idle timeout
     * ([ResponseIdleTimeoutSetting]): same store, range and gate as the UI
     * row, which only OpenAI-compatible API-key instances show. Stored per
     * device, not synced. Applies to requests built after the write.
     */
    private fun responseIdleTimeout(id: String, context: android.content.Context): ConfigField {
        val setting = com.openminis.app.provider.ResponseIdleTimeoutSetting
        return ClosureField(
            path = "providers.$id.responseIdleTimeout",
            displayName = "Response idle timeout",
            description = "Seconds to wait for a streamed reply to START (and the floor for the wait between its pieces) before the request counts as dead and is retried. Not a limit on how long a reply may take. Default ${setting.DEFAULT_SECONDS}; raise it for local models that prefill a long context for minutes (Settings offers ${setting.PRESETS.joinToString(", ")}). OpenAI-compatible API-key providers only; stored on this device.",
            valueSchema = ConfigSchema.Int(min = setting.MIN_SECONDS, max = setting.MAX_SECONDS),
            risk = ConfigRisk.NORMAL,
            revertable = true,
            readable = ConfigReadable(ConfigValueFormatter.Number(com.openminis.app.config.readable.NumberUnit.SECONDS)),
            reader = {
                repo.instance(id) ?: return@ClosureField ConfigValue.Null
                ConfigValue.Int(setting.seconds(context, id))
            },
            writer = { v ->
                val i = (v as? ConfigValue.Int)?.value ?: throw ConfigError.TypeMismatch("int")
                val inst = repo.instance(id) ?: throw ConfigError.InvalidValue("Provider not found")
                if (inst.providerType != com.openminis.app.data.model.ProviderType.openAI ||
                    inst.credentialType == ProviderCredential.oauth
                ) {
                    throw ConfigError.InvalidValue("The response idle timeout only applies to OpenAI-compatible API-key providers; this provider keeps its own time budgets.")
                }
                setting.set(context, id, i)
            },
        )
    }

    /**
     * Write-only API key. Read always throws permission_denied (the
     * bridge surfaces this as `error: permission_denied`, not
     * `read_failed`, thanks to the explicit catch added in T-minis-
     * config-provider-add). Write accepts either a literal value or a
     * `$$ENV_NAME` reference resolved at write time against the env
     * var store; failed lookups throw invalid_value.
     */
    /**
     * [FEAT-261021-03] The provider page's network route (iOS FEAT-261019-01).
     * Device-local (NetworkSettings.providerRoutes), not part of the synced
     * provider record.
     */
    private fun networkRoute(id: String): ConfigField =
        ClosureField(
            path = "providers.$id.networkRoute",
            displayName = "Network",
            description = "How this provider's requests connect: default (follow network.defaultRoute), direct, " +
                "tailscale, or proxy:<id or name> (see `minis-config get proxies`). Kept on this device only.",
            valueSchema = ConfigSchema.Str(maxLength = 128),
            risk = ConfigRisk.SENSITIVE,
            readable = ConfigReadable(ConfigValueFormatter.Custom(com.openminis.app.config.fields.NetworkConfigRoute::phrase)),
            reader = { ConfigValue.Str(com.openminis.app.network.NetworkHub.providerRoute(id).raw) },
            writer = { v ->
                if (repo.instance(id) == null) throw ConfigError.UnknownPath("providers.$id")
                com.openminis.app.network.NetworkHub.setProviderRoute(
                    com.openminis.app.network.NetworkRoute.parse((v as ConfigValue.Str).value), id,
                )
            },
            normalizer = {
                com.openminis.app.config.fields.NetworkConfigRoute.normalize(it, com.openminis.app.network.NetworkHub.settings.value.proxies)
            },
            validator = {
                com.openminis.app.config.fields.NetworkConfigRoute.validate(
                    it, allowFollow = true, com.openminis.app.network.NetworkHub.settings.value.proxies,
                )
            },
        )

    private fun apiKeyField(id: String): ConfigField =
        ClosureField(
            path = "providers.$id.apiKey",
            displayName = "API Key",
            description = "Write-only. Pass a literal value (sk-…) or `\$\$ENV_NAME` to copy from an env var. Reads are blocked.",
            valueSchema = ConfigSchema.Str(maxLength = 10_000),
            risk = ConfigRisk.DESTRUCTIVE,
            revertable = false,
            // [T-config-readable] Never renders the value; a `$$VAR`
            // reference names the variable only.
            readable = ConfigReadable(ConfigValueFormatter.Secret),
            reader = {
                throw ConfigError.PermissionDenied(
                    "API keys are never read back via minis-config. To change one, write a new value."
                )
            },
            writer = { v ->
                val s = (v as? ConfigValue.Str)?.value ?: throw ConfigError.TypeMismatch("string")
                if (repo.instance(id) == null) throw ConfigError.UnknownPath("providers.$id")
                if (s.isEmpty()) {
                    // Empty string clears the stored credential. Useful for
                    // testing the "no key" failure path, or for transitioning
                    // an instance to OAuth.
                    repo.deleteApiKey(id)
                } else {
                    repo.saveApiKey(id, resolveCredential(s, "apiKey"))
                }
            },
        )
}

private val envRefRegex = Regex("^\\$\\$([A-Za-z_][A-Za-z0-9_]*)$")

/**
 * `$$ENV_NAME` -> the variable's value, resolved at write time (see the note in
 * ProvidersCollection). Anything else is returned as is. A missing variable or
 * an empty value is an error; a DISABLED one resolves to "" with a warning.
 */
internal fun resolveEnvReference(envVars: EnvVarRepository, raw: String, fieldName: String): String {
    val m = envRefRegex.matchEntire(raw) ?: return raw
    val key = m.groupValues[1]
    val resolved = envVars.getValue(key)
        ?: throw ConfigError.InvalidValue(
            "$fieldName references env var \$\$$key, but no such env var exists. " +
                "Create it first via [Set $key](minis://settings/environments?create_key=$key&create_value=)."
        )
    // [T-envvar-disable] A disabled variable resolves to an empty string -
    // the same thing it is inside a shell - and the write goes ahead with a
    // warning in the response (and the log) instead of failing.
    if (envVars.isDisabled(key)) {
        val message = "$fieldName references env var \$\$$key, which is disabled; it resolved to an empty string. " +
            "Enable it in [Environment Variables](minis://settings/environments?create_key=$key) and set $fieldName again."
        com.openminis.app.logging.AppLogger.warning("ProvidersCollection", "[EnvVar] $message")
        com.openminis.app.config.ConfigWriteWarnings.add(message)
        return ""
    }
    if (resolved.isEmpty()) {
        throw ConfigError.InvalidValue("$fieldName references env var \$\$$key but its value is empty.")
    }
    return resolved
}
