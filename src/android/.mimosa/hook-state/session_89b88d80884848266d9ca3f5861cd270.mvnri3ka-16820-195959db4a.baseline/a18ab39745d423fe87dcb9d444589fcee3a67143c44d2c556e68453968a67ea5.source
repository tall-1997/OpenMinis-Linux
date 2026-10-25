package com.openminis.app.data.repository

import android.content.Context
import android.content.SharedPreferences
import com.openminis.app.data.db.ProviderConfigDao
import com.openminis.app.data.db.ProviderDatabase
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.model.VoiceProviderTemplate
import com.openminis.app.data.model.isVoiceTemplateSeedShape
import com.openminis.app.provider.BuiltinFreeLane
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json

/**
 * Persistence slice of [ProviderRepository], split out of it verbatim: the
 * SharedPreferences JSON mirror + its SHA-256 hash, the encrypted
 * SharedPreferences secrets (API keys), the provider.db DAO handle, the
 * launch-time config reconciliation (seeding / voice-template migration),
 * and the single-threaded persistence worker the save path hands snapshots
 * to. [ProviderRepository] constructs this store, holds it privately, and
 * keeps every previously-public member reachable as a one-line delegation —
 * external callers are unchanged.
 *
 * Shared state is explicit: the in-memory config, its lock and the load
 * bookkeeping stay on [ProviderRepository] and are reached through the
 * [repo] constructor reference; nothing here is static or mutable beyond
 * what the moved code already owned.
 */
internal class ProviderConfigStore(
    private val repo: ProviderRepository,
    private val context: Context,
) {

    // [T-android-thinking-level-arch] coerceInputValues makes kotlinx.serialization
    // fall back to a property's DEFAULT when it can't decode the wire value —
    // crucially, this covers an unknown ENUM value (e.g. a ThinkingLevel a newer
    // build wrote, like "MAX"/"ULTRA", read by an older build). Without it the
    // JSON-mirror decode throws SerializationException on that enum, and since
    // that mirror is the fallback for a failed Room DB load, the whole config
    // would come back empty (all providers/groups vanish from the UI).
    // `ignoreUnknownKeys` only skips unknown object keys, NOT unknown enum
    // values — coerceInputValues is the piece that handles those. Fields carrying
    // ThinkingLevel are nullable with a null default, so an unknown value coerces
    // cleanly to null.
    internal val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    internal val prefs: SharedPreferences = context.getSharedPreferences("provider_config", Context.MODE_PRIVATE)

    // [T-android-provider-room-store] Per-row provider config DB. Lives in
    // its own provider.db file so a downgrade to a build that doesn't know
    // about provider tables can't crash the main minis.db open. The legacy
    // SharedPreferences JSON mirror under "provider_config / config" is
    // still written on every save so older builds keep reading current
    // config — losing nothing on downgrade. See [loadConfig] for the
    // downgrade-and-back-up reconciliation logic.
    internal val providerDao: ProviderConfigDao =
        ProviderDatabase.getInstance(context).providerConfigDao()

    private val encryptedPrefs: SharedPreferences by lazy {
        // T-android-keystore-aead-fail: route through the self-healing
        // factory so a corrupted master key on Samsung One UI / Android
        // 16 doesn't crash the app at first read.
        val prefs = com.openminis.app.util.EncryptedPrefsFactory.safeCreate(context, "provider_secrets")
        // [T-keypool-orphan-cleanup] Key pools shipped in v2.0.20 and were
        // removed in 5b9aa6f; the pool-preferring read path went with them,
        // but the persisted `keypool_<instanceId>` blobs stayed in
        // provider_secrets forever — and an APK rolled BACK to v2.0.20
        // would happily read them again. No code reads these keys any
        // more; sweep them once per process (no-op after the first run).
        runCatching {
            val orphans = prefs.all.keys.filter { it.startsWith("keypool_") }
            if (orphans.isNotEmpty()) {
                prefs.edit().also { e -> orphans.forEach { e.remove(it) } }.apply()
                android.util.Log.i("ProviderRepo", "swept " + orphans.size + " orphan keypool_* entries")
            }
        }
        prefs
    }

    private val _credentialRevision = MutableStateFlow(0L); val credentialRevision: StateFlow<Long> = _credentialRevision.asStateFlow()

    // ── [T-android-save-off-main-thread] Single-threaded persistence pipeline ──
    //
    // `saveConfig` used to run the whole serialize → 5-table clear+bulk-upsert →
    // `prefs.edit().commit()` sequence inside `synchronized(configLock)` ON THE
    // CALLER THREAD. Every one of those callers is a Compose callback
    // (ui/settings/ModelGroupsScreen.kt slot picks, ui/onboarding/OnboardingScreen.kt),
    // so each model-slot pick paid a full config encode — [T-android-startup-
    // config-stall] #753 measured ~8 s for the decode of a large config on the
    // READ side — plus a synchronous disk commit. Writes now publish
    // in-memory under the lock and hand the durable part to ONE worker
    // coroutine, which keeps the serialisation [ProviderRepository.configLock]
    // bought (writes still never interleave, one snapshot at a time) without
    // blocking Main.
    //
    // FIFO is deliberate: a slot pick that landed after another must not
    // overwrite it in the store, so the queue is consumed by a single worker
    // rather than dispatched onto a multi-threaded pool.
    internal val persistRequests = kotlinx.coroutines.channels.Channel<ProviderPersistRequest>(
        capacity = kotlinx.coroutines.channels.Channel.UNLIMITED,
    )
    internal val writeScope = kotlinx.coroutines.CoroutineScope(
        kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO,
    )
    /** Monotonic id of the queued snapshot the worker is publishing. */
    internal val saveGeneration = java.util.concurrent.atomic.AtomicLong(0L)
    private val persistWorkerStarted = java.util.concurrent.atomic.AtomicBoolean(false)

    /** Starts the persistence worker on the first save. Idempotent. */
    internal fun ensurePersistWorker() {
        if (!persistWorkerStarted.compareAndSet(false, true)) return
        writeScope.launch { repo.drainPersistQueue() }
    }

    /**
     * Suspends until every save queued before this call has reached the store.
     * Backup/restore and any other "durability before returning" path should
     * await it — the fire-and-forget `saveConfig` no longer implies the write
     * landed.
     */
    internal suspend fun awaitPersisted() {
        val done = kotlinx.coroutines.CompletableDeferred<Unit>()
        persistRequests.send(ProviderPersistRequest.Flush(done))
        done.await()
    }

    /**
     * Blocking config read. [T-android-save-off-main-thread] caller audit (this is
     * the ONLY two call sites it has, and the audit is what decides whether the
     * `runBlocking` is allowed):
     *
     *  1. the `init { loadScope.launch { … } }` loader above — that scope is
     *     `SupervisorJob() + Dispatchers.IO`, so genuinely off-main;
     *  2. [ensureConfigLoaded], i.e. every non-suspend mutator/reader in this
     *     class (add/update/removeInstance, add/removeEntry, group + agent-loop +
     *     voice/vision slot setters, resolve*Candidates…). Those ARE reachable from
     *     the composition thread — ui/settings/AddProviderScreen.kt:640
     *     (`addInstance`), ProviderListScreen.kt:407/524,
     *     ProviderDetailScreen.kt:777 (`removeInstance`),
     *     ModelGroupsScreen.kt:402 / OnboardingModelSelectionScreen.kt:226
     *     (`addGroup`) all call it directly from a click/confirm lambda —
     *     alongside genuinely off-main ones (the config/collections adapters run on
     *     minis-config bridge's background pool, debug/ handlers,
     *     provider/agent paths).
     *
     * The stall is therefore real but BOUNDED to the cold-start window: the very
     * first line of [ensureConfigLoaded] returns the moment
     * [ProviderRepository._configLoaded] is
     * true, which the IO loader set during Application startup, so a main-thread
     * caller only pays the decode when it acts before that load lands. Removing
     * even that window means giving the public mutators themselves suspend
     * variants (and moving their Room/keystore work off the lock), which changes
     * the signature of every one of the ~18 Compose call sites listed above —
     * files outside this workstream's scope. Kept as-is on purpose; see the
     * matching note on [ensureConfigLoaded].
     */
    private fun loadConfig(): ProviderConfig = runBlocking { repo.loadConfigSuspending() }

    /**
     * Stable hash of the JSON mirror string, used as the in-DB synced-state
     * marker. SHA-256 hex so collisions are negligible. Returns null only
     * if [str] is null (caller normalizes).
     */
    internal fun hashJsonMirror(str: String): String {
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(str.toByteArray())
        return buildString(digest.size * 2) {
            for (b in digest) {
                val v = b.toInt() and 0xFF
                append(Character.forDigit(v shr 4, 16))
                append(Character.forDigit(v and 0xF, 16))
            }
        }
    }

    /**
     * [T-android-startup-config-stall] Guard for read-modify-write mutators.
     * If the async load hasn't applied yet, load synchronously NOW (on the
     * caller's thread) before the mutator reads the config StateFlow. Without
     * this a write that lands during the startup load window would read the
     * empty placeholder, mutate it, and persist — WIPING the user's real
     * config. Idempotent and cheap once loaded (just a volatile read).
     * Synchronized on [ProviderRepository.configLock] so it can't race the
     * async loader's emit.
     *
     * [T-android-save-off-main-thread] When the config is still unloaded this
     * blocks the CALLER thread through [loadConfig] — see that function's KDoc for
     * the caller audit and why the cold-start-only window is left as it is. Once
     * the initial load has landed this is a volatile read and costs nothing, so
     * main-thread mutators never wait on I/O after startup.
     */
    // internal (was private): the debug server's read-only provider.* handlers
    // read `config.value` directly and must be able to force the lazy load —
    // on a cold process they otherwise report an EMPTY config, which reads as
    // "all your providers are gone" rather than "not loaded yet".
    internal fun ensureConfigLoaded() {
        if (repo._configLoaded.value) return
        synchronized(repo.configLock) {
            if (repo._configLoaded.value) return
            // A read-modify-write operation must never proceed from the empty
            // placeholder when persisted data could not be read. Keep this
            // failure observable to each caller so it can abort before mutation.
            val loaded = try {
                loadConfig()
            } catch (e: Exception) {
                android.util.Log.e(
                    "ProviderRepo",
                    "[ProviderStore] ensureConfigLoaded failed; staying unloaded for retry: ${e.message}",
                )
                throw IllegalStateException("Provider configuration could not be loaded; mutation aborted", e)
            }
            repo._config.value = loaded
            repo._configLoaded.value = true
        }
        // [T-android-thinking-rules-phase2] Warm the resolver's custom-rule cache once
        // config is available, so the (sync) request builder can read user rules.
        repo.loadAllThinkingRulesIntoCache()
        if (!repo.configLoadComplete.isCompleted) repo.configLoadComplete.complete(Unit)
    }

    /**
     * The model entry id the user last actively selected (model picker tap) or
     * sent a message with. Persisted globally (not per-session) so a brand-new
     * chat can fall back to "the model I was just using" when no default group
     * is configured. Null until the user has picked / used a model at least
     * once. Mirrors iOS #636 lastUsedModelEntryId.
     */
    var lastUsedEntryId: String?
        get() = prefs.getString(ProviderRepository.KEY_LAST_USED_ENTRY, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(ProviderRepository.KEY_LAST_USED_ENTRY)
                else putString(ProviderRepository.KEY_LAST_USED_ENTRY, value)
            }.apply()
        }

    /**
     * [T-android-provider-voice] Reconcile voice-template seed entries on every
     * launch: add template models a previous build didn't know, remove RETIRED
     * seeds, and heal corrupted modality. Straight port of iOS
     * ProviderConfigStore.ensureVoiceTemplateModels with its two hard-won
     * guards:
     *
     *  - [T-voice-seed-shape-exact] Stale-seed removal matches ONLY the exact
     *    single-flag seed shape (isVoiceTemplateSeedShape). API-fetched models
     *    always carry the paired text side (≥2 flags), so a future API ASR/TTS
     *    model can never be mistaken for a seed and wiped every launch
     *    (regression class of cffbec0e: MiMo launch model count 15→11).
     *  - Heal: a models refresh can overwrite a template entry with text-only
     *    modality from /v1/models; restore the template's authoritative
     *    modality when it diverged.
     */
    fun ensureVoiceTemplateModels() = synchronized(repo.configLock) {
        repo.ensureConfigLoaded()
        val config = repo.workingCopy()
        var changed = false
        for (instance in config.instances) {
            val tpl = VoiceProviderTemplate.template(instance.customBaseURL) ?: continue
            val templateById = tpl.mockModels.associateBy { it.id }
            val instanceEntries = config.modelEntries.filter { it.providerInstanceId == instance.id }
            val existingIds = instanceEntries.map { it.baseModel.id }.toSet()
            // Remove stale template seeds (exact seed shape only) not in the
            // current template version.
            val staleSeedIds = instanceEntries
                .filter { it.baseModel.isVoiceTemplateSeedShape }
                .map { it.baseModel.id }
                .filter { it !in templateById }
            if (staleSeedIds.isNotEmpty()) {
                config.modelEntries.removeAll {
                    it.providerInstanceId == instance.id && it.baseModel.id in staleSeedIds
                }
                changed = true
                android.util.Log.i("ProviderRepo", "[VoiceTemplateMigrate] ${instance.label}: removed stale voice entries: ${staleSeedIds.sorted()}")
            }
            // Add template models missing from this instance.
            val toAdd = templateById.keys - existingIds
            if (toAdd.isNotEmpty()) {
                val newEntries = tpl.mockModels
                    .filter { it.id in toAdd }
                    .map { ModelEntry(providerInstanceId = instance.id, baseModel = it) }
                config.modelEntries.addAll(newEntries)
                changed = true
                android.util.Log.i("ProviderRepo", "[VoiceTemplateMigrate] ${instance.label}: added ${newEntries.size} new entries: ${toAdd.sorted()}")
            }
            // Heal corrupted modality on surviving template entries.
            for (i in config.modelEntries.indices) {
                val e = config.modelEntries[i]
                if (e.providerInstanceId != instance.id) continue
                val tplModel = templateById[e.baseModel.id] ?: continue
                if (e.baseModel.inputModalities == tplModel.inputModalities &&
                    e.baseModel.outputModalities == tplModel.outputModalities
                ) continue
                android.util.Log.i("ProviderRepo", "[VoiceTemplateMigrate] ${instance.label}: healing modality for ${e.baseModel.id}")
                config.modelEntries[i] = e.copy(baseModel = tplModel)
                changed = true
            }
        }
        if (changed) repo.saveConfig(config)
    }

    /**
     * Seeds every built-in keyless free lane on launch.
     *
     * Credentials live outside the config, so each seeded lane's key is written
     * here, not by the bootstrap. A lane the user deleted on purpose carries a
     * tombstone and stays gone - [restoreBuiltInFreeLane] is the way back. A
     * user-built provider at the same host with a real key is never touched.
     */
    internal fun seedBuiltInFreeLanes() = synchronized(repo.configLock) {
        if (!repo._configLoaded.value) return@synchronized
        val config = repo.workingCopy()
        val seeded = FreeLaneBootstrap.ensureSeeded(config) { loadApiKey(it) }
        if (seeded.isEmpty()) return@synchronized
        repo.saveConfig(config)
        seeded.forEach { if (it.apiKey.isNotEmpty()) saveApiKey(it.instanceId, it.apiKey) }
    }

    /**
     * Recovery channel for the built-in free lane: clears the user's deletion
     * tombstone, re-seeds the instance under its original id, and drops the
     * model-level tombstones recorded against it. Returns true when the config
     * changed, which is the caller's cue to refresh the catalogue.
     */
    internal fun restoreBuiltInFreeLane(builtin: BuiltinFreeLane): Boolean = synchronized(repo.configLock) {
        repo.ensureConfigLoaded()
        val config = repo.workingCopy()
        val done = FreeLaneBootstrap.restore(config, builtin) { loadApiKey(it) } ?: return@synchronized false
        repo.saveConfig(config)
        if (done.apiKey.isNotEmpty()) saveApiKey(done.instanceId, done.apiKey)
        true
    }

    /**
     * Whether [instance] points at a third-party OpenAI-compatible host
     * (xAI Grok, vLLM, Ollama, LiteLLM, DeepSeek via OpenAI shim, etc.).
     * For these instances we must never substitute `LLMModel.allOpenAI` as a
     * fallback / seed — those are GPT-only IDs that don't exist upstream.
     * Mirrors iOS `ProviderConfigStore.isThirdPartyOpenAICompat`.
     */
    internal fun isThirdPartyOpenAICompat(instance: ProviderInstance): Boolean {
        if (instance.providerType != ProviderType.openAI) return false
        val custom = instance.customBaseURL?.lowercase() ?: return false
        val officialHosts = listOf("api.openai.com", "chatgpt.com")
        return officialHosts.none { custom.contains(it) }
    }

    /**
     * [T-android-voice-panel] Explicit voice-input engine override picked in
     * the panel's model selector (mirrors iOS VoiceSelectionStore.inputEntryId).
     * Values: a System sentinel composite id ("<sentinel>/system-asr-online" /
     * "-offline"), a provider entry composite id, or null = follow the Voice
     * Input group binding. Per-device (prefs), not part of the synced config.
     */
    var voiceInputOverrideEntryId: String?
        get() = prefs.getString("voice.input.overrideEntryId", null)
        set(value) {
            prefs.edit().putString("voice.input.overrideEntryId", value).apply()
            synchronized(repo.configLock) {
                val config = repo._config.value
                repo._config.value = config.copy(revision = ProviderConfig.nextRevision())
            }
        }

    /**
     * Explicit voice-output voice override picked in a selector (mirrors iOS
     * VoiceSelectionStore.outputEntryId, and [voiceInputOverrideEntryId] on the
     * input side). Values: a System sentinel composite id
     * ("<sentinel>/system-tts"), a provider entry composite id, or null =
     * follow the Voice Output group binding. Per-device (prefs), not part of
     * the synced config.
     */
    var voiceOutputOverrideEntryId: String?
        get() = prefs.getString("voice.output.overrideEntryId", null)
        set(value) {
            prefs.edit().putString("voice.output.overrideEntryId", value).apply()
            synchronized(repo.configLock) {
                val config = repo._config.value
                repo._config.value = config.copy(revision = ProviderConfig.nextRevision())
            }
        }

    /**
     * [T-android-group-resolve-skip-uncredentialed] Whether [instance] has ANY
     * usable credential — API key, manual bearer, or a stored OAuth token.
     *
     * Mirrors iOS `ProviderInstance.hasAnyCredential`. The distinction that
     * matters here is OAuth: [usableApiKey] only reads `apikey_<id>`, so an
     * instance that is logged in via OAuth (Claude Code / Codex login) reads
     * as "no credential" to every `?: return`/`?: continue` call site even
     * though the chat path authenticates with it perfectly well. Routing must
     * not skip a provider the user is actually signed into.
     *
     * Deliberately synchronous and allocation-light: this runs inside model
     * selection on the main thread. It only probes credential STORAGE — it
     * never refreshes a token or performs I/O beyond an EncryptedSharedPrefs
     * read, so an expired-but-present token still counts as credentialed and
     * the refresh happens later on the request path (same as iOS).
     */
    fun hasAnyCredential(instance: ProviderInstance): Boolean {
        // API-key path, including the keyless-by-design compat endpoints that
        // [usableApiKey] already models via allowsEmptyAPIKey.
        if (usableApiKey(instance) != null) return true
        // OAuth path — token storage is keyed by instance id in the shared
        // oauth prefs, so this needs no OAuthManager instance. Reading the
        // prefs directly also covers provider types that
        // OAuthManager.forInstance deliberately omits (notably gemini),
        // which would otherwise be misreported as uncredentialed.
        // [T-oauth-keep-credentials] A token bundle whose refresh was rejected
        // is kept (never auto-deleted) but is not usable, so routing skips it
        // just as it skipped the deleted bundle before. (needsReauth is already
        // false when a manual bearer is stored — that credential still works.)
        if (com.openminis.app.auth.OAuthManager.needsReauth(context, instance.id)) return false
        return com.openminis.app.auth.OAuthManager.hasStoredCredential(context, instance.id)
    }

    // API Key management
    fun saveApiKey(instanceId: String, key: String) {
        encryptedPrefs.edit().putString("apikey_$instanceId", key).apply()
        _credentialRevision.value = _credentialRevision.value + 1
        // [T-android-provider-memo] The memo keys on the credential fingerprint;
        // a rotated token already yields a new entry. Drop this instance's
        // entries so stale-keyed providers can't linger past the cap —
        // [T-provider-factory-race] without touching unrelated instances.
        com.openminis.app.provider.ProviderFactory.invalidateInstance(instanceId)
    }

    fun loadApiKey(instanceId: String): String? {
        return encryptedPrefs.getString("apikey_$instanceId", null)
    }

    /**
     * [T-empty-key-compat-endpoints] The credential the ROUTING layers should
     * use: the stored key, or "" for instances where an empty key is a valid
     * configuration (third-party OpenAI/Anthropic-compatible endpoint — see
     * ProviderInstance.allowsEmptyAPIKey). Returns null only when the instance
     * genuinely has no usable credential, so existing `?: return/continue`
     * call sites keep their skip semantics for everything else (notably OAuth
     * instances without a token, which must stay unauthenticated).
     */
    // [T-usable-apikey-no-dead-param] The old `modelId: String = ""` was
    // never used — call sites kept passing it and readers assumed per-model
    // bucketing that does not exist.
    fun usableApiKey(instance: ProviderInstance): String? =
        loadApiKey(instance.id)
            ?: if (instance.allowsEmptyAPIKey) "" else null

    fun deleteApiKey(instanceId: String) {
        encryptedPrefs.edit().remove("apikey_$instanceId").apply()
        _credentialRevision.value = _credentialRevision.value + 1
        // [T-key-cache-invalidate] 删 key 同样要让 ProviderFactory 的
        // memo 缓存失效 —— 否则已构造的 provider 继续用旧 key 存活，
        // 删 key 后重加同实例仍是旧 key 生效。
        com.openminis.app.provider.ProviderFactory.invalidateInstance(instanceId)
    }
}
