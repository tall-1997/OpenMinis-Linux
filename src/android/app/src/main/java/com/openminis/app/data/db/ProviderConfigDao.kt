package com.openminis.app.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update

@Dao
interface ProviderConfigDao {

    @Query("SELECT * FROM provider_instances ORDER BY sort_order ASC, created_at ASC")
    suspend fun loadInstances(): List<ProviderInstanceEntity>

    @Query("SELECT * FROM provider_model_entries ORDER BY provider_instance_id, sort_order ASC")
    suspend fun loadEntries(): List<ProviderModelEntryEntity>

    @Query("SELECT * FROM provider_model_groups ORDER BY sort_order ASC")
    suspend fun loadGroups(): List<ProviderModelGroupEntity>

    @Query("SELECT * FROM provider_agent_loop_ids ORDER BY kind, sort_order ASC")
    suspend fun loadAgentLoopIds(): List<ProviderAgentLoopIdEntity>

    @Query("SELECT * FROM provider_config_meta")
    suspend fun loadMeta(): List<ProviderConfigMetaEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertInstances(rows: List<ProviderInstanceEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertEntries(rows: List<ProviderModelEntryEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertGroups(rows: List<ProviderModelGroupEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAgentLoopIds(rows: List<ProviderAgentLoopIdEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertMeta(rows: List<ProviderConfigMetaEntity>)

    // [Wave3-fix-11] Non-cascading write primitives for [replaceDiff]: plain
    // INSERT/UPDATE/DELETE. REPLACE is deliberately NOT used for instance rows
    // here — `INSERT OR REPLACE` on a parent row fires its FK CASCADE and
    // silently wipes that instance's entries, which would turn every unchanged
    // row into a full child-table rewrite.
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertInstances(rows: List<ProviderInstanceEntity>)

    @Update
    suspend fun updateInstances(rows: List<ProviderInstanceEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEntries(rows: List<ProviderModelEntryEntity>)

    @Update
    suspend fun updateEntries(rows: List<ProviderModelEntryEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertGroups(rows: List<ProviderModelGroupEntity>)

    @Update
    suspend fun updateGroups(rows: List<ProviderModelGroupEntity>)

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAgentLoopIds(rows: List<ProviderAgentLoopIdEntity>)

    @Update
    suspend fun updateAgentLoopIds(rows: List<ProviderAgentLoopIdEntity>)

    @Query("DELETE FROM provider_instances WHERE id IN (:ids)")
    suspend fun deleteInstancesByIds(ids: List<String>)

    @Query("DELETE FROM provider_model_entries WHERE id IN (:ids)")
    suspend fun deleteEntriesByIds(ids: List<String>)

    @Query("DELETE FROM provider_model_groups WHERE id IN (:ids)")
    suspend fun deleteGroupsByIds(ids: List<String>)

    @Query("DELETE FROM provider_agent_loop_ids WHERE kind = :kind AND target_id IN (:targetIds)")
    suspend fun deleteAgentLoopIdsByTargetIds(kind: String, targetIds: List<String>)

    @Query("DELETE FROM provider_instances") suspend fun clearInstances()
    @Query("DELETE FROM provider_model_entries") suspend fun clearEntries()
    @Query("DELETE FROM provider_model_groups") suspend fun clearGroups()
    @Query("DELETE FROM provider_agent_loop_ids") suspend fun clearAgentLoopIds()
    @Query("DELETE FROM provider_config_meta") suspend fun clearMeta()

    /**
     * Atomic full replace. Entry FK CASCADE means clearing instances first
     * would wipe entries via cascade; we still clearEntries explicitly to
     * keep the order predictable and to defensively cover schema-without-FK
     * test paths.
     *
     * [Wave3-fix-11] Reserved for full-restore style paths that genuinely want
     * every table emptied first; the regular save path uses [replaceDiff],
     * which writes only the rows that actually changed instead of rewriting
     * all five tables on every slot pick.
     */
    @Transaction
    suspend fun replaceAll(
        instances: List<ProviderInstanceEntity>,
        entries: List<ProviderModelEntryEntity>,
        groups: List<ProviderModelGroupEntity>,
        loopIds: List<ProviderAgentLoopIdEntity>,
        meta: List<ProviderConfigMetaEntity>,
    ) {
        clearAgentLoopIds()
        clearGroups()
        clearEntries()
        clearInstances()
        clearMeta()
        upsertInstances(instances)
        upsertEntries(entries)
        upsertGroups(groups)
        upsertAgentLoopIds(loopIds)
        upsertMeta(meta)
    }

    /**
     * [Wave3-fix-11] Diff-based atomic replace: persists [instances]/[entries]/
     * [groups]/[loopIds]/[meta] as the complete new state of the five tables
     * (same end state as [replaceAll]) but touches ONLY rows that differ from
     * what is already stored. The old clear+bulk-upsert rewrote the entire
     * provider config — thousands of entry rows — on every small mutation
     * (a slot pick, a pin toggle, an endpoint probe stamp), all inside the
     * single persistence worker and therefore serialized behind every other
     * save.
     *
     * Mechanics (all inside one transaction):
     *  1. Load the stored rows, partition each table into insert / update /
     *     delete by primary key, using plain @Insert(ABORT)/@Update/@Delete so
     *     an instance row UPDATE never fires the entries FK CASCADE (REPLACE
     *     would).
     *  2. Write parents (instances) before children (entries) so a new
     *     instance's entries can't hit a transient missing-parent FK failure.
     *  3. Delete stale rows last, instances before entries, so CASCADE has
     *     nothing left to double-delete. `IN (:ids)` lists are chunked at
     *     [DELETE_CHUNK] to stay under SQLite's host-parameter limit on older
     *     Androids (999).
     *
     * `provider_config_meta` is three known keys — cleared and rewritten, like
     * [replaceAll] did (its `json_sync_hash` row changes on every persist).
     */
    @Transaction
    suspend fun replaceDiff(
        instances: List<ProviderInstanceEntity>,
        entries: List<ProviderModelEntryEntity>,
        groups: List<ProviderModelGroupEntity>,
        loopIds: List<ProviderAgentLoopIdEntity>,
        meta: List<ProviderConfigMetaEntity>,
    ) {
        // ── instances ──
        val storedInstances = loadInstances().associateBy { it.id }
        val newInstanceRows = ArrayList<ProviderInstanceEntity>()
        val changedInstanceRows = ArrayList<ProviderInstanceEntity>()
        for (row in instances) {
            val prev = storedInstances[row.id]
            if (prev == null) newInstanceRows.add(row)
            else if (prev != row) changedInstanceRows.add(row)
        }
        val keepInstanceIds = instances.mapTo(HashSet()) { it.id }
        val staleInstanceIds = storedInstances.keys.filterTo(ArrayList()) { it !in keepInstanceIds }
        if (newInstanceRows.isNotEmpty()) insertInstances(newInstanceRows)
        if (changedInstanceRows.isNotEmpty()) updateInstances(changedInstanceRows)
        // Parents (including stale rows, whose CASCADE takes their entries)
        // before touching entry rows.
        staleInstanceIds.chunked(DELETE_CHUNK).forEach { deleteInstancesByIds(it) }

        // ── entries ──
        val storedEntries = loadEntries().associateBy { it.id }
        val newEntryRows = ArrayList<ProviderModelEntryEntity>()
        val changedEntryRows = ArrayList<ProviderModelEntryEntity>()
        for (row in entries) {
            val prev = storedEntries[row.id]
            if (prev == null) newEntryRows.add(row)
            else if (prev != row) changedEntryRows.add(row)
        }
        val keepEntryIds = entries.mapTo(HashSet()) { it.id }
        val staleEntryIds = storedEntries.keys.filterTo(ArrayList()) { it !in keepEntryIds }
        if (newEntryRows.isNotEmpty()) insertEntries(newEntryRows)
        if (changedEntryRows.isNotEmpty()) updateEntries(changedEntryRows)
        staleEntryIds.chunked(DELETE_CHUNK).forEach { deleteEntriesByIds(it) }

        // ── groups ──
        val storedGroups = loadGroups().associateBy { it.id }
        val newGroupRows = ArrayList<ProviderModelGroupEntity>()
        val changedGroupRows = ArrayList<ProviderModelGroupEntity>()
        for (row in groups) {
            val prev = storedGroups[row.id]
            if (prev == null) newGroupRows.add(row)
            else if (prev != row) changedGroupRows.add(row)
        }
        val keepGroupIds = groups.mapTo(HashSet()) { it.id }
        val staleGroupIds = storedGroups.keys.filterTo(ArrayList()) { it !in keepGroupIds }
        if (newGroupRows.isNotEmpty()) insertGroups(newGroupRows)
        if (changedGroupRows.isNotEmpty()) updateGroups(changedGroupRows)
        staleGroupIds.chunked(DELETE_CHUNK).forEach { deleteGroupsByIds(it) }

        // ── agent-loop ids (composite PK kind+target_id) ──
        val storedLoopKeys = loadAgentLoopIds()
            .associateBy { it.kind to it.targetId }
        val newLoopRows = ArrayList<ProviderAgentLoopIdEntity>()
        val changedLoopRows = ArrayList<ProviderAgentLoopIdEntity>()
        for (row in loopIds) {
            val prev = storedLoopKeys[row.kind to row.targetId]
            if (prev == null) newLoopRows.add(row)
            else if (prev != row) changedLoopRows.add(row)
        }
        val keepLoopKeys = loopIds.mapTo(HashSet()) { it.kind to it.targetId }
        val staleLoopKeys = storedLoopKeys.keys.filterTo(ArrayList()) { it !in keepLoopKeys }
        if (newLoopRows.isNotEmpty()) insertAgentLoopIds(newLoopRows)
        if (changedLoopRows.isNotEmpty()) updateAgentLoopIds(changedLoopRows)
        staleLoopKeys
            .groupBy({ it.first }, { it.second })
            .forEach { (kind, targetIds) ->
                targetIds.chunked(DELETE_CHUNK).forEach { deleteAgentLoopIdsByTargetIds(kind, it) }
            }

        // ── meta (3 known keys; hash changes every persist) ──
        clearMeta()
        upsertMeta(meta)
    }

    @Query("SELECT COUNT(*) FROM provider_instances")
    suspend fun instanceCount(): Int

    companion object {
        /**
         * Host-parameter safety bound for the chunked `IN (:ids)` deletes in
         * [replaceDiff]: SQLite's default SQLITE_MAX_VARIABLE_NUMBER is 999 on
         * Androids older than API 30, so 900 ids × 1 parameter each stays safe.
         */
        private const val DELETE_CHUNK = 900
    }

    // ---- [T-android-thinking-rules-phase2] Custom thinking rules ----
    //
    // Deliberately NOT touched by replaceAll(): thinking rules are per-instance user
    // data that is orthogonal to the config snapshot round-trip. They are cleaned up
    // only when their owning instance is deleted (see deleteThinkingRulesForInstance,
    // called from ProviderRepository.deleteInstance).

    @Query("SELECT * FROM provider_thinking_rules WHERE provider_instance_id = :instanceId ORDER BY sort_order ASC")
    suspend fun loadThinkingRules(instanceId: String): List<ProviderThinkingRuleEntity>

    @Query("SELECT * FROM provider_thinking_rules ORDER BY provider_instance_id, sort_order ASC")
    suspend fun loadAllThinkingRules(): List<ProviderThinkingRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertThinkingRule(row: ProviderThinkingRuleEntity)

    @Query("DELETE FROM provider_thinking_rules WHERE id = :id")
    suspend fun deleteThinkingRule(id: String)

    @Query("DELETE FROM provider_thinking_rules WHERE provider_instance_id = :instanceId")
    suspend fun deleteThinkingRulesForInstance(instanceId: String)

    /** Atomic reorder/replace of an instance's whole rule set (drag-reorder + add/edit). */
    @Transaction
    suspend fun replaceThinkingRules(instanceId: String, rows: List<ProviderThinkingRuleEntity>) {
        deleteThinkingRulesForInstance(instanceId)
        for (r in rows) upsertThinkingRule(r)
    }
}
