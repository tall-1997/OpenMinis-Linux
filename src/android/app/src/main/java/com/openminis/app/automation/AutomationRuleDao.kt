package com.openminis.app.automation

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * [T-android-automation-rules] Room rows for conditional-trigger automation
 * (AppDatabase v23). Entities live in the automation package — the ownership
 * rule keeps every automation file under it — while AppDatabase references
 * them the same way it references the code-index entities.
 *
 * The full spec (trigger/conditions/action/allowedTools) is one JSON column:
 * the schema evolves inside [RuleSpec] (validated on read) instead of growing
 * columns, mirroring how ScheduledTask carries its whole row as JSON.
 */
@Entity(tableName = "automation_rules")
data class AutomationRuleEntity(
    @PrimaryKey @ColumnInfo(name = "id") val id: String,
    val name: String,
    @ColumnInfo(name = "spec_json") val specJson: String,
    val enabled: Boolean = true,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
    @ColumnInfo(name = "last_fired_at") val lastFiredAt: Long? = null,
) {
    /** Parsed spec, or null when the stored JSON no longer parses (logged by readers). */
    fun spec(): RuleSpec? = RuleSpec.fromJson(specJson)
}

/**
 * One fire attempt (or refused attempt) of a rule. Newest-first lists read
 * this table directly; [outcome] is one of:
 *  "started" (agent dispatched, updated to ok/error on completion),
 *  "ok", "error: …", "skipped_steps_todo", "skipped_rate_limit".
 */
@Entity(tableName = "automation_runs")
data class AutomationRunEntity(
    @PrimaryKey(autoGenerate = true) @ColumnInfo(name = "run_id") val runId: Long = 0,
    @ColumnInfo(name = "rule_id") val ruleId: String,
    @ColumnInfo(name = "fired_at") val firedAt: Long,
    val outcome: String,
    @ColumnInfo(name = "session_id") val sessionId: String? = null,
    val preview: String? = null,
) {
    val skipped: Boolean get() = outcome.startsWith("skipped")
}

@Dao
interface AutomationRuleDao {

    // ── rules ────────────────────────────────────────────────────────────

    @Query("SELECT * FROM automation_rules ORDER BY created_at DESC")
    fun observeRules(): Flow<List<AutomationRuleEntity>>

    @Query("SELECT * FROM automation_rules ORDER BY created_at DESC")
    suspend fun all(): List<AutomationRuleEntity>

    @Query("SELECT * FROM automation_rules WHERE id = :id")
    suspend fun get(id: String): AutomationRuleEntity?

    /** Blocking variants for the synchronous agent-tool path (IO dispatcher). */
    @Query("SELECT * FROM automation_rules ORDER BY created_at DESC")
    fun allBlocking(): List<AutomationRuleEntity>

    @Query("SELECT * FROM automation_rules WHERE id = :id")
    fun getBlocking(id: String): AutomationRuleEntity?

    @Query("SELECT * FROM automation_rules WHERE enabled = 1")
    suspend fun enabled(): List<AutomationRuleEntity>

    @Upsert
    suspend fun upsert(rule: AutomationRuleEntity)

    @Upsert
    fun upsertBlocking(rule: AutomationRuleEntity)

    @Query("DELETE FROM automation_rules WHERE id = :id")
    suspend fun delete(id: String): Int

    @Query("DELETE FROM automation_rules WHERE id = :id")
    fun deleteBlocking(id: String): Int

    // ── runs ─────────────────────────────────────────────────────────────

    @Insert
    suspend fun insertRun(run: AutomationRunEntity): Long

    @Query(
        "UPDATE automation_runs SET outcome = :outcome, " +
            "session_id = :sessionId, preview = :preview WHERE run_id = :runId",
    )
    suspend fun updateRun(runId: Long, outcome: String, sessionId: String?, preview: String?)

    @Query(
        "SELECT COUNT(*) FROM automation_runs WHERE rule_id = :ruleId " +
            "AND fired_at >= :sinceMs AND outcome NOT LIKE 'skipped%'",
    )
    suspend fun countActionableSince(ruleId: String, sinceMs: Long): Int

    @Query(
        "SELECT COUNT(*) FROM automation_runs WHERE rule_id = :ruleId " +
            "AND fired_at >= :sinceMs AND outcome NOT LIKE 'skipped%'",
    )
    fun countActionableSinceBlocking(ruleId: String, sinceMs: Long): Int

    @Query("SELECT * FROM automation_runs WHERE rule_id = :ruleId ORDER BY fired_at DESC, run_id DESC LIMIT :limit")
    suspend fun recentRuns(ruleId: String, limit: Int): List<AutomationRunEntity>

    @Query("SELECT * FROM automation_runs WHERE rule_id = :ruleId ORDER BY fired_at DESC, run_id DESC LIMIT :limit")
    fun recentRunsBlocking(ruleId: String, limit: Int): List<AutomationRunEntity>

    @Query("SELECT * FROM automation_runs WHERE session_id = :sessionId LIMIT 1")
    suspend fun runForSession(sessionId: String): AutomationRunEntity?

    @Query("DELETE FROM automation_runs WHERE rule_id = :ruleId")
    suspend fun deleteRunsFor(ruleId: String)
}
