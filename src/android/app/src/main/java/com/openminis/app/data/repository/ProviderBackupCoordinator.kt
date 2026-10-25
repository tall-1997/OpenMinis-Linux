package com.openminis.app.data.repository

import android.content.Context
import android.util.Base64
import com.openminis.app.backup.BackupSecrets
import com.openminis.app.data.db.ProviderConfigDao
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject

// Modality bit layout — must match src/ios/Providers/LLMTypes.swift
// ModelModality OptionSet rawValue exactly. Used by export/import to
// transmit modality info as a single Int that iOS can decode.
private const val MODALITY_BIT_TEXT_IN = 1 shl 0
private const val MODALITY_BIT_TEXT_OUT = 1 shl 1
private const val MODALITY_BIT_IMG_IN = 1 shl 2
private const val MODALITY_BIT_PDF_IN = 1 shl 3
private const val MODALITY_BIT_AUD_IN = 1 shl 4
private const val MODALITY_BIT_VID_IN = 1 shl 5
private const val MODALITY_BIT_IMG_OUT = 1 shl 6
private const val MODALITY_BIT_AUD_OUT = 1 shl 7
private const val MODALITY_BIT_VID_OUT = 1 shl 8

internal class ProviderBackupCoordinator(
    private val context: Context,
    private val host: Host,
) {
    interface Host {
        val configValue: ProviderConfig
        fun saveConfig(config: ProviderConfig)
        fun workingCopy(): ProviderConfig
        fun ensureConfigLoaded()
        fun addInstance(instance: ProviderInstance)
        fun instance(id: String): ProviderInstance?
        fun visibleEntries(instanceId: String): List<ModelEntry>
        val configLock: Any
        val providerDao: ProviderConfigDao
        fun loadApiKey(instanceId: String): String?
        fun saveApiKey(instanceId: String, key: String)
        fun loadAllThinkingRulesIntoCache()
    }

    // -- Import / Export --

    /**
     * [T-android-provider-export-oauth-token] OAuth manager for [instance],
     * covering EVERY OAuth provider type — including gemini / antigravity, which
     * OAuthManager.forInstance deliberately omits (it's tuned for the
     * login/logout/manual-bearer UI paths). Used only by the export/import
     * round-trip so we don't widen forInstance's shared behavior.
     */
    private fun oauthManagerFor(
        instance: ProviderInstance,
    ): com.openminis.app.auth.OAuthManager? = when (instance.providerType) {
        ProviderType.anthropic -> com.openminis.app.auth.ClaudeOAuthManager(context, instance.id)
        ProviderType.openAI -> com.openminis.app.auth.OpenAIOAuthManager(context, instance.id)
        ProviderType.xAI -> com.openminis.app.auth.XAIOAuthManager(context, instance.id)
        ProviderType.gemini -> com.openminis.app.auth.GeminiOAuthManager(context, instance.id)
        ProviderType.kimiCode -> com.openminis.app.auth.KimiOAuthManager(context, instance.id)
        else -> null
    }

    /** Export an instance as shareable JSON (includes base64-encoded API key). */
    fun exportInstanceJSON(instanceId: String): String? {
        host.ensureConfigLoaded()
        val instance = host.instance(instanceId) ?: return null
        val entries = host.visibleEntries(instanceId) + host.configValue.modelEntries.filter {
            it.providerInstanceId == instanceId && it.isHidden
        }

        val obj = JSONObject().apply {
            put("providerType", instance.providerType.name)
            put("label", instance.label)
            put("credentialType", instance.credentialType.name)
            val modelsArr = JSONArray()
            for (entry in entries) {
                modelsArr.put(JSONObject().apply {
                    put("modelId", entry.baseModel.id)
                    put("displayName", entry.baseModel.displayName)
                    put("isHidden", entry.isHidden)
                    if (entry.isCustom) put("isCustom", true)
                    entry.baseModel.contextWindow?.let { put("contextWindow", it) }
                    entry.baseModel.maxOutputTokens?.let { put("maxOutputTokens", it) }
                    entry.baseModel.supportsReasoning?.let { put("supportsReasoning", it) }
                    entry.baseModel.interleavedReasoningField?.let { put("interleavedReasoningField", it) }
                    // [T-provider-export-model-overrides] Serialize the FULL
                    // ModelOverrides layer, not just displayName/maxOutputTokens.
                    // contextWindow / supportsReasoning / modality (input+output)
                    // were previously dropped — a hand-corrected proxied model
                    // lost those edits on round-trip. Each key is additive +
                    // optional: older builds ignore unknown keys, and import
                    // below reads each independently so a partial override
                    // object restores exactly the fields present.
                    //
                    // For cross-platform interop with iOS we ALSO write a
                    // `modalityOverride` bitfield (Int, matching
                    // ios/Providers/LLMTypes.swift ModelModality OptionSet):
                    // textInput=1, textOutput=2, imageInput=4, pdfInput=8,
                    // audioInput=16, videoInput=32, imageOutput=64,
                    // audioOutput=128, videoOutput=256. Android natively
                    // carries inputModalities/outputModalities as string lists;
                    // the bitfield is purely an interop wire-format that iOS
                    // can consume directly. On import, the native list fields
                    // win when present (Android↔Android lossless), bitfield is
                    // the iOS→Android fallback.
                    if (!entry.overrides.isEmpty) {
                        val o = JSONObject()
                        entry.overrides.displayName?.let { o.put("displayName", it) }
                        entry.overrides.maxOutputTokens?.let { o.put("maxOutputTokens", it) }
                        entry.overrides.contextWindow?.let { o.put("contextWindow", it) }
                        entry.overrides.supportsReasoning?.let { o.put("supportsReasoning", it) }
                        entry.overrides.temperature?.let { o.put("temperature", it) }
                        entry.overrides.inputModalities?.let {
                            o.put("inputModalities", JSONArray(it))
                        }
                        entry.overrides.outputModalities?.let {
                            o.put("outputModalities", JSONArray(it))
                        }
                        val bitfield = modalityBitfieldFromLists(
                            entry.overrides.inputModalities,
                            entry.overrides.outputModalities,
                        )
                        if (bitfield != 0) o.put("modalityOverride", bitfield)
                        put("overrides", o)
                    }
                    // Mirror iOS export of baseModel.modalityOverride — when
                    // the base model itself carries explicit modality info,
                    // serialize an interop bitfield so iOS can faithfully
                    // restore it. Android's native baseModel uses string
                    // lists too; this is purely additive for iOS readers.
                    run {
                        val bf = modalityBitfieldFromLists(
                            entry.baseModel.inputModalities,
                            entry.baseModel.outputModalities,
                        )
                        if (bf != 0) put("modalityOverride", bf)
                    }
                    entry.baseModel.inputModalities?.let {
                        put("inputModalities", JSONArray(it))
                    }
                    entry.baseModel.outputModalities?.let {
                        put("outputModalities", JSONArray(it))
                    }
                })
            }
            put("models", modelsArr)
            host.loadApiKey(instanceId)?.let { key ->
                put("apiKey", Base64.encodeToString(key.toByteArray(), Base64.NO_WRAP))
            }
            // Export manual OAuth bearer token (mirrors iOS `manualOAuthToken` key).
            // Stored per-instance via OAuthManager; only present for OAuth providers
            // where the user pasted a static token via the Manual Bearer Token UI.
            run {
                val mgr = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
                val manual = mgr?.loadManualBearerToken()
                if (!manual.isNullOrEmpty()) {
                    put("manualOAuthToken", Base64.encodeToString(manual.toByteArray(), Base64.NO_WRAP))
                }
            }
            // [T-android-provider-export-oauth-token] (XIN 38955) Export the
            // STRUCTURED OAuth-login credential (access_token / refresh_token /
            // expire_at) saved by the OAuth login flow under a separate pref than
            // apiKey / manualOAuthToken. Previously omitted, so an OAuth-logged-in
            // Claude / OpenAI / Gemini / xAI provider exported with no usable
            // credential and imported as not-authenticated. The whole token is one
            // JSON blob on Android (OAuthManager.loadStoredTokens); JSON-encode +
            // base64 it under "oauthToken", matching iOS 703ff4bc's field name and
            // the existing apiKey / manualOAuthToken base64 encoding. Covers every
            // OAuth provider type via oauthManagerFor (not just the forInstance set).
            run {
                val mgr = oauthManagerFor(instance)
                mgr?.exportStoredTokensJson()?.let { tokenJson ->
                    put("oauthToken", Base64.encodeToString(tokenJson.toByteArray(), Base64.NO_WRAP))
                }
                // Gemini also stores the resolved account email + GCP project as
                // separate OAuth strings (mirrors iOS oauthEmail / oauthGcpProject);
                // carry them so the imported instance can call the API.
                if (instance.providerType == ProviderType.gemini && mgr != null) {
                    mgr.exportOAuthString("email")?.takeIf { it.isNotEmpty() }?.let {
                        put("oauthEmail", Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP))
                    }
                    mgr.exportOAuthString("gcp_project")?.takeIf { it.isNotEmpty() }?.let {
                        put("oauthGcpProject", Base64.encodeToString(it.toByteArray(), Base64.NO_WRAP))
                    }
                }
            }
            instance.customBaseURL?.let { put("customBaseURL", it) }
            if (!instance.appendV1Suffix) put("appendV1Suffix", false)
            if (instance.useResponsesAPI) put("useResponsesAPI", true)
            // [T-provider-custom-user-agent] Additive, optional. Only written
            // when set; old/new readers without the key decode to null →
            // default UA. Field name matches iOS for cross-platform interop.
            instance.customUserAgent?.takeIf { it.isNotBlank() }?.let { put("customUserAgent", it) }
        }
        return obj.toString(2)
    }

    /**
     * [T-android-backup-secrets] Collect ONE instance's credentials for the
     * backup `secrets.json`, base64-encoded exactly as [exportInstanceJSON]
     * does (same keychain + OAuth-manager access paths, same field semantics).
     * Returns null when the instance carries no usable credential, so the
     * caller can skip empty entries — matching iOS `BackupSecretsCollector`'s
     * `if !secret.isEmpty` guard.
     */
    fun collectBackupProviderSecret(
        instance: ProviderInstance,
    ): BackupSecrets.ProviderSecret? {
        fun b64(s: String): String =
            Base64.encodeToString(s.toByteArray(), Base64.NO_WRAP)

        val apiKey = host.loadApiKey(instance.id)?.let(::b64)
        val manualOAuth = com.openminis.app.auth.OAuthManager
            .forInstance(context, instance)?.loadManualBearerToken()
            ?.takeIf { it.isNotEmpty() }?.let(::b64)

        var oauthToken: String? = null
        var oauthEmail: String? = null
        var oauthGcpProject: String? = null
        val mgr = oauthManagerFor(instance)
        if (mgr != null) {
            oauthToken = mgr.exportStoredTokensJson()?.let(::b64)
            if (instance.providerType == ProviderType.gemini) {
                oauthEmail = mgr.exportOAuthString("email")
                    ?.takeIf { it.isNotEmpty() }?.let(::b64)
                oauthGcpProject = mgr.exportOAuthString("gcp_project")
                    ?.takeIf { it.isNotEmpty() }?.let(::b64)
            }
        }

        val secret = BackupSecrets.ProviderSecret(
            instanceId = instance.id,
            label = instance.label,
            providerType = instance.providerType.name,
            apiKey = apiKey,
            manualOAuthToken = manualOAuth,
            oauthToken = oauthToken,
            oauthEmail = oauthEmail,
            oauthGcpProject = oauthGcpProject,
        )
        return if (secret.isEmpty) null else secret
    }

    /**
     * [T-android-backup-secrets] Restore one instance's credentials from a
     * backup `secrets.json` entry. Returns true if any credential was WRITTEN,
     * false if the instance already had a key (kept) or the secret was empty —
     * this true/false is what the importer accumulates into
     * `providersRestored` vs `providersSkippedExisting` (the counts that drive
     * the restore-complete credentials message, iOS parity).
     *
     * 以备份为准：备份里带的凭据覆盖本机已有的值。返回值表示是否写入，
     * 内容与本机完全相同的不重复写、不计改动。
     */
    fun restoreBackupProviderSecret(secret: BackupSecrets.ProviderSecret): Boolean {
        fun deb64(s: String?): String? = s?.let {
            runCatching { String(Base64.decode(it, Base64.NO_WRAP)) }.getOrNull()
        }
        val instance = host.instance(secret.instanceId) ?: return false
        var wrote = false

        deb64(secret.apiKey)?.let { key ->
            if (host.loadApiKey(instance.id) != key) {
                host.saveApiKey(instance.id, key)
                wrote = true
            }
        }
        deb64(secret.manualOAuthToken)?.let { tok ->
            val mgr = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
            if (mgr != null && mgr.loadManualBearerToken() != tok) {
                mgr.saveManualBearerToken(tok)
                wrote = true
            }
        }
        val mgr = oauthManagerFor(instance)
        if (mgr != null) {
            deb64(secret.oauthToken)?.let { json ->
                if (mgr.exportStoredTokensJson() != json) {
                    mgr.importStoredTokensJson(json)
                    wrote = true
                }
            }
            if (instance.providerType == ProviderType.gemini) {
                deb64(secret.oauthEmail)?.let {
                    if (mgr.exportOAuthString("email") != it) {
                        mgr.importOAuthString("email", it); wrote = true
                    }
                }
                deb64(secret.oauthGcpProject)?.let {
                    if (mgr.exportOAuthString("gcp_project") != it) {
                        mgr.importOAuthString("gcp_project", it); wrote = true
                    }
                }
            }
        }
        return wrote
    }

    /**
     * [T-android-backup-restore-order] Non-destructive, ORDER-PRESERVING union
     * merge of a restored [remote] ProviderConfig into the live config. This is
     * the Android port of iOS `mergeProviderConfigFallback`
     * (BackupImporter+Categories.swift), including the fix `T-backup-restore-order`
     * (b33eb1ff6): the package decides POSITION for the items it carries, so a
     * restore reproduces the backed-up arrangement instead of re-sorting by
     * createdAt.
     *
     * Rules (identical to iOS):
     *  - instances / modelGroups: rebuilt in PACKAGE order. For each remote id,
     *    if the id already exists locally keep the LOCAL element (content wins),
     *    else take the remote element. Local-only ids are appended afterwards in
     *    their original relative order.
     *  - modelEntries: additive by id (append remote ids not present locally);
     *    order not significant (entries are looked up by id, ordered per group).
     *  - agentLoop bindings: set-union.
     *  - per-device pointers (default / voice / vision group ids, session
     *    bindings) are NOT part of ProviderConfig here and are untouched.
     *
     * Returns (beforeInstances, afterInstances) so the caller derives
     * imported = after-before and skipped = package.count - imported — the same
     * counting iOS uses (fix 93cad55ae: union-by-id already-present is SKIPPED,
     * not "updated").
     */
    fun mergeBackupProviderConfig(remote: ProviderConfig): Pair<Int, Int> {
        host.ensureConfigLoaded()
        return synchronized(host.configLock) {
            val local = host.configValue
            // 备份优先：同 id 替换、同名分组合并、本机独有内容保留。
            // 返回的第一个数是实际改动数量，调用方直接显示它。
            val result = com.openminis.app.backup.BackupMerge.merge(local, remote)
            val mergedAgentEntries =
                (local.agentLoopModelEntryIds + remote.agentLoopModelEntryIds).distinct()
            val mergedAgentGroups =
                (local.agentLoopGroupIds + remote.agentLoopGroupIds).distinct()
            val merged = result.config.copy(
                agentLoopModelEntryIds = mergedAgentEntries.toMutableList(),
                agentLoopGroupIds = mergedAgentGroups.toMutableList(),
            )
            host.saveConfig(merged)
            android.util.Log.i(
                "ProviderRepo",
                "[Restore] provider merge: changed=${result.changedCount} " +
                    "instances=${merged.instances.size} entries=${merged.modelEntries.size} " +
                    "groups=${merged.modelGroups.size}",
            )
            result.changedCount to result.changedCount
        }
    }

    /**
     * [T-android-backup-thinking-rules] Restore custom thinking rules by
     * id-keyed replace. Returns (written, skipped).
     *
     * Divergence from iOS, called out deliberately: iOS does `updated_at` LWW
     * (local equal-or-newer wins). Android's `provider_thinking_rules` table has
     * NO time columns, so there is nothing to compare — a rule already present
     * by id is left as-is (skipped), an absent one is inserted. This is the
     * closest faithful behaviour the local schema allows; the record's carried
     * createdAt/updatedAt are ignored on import.
     */
    fun restoreBackupThinkingRules(
        rules: List<com.openminis.app.backup.BackupThinkingRuleRecord>,
    ): Pair<Int, Int> = runBlocking {
        if (rules.isEmpty()) return@runBlocking 0 to 0
        val existing = host.providerDao.loadAllThinkingRules().map { it.id }.toSet()
        var written = 0
        var skipped = 0
        for (r in rules) {
            if (r.id in existing) { skipped++; continue }
            host.providerDao.upsertThinkingRule(
                com.openminis.app.data.db.ProviderThinkingRuleEntity(
                    id = r.id,
                    providerInstanceId = r.instanceId,
                    label = r.label,
                    scopeKind = r.scopeKind,
                    scopePattern = r.scopePattern,
                    wireFormatJson = r.wireFormatJson.takeIf { it.isNotBlank() && it != "{}" },
                    reasoningEchoJson = null,
                    sortOrder = r.sortOrder,
                )
            )
            written++
        }
        host.loadAllThinkingRulesIntoCache()
        written to skipped
    }

    /**
     * Import a provider from exported JSON. Returns the new instance label on success.
     * - Auto-renames on label conflict
     * - Decodes base64-encoded API key (falls back to plain text)
     */
    fun importInstanceJSON(jsonStr: String): String? {
        host.ensureConfigLoaded()
        val dict = try { JSONObject(jsonStr) } catch (_: Exception) { return null }
        val providerTypeRaw = dict.optString("providerType", "").ifEmpty { return null }
        val providerType = try { ProviderType.valueOf(providerTypeRaw) } catch (_: Exception) { return null }
        val label = dict.optString("label", "").ifEmpty { return null }

        val credentialType = try {
            ProviderCredential.valueOf(dict.optString("credentialType", "apiKey"))
        } catch (_: Exception) { ProviderCredential.apiKey }

        // Resolve label conflict
        val existingLabels = host.configValue.instances.map { it.label }.toSet()
        var resolvedLabel = label
        if (resolvedLabel in existingLabels) {
            var suffix = 2
            while ("$label ($suffix)" in existingLabels) suffix++
            resolvedLabel = "$label ($suffix)"
        }

        val customBaseURL = dict.optString("customBaseURL", "").ifEmpty { null }
        val appendV1 = dict.optBoolean("appendV1Suffix", true)
        val useResponsesAPI = dict.optBoolean("useResponsesAPI", false)
        // [T-provider-custom-user-agent] Additive: old exports lack the key →
        // empty → null → default UA. Field name matches iOS.
        val customUserAgent = dict.optString("customUserAgent", "").ifEmpty { null }

        val instance = ProviderInstance(
            id = java.util.UUID.randomUUID().toString(),
            label = resolvedLabel,
            providerType = providerType,
            credentialType = credentialType,
            customBaseURL = customBaseURL,
            appendV1Suffix = appendV1,
            useResponsesAPI = useResponsesAPI,
            customUserAgent = customUserAgent,
        )
        host.addInstance(instance)

        // Decode API key (base64 or plain text)
        val keyValue = dict.optString("apiKey", "").ifEmpty { null }
        if (keyValue != null) {
            val apiKey = try {
                String(Base64.decode(keyValue, Base64.NO_WRAP))
            } catch (_: Exception) {
                keyValue // plain text fallback
            }
            host.saveApiKey(instance.id, apiKey)
        }

        // Decode manual OAuth bearer token (mirrors iOS `manualOAuthToken`).
        // base64-encoded UTF-8 string, with plain-text fallback for older exports.
        val manualTokenValue = dict.optString("manualOAuthToken", "").ifEmpty { null }
        if (manualTokenValue != null) {
            val manualToken = try {
                String(Base64.decode(manualTokenValue, Base64.NO_WRAP))
            } catch (_: Exception) {
                manualTokenValue
            }
            val mgr = com.openminis.app.auth.OAuthManager.forInstance(context, instance)
            mgr?.saveManualBearerToken(manualToken)
        }

        // [T-android-provider-export-oauth-token] (XIN 38955) Restore the
        // structured OAuth-login credential so the imported instance is
        // authenticated. Decode base64 → JSON → write back via the OAuth
        // manager. Mirrors iOS 703ff4bc; purely additive alongside the
        // apiKey / manualOAuthToken restore above.
        val oauthTokenValue = dict.optString("oauthToken", "").ifEmpty { null }
        if (oauthTokenValue != null) {
            val tokenJson = try {
                String(Base64.decode(oauthTokenValue, Base64.NO_WRAP))
            } catch (_: Exception) {
                oauthTokenValue // plain-text fallback for hand-edited exports
            }
            oauthManagerFor(instance)?.importStoredTokensJson(tokenJson)
        }
        // Gemini account email + GCP project (base64-encoded OAuth strings).
        if (instance.providerType == ProviderType.gemini) {
            val mgr = oauthManagerFor(instance)
            dict.optString("oauthEmail", "").ifEmpty { null }?.let { b64 ->
                val email = try { String(Base64.decode(b64, Base64.NO_WRAP)) } catch (_: Exception) { b64 }
                mgr?.importOAuthString("email", email)
            }
            dict.optString("oauthGcpProject", "").ifEmpty { null }?.let { b64 ->
                val project = try { String(Base64.decode(b64, Base64.NO_WRAP)) } catch (_: Exception) { b64 }
                mgr?.importOAuthString("gcp_project", project)
            }
        }

        // Import models (replace built-in defaults)
        val models = dict.optJSONArray("models")
        if (models != null && models.length() > 0) {
            val entries = mutableListOf<ModelEntry>()
            for (i in 0 until models.length()) {
                val m = models.getJSONObject(i)
                val modelId = m.optString("modelId", "")
                if (modelId.isEmpty()) continue
                val displayName = m.optString("displayName", modelId)
                val isCustom = m.optBoolean("isCustom", false)
                val isHidden = m.optBoolean("isHidden", false)
                val contextWindow = if (m.has("contextWindow")) m.optInt("contextWindow").takeIf { it > 0 } else null
                val maxOutputTokens = if (m.has("maxOutputTokens")) m.optInt("maxOutputTokens").takeIf { it > 0 } else null
                val supportsReasoning = if (m.has("supportsReasoning")) m.optBoolean("supportsReasoning") else null
                val interleavedReasoningField = m.optString("interleavedReasoningField", "").ifEmpty { null }
                // [T-provider-export-model-overrides] Restore baseModel
                // modalities. Android-native list fields win when present;
                // otherwise fall back to iOS's `modalityOverride` bitfield so
                // a provider exported on iOS retains its capability info.
                val (baseIn, baseOut) = readModalitiesWithBitfieldFallback(m)
                val model = LLMModel(
                    id = modelId,
                    displayName = displayName,
                    provider = providerType.displayName,
                    contextWindow = contextWindow,
                    maxOutputTokens = maxOutputTokens,
                    supportsReasoning = supportsReasoning,
                    interleavedReasoningField = interleavedReasoningField,
                    inputModalities = baseIn,
                    outputModalities = baseOut,
                )
                val overridesObj = m.optJSONObject("overrides")
                val overrides = if (overridesObj != null) {
                    // [T-provider-export-model-overrides] Read the full
                    // overrides layer. Each key is read independently — a
                    // missing key (old export, partial object) simply stays
                    // null → the field falls back to baseModel / defaults.
                    val (ovIn, ovOut) = readModalitiesWithBitfieldFallback(overridesObj)
                    ModelOverrides(
                        displayName = overridesObj.optString("displayName", "").ifEmpty { null },
                        maxOutputTokens = if (overridesObj.has("maxOutputTokens")) overridesObj.optInt("maxOutputTokens").takeIf { it > 0 } else null,
                        contextWindow = if (overridesObj.has("contextWindow")) overridesObj.optInt("contextWindow").takeIf { it > 0 } else null,
                        supportsReasoning = if (overridesObj.has("supportsReasoning")) overridesObj.optBoolean("supportsReasoning") else null,
                        temperature = if (overridesObj.has("temperature")) {
                            overridesObj.optDouble("temperature").takeUnless { it.isNaN() }
                                ?.takeIf { it in 0.0..2.0 }
                        } else null,
                        inputModalities = ovIn,
                        outputModalities = ovOut,
                    )
                } else {
                    ModelOverrides()
                }
                entries.add(ModelEntry(
                    providerInstanceId = instance.id,
                    baseModel = model,
                    overrides = overrides,
                    isCustom = isCustom,
                    isHidden = isHidden,
                ))
            }
            // Import replaces built-in entries directly (not via replaceEntries which takes LLMModel list).
            // [T-android-provider-mutator-lock] Lock + working copy, like every
            // other mutator. This function is the actual provider.import /
            // Share-import path, and it read `_config.value` back — the object
            // addInstance had just PUBLISHED — and mutated its live list. That
            // is the exact race behind both device CMEs; the earlier sweep
            // missed it because the audit went by function name and this one
            // isn't called add*/update*/remove*.
            synchronized(host.configLock) {
                val cfg = host.workingCopy()
                cfg.modelEntries.removeAll { it.providerInstanceId == instance.id }
                cfg.modelEntries.addAll(entries)
                host.saveConfig(cfg)
            }
        }

        return resolvedLabel
    }

    // -- Modality interop with iOS ----------------------------------------
    //
    // iOS encodes ModelModality as a single Int bitfield (OptionSet rawValue);
    // Android carries inputModalities / outputModalities as bare string lists
    // ("text" / "image" / "pdf" / "audio" / "video"). The export/import path
    // writes both encodings so the wire format is portable in either
    // direction without losing fidelity:
    //   - Android → Android: the native string lists round-trip exactly.
    //   - Android → iOS:    iOS reads `modalityOverride` Int and ignores
    //                       the unknown list keys (forward-compatible).
    //   - iOS → Android:    Android prefers the native list keys when
    //                       present (Android-original export); otherwise
    //                       decodes `modalityOverride` Int back into lists.
    //
    // Bit layout constants live at file scope above the class (Kotlin
    // forbids a second companion object, and ProviderRepository already
    // has one).

    private fun modalityBitfieldFromLists(
        inputs: List<String>?,
        outputs: List<String>?,
    ): Int {
        var bits = 0
        inputs?.forEach { raw ->
            when (raw.lowercase()) {
                "text" -> bits = bits or MODALITY_BIT_TEXT_IN
                "image" -> bits = bits or MODALITY_BIT_IMG_IN
                "pdf" -> bits = bits or MODALITY_BIT_PDF_IN
                "audio" -> bits = bits or MODALITY_BIT_AUD_IN
                "video" -> bits = bits or MODALITY_BIT_VID_IN
            }
        }
        outputs?.forEach { raw ->
            when (raw.lowercase()) {
                "text" -> bits = bits or MODALITY_BIT_TEXT_OUT
                "image" -> bits = bits or MODALITY_BIT_IMG_OUT
                "audio" -> bits = bits or MODALITY_BIT_AUD_OUT
                "video" -> bits = bits or MODALITY_BIT_VID_OUT
            }
        }
        return bits
    }

    private fun modalityListsFromBitfield(bits: Int): Pair<List<String>?, List<String>?> {
        if (bits == 0) return null to null
        val inputs = buildList {
            if (bits and MODALITY_BIT_TEXT_IN != 0) add("text")
            if (bits and MODALITY_BIT_IMG_IN != 0) add("image")
            if (bits and MODALITY_BIT_PDF_IN != 0) add("pdf")
            if (bits and MODALITY_BIT_AUD_IN != 0) add("audio")
            if (bits and MODALITY_BIT_VID_IN != 0) add("video")
        }
        val outputs = buildList {
            if (bits and MODALITY_BIT_TEXT_OUT != 0) add("text")
            if (bits and MODALITY_BIT_IMG_OUT != 0) add("image")
            if (bits and MODALITY_BIT_AUD_OUT != 0) add("audio")
            if (bits and MODALITY_BIT_VID_OUT != 0) add("video")
        }
        return inputs.ifEmpty { null } to outputs.ifEmpty { null }
    }

    /**
     * Read modality info from a JSON object. Returns (inputs, outputs):
     *   - native `inputModalities` / `outputModalities` list keys take
     *     precedence (Android-original export — lossless).
     *   - if neither list is present, decode iOS's `modalityOverride`
     *     bitfield as the fallback.
     *   - if neither shape is present, returns null pair (caller treats
     *     as "no modality info" → baseModel defaults apply).
     */
    private fun readModalitiesWithBitfieldFallback(
        obj: JSONObject,
    ): Pair<List<String>?, List<String>?> {
        val nativeIn = obj.optJSONArray("inputModalities")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }.takeIf { it.isNotEmpty() }
        }
        val nativeOut = obj.optJSONArray("outputModalities")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotEmpty() } }.takeIf { it.isNotEmpty() }
        }
        if (nativeIn != null || nativeOut != null) return nativeIn to nativeOut
        if (!obj.has("modalityOverride")) return null to null
        val bits = obj.optInt("modalityOverride", 0)
        return modalityListsFromBitfield(bits)
    }
}
