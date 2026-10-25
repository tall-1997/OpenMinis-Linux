package com.openminis.app.data.db

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * A single code symbol (function/class/method/variable) found in the workspace.
 * Kept lean — only the fields the agent actually needs to locate and reason
 * about a symbol without re-reading whole files.
 *
 * One row per definition. Indexing is best-effort; gaps are surfaced to the
 * agent as "no index" so it falls back to grep_source instead of guessing.
 */
@Entity(
    tableName = "code_symbols",
    indices = [Index("root"), Index("name"), Index("filePath")],
)
data class CodeSymbolEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Project root used for cleanup when switching workspace. */
    val root: String,
    val filePath: String,
    /** Symbol name the agent will search for, e.g. `buildNotification`. */
    val name: String,
    /** Fully-qualified name when known, e.g. `com.openminis.app.service.ForegroundService`. */
    val qualifiedName: String = "",
    /** Kind from the indexer: function / class / method / variable / import … */
    val kind: String = "",
    val startLine: Int = 0,
    val endLine: Int = 0,
    /** Human-readable signature, e.g. `fun build(ctx: Context): Notification?`. */
    val signature: String = "",
    val indexedAt: Long = System.currentTimeMillis(),
)

/**
 * An edge — a call, reference, or usage relationship between two symbols.
 * Backs the callers-of and callees-of queries; empty when a symbol has no
 * traced references (most entries will, since our lightweight indexer is
 * line-based, not full-AST).
 */
@Entity(
    tableName = "code_edges",
    indices = [Index("root"), Index("fromName"), Index("toName")],
)
data class CodeEdgeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val root: String,
    val filePath: String,
    /** calls | imports | references | extends … */
    val kind: String = "",
    val fromName: String = "",
    val toName: String = "",
    /** File-local 1-based line where the reference appears. */
    val line: Int = 0,
)

@Dao
interface CodeIndexDao {

    // ---- writes ----

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertSymbols(items: List<CodeSymbolEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEdges(items: List<CodeEdgeEntity>)

    // ---- deletes (re-index a file) ----

    @Query("DELETE FROM code_symbols WHERE filePath = :path")
    suspend fun deleteSymbolsOf(path: String)

    @Query("DELETE FROM code_edges WHERE filePath = :path")
    suspend fun deleteEdgesOf(path: String)

    // ---- queries ----

    @Query("""
        SELECT * FROM code_symbols
        WHERE root = :root AND (name = :name OR name LIKE :name || '%')
        ORDER BY CASE WHEN name = :name THEN 0 ELSE 1 END, LENGTH(name), name
        LIMIT :limit
    """)
    suspend fun findByName(root: String, name: String, limit: Int = 30): List<CodeSymbolEntity>

    @Query("SELECT * FROM code_symbols WHERE filePath = :path ORDER BY startLine")
    suspend fun symbolsOf(path: String): List<CodeSymbolEntity>

    @Query("""
        SELECT * FROM code_edges
        WHERE root = :root AND toName = :name
        ORDER BY filePath, line
        LIMIT :limit
    """)
    suspend fun callersOf(root: String, name: String, limit: Int = 50): List<CodeEdgeEntity>

    @Query("""
        SELECT * FROM code_edges
        WHERE root = :root AND fromName = :name
        ORDER BY line
        LIMIT :limit
    """)
    suspend fun calleesOf(root: String, name: String, limit: Int = 50): List<CodeEdgeEntity>

    @Query("SELECT COUNT(*) FROM code_symbols WHERE root = :root")
    suspend fun symbolCount(root: String): Int

    @Query("SELECT COUNT(DISTINCT filePath) FROM code_symbols WHERE root = :root")
    suspend fun fileCount(root: String): Int

    // ---- cleanup ----

    @Query("DELETE FROM code_symbols WHERE root = :root")
    suspend fun clearSymbols(root: String)

    @Query("DELETE FROM code_edges WHERE root = :root")
    suspend fun clearEdges(root: String)
}
