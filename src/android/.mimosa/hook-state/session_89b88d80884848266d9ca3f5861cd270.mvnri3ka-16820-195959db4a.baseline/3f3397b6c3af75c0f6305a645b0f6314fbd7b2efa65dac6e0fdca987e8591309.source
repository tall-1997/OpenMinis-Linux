package com.openminis.app.data.repository

import android.content.SharedPreferences
import com.openminis.app.data.model.ModelEntry
import com.openminis.app.data.model.ModelGroup
import com.openminis.app.data.model.ProviderConfig
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.RoutingStrategy
import com.openminis.app.data.model.SystemVoiceIds
import com.openminis.app.data.model.hasAudioInput
import com.openminis.app.data.model.hasAudioOutput
import com.openminis.app.data.model.hasImageInput
import com.openminis.app.data.model.hasVoiceModality
import com.openminis.app.data.repository.ProviderRepository.ShadowVoiceProvider
import com.openminis.app.data.repository.ProviderRepository.ShadowVoiceSnapshot
import com.openminis.app.data.repository.ProviderRepository.VoiceInputChoice
import com.openminis.app.data.repository.ProviderRepository.VoiceOutputChoice

/**
 * Model-grouping / slot-resolution slice of [ProviderRepository], split out of
 * it verbatim. Covers the agent-loop composition (direct entry pins + group
 * pins, their read/write/reorder helpers and the effective-entry resolution),
 * the model-group CRUD and reorder rules, the default/voice/vision/compact
 * slot bindings, vision + compact-fallback + voice candidate resolution, and
 * the Shadow-Voice runtime mirror. [ProviderRepository] constructs this class,
 * holds it privately, and keeps every previously-public member reachable as a
 * one-line delegation — external callers are unchanged.
 *
 * The agent-loop set was folded into this class instead of standing alone as
 * its own collaborator: its natural body (~110 lines, all of it sharing the
 * group-member expansion + workingCopy/save machinery) sits far below the
 * 400-line collaborator floor, and `resolvedAgentLoopEntries` is literally a
 * group-member resolution — the same job as this class's candidate resolvers.
 *
 * Shared state is explicit: the in-memory config, its lock, the lazy-load
 * guard and the persistence pipeline stay on [ProviderRepository] and are
 * reached through the [repo] constructor reference; the per-device prefs
 * handle (voice shadow flag) is passed in as its own constructor parameter.
 * No static mutable state.
 */
internal class ModelGrouping(
    private val repo: ProviderRepository,
    private val prefs: SharedPreferences,
) {

    /** Set the agent-loop-visible entry ID list (individual model entries). */
    fun setAgentLoopEntryIds(ids: List<String>): Unit = synchronized(repo.configLock) {
        repo.ensureConfigLoaded()
        val config = repo.workingCopy()
        config.agentLoopModelEntryIds.clear()
        // [T-android-agentloop-dup-key-crash] Dedup at the sink (preserving
        // first-seen order). addAgentLoopEntry guards against dups, but any
        // path writing straight through this sink (cross-device sync merge,
        // reorder write-back, data migration) could otherwise persist a
        // duplicate id — which surfaces downstream as two pinnedEntries with
        // the same id, a duplicate LazyColumn key, and a crash on scroll.
        config.agentLoopModelEntryIds.addAll(ids.distinct())
        repo.saveConfig(config)
    }

    /** Set the agent-loop-visible group ID list. */
    fun setAgentLoopGroupIds(ids: List<String>): Unit = synchronized(repo.configLock) {
        repo.ensureConfigLoaded()
        val config = repo.workingCopy()
        config.agentLoopGroupIds.clear()
        // [T-android-agentloop-dup-key-crash] Dedup at the sink (see above) so
        // no write path can persist duplicate group ids → duplicate LazyColumn
        // key on the agent-loop groups list.
        config.agentLoopGroupIds.addAll(ids.distinct())
        repo.saveConfig(config)
    }

    // T182: thin add/remove helpers used by AgentLoopModelsSection +
    // AddAgentLoopModelsSheet / AddAgentLoopGroupsSheet. These wrap the
    // set* functions so caller doesn't have to round-trip through the
    // existing list (mutating in place would race with a parallel
    // saveConfig). Keep insertion order — preserves the order the user
    // added items in the picker, mirrors iOS appendIfNeeded behaviour.

    /** Append [entryId] to the agent-loop direct-pin list if not already there. */
    fun addAgentLoopEntry(entryId: String) {
        repo.ensureConfigLoaded()
        val cur = repo._config.value.agentLoopModelEntryIds.toList()
        if (entryId in cur) return
        setAgentLoopEntryIds(cur + entryId)
    }

    /** Remove [entryId] from the agent-loop direct-pin list. No-op if absent. */
    fun removeAgentLoopEntry(entryId: String) {
        repo.ensureConfigLoaded()
        val cur = repo._config.value.agentLoopModelEntryIds.toList()
        if (entryId !in cur) return
        setAgentLoopEntryIds(cur.filterNot { it == entryId })
    }

    /** Append [groupId] to the agent-loop group-pin list if not already there. */
    fun addAgentLoopGroup(groupId: String) {
        repo.ensureConfigLoaded()
        val cur = repo._config.value.agentLoopGroupIds.toList()
        if (groupId in cur) return
        setAgentLoopGroupIds(cur + groupId)
    }

    /** Remove [groupId] from the agent-loop group-pin list. No-op if absent. */
    fun removeAgentLoopGroup(groupId: String) {
        repo.ensureConfigLoaded()
        val cur = repo._config.value.agentLoopGroupIds.toList()
        if (groupId !in cur) return
        setAgentLoopGroupIds(cur.filterNot { it == groupId })
    }

    // T186: reorder helpers — UI keeps a local mutable copy during
    // drag and pushes the final order through here on drop. Validates
    // that [newOrder] is a permutation of the current list to defend
    // against a stale dragged-from-different-snapshot reorder slipping
    // in mid-write (e.g. a cascade-cleanup raced with the drag).
    fun reorderAgentLoopEntries(newOrder: List<String>) {
        val cur = repo._config.value.agentLoopModelEntryIds.toSet()
        if (newOrder.toSet() != cur) return
        setAgentLoopEntryIds(newOrder)
    }

    fun reorderAgentLoopGroups(newOrder: List<String>) {
        val cur = repo._config.value.agentLoopGroupIds.toSet()
        if (newOrder.toSet() != cur) return
        setAgentLoopGroupIds(newOrder)
    }

    /**
     * Resolve the effective model entries visible to the agent loop (minis-model-use).
     * Expands groups to their members, unions with individual entries, dedupes by ID,
     * and filters to entries of enabled provider instances. Mirrors iOS
     * ProviderConfigStore.resolvedAgentLoopEntries.
     */
    fun resolvedAgentLoopEntries(): List<ModelEntry> {
        val config = repo._config.value
        val enabledIds = config.instances.filter { it.isEnabled }.map { it.id }.toSet()
        val seen = mutableSetOf<String>()
        val out = mutableListOf<ModelEntry>()

        fun consider(entry: ModelEntry) {
            if (entry.providerInstanceId !in enabledIds) return
            if (!seen.add(entry.id)) return
            out.add(entry)
        }

        // Individual entries first (preserves the order the user arranged in UI)
        for (id in config.agentLoopModelEntryIds) {
            config.modelEntries.find { it.id == id }?.let(::consider)
        }
        // Then group members, in group order
        for (gid in config.agentLoopGroupIds) {
            val group = config.modelGroups.find { it.id == gid } ?: continue
            for (memberId in group.memberEntryIds) {
                config.modelEntries.find { it.id == memberId }?.let(::consider)
            }
        }
        return out
    }


// ================ Moved from ProviderRepository (step 4): groups, slots, vision, voice, shadow ================

    // --- Model Group management ---

    fun addGroup(group: ModelGroup): Unit = synchronized(repo.configLock) {
        repo.ensureConfigLoaded()
        val config = repo.workingCopy()
        config.modelGroups.add(group)
        repo.saveConfig(config)
    }

    fun updateGroup(group: ModelGroup): Unit = synchronized(repo.configLock) {
        repo.ensureConfigLoaded()
        val config = repo.workingCopy()
        val idx = config.modelGroups.indexOfFirst { it.id == group.id }
        if (idx >= 0) {
            // Defensive re-wrap: callers routinely pass `published.copy(...)`,
            // and data-class copy() shares memberEntryIds BY REFERENCE with the
            // published group. Storing that object would put published inner
            // state back inside a working copy, quietly undoing the invariant.
            config.modelGroups[idx] = group.copy(
                memberEntryIds = group.memberEntryIds.toMutableList(),
            )
            repo.saveConfig(config)
        }
    }

    fun removeGroup(groupId: String): Unit = synchronized(repo.configLock) {
        repo.ensureConfigLoaded()
        val config = repo.workingCopy()
        config.modelGroups.removeAll { it.id == groupId }
        if (config.defaultPrimaryGroupId == groupId) {
            config.defaultPrimaryGroupId = null
        }
        if (config.defaultSubGroupId == groupId) {
            config.defaultSubGroupId = null
        }
        if (config.voiceInputGroupId == groupId) {
            config.voiceInputGroupId = null
        }
        if (config.voiceOutputGroupId == groupId) {
            config.voiceOutputGroupId = null
        }
        if (config.visionGroupId == groupId) {
            config.visionGroupId = null
        }
        if (config.compactFallbackGroupId == groupId) {
            config.compactFallbackGroupId = null
        }
        config.agentLoopGroupIds.removeAll { it == groupId }
        repo.saveConfig(config)
    }


    /**
     * [T-android-modelgroup-reorder] Reorder the user's Model Groups
     * (drag-to-sort in the Model Groups screen). Mirrors iOS
     * `ProviderConfigStore.reorderGroups` (4ba54ff5) and follows the exact
     * contract of [reorderInstances] above: unknown ids dropped, duplicates
     * collapsed, unmentioned groups appended in their existing relative order,
     * no-op guard, and the list mutation held under [configLock] — same
     * CME/empty-window rationale as documented on reorderInstances
     * ([T-android-reorder-unlocked-mutation]).
     *
     * Persistence needs nothing extra: ProviderConfigMapping writes each
     * group's `sort_order` from its list index at save time and the DAO reads
     * `ORDER BY sort_order ASC`, so list position IS the stored order.
     */
    fun reorderModelGroups(newOrder: List<String>): Unit = synchronized(repo.configLock) {
        repo.ensureConfigLoaded()
        val config = repo.workingCopy()
        val current = config.modelGroups.toList()
        if (current.isEmpty()) return

        val byId = current.associateBy { it.id }
        val seen = LinkedHashSet<String>()
        val reordered = ArrayList<ModelGroup>(current.size)
        for (id in newOrder) {
            val group = byId[id] ?: continue     // drop unknown ids
            if (!seen.add(id)) continue          // drop duplicates
            reordered.add(group)
        }
        // Anything the caller didn't mention keeps its existing relative order.
        for (group in current) {
            if (seen.add(group.id)) reordered.add(group)
        }

        // No-op guard: skip the DB write + StateFlow churn when nothing moved.
        if (reordered.map { it.id } == current.map { it.id }) return

        synchronized(repo.configLock) {
            config.modelGroups.clear()
            config.modelGroups.addAll(reordered)
            repo.saveConfig(config)
        }
    }

    fun group(id: String): ModelGroup? =
        repo._config.value.modelGroups.find { it.id == id }

    var defaultPrimaryGroupId: String?
        get() = repo._config.value.defaultPrimaryGroupId
        set(value) = synchronized(repo.configLock) {
            repo.ensureConfigLoaded()
            val config = repo.workingCopy()
            config.defaultPrimaryGroupId = value
            repo.saveConfig(config)
        }

    var defaultSubGroupId: String?
        get() = repo._config.value.defaultSubGroupId
        set(value) = synchronized(repo.configLock) {
            repo.ensureConfigLoaded()
            val config = repo.workingCopy()
            config.defaultSubGroupId = value
            repo.saveConfig(config)
        }

    // --- Voice groups [T-android-provider-voice] ---

    var voiceInputGroupId: String?
        get() = repo._config.value.voiceInputGroupId
        set(value) = synchronized(repo.configLock) {
            repo.ensureConfigLoaded()
            val config = repo.workingCopy()
            config.voiceInputGroupId = value
            repo.saveConfig(config)
        }

    var voiceOutputGroupId: String?
        get() = repo._config.value.voiceOutputGroupId
        set(value) = synchronized(repo.configLock) {
            repo.ensureConfigLoaded()
            val config = repo.workingCopy()
            config.voiceOutputGroupId = value
            repo.saveConfig(config)
        }

    // --- Vision group [T-android-vision-group / GH#182] ---

    var visionGroupId: String?
        get() = repo._config.value.visionGroupId
        set(value) = synchronized(repo.configLock) {
            repo.ensureConfigLoaded()
            val config = repo.workingCopy()
            config.visionGroupId = value
            repo.saveConfig(config)
        }

    var compactFallbackGroupId: String?
        get() = repo._config.value.compactFallbackGroupId
        set(value) = synchronized(repo.configLock) {
            repo.ensureConfigLoaded()
            val config = repo.workingCopy()
            config.compactFallbackGroupId = value
            repo.saveConfig(config)
        }

    /** True when a Vision Group is bound AND still exists. Gates read_image
     *  tool exposure for main models that cannot natively see images. */
    fun hasVisionGroupConfigured(): Boolean {
        val gid = repo._config.value.visionGroupId ?: return false
        val pinned = com.openminis.app.data.model.ModelSlotRef.entryId(gid)
        if (pinned != null) {
            val entry = repo._config.value.modelEntries.find { it.id == pinned } ?: return false
            val inst = repo._config.value.instances.find { it.id == entry.providerInstanceId } ?: return false
            return inst.isEnabled && entry.model.hasImageInput
        }
        return repo._config.value.modelGroups.any { it.id == gid }
    }

    /** Bound Vision group's display name, or the pinned model's name. */
    fun visionGroupName(): String? = slotDisplayName(repo._config.value.visionGroupId)

    /** Group name, or the pinned model's display name when the slot is `entry:`. */
    fun slotDisplayName(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val pinned = com.openminis.app.data.model.ModelSlotRef.entryId(raw)
        if (pinned != null) {
            return repo._config.value.modelEntries.find { it.id == pinned }?.model?.displayName
        }
        return repo._config.value.modelGroups.find { it.id == raw }?.name
    }

    /**
     * [T-android-vision-group] Ordered vision-capable fail-over candidates from
     * the bound Vision Group. Mirrors resolveVoiceInputCandidates: filters
     * members to enabled instances whose model declares image input, honours
     * the group's routing strategy (`fallback` keeps order; `loadBalance`
     * rotates the start by [loadBalanceSeed] so separate reads spread across
     * members). Returns [] when no group is bound or no member is usable — the
     * caller (ReadImageTool) then returns a clear failure text.
     */
    fun resolveVisionCandidates(loadBalanceSeed: Int = 0): List<Pair<ProviderInstance, ModelEntry>> {
        repo.ensureConfigLoaded()
        val config = repo._config.value

        fun providerEntry(memberId: String): Pair<ProviderInstance, ModelEntry>? {
            val entry = config.modelEntries.find { it.id == memberId } ?: return null
            val inst = config.instances.find { it.id == entry.providerInstanceId } ?: return null
            if (!inst.isEnabled || !entry.model.hasImageInput) return null
            return inst to entry
        }

        val gid = config.visionGroupId ?: return emptyList()
        val pinned = com.openminis.app.data.model.ModelSlotRef.entryId(gid)
        if (pinned != null) return listOfNotNull(providerEntry(pinned))
        val group = config.modelGroups.find { it.id == gid } ?: return emptyList()
        var members = group.memberEntryIds.mapNotNull { providerEntry(it) }
        if (group.strategy == RoutingStrategy.loadBalance && members.size > 1) {
            val offset = kotlin.math.abs(loadBalanceSeed) % members.size
            members = members.drop(offset) + members.take(offset)
        }
        val out = mutableListOf<Pair<ProviderInstance, ModelEntry>>()
        for (m in members) {
            if (out.none { it.second.id == m.second.id }) out.add(m)
        }
        return out
    }

    /**
     * Compact fallback candidates from Defaults → Compact fallback.
     * Pinned entry or group members; skips disabled instances. The compact
     * path still prefers the current session model and only walks this list
     * after that model fails.
     */
    fun resolveCompactFallbackCandidates(): List<Pair<ProviderInstance, ModelEntry>> {
        repo.ensureConfigLoaded()
        val config = repo._config.value
        fun providerEntry(memberId: String): Pair<ProviderInstance, ModelEntry>? {
            val entry = config.modelEntries.find { it.id == memberId } ?: return null
            val inst = config.instances.find { it.id == entry.providerInstanceId } ?: return null
            if (!inst.isEnabled) return null
            return inst to entry
        }
        val gid = config.compactFallbackGroupId ?: return emptyList()
        val pinned = com.openminis.app.data.model.ModelSlotRef.entryId(gid)
        if (pinned != null) return listOfNotNull(providerEntry(pinned))
        val group = config.modelGroups.find { it.id == gid } ?: return emptyList()
        val out = mutableListOf<Pair<ProviderInstance, ModelEntry>>()
        for (id in group.memberEntryIds) {
            val m = providerEntry(id) ?: continue
            if (out.none { it.second.id == m.second.id }) out.add(m)
        }
        return out
    }


    /**
     * Ensure a default Voice INPUT group exists and is bound when the user
     * hasn't configured one. Seeds "Voice Input" with the System ASR sentinel
     * entries (device SpeechRecognizer online/offline). Sentinel composite ids
     * match iOS so a config moved cross-platform keeps its selection. No-op
     * when a valid binding already exists. Mirrors iOS
     * ProviderConfigStore.ensureDefaultVoiceInputGroup.
     */
    fun ensureDefaultVoiceInputGroup(): String? = synchronized(repo.configLock) {
        repo.ensureConfigLoaded()
        val config = repo.workingCopy()
        config.voiceInputGroupId?.let { gid ->
            if (config.modelGroups.any { it.id == gid }) return gid
        }
        val sentinel = SystemVoiceIds.BUILTIN_PROVIDER_ID
        val group = ModelGroup(
            name = "Voice Input",
            memberEntryIds = mutableListOf(
                "$sentinel/${SystemVoiceIds.SYSTEM_ASR_ONLINE}",
                "$sentinel/${SystemVoiceIds.SYSTEM_ASR_OFFLINE}",
            ),
        )
        config.modelGroups.add(group)
        config.voiceInputGroupId = group.id
        repo.saveConfig(config)
        android.util.Log.i("ProviderRepo", "[Voice] auto-created default Voice Input group ${group.id.take(8)} [System ASR online+offline]")
        return group.id
    }

    /**
     * Ensure a default Voice OUTPUT group exists and is bound. Seeds
     * "Voice Output" with the System TTS auto sentinel (device TextToSpeech,
     * best voice per reply language). Mirrors iOS ensureDefaultVoiceOutputGroup.
     */
    fun ensureDefaultVoiceOutputGroup(): String? = synchronized(repo.configLock) {
        repo.ensureConfigLoaded()
        val config = repo.workingCopy()
        config.voiceOutputGroupId?.let { gid ->
            if (config.modelGroups.any { it.id == gid }) return gid
        }
        val sentinel = SystemVoiceIds.BUILTIN_PROVIDER_ID
        val group = ModelGroup(
            name = "Voice Output",
            memberEntryIds = mutableListOf("$sentinel/${SystemVoiceIds.SYSTEM_TTS}"),
        )
        config.modelGroups.add(group)
        config.voiceOutputGroupId = group.id
        repo.saveConfig(config)
        android.util.Log.i("ProviderRepo", "[Voice] auto-created default Voice Output group ${group.id.take(8)} [System Voice (Auto)]")
        return group.id
    }


    fun resolveVoiceInputChoice(): VoiceInputChoice {
        repo.ensureConfigLoaded()
        val config = repo._config.value

        fun systemChoice(memberId: String): VoiceInputChoice = VoiceInputChoice(
            systemPreferOffline = memberId.endsWith("/${SystemVoiceIds.SYSTEM_ASR_OFFLINE}"),
            entry = null,
        )

        fun providerEntry(memberId: String): Pair<ProviderInstance, ModelEntry>? {
            val entry = config.modelEntries.find { it.id == memberId } ?: return null
            val inst = config.instances.find { it.id == entry.providerInstanceId } ?: return null
            if (!inst.isEnabled || !entry.model.hasAudioInput) return null
            return inst to entry
        }

        repo.voiceInputOverrideEntryId?.let { override ->
            if (override.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) return systemChoice(override)
            providerEntry(override)?.let { return VoiceInputChoice(null, it) }
            // Stale override (entry removed) — fall through to the group.
        }
        val gid = config.voiceInputGroupId
        val pinnedVoice = com.openminis.app.data.model.ModelSlotRef.entryId(gid)
        if (pinnedVoice != null) {
            providerEntry(pinnedVoice)?.let { return VoiceInputChoice(null, it) }
            return VoiceInputChoice(systemPreferOffline = null, entry = null)
        }
        val group = gid?.let { g -> config.modelGroups.find { it.id == g } }
        if (group != null) {
            for (memberId in group.memberEntryIds) {
                if (memberId.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) return systemChoice(memberId)
                providerEntry(memberId)?.let { return VoiceInputChoice(null, it) }
            }
        }
        return VoiceInputChoice(systemPreferOffline = null, entry = null)
    }


    /** Bound Voice Input group's display name, or null (chip's "Group · Model"). */
    fun voiceInputGroupName(): String? = slotDisplayName(repo._config.value.voiceInputGroupId)

    /**
     * [T-android-provider-voice] Resolve the current provider-backed voice
     * INPUT selection: honours the explicit override first, then walks the
     * Voice Input group's members in order (fallback semantics). System
     * sentinel members are skipped — they are served by
     * SystemSpeechRecognitionEngine, not a cloud provider.
     */
    fun resolveVoiceInputEntry(): Pair<ProviderInstance, ModelEntry>? =
        resolveVoiceInputChoice().entry


    /**
     * [T-voice-asr-group-failover] Ordered ASR fail-over candidates: the
     * explicit override first (if usable and provider-backed), then every
     * usable member of the Voice Input group, ordered per the group's routing
     * strategy — `fallback` keeps group order, `loadBalance` rotates the start
     * position by [loadBalanceSeed] so separate capture sessions spread across
     * members (mirrors ModelGroupRouter's per-session rotation for text chat,
     * and iOS VoiceProviderResolver.resolvedInputCandidates). An explicit
     * System override returns [] — the caller uses the System engine directly.
     * System sentinel group members are skipped: they never serve cloud ASR.
     */
    fun resolveVoiceInputCandidates(loadBalanceSeed: Int = 0): List<Pair<ProviderInstance, ModelEntry>> {
        repo.ensureConfigLoaded()
        val config = repo._config.value

        fun providerEntry(memberId: String): Pair<ProviderInstance, ModelEntry>? {
            val entry = config.modelEntries.find { it.id == memberId } ?: return null
            val inst = config.instances.find { it.id == entry.providerInstanceId } ?: return null
            if (!inst.isEnabled || !entry.model.hasAudioInput) return null
            return inst to entry
        }

        val out = mutableListOf<Pair<ProviderInstance, ModelEntry>>()
        repo.voiceInputOverrideEntryId?.let { override ->
            // Explicit System override → System engine, no cloud candidates.
            if (override.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) return emptyList()
            providerEntry(override)?.let { out.add(it) }
            // Stale override (entry removed) — fall through to the group.
        }
        val gid = config.voiceInputGroupId
        val group = gid?.let { g -> config.modelGroups.find { it.id == g } }
        if (group != null) {
            var members = group.memberEntryIds
                .filter { !it.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID) }
                .mapNotNull { providerEntry(it) }
            if (group.strategy == RoutingStrategy.loadBalance && members.size > 1) {
                val offset = kotlin.math.abs(loadBalanceSeed) % members.size
                members = members.drop(offset) + members.take(offset)
            }
            for (m in members) {
                if (out.none { it.second.id == m.second.id }) out.add(m)
            }
        }
        return out
    }


    // --- Voice OUTPUT resolution [T-android-voice-output-resolver] ---

    /**
     * Resolution order mirrors [resolveVoiceInputChoice] exactly: explicit
     * override first, then the Voice Output group's members in fallback order
     * (a System sentinel member selects the device engine), then the System
     * default as the terminal fallback.
     *
     * The capability gate is `hasAudioOutput` (vs `hasAudioInput` on the input
     * side) — a model that only *accepts* audio must never be picked to
     * *produce* it.
     */
    fun resolveVoiceOutputChoice(): VoiceOutputChoice {
        repo.ensureConfigLoaded()
        val config = repo._config.value

        fun providerEntry(memberId: String): Pair<ProviderInstance, ModelEntry>? {
            val entry = config.modelEntries.find { it.id == memberId } ?: return null
            val inst = config.instances.find { it.id == entry.providerInstanceId } ?: return null
            if (!inst.isEnabled || !entry.model.hasAudioOutput) return null
            return inst to entry
        }

        repo.voiceOutputOverrideEntryId?.let { override ->
            if (override.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) {
                return VoiceOutputChoice(isSystemEngine = true, entry = null)
            }
            providerEntry(override)?.let { return VoiceOutputChoice(false, it) }
            // Stale override (entry removed) — fall through to the group.
        }
        val gid = config.voiceOutputGroupId
        val pinnedOut = com.openminis.app.data.model.ModelSlotRef.entryId(gid)
        if (pinnedOut != null) {
            providerEntry(pinnedOut)?.let { return VoiceOutputChoice(false, it) }
            return VoiceOutputChoice(isSystemEngine = true, entry = null)
        }
        val group = gid?.let { g -> config.modelGroups.find { it.id == g } }
        if (group != null) {
            for (memberId in group.memberEntryIds) {
                if (memberId.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) {
                    return VoiceOutputChoice(isSystemEngine = true, entry = null)
                }
                providerEntry(memberId)?.let { return VoiceOutputChoice(false, it) }
            }
        }
        return VoiceOutputChoice(isSystemEngine = true, entry = null)
    }


    /**
     * [T-android-voice-picker-active] Which MEMBER of a bound voice group is
     * the one that would actually serve the next request — the id the picker
     * marks "Active" when no explicit override is set.
     *
     * Deliberately derived here rather than recomputed in the picker: this is
     * the same first-usable-member-in-group-order walk that
     * [resolveVoiceInputChoice] / [resolveVoiceOutputChoice] perform, and a
     * second copy in the UI would silently drift from the runtime rule the
     * moment either resolver's usability test changes. Returns null when no
     * member is usable (the group can serve nothing).
     *
     * Note this reports the group's *static* first choice. Under
     * `loadBalance`, or after a fail-over, the request may land on a later
     * member; the badge answers "which one leads", which is what the row's
     * fallback-strategy chip already promises.
     */
    fun activeVoiceGroupMemberId(output: Boolean): String? {
        repo.ensureConfigLoaded()
        val config = repo._config.value
        val gid = if (output) config.voiceOutputGroupId else config.voiceInputGroupId
        val pinnedMember = com.openminis.app.data.model.ModelSlotRef.entryId(gid)
        if (pinnedMember != null) return pinnedMember
        val group = config.modelGroups.find { it.id == gid } ?: return null
        for (memberId in group.memberEntryIds) {
            // System sentinels are always usable — the on-device engine needs
            // no instance and is never disabled.
            if (memberId.startsWith(SystemVoiceIds.BUILTIN_PROVIDER_ID)) return memberId
            val entry = config.modelEntries.find { it.id == memberId } ?: continue
            val inst = config.instances.find { it.id == entry.providerInstanceId } ?: continue
            if (!inst.isEnabled) continue
            val usable = if (output) entry.model.hasAudioOutput else entry.model.hasAudioInput
            if (usable) return memberId
        }
        return null
    }


    /** Bound Voice Output group's display name, or null. */
    fun voiceOutputGroupName(): String? = slotDisplayName(repo._config.value.voiceOutputGroupId)

    /**
     * Resolve the current provider-backed voice OUTPUT selection, or null when
     * read-aloud should use the on-device engine. Counterpart of
     * [resolveVoiceInputEntry].
     */
    fun resolveVoiceOutputEntry(): Pair<ProviderInstance, ModelEntry>? =
        resolveVoiceOutputChoice().entry


    // --- Shadow Voice Providers [T-android-provider-voice] ---

    /** True if [instanceId] has ANY model entry with an audio modality. */
    fun hasVoiceModels(instanceId: String): Boolean =
        repo._config.value.modelEntries.any {
            it.providerInstanceId == instanceId && it.model.hasVoiceModality
        }

    /**
     * Per-instance "hide from Voice Services" flag ("I only want the text
     * models"). Absent = shadow shown by default. Stored in prefs (mirrors iOS
     * UserDefaults voiceShadowDisabled.<id>), not the synced config.
     */
    fun isVoiceShadowDisabled(instanceId: String): Boolean =
        prefs.getBoolean("voiceShadowDisabled.$instanceId", false)

    fun setVoiceShadowDisabled(instanceId: String, disabled: Boolean) {
        prefs.edit().putBoolean("voiceShadowDisabled.$instanceId", disabled).apply()
        // Bump revision so Compose collectors re-read the shadow list.
        // The read must be INSIDE the lock: reading _config.value outside it
        // and re-publishing that snapshot afterwards would silently revert any
        // save that landed in between (lost update). The other two
        // revision-bump sites already read inside their block.
        synchronized(repo.configLock) {
            repo._config.value = repo._config.value.copy(revision = ProviderConfig.nextRevision())
        }
    }


    /**
     * All shadow voice providers: one per enabled instance that has audio
     * models and isn't shadow-disabled, FOLDED by normalized base URL so two
     * instances on one host show a single deterministic representative row.
     */
    fun shadowVoiceSnapshot(): ShadowVoiceSnapshot {
        repo.ensureConfigLoaded()
        val config = repo._config.value
        val candidates = config.instances.toList().filter { inst ->
            inst.isEnabled &&
                config.modelEntries.any { entry ->
                    entry.providerInstanceId == inst.id && entry.model.hasVoiceModality
                } &&
                !isVoiceShadowDisabled(inst.id) &&
                com.openminis.app.provider.voice.VoiceProviderFactory.supports(inst, repo.loadApiKey(inst.id))
        }
        val byKey = candidates.groupBy { inst ->
            ProviderRepository.normalizedShadowKey(inst.customBaseURL).ifEmpty { "id:${inst.id}" }
        }

        fun mostRecentModified(instanceId: String): Long =
            config.modelEntries
                .filter { it.providerInstanceId == instanceId }
                .mapNotNull { it.userModifiedAt }
                .maxOrNull() ?: Long.MIN_VALUE

        val providers = byKey.values.map { insts ->
            val rep = insts.sortedWith(
                compareByDescending<ProviderInstance> { it.isEnabled }
                    .thenByDescending { mostRecentModified(it.id) }
                    .thenBy { it.createdAt }
                    .thenBy { it.id },
            ).first()
            val entries = config.modelEntries.filter { it.providerInstanceId == rep.id }
            ShadowVoiceProvider(
                instanceId = rep.id,
                displayName = rep.label,
                inputModels = entries.filter { it.model.hasAudioInput },
                outputModels = entries.filter { it.model.hasAudioOutput },
            )
        }.sortedWith(compareBy({ it.displayName }, { it.instanceId }))
        val hasFoldedDuplicates = candidates
            .map { ProviderRepository.normalizedShadowKey(it.customBaseURL) }
            .filter { it.isNotEmpty() }
            .let { keys -> keys.size != keys.toSet().size }
        return ShadowVoiceSnapshot(providers, hasFoldedDuplicates)
    }

    fun shadowVoiceProviders(): List<ShadowVoiceProvider> =
        shadowVoiceSnapshot().providers

    fun hasFoldedShadowDuplicates(): Boolean {
        return shadowVoiceSnapshot().hasFoldedDuplicates
    }

}
