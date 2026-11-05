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
import com.openminis.app.util.Digest
import com.openminis.app.provider.BuiltinFreeLane
import com.openminis.app.provider.BuiltinFreeLanes
import com.openminis.app.provider.FreeLaneHealth
import com.openminis.app.provider.FreeLaneManifest
import java.io.File
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

    // [T-credential-pool-x] Multi-account credential pool: N ≥ 2 API keys per
    // instance, round-robin pick + per-index cooldown. Storage lives in the
    // same encrypted provider_secrets prefs as the single key.
    private val credentialPool by lazy { ProviderCredentialPool(context) }

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
     *  1. the `init { loadScope.launch { … } }` loader in [ProviderRepository] —
     *     that scope is `SupervisorJob() + Dispatchers.IO`, so genuinely off-main;
     *  2. [ensureConfigLoaded] — and ONLY for callers that pass its main-thread
     *     guard. Since [Wave3-fix-1] [ensureConfigLoaded] refuses to load on the
     *     main thread (returns false instead), every remaining [loadConfig]
     *     caller is provably off-main (IO loader, debug/ handlers, config
     *     bridge's background pool, provider/agent paths), so the `runBlocking`
     *     here can no longer park a Compose frame through the ~8 s decode of
     *     [T-android-startup-config-stall] #753.
     */
    private fun loadConfig(): ProviderConfig = runBlocking { repo.loadConfigSuspending() }

    /**
     * Stable hash of the JSON mirror string, used as the in-DB synced-state
     * marker. SHA-256 hex so collisions are negligible. Returns null only
     * if [str] is null (caller normalizes).
     */
    internal fun hashJsonMirror(str: String): String = Digest.sha256Hex(str)

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
     * [Wave3-fix-1][T-android-save-off-main-thread] Returns **false** instead of
     * blocking when the caller is on the MAIN thread and the config is still
     * unloaded: the cold-start decode of a large config measured ~8 s (#753),
     * and ~18 Compose call sites (AddProviderScreen.kt:606/721/886,
     * ProviderListScreen.kt:407/524, ProviderDetailScreen.kt:777,
     * ModelGroupsScreen.kt:402, OnboardingModelSelectionScreen.kt:226, …) can
     * reach this from click/confirm lambdas inside that window. Making the
     * public mutators suspend would change every one of those signatures — files
     * outside the data layer — so instead the main-thread caller is told to
     * ABORT: the mutator logs and refuses to write, which matches the existing
     * anti-wipe refusal style (a refused write is always safer than a write
     * against an unverified empty placeholder). Once the initial load has
     * landed this is a volatile read that returns true on any thread. Off-main
     * callers keep the old behaviour: block until the load lands (debug/
     * handlers and the config bridge NEED that force-load on a cold process,
     * where the async loader may not have run yet).
     *
     * @return true when the config is loaded and the caller may proceed with
     *   its read-modify-write; false ONLY in the main-thread cold-start window —
     *   the caller must abort the mutation (log + no-op), never write the
     *   empty placeholder. Throws when the load itself failed (store unreadable).
     */
    // internal (was private): the debug server's read-only provider.* handlers
    // read `config.value` directly and must be able to force the lazy load —
    // on a cold process they otherwise report an EMPTY config, which reads as
    // "all your providers are gone" rather than "not loaded yet". Those run
    // off-main, so they still get the force-load.
    internal fun ensureConfigLoaded(): Boolean {
        if (repo._configLoaded.value) return true
        if (isOnMainThread()) {
            // [Wave3-fix-1] Refuse instead of runBlocking: never park Main for
            // the startup decode, and never let a mutation run against the
            // empty placeholder. The async loader will set _configLoaded
            // momentarily; the user's click is a no-op this once.
            android.util.Log.w(
                "ProviderRepo",
                "[ProviderStore] ensureConfigLoaded: config not loaded yet and caller is on Main — " +
                    "refusing the mutation instead of runBlocking the load; retry after the async load lands",
            )
            return false
        }
        synchronized(repo.configLock) {
            if (repo._configLoaded.value) return true
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
        return true
    }

    /**
     * True only when a REAL main Looper exists and the caller is on it.
     *
     * The null/failure guards matter off-device: JVM unit tests run with
     * `unitTests.isReturnDefaultValues = true`, where both Looper accessors
     * return null — a naive `myLooper() == mainLooper` would read `null == null`
     * as "on Main" and silently turn every mutator into a no-op in tests. A
     * missing main Looper therefore means "not Main" (off-device, or a thread
     * on a bare JVM), which restores the legacy blocking behaviour there.
     */
    private fun isOnMainThread(): Boolean = try {
        val main = android.os.Looper.getMainLooper() ?: return false
        android.os.Looper.myLooper() == main
    } catch (_: Throwable) {
        false
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
        // [Wave3-fix-1] Main-thread cold-start window: refuse the reconciliation
        // (a no-op) instead of runBlocking the ~8s decode. The init loader on
        // Dispatchers.IO re-runs it right after the load lands.
        if (!repo.ensureConfigLoaded()) return@synchronized
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
     * Removes the provider rows of built-in lanes this build no longer ships
     * ([BuiltinLaneRetirement]), and reclaims what those lanes left on disk.
     *
     * Why a boot pass and not a one-off script: the descriptor leaves
     * [BuiltinFreeLanes.ALL] with the code, so an install that used such a lane
     * keeps a provider row pointed at a loopback port nothing binds — every
     * refresh fails, the model picker offers models that cannot answer, and the
     * only way out is deleting it by hand. The row is recognised by id only,
     * because everything else about the lane is already gone.
     *
     * The removal goes through [ProviderRepository.removeInstance] rather than
     * hand-rolled list surgery: that call already carries the full cleanup
     * (model rows, group members, agent-loop membership, tombstones,
     * credentials, OAuth tokens, the voice-shadow flag, the last-used pointer,
     * thinking rules). A second implementation is how those bugs come back.
     */
    internal fun purgeRetiredBuiltInLanes(
        activeInstanceIds: Set<String> = BuiltinFreeLanes.ALL_INSTANCE_IDS,
    ) {
        val doomed = synchronized(repo.configLock) {
            if (!repo.ensureConfigLoaded()) return@synchronized emptyList()
            BuiltinLaneRetirement.instancesToPurge(repo.workingCopy(), activeInstanceIds)
        }
        doomed.forEach { runCatching { repo.removeInstance(it) } }
        purgeRetiredBuiltInLaneFiles()
    }

    /**
     * Deletes the retired lane's deployed payload and its preference store.
     *
     * The payload is the expensive half — a glibc runtime and two upstream packs
     * (~15 MiB) under `<filesDir>/builtin-plugins/a2a` — and it outlives the row:
     * a user who started the gateway once but never added the provider instance
     * still carries it. Both steps are idempotent and cheap on every later boot
     * (a missing directory and an empty prefs map), so no "already purged" flag
     * has to be kept in sync with reality.
     */
    private fun purgeRetiredBuiltInLaneFiles() {
        runCatching { File(context.filesDir, "builtin-plugins/a2a").deleteRecursively() }
        runCatching {
            val legacy = context.getSharedPreferences("a2a_gateway", Context.MODE_PRIVATE)
            if (legacy.all.isNotEmpty()) legacy.edit().clear().apply()
        }
    }

    /**
     * Seeds every built-in keyless free lane on launch — the compiled-in set
     * plus whatever the manifest ([FreeLaneManifest]) synthesises. A lane
     * introduced or dropped by the manifest is therefore a data change, not a
     * release.
     *
     * Credentials live outside the config, so each seeded lane's key is written
     * here, not by the bootstrap. A lane the user deleted on purpose carries a
     * tombstone and stays gone - [restoreBuiltInFreeLane] is the way back. A
     * user-built provider at the same host with a real key is never touched.
     */
    internal fun seedBuiltInFreeLanes() = synchronized(repo.configLock) {
        if (!repo._configLoaded.value) return@synchronized
        val manifest = FreeLaneManifest.load(context)
        val lanes = BuiltinFreeLanes.fromManifest(manifest.entries)
        // The active set is what retirement vetoes against. It must reflect a
        // manifest that actually ruled — when the remote copy failed to parse,
        // the persisted snapshot keeps manifest lanes alive; when it parsed,
        // lanes it dropped leave the set and retire on this very purge.
        val activeIds = lanes.map { it.instanceId }.toSet() +
            if (!manifest.remoteOk) FreeLaneManifest.loadActiveIds(context) else emptySet()
        // Retirement first, seeding second: a build that dropped a lane must
        // delete its stale row before the seed pass decides what is missing.
        purgeRetiredBuiltInLanes(activeIds)
        // Health scopes for manifest lanes so a restart never scrubs their
        // evidence as "a lane nothing ships any more".
        FreeLaneHealth.registerLanes(lanes.map { it.lane.id }.toSet())
        val config = repo.workingCopy()
        val seeded = FreeLaneBootstrap.ensureSeeded(config, lanes) { loadApiKey(it) }
        FreeLaneManifest.saveActiveIds(context, activeIds)
        if (seeded.isEmpty()) return@synchronized
        repo.saveConfig(config)
        seeded.forEach { if (it.apiKey.isNotEmpty()) saveApiKey(it.instanceId, it.apiKey) }
    }

    /**
     * Persists a refreshed manifest (only when it parses) and re-runs the seed
     * pass so a lane the refresh introduces is live without a restart, and a
     * lane it drops retires on the next boot. Returns true when the payload was
     * accepted; a refused payload changes nothing at all.
     */
    internal fun installRefreshedFreeLaneManifest(json: String): Boolean = synchronized(repo.configLock) {
        if (!FreeLaneManifest.saveRefreshed(context, json)) return@synchronized false
        seedBuiltInFreeLanes()
        true
    }

    /**
     * Recovery channel for the built-in free lane: clears the user's deletion
     * tombstone, re-seeds the instance under its original id, and drops the
     * model-level tombstones recorded against it. Returns true when the config
     * changed, which is the caller's cue to refresh the catalogue.
     */
    internal fun restoreBuiltInFreeLane(builtin: BuiltinFreeLane): Boolean = synchronized(repo.configLock) {
        // [Wave3-fix-1] Refused in the main-thread cold-start window: report
        // "not restored" instead of runBlocking the load.
        if (!repo.ensureConfigLoaded()) return@synchronized false
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
        // [T-credential-pool-x] Slot 0 of the pool IS the primary key — an
        // edit of the main key must propagate so the pool keeps rotating
        // over what the user actually configured.
        val currentPool = credentialPool.keys(instanceId)
        if (currentPool.isNotEmpty() && currentPool[0] != key) {
            credentialPool.setKeys(instanceId, listOf(key) + currentPool.drop(1))
        }
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
        // [T-credential-pool-x] Deleting the credential wipes the whole
        // pool — a "clear" is a clear, not a partial removal.
        credentialPool.setKeys(instanceId, emptyList())
        _credentialRevision.value = _credentialRevision.value + 1
        // [T-key-cache-invalidate] 删 key 同样要让 ProviderFactory 的
        // memo 缓存失效 —— 否则已构造的 provider 继续用旧 key 存活，
        // 删 key 后重加同实例仍是旧 key 生效。
        com.openminis.app.provider.ProviderFactory.invalidateInstance(instanceId)
    }

    // ── [T-credential-pool-x] Multi-account credential pool API ─────────

    /** The pooled keys for [instanceId] in order (empty when pool off). */
    fun poolKeys(instanceId: String): List<String> = credentialPool.keys(instanceId)

    /**
     * Add [key] as an extra account.  The first add seeds slot 0 from the
     * legacy single key.  Duplicates and pools that would stay below the
     * 2-key minimum are rejected (false).  Successful adds bump the
     * credential revision and invalidate the instance memo so the next
     * request builds a provider from a fresh rotation.
     */
    fun addPoolKey(instanceId: String, key: String): Boolean {
        if (key.isBlank()) return false
        val current = credentialPool.keys(instanceId)
        val next = poolAfterAdd(current, loadApiKey(instanceId), key)
        if (next.size < 2) return false
        credentialPool.setKeys(instanceId, next)
        _credentialRevision.value = _credentialRevision.value + 1
        com.openminis.app.provider.ProviderFactory.invalidateInstance(instanceId)
        return true
    }

    /**
     * Remove pooled key [index].  Collapsing below 2 keys clears the pool
     * and the survivor (when any) is promoted into the primary slot.
     */
    fun removePoolKey(instanceId: String, index: Int): Boolean {
        val current = credentialPool.keys(instanceId)
        if (index !in current.indices) return false
        val removal = poolAfterRemove(current, index)
        credentialPool.setKeys(instanceId, removal.keys)
        removal.primaryOverride?.let {
            encryptedPrefs.edit().putString("apikey_$instanceId", it).apply()
        }
        _credentialRevision.value = _credentialRevision.value + 1
        com.openminis.app.provider.ProviderFactory.invalidateInstance(instanceId)
        return true
    }

    /**
     * [T-credential-pool-x] Request the credential the routing layers should
     * use for [instance]: (key, index) from the rotation pool when active,
     * otherwise the legacy single key with index -1.  Null only when the
     * instance has no usable credential at all (existing skip semantics).
     */
    fun requestCredential(instance: ProviderInstance): Pair<String, Int>? {
        if (instance.credentialType == com.openminis.app.data.model.ProviderCredential.apiKey) {
            val pooled = credentialPool.pick(instance.id)
            if (pooled != null) return pooled
        }
        return usableApiKey(instance)?.let { it to -1 }
    }

    /**
     * [T-credential-pool-x] Error-path cooldown: park the key this instance
     * last served with (per [ProviderCredentialPool.lastPick]) and, when the
     * instance declares a [com.openminis.app.data.model.ProviderInstance.quotaGroupId],
     * the SAME slot of every sibling instance in that group — a sibling is
     * known to be out of quota / revoked too, so routing to it would burn a
     * request that fails the same way.
     */
    fun markCredentialCooled(instanceId: String, error: com.openminis.app.data.model.LLMError) {
        val coolFor = credentialCooldownFor(error) ?: return
        val lastIndex = credentialPool.lastPick(instanceId) ?: return
        credentialPool.markCooled(instanceId, lastIndex, System.currentTimeMillis() + coolFor)
        val instance = repo.instance(instanceId) ?: return
        val quotaGroup = instance.quotaGroupId?.takeIf { it.isNotBlank() } ?: return
        for (other in repo.config.value.instances) {
            if (other.id == instanceId) continue
            if (other.quotaGroupId != quotaGroup) continue
            credentialPool.markCooled(other.id, lastIndex, System.currentTimeMillis() + coolFor)
        }
    }
}
