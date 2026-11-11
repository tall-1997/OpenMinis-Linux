package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * [T-audit-room-persist] Durable copy of one [com.openminis.app.security.SecurityGate]
 * audit decision. The gate keeps its own bounded in-memory sha256 hash chain
 * (500 newest entries, process lifetime only); this table mirrors every entry
 * as it is appended so the trail survives process death and grows past the
 * memory cap. It is a REVIEW AID, not a tamper-proof ledger — same as the
 * in-memory trail, the chain verification stays a gate-side concern.
 *
 * Written by the app-side `auditSink` wiring (AppGraph, next to the gate's
 * other app-provided hooks). The security layer never imports Room and this
 * layer never imports security types: the sink lambda maps the loose fields.
 *
 * Column notes:
 * - `session_id` is best-effort attribution (the foreground chat at the time
 *   of the call); `""` means "no visible session". Not a foreign key on
 *   purpose — an audit row must survive its session being deleted.
 * - `result_preview` is capped at 400 chars BY THE WRITER: SQLite TEXT has no
 *   length, and audit rows must never carry full tool output.
 */
@Entity(
    tableName = "audit_entries",
    indices = [
        Index(value = ["session_id"], name = "index_audit_entries_session"),
        Index(value = ["created_at"], name = "index_audit_entries_created_at"),
    ],
)
data class AuditEntryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "session_id") val sessionId: String,
    val tool: String,
    val decision: String,
    @ColumnInfo(name = "result_preview") val resultPreview: String?,
    val hash: String,
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

@Dao
interface AuditEntryDao {
    @Insert
    suspend fun insert(entry: AuditEntryEntity)

    /** Newest-first tail of one session's audit trail (review surfaces only). */
    @Query(
        "SELECT * FROM audit_entries WHERE session_id = :sessionId " +
            "ORDER BY created_at DESC LIMIT :limit",
    )
    suspend fun recent(sessionId: String, limit: Int): List<AuditEntryEntity>
}
