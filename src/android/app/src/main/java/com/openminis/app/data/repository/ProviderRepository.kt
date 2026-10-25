package com.openminis.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.backup.BackupSecrets
import com.openminis.app.data.db.ProviderConfigDao
import com.openminis.app.data.toEntity
import com.openminis.app.data.toRule
import com.openminis.app.provider.thinking.ThinkingRule
import com.openminis.app.provider.thinking.ThinkingRuleResolver
import com.openminis.app.data.model.ImageEndpointMode
import com.openminis.app.data.model.LLMModel
import com.openminis.app.data.model.applyUnrecognizedModelDefaults
import com.openminis.app.data.model.ModelDeletion
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelOverrides
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.provider.BuiltinFreeLane
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.VoiceProviderTemplate
import com.openminis.app.data.model.hasVoiceModality
import com.openminis.app.data.model.withInferredVoiceModality
import com.openminis.app.data.model.withInferredVideoModality
import com.openminis.app.data.model.withInferredImageModality
import com.openminis.app.provider.ModelReleaseIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProviderRepository(private val context: Context) {

    // ── Persistence collaborator [ProviderRepository split] ──
    // The persistence slice (JSON codec, prefs, encrypted prefs, hash mirror,
    // persist worker, secrets) lives in [ProviderConfigStore]. This class
    // constructs it, holds it privately, and keeps the previous public and
    // internal surface reachable through the one-line delegations below.
    internal val configStore = ProviderConfigStore(this, context)

    // Model-catalog collaborator [ProviderRepository split]: TTL staleness
    // marks + the vendor/models.dev refresh pipeline, in [ModelCatalogCache].
    private val catalogCache = ModelCatalogCache(this, context, configStore.prefs)

    // Grouping / slot-resolution collaborator [ProviderRepository split]:
    // agent-loop composition, group CRUD + reorder, slot bindings, vision /
    // compact / voice candidate resolution and the shadow-voice mirror, in
    // [ModelGrouping].
    private val grouping = ModelGrouping(this, configStore.prefs)

    internal val json get() = configStore.json

    internal val prefs: SharedPreferences get() = configStore.prefs

    internal val providerDao: ProviderConfigDao get() = configStore.providerDao

    companion object {
        /** [T-newchat-default-model-fallback-android] Global last-used model entry id. */
        internal const val KEY_LAST_USED_ENTRY = "lastUsedModelEntryId"
        /** Single source of truth for provider type × credential support. */
        internal fun supportedCredentials(type: ProviderType): List<ProviderCredential> = when (type) {
            ProviderType.anthropic, ProviderType.openAI, ProviderType.openRouter,
            ProviderType.xAI, ProviderType.kimiCode, ProviderType.openAIResponses ->
                listOf(ProviderCredential.apiKey, ProviderCredential.oauth)
            ProviderType.gemini -> listOf(ProviderCredential.apiKey)
            ProviderType.antigravity, ProviderType.unsupported -> listOf(ProviderCredential.apiKey)
        }

        internal fun supportsCredential(type: ProviderType, credential: ProviderCredential): Boolean =
            credential in supportedCredentials(type)

        /**
         * [T-android-provider-voice] Normalize a base URL for shadow-voice
         * cross-instance de-dup: lowercased, trailing "/" and "/v1" stripped.
         * Mirrors iOS ProviderConfigStore.normalizedShadowKey.
         */
        fun normalizedShadowKey(baseURL: String?): String {
            var s = baseURL?.trim()?.lowercase() ?: return ""
            while (s.endsWith("/")) s = s.dropLast(1)
            if (s.endsWith("/v1")) s = s.dropLast(3)
            while (s.endsWith("/")) s = s.dropLast(1)
            return s
        }
    }

    // Model-catalog TTL bookkeeping (mark/stale/invalidate) moved to
    // [ModelCatalogCache]; public invalidation stays reachable via the facade.

    /**
     * Clear the stored `lastFetchAt` for [instanceId] so the next
     * background-refresh sweep refreshes it immediately. Called when instance
     * config changes in ways that could alter the set of available models
     * (e.g. base URL rewrite, credential swap). Mirrors iOS's implicit
     * invalidation on `updateInstance` / `removeInstance`.
     */
    fun invalidateModelCache(instanceId: String) =
        catalogCache.invalidateModelCache(instanceId)

    // encryptedPrefs (provider_secrets) moved to ProviderConfigStore.

    // [T-android-startup-config-stall] #753: loadConfig() does a synchronous
    // SharedPreferences read (~3s first-touch as the XML is parsed) + a
    // Json.decodeFromString<ProviderConfig> (~8s on a large config). It used to
    // run INLINE in this field initializer, i.e. inside ProviderRepository's
    // constructor, which MinisApp.onCreate() invokes on the MAIN THREAD — so
    // cold start hung >11s. Now we start with an empty placeholder (instant, no
    // I/O) and load the real config on Dispatchers.IO, emitting it when ready.
    // Reactive consumers (config.collectAsState) update automatically on emit;
    // action-time `config.value` reads tolerate the brief empty window (a send
    // before load just has no model entry, exactly like a fresh install).
    internal val _config = MutableStateFlow(ProviderConfig())
    val config: StateFlow<ProviderConfig> = _config.asStateFlow()

    /**
     * [T-android-startup-config-stall] False until the persisted config has
     * been read off-thread and emitted. First-screen code that branches on
     * `instances.isEmpty()` (the onboarding gate) reads this to avoid flashing
     * a "no providers" / onboarding state for an existing user during the load
     * window. Distinguishes "empty because not loaded yet" from "empty because
     * the user genuinely has no providers".
     */
    // internal (was private): ProviderConfigStore.ensureConfigLoaded mutates it.
    internal val _configLoaded = MutableStateFlow(false)
    val configLoaded: StateFlow<Boolean> = _configLoaded.asStateFlow()
    val credentialRevision: StateFlow<Long> get() = configStore.credentialRevision
    /** Internal scope for the one-shot async config load. */
    private val loadScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )

    /**
     * Completes once the initial off-thread load has emitted (or determined
     * there's nothing persisted). Lets startup consumers that genuinely need
     * the config (e.g. the daily model refresh) wait instead of racing the
     * empty placeholder.
     */
    // internal (was private): ProviderConfigStore.ensureConfigLoaded completes it.
    internal val configLoadComplete = kotlinx.coroutines.CompletableDeferred<Unit>()

    suspend fun awaitConfigLoaded() = configLoadComplete.await()

    init {
        loadScope.launch {
            // [T-android-provider-empty-load-wipe] loadConfig() THROWS when the
            // store is unreadable (rather than returning an empty config that a
            // later save would write over real data). Contain it here: an
            // uncaught throw in this coroutine reaches the default uncaught
            // handler and crashes cold start — in a LOOP, for as long as the DB
            // stays unreadable, which is precisely the post-crash state the
            // refusal exists for. Swallowing it leaves _configLoaded false, so
            // ensureConfigLoaded retries on the next access.
            try {
                // [T-nonstream-json-default] loadConfigSuspending directly (no runBlocking hop);
                // JSON/DAO decode moves to Dispatchers.Default, not the IO pool next to blocking I/O.
                val loaded = withContext(Dispatchers.Default) { loadConfigSuspending() }
                synchronized(configLock) {
                    // Only adopt the disk config if nothing has written in the
                    // meantime. A write during the load window (rare — writes come
                    // from user/refresh actions that themselves need config) flips
                    // _configLoaded true and takes precedence; we must not clobber
                    // it with the stale on-disk snapshot.
                    if (!_configLoaded.value) {
                        _config.value = loaded
                        _configLoaded.value = true
                        android.util.Log.i(
                            "ProviderRepo",
                            "[ProviderStore] async loader adopted ${loaded.instances.size} instances",
                        )
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e(
                    "ProviderRepo",
                    "[ProviderStore] initial load failed; leaving config unloaded for retry: ${e.message}",
                )
            }
            // Completed in every path, including the failure one: awaitConfigLoaded()
            // callers (refreshAllModelsIfNeeded) would otherwise suspend forever.
            if (!configLoadComplete.isCompleted) configLoadComplete.complete(Unit)
            // The async loader sets _configLoaded directly, so normal readers
            // no longer pass through ensureConfigLoaded(). Warm the synchronous
            // request-builder cache explicitly here; otherwise the first cold
            // start after this refactor would silently resolve built-in-only
            // thinking rules until a later mutation happened to warm it.
            loadAllThinkingRulesIntoCache()
            // [T-android-provider-voice] Reconcile voice-template seeds after
            // the initial load (add new template models, drop retired seeds,
            // heal corrupted modality). Mirrors iOS ProviderConfigStore.init.
            try {
                ensureVoiceTemplateModels()
                configStore.seedBuiltInFreeLanes()
            } catch (e: Exception) {
                android.util.Log.w("ProviderRepo", "[BuiltIn] provider reconciliation failed: ${e.message}")
            }
        }
    }

    /**
     * Lock guarding all mutate-and-save sequences against concurrent writers
     * (notably the `for instance in enabled { scope.launch { autoRefreshModels(...) } }`
     * fan-out in [refreshAllModelsIfNeeded] / [triggerBackgroundRefreshIfStale]).
     *
     * Without this lock, two parallel `replaceEntries(instanceA, …)` and
     * `replaceEntries(instanceB, …)` calls share the same `_config.value`
     * reference (its inner ArrayLists are the same objects across reads),
     * so one's `removeAll` / `addAll` runs while the other's `saveConfig`
     * is mid-`Json.encodeToString` — that's the
     * `ConcurrentModificationException → ArrayList.next` crash captured on
     * Pixel 6 / 4a.
     */
    internal val configLock = Any()

    // ── [T-android-save-off-main-thread] Persistence pipeline (queue, worker
    // scope, generation counter) lives in [ProviderConfigStore]; the facade
    // below keeps the internal surface the save-path extensions rely on. ──
    internal val persistRequests get() = configStore.persistRequests
    internal val writeScope get() = configStore.writeScope
    internal val saveGeneration get() = configStore.saveGeneration

    /** Starts the persistence worker on the first save. Idempotent. */
    internal fun ensurePersistWorker() = configStore.ensurePersistWorker()

    /**
     * Suspends until every save queued before this call has reached the store.
     * Backup/restore and any other "durability before returning" path should
     * await it — the fire-and-forget `saveConfig` no longer implies the write
     * landed.
     */
    internal suspend fun awaitPersisted() = configStore.awaitPersisted()

    /** Stable SHA-256 hash of the JSON mirror string; implementation in [ProviderConfigStore]. */
    internal fun hashJsonMirror(str: String): String = configStore.hashJsonMirror(str)

    // internal (was private): the debug server's read-only provider.* handlers
    // read `config.value` directly and must be able to force the lazy load —
    // on a cold process they otherwise report an EMPTY config, which reads as
    // "all your providers are gone" rather than "not loaded yet".
    // Implementation (and the full caller-audit KDoc) in [ProviderConfigStore].
    internal fun ensureConfigLoaded() = configStore.ensureConfigLoaded()

    // [T-android-provider-mutator-lock] Snapshot, not the live list. The
    // declared type is List, but the backing object is the MutableList that
    // mutators append to in place — so a caller iterating this (e.g.
    // ProvidersCollection.childIds) could take a ConcurrentModificationException
    // from a concurrent addInstance. Copying under the lock costs a few objects
    // and removes the hazard for every current and future caller.
    val instances: List<ProviderInstance>
        get() = synchronized(configLock) { _config.value.instances.toList() }

    /**
     * [T-android-provider-emitted-list-cow] A PRIVATE working copy of the
     * current config for a mutator to modify.
     *
     * The lock alone does not make mutation safe, because Compose readers do
     * not take it: `_config.value` is the object collectors are iterating, and
     * mutating its lists in place throws ConcurrentModificationException *in
     * the reader's* frame — reproduced on Pixel 6 as a crash inside
     * UnifiedModelPickerSheet's LazyColumn while three imports ran
     * concurrently:
     *
     *   ArrayList$Itr.checkForComodification → UnifiedModelPickerSheet
     *     → LazyListIntervalContent.<init> → Snapshot.observe
     *
     * saveConfig already EMITS defensive copies; the gap was that the next
     * mutator read that emitted object back and mutated it. Copy-on-write here
     * closes the loop: a mutator never touches a published object, so whatever
     * a reader is iterating stays frozen for the life of that iteration.
     */
    // internal (was private): the ProviderConfigStore seeding/reconciliation
    // collaborators mutate the config through this COW primitive too.
    internal fun workingCopy(): ProviderConfig {
        val live = _config.value
        return live.copy(
            instances = live.instances.toMutableList(),
            modelEntries = live.modelEntries.toMutableList(),
            // Deep-copy the groups too: ModelGroup.memberEntryIds is itself a
            // MutableList that removeEntry/removeGroup edit in place, so a
            // shallow list copy would still expose the published members to a
            // reader mid-iteration.
            modelGroups = live.modelGroups
                .map { g -> g.copy(memberEntryIds = g.memberEntryIds.toMutableList()) }
                .toMutableList(),
            agentLoopModelEntryIds = live.agentLoopModelEntryIds.toMutableList(),
            agentLoopGroupIds = live.agentLoopGroupIds.toMutableList(),
        )
    }

    fun addInstance(instance: ProviderInstance): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        config.instances.add(instance)
        // Seed built-in model entries ONLY when the seed is appropriate for this
        // instance. Mirrors iOS ProviderConfigStore.addInstance:
        //   - OAuth instances always get seeded (their /v1/models often requires
        //     a manual token or isn't reachable, so the static list is the
        //     baseline UX).
        //   - API-key instances on a third-party OpenAI-compatible base URL
        //     (e.g. xAI Grok https://api.x.ai, vLLM, Ollama, LiteLLM, DeepSeek
        //     via OpenAI shim) MUST NOT inherit `LLMModel.allOpenAI` — that's
        //     where the "Refresh on Grok returns GPT-5.5/5.3-codex" bug came
        //     from. For these, leave entries empty and let refreshModels()
        //     populate from the upstream /v1/models call.
        //   - API-key instances on an official endpoint (no customBaseURL, or
        //     a customBase that points at the canonical host) keep the seed so
        //     UI isn't blank during the first refresh round-trip.
        val shouldSeed = instance.credentialType == ProviderCredential.oauth ||
            !configStore.isThirdPartyOpenAICompat(instance)
        if (shouldSeed) {
            val entries = instance.providerType.builtInModels.map { model ->
                ModelEntry(providerInstanceId = instance.id, baseModel = model)
            }
            config.modelEntries.addAll(entries)
        } else {
            android.util.Log.i(
                "ProviderRepo",
                "[ModelList] addInstance: skip built-in seed for third-party OpenAI-compat base " +
                    "(label=${instance.label} base=${instance.effectiveBaseURL}) — " +
                    "models will populate from upstream /v1/models on refresh",
            )
        }
        // [T-android-provider-voice] Seed voice-template mock models when the
        // base URL matches a voice vendor (MiMo / MiniMax / Doubao …). These
        // vendors have no /v1/models for their voices, so the template list is
        // the only source. Mirrors iOS addInstance + VoiceProviderTemplate.
        val voiceSeeds = VoiceProviderTemplate.mockEntries(instance)
            .filter { seed ->
                config.modelEntries.none {
                    it.providerInstanceId == instance.id && it.baseModel.id == seed.baseModel.id
                }
            }
        if (voiceSeeds.isNotEmpty()) {
            config.modelEntries.addAll(voiceSeeds)
            android.util.Log.i(
                "ProviderRepo",
                "[Voice] addInstance seeded ${voiceSeeds.size} voice-template models for ${instance.label}",
            )
        }
        saveConfig(config)
        // Stale from the start so the next background sweep (or an explicit
        // triggerBackgroundRefreshIfStale call) will fetch it.
        invalidateModelCache(instance.id)
    }
    /** Voice-template seed reconciliation; implementation in [ProviderConfigStore]. */
    fun ensureVoiceTemplateModels() = configStore.ensureVoiceTemplateModels()

    /**
     * Recovery channel for the built-in free lane: clears the user's deletion
     * tombstone, re-seeds the instance under its original id, and drops the
     * model-level tombstones recorded against it. Returns true when the config
     * changed, which is the caller's cue to refresh the catalogue.
     */
    internal fun restoreBuiltInFreeLane(builtin: BuiltinFreeLane): Boolean =
        configStore.restoreBuiltInFreeLane(builtin)

    fun updateInstance(instance: ProviderInstance): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        val idx = config.instances.indexOfFirst { it.id == instance.id }
        if (idx >= 0) {
            val prior = config.instances[idx]
            // [T-android-image-endpoint-mode] If the base URL or v1-suffix
            // changed, the previously probed image endpoint may not exist on the
            // new upstream — drop the cached resolution so auto mode re-probes.
            // Mirrors iOS ProviderConfigStore.updateInstance. Only touch it when
            // the caller didn't already set it (e.g. the UI clears it itself when
            // the user forces a non-auto mode).
            if ((prior.customBaseURL != instance.customBaseURL ||
                    prior.appendV1Suffix != instance.appendV1Suffix) &&
                instance.imageEndpointResolved != null
            ) {
                instance.imageEndpointResolved = null
            }
            config.instances[idx] = instance
            saveConfig(config)
            // Any change that could move the model list (base URL, credential
            // swap, enabled flag, API format) invalidates the cache. Cheap to
            // over-invalidate.
            if (prior.effectiveBaseURL != instance.effectiveBaseURL ||
                prior.credentialType != instance.credentialType ||
                prior.isEnabled != instance.isEnabled ||
                prior.useResponsesAPI != instance.useResponsesAPI
            ) {
                invalidateModelCache(instance.id)
            }
        }
    }
    fun setInstancePinned(id: String, pinned: Boolean): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        val inst = config.instances.find { it.id == id } ?: return
        inst.pinned = pinned
        saveConfig(config)
    }
    fun removeInstance(instanceId: String): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        invalidateModelCache(instanceId)
        val config = workingCopy()
        val removedInstance = config.instances.find { it.id == instanceId }
        val removedEntryIds = config.modelEntries
            .filter { it.providerInstanceId == instanceId }
            .map { it.id }
            .toSet()
        config.instances.removeAll { it.id == instanceId }
        config.modelEntries.removeAll { it.providerInstanceId == instanceId }
        if (removedEntryIds.isNotEmpty()) {
            for (group in config.modelGroups) {
                group.memberEntryIds.removeAll { it in removedEntryIds }
            }
            config.agentLoopModelEntryIds.removeAll { it in removedEntryIds }
        }
        val emptyGroupIds = config.modelGroups.filter { it.memberEntryIds.isEmpty() }.map { it.id }.toSet()
        if (emptyGroupIds.isNotEmpty()) {
            config.modelGroups.removeAll { it.id in emptyGroupIds }
            if (config.defaultPrimaryGroupId in emptyGroupIds) config.defaultPrimaryGroupId = null
            if (config.defaultSubGroupId in emptyGroupIds) config.defaultSubGroupId = null
        }
        // Clears this id's model tombstones and records the instance-level one,
        // so a deliberate removal is not undone by the next launch's seed pass.
        FreeLaneBootstrap.forgetInstance(config, instanceId)
        saveConfig(config)
        deleteApiKey(instanceId)
        // [T-remove-instance-cleanup] deleteApiKey only removes apikey_<id>.
        // OAuth tokens (oauth_tokens_<id>) and manual bearer tokens survived
        // the instance, so OAuthManager.isAuthenticated() kept answering
        // "logged in" for a provider that no longer exists — plus the
        // voiceShadowDisabled flag and a possibly dangling
        // lastUsedModelEntryId. Clean everything in one place.
        if (removedInstance != null) {
            runCatching {
                com.openminis.app.auth.OAuthManager.forInstance(context, removedInstance)?.logout()
            }
            prefs.edit().remove("voiceShadowDisabled.$instanceId").apply()
        }
        if (prefs.getString(KEY_LAST_USED_ENTRY, null) in removedEntryIds) {
            prefs.edit().remove(KEY_LAST_USED_ENTRY).apply()
        }
        // [T-android-thinking-rules-phase2] The instance is gone — drop its custom
        // rules from Room and the resolver cache (they can never fire again).
        // [T-android-save-off-main-thread] The DAO DELETE used to be
        // `runBlocking { … }` HERE, on the thread that pressed Delete. This is a
        // public, non-suspend mutator and its callers are Compose confirm
        // dialogs — ui/settings/ProviderListScreen.kt:524 and
        // ui/settings/ProviderDetailScreen.kt:777 call `removeInstance` straight
        // from `onConfirm`, i.e. the main thread, so a cold/hot provider.db write
        // stalled the frame that was supposed to close the dialog. The durable
        // half now rides the same FIFO persistence worker as the config save
        // queued above (ordered behind it, on Dispatchers.IO); the in-memory
        // clears stay synchronous so no reader can resurrect the rules this turn.
        ThinkingRuleResolver.setCustomRules(instanceId, emptyList())
        thinkingRulesCache = thinkingRulesCache - instanceId
        ensurePersistWorker()
        persistRequests.trySend(ProviderPersistRequest.Task {
            runCatching { providerDao.deleteThinkingRulesForInstance(instanceId) }
                .onFailure {
                    android.util.Log.w(
                        "ProviderRepo",
                        "[ThinkingRules] delete for removed instance $instanceId failed: ${it.message}",
                    )
                }
        })
    }
    /**
     * [T-android-image-endpoint-mode] Persist the endpoint that last worked for
     * `auto`-mode image generation on [instanceId]. Called by
     * ModelUseOffloadHandler after a successful /images/generations call (cache
     * `imagesGenerations`) or after a route-missing fallback (cache
     * `chatCompletions`). No-op when unchanged so we don't churn the config /
     * iCloud sync on every image call. Does not invalidate the model cache —
     * the endpoint choice never moves the model list.
     */
    // [T-android-provider-mutator-lock] `ProviderInstance` has mutable `var`
    // fields, so `workingCopy()`'s list copy is not enough on its own — the
    // instance objects inside it are still the published ones. Replace the
    // element with a `.copy()` rather than writing through to the shared
    // object. Called from ModelUseOffloadHandler on an offload worker thread
    // during image generation, so the unsynchronized write had no
    // happens-before with main-thread readers.
    fun setImageEndpointResolved(instanceId: String, endpoint: ImageEndpointMode): Unit =
        synchronized(configLock) {
            ensureConfigLoaded()
            val config = workingCopy()
            val idx = config.instances.indexOfFirst { it.id == instanceId }
            if (idx < 0) return@synchronized
            if (config.instances[idx].imageEndpointResolved == endpoint) return@synchronized
            config.instances[idx] = config.instances[idx].copy(imageEndpointResolved = endpoint)
            saveConfig(config)
        }

    fun instance(id: String): ProviderInstance? =
        _config.value.instances.find { it.id == id }

    fun enabledInstances(providerType: ProviderType): List<ProviderInstance> =
        _config.value.instances.filter { it.providerType == providerType && it.isEnabled }

    fun entriesFor(instanceId: String): List<ModelEntry> =
        _config.value.modelEntries
            .filter { it.providerInstanceId == instanceId }
            .sortedWith(releaseRankOrder)

    fun visibleEntries(instanceId: String): List<ModelEntry> =
        _config.value.modelEntries
            .filter { it.providerInstanceId == instanceId && !it.isHidden }
            .sortedWith(releaseRankOrder)

    fun allVisibleEntries(): List<ModelEntry> =
        _config.value.let { config ->
            val enabledIds = config.instances.filter { it.isEnabled }.map { it.id }.toSet()
            config.modelEntries
                .filter { it.providerInstanceId in enabledIds && !it.isHidden }
                .sortedWith(releaseRankOrder)
        }

    /**
     * [T-model-release-ranking] Newest / most capable model first, so a picker
     * never opens on a stale (or, as in OpenMinis#83, an uncallable) model.
     * These lists previously came back in raw config order, which is insertion
     * order from the provider's /models response — effectively arbitrary.
     * Falls back to the model id so the ordering is total and stable when two
     * entries rank identically; otherwise the list could visibly reshuffle
     * between reads. Mirrors iOS `ProviderConfigStore.releaseRankOrder`.
     */
    private val releaseRankOrder = Comparator<ModelEntry> { a, b ->
        val ra = ModelReleaseIndex.rank(
            a.baseModel.id, a.baseModel.displayName, a.baseModel.contextWindow
        )
        val rb = ModelReleaseIndex.rank(
            b.baseModel.id, b.baseModel.displayName, b.baseModel.contextWindow
        )
        val byRank = ModelReleaseIndex.comparator.compare(ra, rb)
        if (byRank != 0) byRank else a.baseModel.id.compareTo(b.baseModel.id)
    }

    // ── [T-newchat-default-model-fallback-android] last-used + newest-model ──

    /**
     * The model entry id the user last actively selected (model picker tap) or
     * sent a message with. Persisted globally (not per-session) so a brand-new
     * chat can fall back to "the model I was just using" when no default group
     * is configured. Null until the user has picked / used a model at least
     * once. Mirrors iOS #636 lastUsedModelEntryId.
     */
    var lastUsedEntryId: String?
        get() = configStore.lastUsedEntryId
        set(value) {
            configStore.lastUsedEntryId = value
        }

    /**
     * Resolve [lastUsedEntryId] to a still-valid, visible, enabled-provider
     * entry — or null when the recorded id was deleted / hidden / its provider
     * disabled. Callers treat null as "fall through to the next tier".
     */
    fun lastUsedVisibleEntry(): ModelEntry? {
        val id = lastUsedEntryId ?: return null
        return allVisibleEntries().firstOrNull { it.id == id }
    }

    /**
     * [T-newchat-default-model-fallback-android] Final-tier default for a new
     * chat: the newest text-output model from the newest-added enabled
     * provider. "Newest provider" = max [ProviderInstance.createdAt]; "newest
     * model" = last text-capable entry in add order (modelEntries is appended
     * in add order, so the instance's last matching entry is the most recently
     * added). Image/audio-only models are excluded — a fresh chat must default
     * to something that can produce a text reply.
     *
     * Walks providers newest→oldest so that if the newest provider somehow has
     * no text model (all image/audio), we still land on the newest text model
     * from the next provider rather than returning null.
     */
    fun newestProviderNewestTextEntry(): ModelEntry? {
        val config = _config.value
        val enabledProviders = config.instances
            .filter { it.isEnabled }
            .sortedByDescending { it.createdAt }
        for (instance in enabledProviders) {
            val textEntry = config.modelEntries
                .filter { it.providerInstanceId == instance.id && !it.isHidden && it.model.isTextOutput }
                .lastOrNull()
            if (textEntry != null) return textEntry
        }
        return null
    }

    /**
     * [T-disabled-provider-via-group-android] Members of [group] whose
     * provider instance is currently enabled. Used everywhere we previously
     * read `group.memberEntryIds` directly to resolve / display a model —
     * runtime selection must skip disabled providers, and the settings UI
     * uses this to surface "no available models" when every member sits
     * behind a disabled provider.
     *
     * Returns entries in the same order as [ModelGroup.memberEntryIds] so
     * the routing-strategy semantics (primary = first member) carry over.
     * Entries whose [ModelEntry.providerInstanceId] no longer maps to any
     * instance are also filtered — orphaned ids would otherwise pin the
     * group to a model that can't be instantiated.
     */
    fun enabledMemberEntries(group: ModelGroup): List<ModelEntry> {
        val config = _config.value
        val enabledIds = config.instances.filter { it.isEnabled }.map { it.id }.toSet()
        return group.memberEntryIds.mapNotNull { entryId ->
            val entry = config.modelEntries.find { it.id == entryId }
            if (entry != null && entry.providerInstanceId in enabledIds) entry else null
        }
    }

    /** Convenience: first enabled member of [group] in declaration order. */
    fun firstEnabledMemberEntry(group: ModelGroup): ModelEntry? =
        enabledMemberEntries(group).firstOrNull()

    /**
     * [T-android-group-resolve-skip-uncredentialed] Credential-storage probe;
     * implementation in [ProviderConfigStore].
     */
    fun hasAnyCredential(instance: ProviderInstance): Boolean =
        configStore.hasAnyCredential(instance)

    /**
     * [T-android-group-resolve-skip-uncredentialed] Members of [group] that are
     * actually usable RIGHT NOW: not hidden, provider enabled, and provider
     * holding a credential.
     *
     * This is the Android counterpart of iOS `ModelGroupRouter.availableEntryIds`
     * and exists because [enabledMemberEntries] answers a narrower question
     * ("is the provider switched on"), which is the right filter for the
     * settings UI but too weak for routing: a member whose provider has no
     * credential cannot serve a request, so selecting it only produces an
     * immediate auth failure.
     *
     * Order is preserved from [ModelGroup.memberEntryIds] so "primary = first
     * member" still holds — the filter removes candidates, it never reorders.
     */
    fun availableMemberEntries(group: ModelGroup): List<ModelEntry> {
        val config = _config.value
        return group.memberEntryIds.mapNotNull { entryId ->
            val entry = config.modelEntries.find { it.id == entryId } ?: return@mapNotNull null
            if (entry.isHidden) return@mapNotNull null
            val instance = config.instances.find { it.id == entry.providerInstanceId }
                ?: return@mapNotNull null
            if (!instance.isEnabled) return@mapNotNull null
            if (!hasAnyCredential(instance)) return@mapNotNull null
            entry
        }
    }

    /**
     * [T-android-regenerate-title-submodel] The dedicated title-generation
     * sub-model entry: the first enabled member of the configured
     * `defaultSubGroupId` group. Returns null when no sub-group is configured or
     * every member sits behind a disabled provider (caller then falls back to
     * the primary model). Single source of truth for both the auto-title path
     * (ChatViewModel.resolveTitleProvider) and the manual Regenerate path
     * (SessionListViewModel.regenerateTitle), mirroring iOS resolveSubEntry.
     */
    fun resolveTitleSubEntry(): ModelEntry? {
        val subGroupId = defaultSubGroupId ?: return null
        val group = group(subGroupId) ?: return null
        // [T-android-group-resolve-skip-uncredentialed] Routing decision, so it
        // needs the credential-aware filter: firstEnabledMemberEntry would hand
        // back a member whose provider has no credential and title generation
        // would fail on it instead of using the next usable member.
        return availableMemberEntries(group).firstOrNull()
    }

    /**
     * [T-android-title-failover] EVERY usable member of the configured title
     * sub-group, in group order — the failover chain for title generation.
     * Upstream 1.14: "A failed title model hands over to the next one."
     * resolveTitleSubEntry only exposes the first member, so a failing
     * sub-model was retried until the attempt budget burned out instead of
     * handing over to the next usable member. Same credential-aware filter
     * as availableMemberEntries.
     */
    fun resolveTitleSubGroupEntries(): List<ModelEntry> {
        val subGroupId = defaultSubGroupId ?: return emptyList()
        val group = group(subGroupId) ?: return emptyList()
        return availableMemberEntries(group)
    }

    /**
     * [T-disabled-provider-via-group-android] True when [entryId] resolves
     * to an entry whose provider instance is currently enabled. Used by the
     * settings UI to dim members that won't be reachable at runtime.
     */
    fun isEntryProviderEnabled(entryId: String): Boolean {
        val config = _config.value
        val entry = config.modelEntries.find { it.id == entryId } ?: return false
        val instance = config.instances.find { it.id == entry.providerInstanceId } ?: return false
        return instance.isEnabled
    }

    /**
     * Rebuilds this instance's non-custom rows from a fetched catalogue. The body
     * is removeAll + addAll, so an empty [models] means ABSENT information, never
     * "delete every row" — a blocked or offline /models response looks exactly
     * like that from here. Clearing rows is hardClear's job, not this function's.
     */
    fun replaceEntries(instanceId: String, models: List<LLMModel>, dropStaleRefs: Boolean = false): Set<String> = synchronized(configLock) {
        if (models.isEmpty()) return@synchronized emptySet()
        ensureConfigLoaded()
        // Hot path for concurrent autoRefreshModels coroutines (one per
        // enabled instance) — without this lock, two replaceEntries() calls
        // race on the shared config.modelEntries ArrayList. The working copy
        // additionally keeps this refresh off the list Compose is iterating.
        val config = workingCopy()
        val existing = config.modelEntries.filter { it.providerInstanceId == instanceId }
        val existingEntryIds = existing.map { it.id }.toSet()

        // Build lookup: baseModel.id → existing entry (prefer non-custom if duplicates exist)
        val existingByModelId = mutableMapOf<String, ModelEntry>()
        for (entry in existing) {
            val key = entry.baseModel.id
            val current = existingByModelId[key]
            if (current == null || current.isCustom) {
                existingByModelId[key] = entry
            }
        }

        val admitted = ModelDeletion.filterDeleted(config, instanceId, models)
        // Build new entries, carrying forward uuid / overrides / isHidden from prior entries
        val refreshedModelIds = admitted.map { it.id }.toSet()
        // [T-android-provider-voice] Template-sourced voice models carry an
        // authoritative modality that API-inferred modality (typically no audio
        // bits) must never overwrite. Mirrors iOS replaceEntries
        // templateModalityById guard (d55cd821).
        val instanceBaseURL = config.instances.firstOrNull { it.id == instanceId }?.customBaseURL
        val voiceTemplate = VoiceProviderTemplate.template(instanceBaseURL)
        val templateVoiceModelById = voiceTemplate?.mockModels
            ?.filter { it.hasVoiceModality }
            ?.associateBy { it.id }
            ?: emptyMap()
        val newEntries = admitted.map { model ->
            val prior = existingByModelId[model.id]
            // Dedicated ASR/TTS id/name patterns fill the exact voice shape when
            // the API returned no modality info; the template's shape wins last.
            var resolved = model.withInferredVoiceModality()
                .withInferredVideoModality()
                .withInferredImageModality()
            templateVoiceModelById[model.id]?.let { tplModel ->
                resolved = resolved.copy(
                    inputModalities = tplModel.inputModalities,
                    outputModalities = tplModel.outputModalities,
                )
            }
            ModelEntry(
                providerInstanceId = instanceId,
                baseModel = applyUnrecognizedModelDefaults(resolved),
                overrides = prior?.overrides ?: ModelOverrides(),
                isCustom = false,
                isHidden = prior?.isHidden ?: false,
                uuid = prior?.id ?: java.util.UUID.randomUUID().toString(),
                userModifiedAt = prior?.userModifiedAt,
            )
        }

        // Keep custom entries that weren't in the refreshed list
        val remainingCustom = existing.filter { it.isCustom && it.baseModel.id !in refreshedModelIds }

        // [T-android-provider-voice] Preserve voice-template SEED entries the
        // /models list didn't return. Vendors like MiMo never expose their
        // ASR/TTS voices via /v1/models, so without this every refresh wipes
        // the seeds (iOS regression cffbec0e: MiMo launch model count 15→11).
        // Only genuine template members are protected — never stray audio
        // entries. Mirrors iOS replaceEntries preservedVoice.
        val preservedVoice = existing.filter { e ->
            !e.isCustom &&
                e.baseModel.id !in refreshedModelIds &&
                e.baseModel.id in templateVoiceModelById
        }

        config.modelEntries.removeAll { it.providerInstanceId == instanceId }
        config.modelEntries.addAll(newEntries)
        config.modelEntries.addAll(remainingCustom)
        if (preservedVoice.isNotEmpty()) {
            config.modelEntries.addAll(preservedVoice)
            android.util.Log.i("ProviderRepo", "[ModelList] replaceEntries preserved ${preservedVoice.size} voice-template seed entries: ${preservedVoice.map { it.baseModel.id }.take(10)}")
        }

        // Prune stale group member references
        val survivingEntryIds = config.modelEntries.map { it.id }.toSet()
        val prunedEntryIds = existingEntryIds - survivingEntryIds
        if (prunedEntryIds.isNotEmpty()) {
            val suspiciousShrink = !dropStaleRefs && existing.size >= 4 && models.size * 2 < existing.size
            if (suspiciousShrink) {
                android.util.Log.w("ProviderRepo", "[ModelList] replaceEntries SUSPICIOUS SHRINK before=${existing.size} after=${models.size} — group references PRESERVED as stale")
            } else {
                for (i in config.modelGroups.indices) {
                    val before = config.modelGroups[i].memberEntryIds.size
                    config.modelGroups[i].memberEntryIds.removeAll { it in prunedEntryIds }
                    val removed = before - config.modelGroups[i].memberEntryIds.size
                    if (removed > 0) {
                        android.util.Log.i("ProviderRepo", "[ModelList] replaceEntries pruned $removed stale refs from group '${config.modelGroups[i].name}'")
                    }
                }
                // T171: same cascade for agent-loop direct-entry pins —
                // mirrors iOS ProviderConfigStore.replaceEntries (L716).
                // Skipped under the suspicious-shrink branch alongside the
                // group-member preservation, so a transient API hiccup
                // never silently nukes the user's curated agent-loop set.
                val agentBefore = config.agentLoopModelEntryIds.size
                config.agentLoopModelEntryIds.removeAll { it in prunedEntryIds }
                val agentRemoved = agentBefore - config.agentLoopModelEntryIds.size
                if (agentRemoved > 0) {
                    android.util.Log.i("ProviderRepo", "[ModelList] replaceEntries pruned $agentRemoved stale agent-loop entry pins")
                }
            }
        }

        val droppedRefs = prunedEntryIds.isNotEmpty() && (dropStaleRefs || existing.size < 4 || models.size * 2 >= existing.size)
        if (droppedRefs) scrubConfigEntryRefs(config, prunedEntryIds)
        saveConfig(config)
        // Stamp so staleness checks know this instance just refreshed.
        catalogCache.markInstanceFetched(instanceId)
        if (droppedRefs) prunedEntryIds else emptySet()
    }

    // clearFetchedModelEntries moved to [ModelCatalogCache] (hard-clear is the
    // refresh pipeline's own tool).

    /** Group pins, agent-loop pins, and default slots that pointed at removed entries. */
    // internal (was private): ModelCatalogCache.clearFetchedModelEntries reuses it.
    internal fun scrubConfigEntryRefs(config: ProviderConfig, removedIds: Set<String>) {
        if (removedIds.isEmpty()) return
        for (i in config.modelGroups.indices) {
            config.modelGroups[i].memberEntryIds.removeAll { it in removedIds }
        }
        config.agentLoopModelEntryIds.removeAll { it in removedIds }
        fun clearSlot(current: String?): String? {
            if (current == null) return null
            val pinned = com.openminis.app.data.model.ModelSlotRef.entryId(current)
            return if (current in removedIds || pinned in removedIds) null else current
        }
        // [T-android-translation-single-source] `defaultTranslationModelId` is NOT
        // scrubbed any more: nothing reads the field at all (live translation uses
        // TranslationPrefs), so nulling a dead slot on every provider delete was
        // the second half of a second source of truth. The field itself stays on
        // ProviderConfig so older persisted JSON mirrors keep decoding.
        config.defaultPrimaryGroupId = clearSlot(config.defaultPrimaryGroupId)
        config.defaultSubGroupId = clearSlot(config.defaultSubGroupId)
        val last = prefs.getString(KEY_LAST_USED_ENTRY, null)
        if (last in removedIds) prefs.edit().remove(KEY_LAST_USED_ENTRY).apply()
    }

    /**
     * Sessions and scheduled tasks keep their own copy of the entry id.
     * Clearing the provider list must not leave those copies resolving to a
     * missing row. Null the binding so the existing fallback (app default)
     * runs instead of crashing or sending to a deleted model.
     *
     * [T-android-save-off-main-thread] One queued unit of work instead of a
     * per-id round-trip on the caller's thread: this used to run
     * `clearBindingReferencing` once per removed id INSIDE the config-lock
     * critical path, i.e. N full-table `UPDATE sessions` scans while every
     * Compose mutator was blocked behind the lock. The whole set now goes to the
     * persistence worker as a single task, ordered behind the save that removed
     * the entries (which is what publishes the removal in memory anyway).
     *
     * COORDINATION: the DAO updates the row whose binding literally contains
     * `"entryId":"<id>"` (ChatDao.clearBindingReferencing, `instr` not `LIKE`),
     * and [com.openminis.app.scheduled.ScheduledTaskStore.dropEntryRefs] parses
     * the same JSON before comparing. Both used to substring-match, which
     * un-pinned `inst/gpt-4o` whenever `inst/gpt-4` was removed; keep the two
     * call sites exact if either is touched again.
     */
    // internal (was private): ModelCatalogCache.refreshModels reuses it.
    internal fun scrubExternalEntryRefs(removedIds: Set<String>) {
        if (removedIds.isEmpty()) return
        val ids = removedIds.toList()
        ensurePersistWorker()
        persistRequests.trySend(ProviderPersistRequest.Task {
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                val dao = com.openminis.app.data.db.AppDatabase.getInstance(context).chatDao()
                for (id in ids) {
                    dao.clearBindingReferencing(id)
                }
                com.openminis.app.scheduled.ScheduledTaskStore(context).dropEntryRefs(ids.toSet())
            }
        })
    }

    // --- Model Entry management ---
    //
    // [T-android-provider-mutator-lock] Every read-modify-write mutator below
    // holds configLock for its WHOLE body, not just the saveConfig at the end.
    //
    // These mutate `_config.value`'s inner MutableLists IN PLACE — the very
    // lists already handed to collectors (the pickers filter over
    // config.modelEntries on the main thread). Mutating them from an IO thread
    // while a composition iterates throws ConcurrentModificationException in
    // the READER. 5399fe270 removed one such walk (ProviderConfig.equals inside
    // StateFlow emission) but left every consumer exposed; the shared mutable
    // list is the actual defect, and the lock is what serialises it against
    // saveConfig's defensive copy (which only snapshots AFTER the mutation).
    // configLock is a plain JVM monitor, so the nested saveConfig re-entry is
    // fine — replaceEntries / ensureVoiceTemplateModels already rely on that.

    fun addEntry(entry: ModelEntry): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        if (!entry.isCustom) {
            val exists = config.modelEntries.any {
                it.providerInstanceId == entry.providerInstanceId && it.baseModel.id == entry.baseModel.id
            }
            // Explicit label: a bare `return` in an expression body reads as if
            // it might only leave the synchronized lambda.
            if (exists) return@addEntry
            // [T-android-composite-key-collision] The check deliberately does NOT
            // run for custom entries: two custom entries on one instance sharing
            // a base-model id are the legitimate "same model, different overrides"
            // case, and refusing the second one would delete the feature. What
            // broke instead was storage — ProviderConfigMapping keyed the row on
            // compositeEntryKey(instanceId, baseModel.id), so both entries mapped
            // to ONE row (@Insert(REPLACE) kept one) and, until the id was
            // canonicalised, the UI rendered both with the SAME id: the duplicate
            // LazyColumn key [T-android-agentloop-dup-key-crash] warns about.
            // Fixed at the mapping level, where the row id is minted (colliding
            // entries get their uuid appended); see ProviderConfigMappingRoundTripTest.
        }
        config.modelEntries.add(entry)
        ModelDeletion.clearDeletion(config, entry)
        saveConfig(config)
    }

    fun updateEntry(entry: ModelEntry): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        val idx = config.modelEntries.indexOfFirst { it.id == entry.id }
        if (idx >= 0) {
            config.modelEntries[idx] = entry.copy(userModifiedAt = System.currentTimeMillis())
            saveConfig(config)
        }
    }

    fun removeEntry(entryId: String): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        // 非自定义条目记墓碑，刷新不复活。
        config.modelEntries.firstOrNull { it.id == entryId }?.let { ModelDeletion.recordDeletion(config, it) }
        config.modelEntries.removeAll { it.id == entryId }
        config.modelGroups.forEach { group ->
            group.memberEntryIds.removeAll { it == entryId }
        }
        // T171: cascade-clean the agent-loop direct-entry pin so the
        // AgentLoopModelsScreen never surfaces a checkmark on a model that
        // no longer exists. Mirrors iOS ProviderConfigStore.removeEntry
        // (Providers/ProviderConfigStore.swift L268).
        config.agentLoopModelEntryIds.removeAll { it == entryId }
        saveConfig(config)
    }

    // --- Model Group management (impl in [ModelGrouping]) ---

    fun addGroup(group: ModelGroup): Unit = grouping.addGroup(group)

    fun updateGroup(group: ModelGroup): Unit = grouping.updateGroup(group)

    fun removeGroup(groupId: String): Unit = grouping.removeGroup(groupId)

    // Agent-loop composition moved to [ModelGrouping]; facades below.

    /** Set the agent-loop-visible entry ID list (individual model entries). */
    fun setAgentLoopEntryIds(ids: List<String>) = grouping.setAgentLoopEntryIds(ids)

    /** Set the agent-loop-visible group ID list. */
    fun setAgentLoopGroupIds(ids: List<String>) = grouping.setAgentLoopGroupIds(ids)

    /** Append [entryId] to the agent-loop direct-pin list if not already there. */
    fun addAgentLoopEntry(entryId: String) = grouping.addAgentLoopEntry(entryId)

    /** Remove [entryId] from the agent-loop direct-pin list. No-op if absent. */
    fun removeAgentLoopEntry(entryId: String) = grouping.removeAgentLoopEntry(entryId)

    /** Append [groupId] to the agent-loop group-pin list if not already there. */
    fun addAgentLoopGroup(groupId: String) = grouping.addAgentLoopGroup(groupId)

    /** Remove [groupId] from the agent-loop group-pin list. No-op if absent. */
    fun removeAgentLoopGroup(groupId: String) = grouping.removeAgentLoopGroup(groupId)

    fun reorderAgentLoopEntries(newOrder: List<String>) =
        grouping.reorderAgentLoopEntries(newOrder)

    fun reorderAgentLoopGroups(newOrder: List<String>) =
        grouping.reorderAgentLoopGroups(newOrder)

    /**
     * [T-android-provider-reorder] Reorder provider instances (drag-to-sort in
     * the Providers list). Mirrors iOS `ProviderConfigStore.reorderInstances`.
     *
     * [newOrder] is a list of instance ids. Unknown ids are dropped and any
     * instance missing from it is appended in its existing relative order, so a
     * caller that only knows about ONE provider-type section can pass just that
     * section's ids and leave the rest untouched — which is exactly what the
     * per-section drag in ProviderListScreen does.
     *
     * Persistence: `sort_order` is derived from list position at save time
     * (ProviderConfigMapping writes `sortOrder = idx`) and the DAO reads back
     * `ORDER BY sort_order ASC`, so permuting the list IS the persistence — no
     * per-row column write is needed.
     *
     * Unlike iOS there is no dirty-marking step here: Android keeps provider
     * config local-only (Room + the JSON mirror), with no CloudKit upload, so
     * the "pure reorder doesn't mark dirty" bug iOS had to fix has no analogue.
     * `saveConfig` bumps `revision`, which is the Android-side equivalent
     * concern — it guarantees the StateFlow re-emits even though a pure
     * permutation compares equal under data-class structural equality.
     */
    fun reorderInstances(newOrder: List<String>): Unit = synchronized(configLock) {
        ensureConfigLoaded()
        val config = workingCopy()
        val current = config.instances.toList()
        if (current.isEmpty()) return

        val byId = current.associateBy { it.id }
        val seen = LinkedHashSet<String>()
        val reordered = ArrayList<ProviderInstance>(current.size)
        for (id in newOrder) {
            val inst = byId[id] ?: continue      // drop unknown ids
            if (!seen.add(id)) continue          // drop duplicates
            reordered.add(inst)
        }
        // Anything the caller didn't mention keeps its existing relative order.
        for (inst in current) {
            if (seen.add(inst.id)) reordered.add(inst)
        }

        // No-op guard: skip the DB write + StateFlow churn when nothing moved.
        if (reordered.map { it.id } == current.map { it.id }) return

        // [T-android-reorder-unlocked-mutation] Mutate under configLock.
        //
        // `config.instances` is the shared MutableList inside _config.value,
        // and clear()/addAll() ran unprotected here — exactly the window the
        // configLock KDoc above was written to close (it cites the
        // "ConcurrentModificationException → ArrayList.next" crash seen on
        // Pixel 6 / 4a). Concurrent readers iterate that same list with no
        // lock: hasFoldedShadowDuplicates() and shadowVoiceProviders() are
        // called from ProviderListScreen during composition — on the main
        // thread, and recomposition fires precisely BECAUSE this reorder just
        // emitted — while the background model-refresh fan-out reads it too.
        //
        // The empty window was the worse half: a saveConfig snapshotting
        // between clear() and addAll() would persist ZERO instances to Room
        // and the JSON mirror, wiping the user's providers.
        //
        // synchronized is reentrant, so the nested saveConfig (which takes the
        // same lock) is fine. Matches ensureVoiceTemplateModels / replaceEntries.
        synchronized(configLock) {
            config.instances.clear()
            config.instances.addAll(reordered)
            saveConfig(config)
        }
    }

    /**
     * [T-android-modelgroup-reorder] Reorder implementation in [ModelGrouping];
     * facade keeps the public surface unchanged.
     */
    fun reorderModelGroups(newOrder: List<String>): Unit = grouping.reorderModelGroups(newOrder)

    /**
     * Resolve the effective model entries visible to the agent loop (minis-model-use).
     * Implementation (group expansion + union + dedupe) in [ModelGrouping].
     */
    fun resolvedAgentLoopEntries(): List<ModelEntry> =
        grouping.resolvedAgentLoopEntries()

    fun group(id: String): ModelGroup? = grouping.group(id)

    var defaultPrimaryGroupId: String?
        get() = grouping.defaultPrimaryGroupId
        set(value) {
            grouping.defaultPrimaryGroupId = value
        }

    var defaultSubGroupId: String?
        get() = grouping.defaultSubGroupId
        set(value) {
            grouping.defaultSubGroupId = value
        }

    var voiceInputGroupId: String?
        get() = grouping.voiceInputGroupId
        set(value) {
            grouping.voiceInputGroupId = value
        }

    var voiceOutputGroupId: String?
        get() = grouping.voiceOutputGroupId
        set(value) {
            grouping.voiceOutputGroupId = value
        }

    var visionGroupId: String?
        get() = grouping.visionGroupId
        set(value) {
            grouping.visionGroupId = value
        }

    var compactFallbackGroupId: String?
        get() = grouping.compactFallbackGroupId
        set(value) {
            grouping.compactFallbackGroupId = value
        }

    /** True when a Vision Group is bound AND still exists; impl in [ModelGrouping]. */
    fun hasVisionGroupConfigured(): Boolean = grouping.hasVisionGroupConfigured()

    /** Bound Vision group's display name, or the pinned model's name. */
    fun visionGroupName(): String? = grouping.visionGroupName()

    /** Group name, or the pinned model's display name when the slot is `entry:`. */
    fun slotDisplayName(raw: String?): String? = grouping.slotDisplayName(raw)

    fun resolveVisionCandidates(loadBalanceSeed: Int = 0): List<Pair<ProviderInstance, ModelEntry>> =
        grouping.resolveVisionCandidates(loadBalanceSeed)

    fun resolveCompactFallbackCandidates(): List<Pair<ProviderInstance, ModelEntry>> =
        grouping.resolveCompactFallbackCandidates()

    // --- Thinking rules (custom) [T-android-thinking-rules-phase2] ---
    //
    // User-authored rules live in provider.db (provider_thinking_rules), keyed by
    // provider-instance id, in sort_order priority order. Built-in vendor rules are
    // never stored. On every mutation we publish the instance's rules into the
    // ThinkingRuleResolver cache so the (sync) request-builder can read them.

    /** Read the last off-thread snapshot; suspend callers can read Room directly. */
    fun thinkingRules(instanceId: String): List<ThinkingRule> =
        thinkingRulesCache[instanceId].orEmpty()

    /** Suspend variant of [thinkingRules] — reads provider.db on IO, never the caller's thread. */
    suspend fun thinkingRulesSuspending(instanceId: String): List<ThinkingRule> =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { providerDao.loadThinkingRules(instanceId).map { it.toRule() } }
                .getOrDefault(emptyList())
        }

    fun thinkingRuleIds(instanceId: String): List<String> =
        thinkingRules(instanceId).map { it.id }

    suspend fun thinkingRuleIdsSuspending(instanceId: String): List<String> =
        thinkingRulesSuspending(instanceId).map { it.id }

    data class ThinkingRulesSnapshot(
        val customRules: List<ThinkingRule> = emptyList(),
        val builtIns: List<ThinkingRule> = emptyList(),
        val sampleModelId: String? = null,
    )

    suspend fun thinkingRulesSnapshot(instanceId: String): ThinkingRulesSnapshot =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            ensureConfigLoaded()
            val customRules = runCatching {
                providerDao.loadThinkingRules(instanceId).map { it.toRule() }
            }.getOrDefault(emptyList())
            val builtIns = builtInThinkingRulesForDisplay(instanceId)
            val sampleModelId = _config.value.modelEntries
                .firstOrNull { it.providerInstanceId == instanceId }?.model?.id
            ThinkingRulesSnapshot(customRules, builtIns, sampleModelId)
        }

    fun firstModelId(instanceId: String): String? {
        ensureConfigLoaded()
        return _config.value.modelEntries.firstOrNull { it.providerInstanceId == instanceId }?.model?.id
    }

    /** Warm the resolver cache on the persistence worker. */
    fun loadAllThinkingRulesIntoCache() {
        ensurePersistWorker()
        persistRequests.trySend(ProviderPersistRequest.Task {
            runCatching {
                val rows = providerDao.loadAllThinkingRules()
                val byInstance = rows.groupBy { it.providerInstanceId }
                    .mapValues { (_, rs) -> rs.sortedBy { it.sortOrder }.map { it.toRule() } }
                thinkingRulesCache = byInstance
                ThinkingRuleResolver.setAllCustomRules(byInstance)
            }.onFailure {
                android.util.Log.w("ProviderRepo", "[ThinkingRules] cache warm failed: ${it.message}")
            }
        })
    }

    /**
     * [T-android-save-off-main-thread] Last-warm snapshot of `provider_thinking_rules`
     * by instance id, filled off-thread by [loadAllThinkingRulesIntoCache] and kept
     * current by every rule mutation. Lets the non-suspend [thinkingRules] read be a
     * map lookup instead of a `runBlocking` DAO query on the composition thread.
     */
    @Volatile
    private var thinkingRulesCache: Map<String, List<ThinkingRule>> = emptyMap()

    /**
     * Re-warm the rule cache after a mutation. Reloads every instance's rows
     * rather than just [instanceId] so a rename/reorder can never leave a stale
     * entry behind; the table is small (user-authored rules only).
     *
     * [T-android-save-off-main-thread] suspend because the DAO read is a
     * suspend query — every caller is already on the persistence worker
     * ([saveThinkingRuleSuspending] / [deleteThinkingRuleSuspending] /
     * [reorderThinkingRulesSuspending]).
     */
    private suspend fun republishThinkingCache(instanceId: String) {
        runCatching {
            val rows = providerDao.loadAllThinkingRules()
                .groupBy { it.providerInstanceId }
                .mapValues { (_, rs) -> rs.sortedBy { it.sortOrder }.map { it.toRule() } }
            thinkingRulesCache = rows
            ThinkingRuleResolver.setAllCustomRules(rows)
        }.onFailure {
            android.util.Log.w(
                "ProviderRepo",
                "[ThinkingRules] republish after $instanceId failed: ${it.message}",
            )
        }
    }

    /**
     * Insert or update a custom rule. [id] null ⇒ new rule minted at the TOP of the
     * list (position 0) — a rule overriding a built-in is useless below it; existing
     * rules shift down. A non-null [id] updates in place, preserving position.
     * Returns the rule id.
     *
     * [T-android-save-off-main-thread] Used to be `runBlocking { load → replace →
     * republish }` on the Compose callback thread (called from
     * ui/settings/ThinkingRulesSection.kt). The id is still minted synchronously so
     * the caller can key its own state on it; the three DAO round-trips move to the
     * persistence worker, where they stay ordered with config saves. Await the
     * durable write with [saveThinkingRuleSuspending].
     */
    fun saveThinkingRule(instanceId: String, rule: ThinkingRule, id: String? = null): String {
        val ruleId = id ?: java.util.UUID.randomUUID().toString()
        ensurePersistWorker()
        persistRequests.trySend(ProviderPersistRequest.Task {
            runCatching { saveThinkingRuleSuspending(instanceId, rule, ruleId) }
                .onFailure { android.util.Log.e("ProviderRepo", "[ThinkingRules] save failed: ${it.message}", it) }
        })
        return ruleId
    }

    /** Suspend variant of [saveThinkingRule]; returns the rule id it persisted. */
    suspend fun saveThinkingRuleSuspending(instanceId: String, rule: ThinkingRule, id: String? = null): String =
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val existing = providerDao.loadThinkingRules(instanceId).toMutableList()
            val ruleId = id ?: java.util.UUID.randomUUID().toString()
            val idx = existing.indexOfFirst { it.id == ruleId }
            if (idx >= 0) {
                // Update in place at its current sort_order.
                existing[idx] = rule.toEntity(ruleId, instanceId, existing[idx].sortOrder)
            } else {
                // New rule at the top; everything else shifts down.
                existing.add(0, rule.toEntity(ruleId, instanceId, 0))
            }
            val renumbered = existing.mapIndexed { i, e -> e.copy(sortOrder = i) }
            providerDao.replaceThinkingRules(instanceId, renumbered)
            republishThinkingCache(instanceId)
            ruleId
        }

    /** Delete a custom rule by id. Hard delete — Android provider config is local-only,
     *  so there is no sync channel that could resurrect it (no tombstone needed). */
    fun deleteThinkingRule(instanceId: String, id: String) {
        ensurePersistWorker()
        persistRequests.trySend(ProviderPersistRequest.Task {
            runCatching { deleteThinkingRuleSuspending(instanceId, id) }
                .onFailure { android.util.Log.e("ProviderRepo", "[ThinkingRules] delete failed: ${it.message}", it) }
        })
    }

    /** Suspend variant of [deleteThinkingRule]. */
    suspend fun deleteThinkingRuleSuspending(instanceId: String, id: String) {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            providerDao.deleteThinkingRule(id)
            // Renumber survivors so sort_order stays dense.
            val survivors = providerDao.loadThinkingRules(instanceId)
                .sortedBy { it.sortOrder }
                .mapIndexed { i, e -> e.copy(sortOrder = i) }
            providerDao.replaceThinkingRules(instanceId, survivors)
            republishThinkingCache(instanceId)
        }
    }

    /** Reorder an instance's custom rules to match [orderedIds] (a permutation). */
    fun reorderThinkingRules(instanceId: String, orderedIds: List<String>) {
        ensurePersistWorker()
        persistRequests.trySend(ProviderPersistRequest.Task {
            runCatching { reorderThinkingRulesSuspending(instanceId, orderedIds) }
                .onFailure { android.util.Log.e("ProviderRepo", "[ThinkingRules] reorder failed: ${it.message}", it) }
        })
    }

    /** Suspend variant of [reorderThinkingRules]. */
    suspend fun reorderThinkingRulesSuspending(instanceId: String, orderedIds: List<String>) {
        withContext(kotlinx.coroutines.Dispatchers.IO) {
            val byId = providerDao.loadThinkingRules(instanceId).associateBy { it.id }
            val reordered = orderedIds.mapNotNull { byId[it] }
                .mapIndexed { i, e -> e.copy(sortOrder = i) }
            // Keep any id the caller omitted (defensive against a partial list) appended.
            val omitted = byId.values.filter { it.id !in orderedIds }.map { it }
            providerDao.replaceThinkingRules(instanceId, reordered + omitted)
            republishThinkingCache(instanceId)
        }
    }

    /**
     * Built-in rules relevant to THIS instance, for the Provider-detail UI. Mirrors iOS
     * builtInRulesForDisplay: resolve the vendor context from the instance's base URL,
     * then keep every AllModels-scoped rule (endpoint/provider-type defaults) plus any
     * ModelPattern rule the provider actually serves a matching model for. An empty
     * catalog keeps everything (list must not be mysteriously empty before first fetch).
     */
    fun builtInThinkingRulesForDisplay(instanceId: String): List<ThinkingRule> {
        ensureConfigLoaded()
        val config = _config.value
        val inst = config.instances.find { it.id == instanceId } ?: return emptyList()
        val base = (inst.effectiveBaseURL ?: "").lowercase()
        val ctx = com.openminis.app.provider.thinking.ThinkingResolveContext(
            modelId = "",
            supportsReasoning = null,
            declaredEffortValues = null,
            level = com.openminis.app.data.model.ThinkingLevel.OFF,
            maxTokens = 0,
            isOpenRouter = base.contains("openrouter.ai"),
            usesUnifiedReasoningEffort = base.contains("volces") || base.contains("ark.") || base.contains("venice.ai"),
            isMistral = base.contains("mistral.ai"),
            isDashScope = base.contains("dashscope"),
            offEffort = null,
        )
        val modelIds = config.modelEntries.filter { it.providerInstanceId == instanceId }.map { it.model.id }
        return ThinkingRuleResolver.builtInRules(ctx).filter { rule ->
            when (rule.scope) {
                is ThinkingRule.Scope.AllModels -> true
                is ThinkingRule.Scope.ModelPattern ->
                    modelIds.isEmpty() || modelIds.any { rule.scope.matches(it) }
            }
        }
    }

    /** Default Voice Input group seeding; impl in [ModelGrouping]. */
    fun ensureDefaultVoiceInputGroup(): String? = grouping.ensureDefaultVoiceInputGroup()

    /** Default Voice Output group seeding; impl in [ModelGrouping]. */
    fun ensureDefaultVoiceOutputGroup(): String? = grouping.ensureDefaultVoiceOutputGroup()

    /**
     * [T-android-voice-panel] Explicit voice-input engine override picked in
     * the panel's model selector. Per-device (prefs) state; storage in
     * [ProviderConfigStore].
     */
    var voiceInputOverrideEntryId: String?
        get() = configStore.voiceInputOverrideEntryId
        set(value) {
            configStore.voiceInputOverrideEntryId = value
        }

    /**
     * The resolved ACTIVE voice-input choice for the panel: either the on-device
     * System engine (with an online/offline preference) or a provider entry.
     * Resolution order mirrors iOS VoiceProviderResolver: explicit override
     * first, then the Voice Input group's members in fallback order (a System
     * sentinel member selects the System engine), then the System default.
     */
    data class VoiceInputChoice(
        /** null → provider-backed; non-null → System engine (true = prefer offline). */
        val systemPreferOffline: Boolean?,
        val entry: Pair<ProviderInstance, ModelEntry>?,
    ) {
        val isSystem: Boolean get() = entry == null
    }

    /** Resolved ACTIVE voice-input choice; impl in [ModelGrouping]. */
    fun resolveVoiceInputChoice(): VoiceInputChoice = grouping.resolveVoiceInputChoice()

    /** Bound Voice Input group's display name, or null (chip's "Group · Model"). */
    fun voiceInputGroupName(): String? = grouping.voiceInputGroupName()

    /** Provider-backed voice INPUT selection; impl in [ModelGrouping]. */
    fun resolveVoiceInputEntry(): Pair<ProviderInstance, ModelEntry>? =
        grouping.resolveVoiceInputEntry()

    fun resolveVoiceInputCandidates(loadBalanceSeed: Int = 0): List<Pair<ProviderInstance, ModelEntry>> =
        grouping.resolveVoiceInputCandidates(loadBalanceSeed)

    // --- Voice OUTPUT resolution [T-android-voice-output-resolver] ---
    // (impl moved to [ModelGrouping]; override facade below)

    /**
     * Explicit voice-output voice override picked in a selector (mirrors
     * [voiceInputOverrideEntryId] on the input side). Per-device (prefs)
     * state; storage in [ProviderConfigStore].
     */
    var voiceOutputOverrideEntryId: String?
        get() = configStore.voiceOutputOverrideEntryId
        set(value) {
            configStore.voiceOutputOverrideEntryId = value
        }

    /**
     * The resolved ACTIVE voice-output choice for read-aloud: either the
     * on-device System TextToSpeech or a provider entry. Same shape and
     * resolution order as [VoiceInputChoice].
     */
    data class VoiceOutputChoice(
        /** null → provider-backed; true → System TextToSpeech. */
        val isSystemEngine: Boolean,
        val entry: Pair<ProviderInstance, ModelEntry>?,
    ) {
        val isSystem: Boolean get() = entry == null
    }

    /**
     * Resolution order mirrors [resolveVoiceInputChoice] exactly; impl in
     * [ModelGrouping].
     */
    fun resolveVoiceOutputChoice(): VoiceOutputChoice = grouping.resolveVoiceOutputChoice()

    /** Which group MEMBER would serve the next request; impl in [ModelGrouping]. */
    fun activeVoiceGroupMemberId(output: Boolean): String? =
        grouping.activeVoiceGroupMemberId(output)

    /** Bound Voice Output group's display name, or null. */
    fun voiceOutputGroupName(): String? = grouping.voiceOutputGroupName()

    /** Provider-backed voice OUTPUT selection; impl in [ModelGrouping]. */
    fun resolveVoiceOutputEntry(): Pair<ProviderInstance, ModelEntry>? =
        grouping.resolveVoiceOutputEntry()

    // --- Shadow Voice Providers [T-android-provider-voice] ---
    // (impl moved to [ModelGrouping]; data classes + facades stay here)

    /** True if [instanceId] has ANY model entry with an audio modality. */
    fun hasVoiceModels(instanceId: String): Boolean = grouping.hasVoiceModels(instanceId)

    /** Per-instance "hide from Voice Services" flag; impl in [ModelGrouping]. */
    fun isVoiceShadowDisabled(instanceId: String): Boolean =
        grouping.isVoiceShadowDisabled(instanceId)

    fun setVoiceShadowDisabled(instanceId: String, disabled: Boolean) =
        grouping.setVoiceShadowDisabled(instanceId, disabled)

    /**
     * A read-only mirror of an instance's voice capability, surfaced in Voice
     * Services. Shares the underlying instance's credential + endpoint.
     */
    data class ShadowVoiceProvider(
        val instanceId: String,
        val displayName: String,
        val inputModels: List<ModelEntry>,   // audio-in entries (ASR)
        val outputModels: List<ModelEntry>,  // audio-out entries (TTS)
    )

    data class ShadowVoiceSnapshot(
        val providers: List<ShadowVoiceProvider>,
        val hasFoldedDuplicates: Boolean,
    )

    fun shadowVoiceSnapshot(): ShadowVoiceSnapshot = grouping.shadowVoiceSnapshot()

    fun shadowVoiceProviders(): List<ShadowVoiceProvider> =
        grouping.shadowVoiceProviders()

    fun hasFoldedShadowDuplicates(): Boolean = grouping.hasFoldedShadowDuplicates()

    // ── Model catalog refresh pipeline [ProviderRepository split] ──
    // Implementation (vendor /models fetch, models.dev fallback, hard-clear,
    // background + daily sweeps) in [ModelCatalogCache]; the facades below
    // keep the public surface unchanged.

    suspend fun refreshModels(
        instance: ProviderInstance,
        forceRefresh: Boolean = false,
        clearFirst: Boolean = false,
    ): ModelRefreshResult =
        catalogCache.refreshModels(instance, forceRefresh, clearFirst)

    suspend fun refreshAllModelsForce(): List<Pair<String, ModelRefreshResult>> =
        catalogCache.refreshAllModelsForce()

    fun triggerBackgroundRefreshIfStale(scope: kotlinx.coroutines.CoroutineScope) =
        catalogCache.triggerBackgroundRefreshIfStale(scope)

    fun refreshAllModelsIfNeeded(scope: kotlinx.coroutines.CoroutineScope) =
        catalogCache.refreshAllModelsIfNeeded(scope)

    // API Key management — implementations in [ProviderConfigStore].
    fun saveApiKey(instanceId: String, key: String) =
        configStore.saveApiKey(instanceId, key)

    fun loadApiKey(instanceId: String): String? =
        configStore.loadApiKey(instanceId)

    /**
     * [T-empty-key-compat-endpoints] The credential the ROUTING layers should
     * use: the stored key, or "" for instances where an empty key is a valid
     * configuration (third-party OpenAI/Anthropic-compatible endpoint — see
     * ProviderInstance.allowsEmptyAPIKey). Returns null only when the instance
     * genuinely has no usable credential.
     */
    fun usableApiKey(instance: ProviderInstance): String? =
        configStore.usableApiKey(instance)

    fun deleteApiKey(instanceId: String) =
        configStore.deleteApiKey(instanceId)

    private val backupCoordinator by lazy {
        ProviderBackupCoordinator(context, ProviderBackupHost())
    }
    private inner class ProviderBackupHost : ProviderBackupCoordinator.Host {
        override val configValue get() = _config.value
        override fun saveConfig(config: ProviderConfig) = this@ProviderRepository.saveConfig(config)
        override fun workingCopy(): ProviderConfig = this@ProviderRepository.workingCopy()
        override fun ensureConfigLoaded() = this@ProviderRepository.ensureConfigLoaded()
        override fun addInstance(instance: ProviderInstance) = this@ProviderRepository.addInstance(instance)
        override fun instance(id: String): ProviderInstance? = this@ProviderRepository.instance(id)
        override fun visibleEntries(instanceId: String): List<ModelEntry> =
            this@ProviderRepository.visibleEntries(instanceId)
        override val configLock get() = this@ProviderRepository.configLock
        override val providerDao get() = this@ProviderRepository.providerDao
        override fun loadApiKey(instanceId: String): String? = this@ProviderRepository.loadApiKey(instanceId)
        override fun saveApiKey(instanceId: String, key: String) =
            this@ProviderRepository.saveApiKey(instanceId, key)
        override fun loadAllThinkingRulesIntoCache() =
            this@ProviderRepository.loadAllThinkingRulesIntoCache()
    }
    fun exportInstanceJSON(instanceId: String): String? =
        backupCoordinator.exportInstanceJSON(instanceId)
    fun collectBackupProviderSecret(instance: ProviderInstance): BackupSecrets.ProviderSecret? =
        backupCoordinator.collectBackupProviderSecret(instance)
    fun restoreBackupProviderSecret(secret: BackupSecrets.ProviderSecret): Boolean =
        backupCoordinator.restoreBackupProviderSecret(secret)
    fun mergeBackupProviderConfig(remote: ProviderConfig): Pair<Int, Int> =
        backupCoordinator.mergeBackupProviderConfig(remote)
    fun restoreBackupThinkingRules(
        rules: List<com.openminis.app.backup.BackupThinkingRuleRecord>,
    ): Pair<Int, Int> = backupCoordinator.restoreBackupThinkingRules(rules)
    fun importInstanceJSON(jsonStr: String): String? =
        backupCoordinator.importInstanceJSON(jsonStr)
}
