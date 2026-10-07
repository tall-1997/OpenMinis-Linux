package com.openminis.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * [T-msg-version-archive] Archived snapshot of a message row that was
 * truncated by a retry / regenerate / delete-from-here. cuplivo parity:
 * `message_versions` — re-generating an answer archives the old version
 * instead of destroying it, so the user (or a later "browse versions" UI)
 * can still reach it.
 *
 * Deliberately NO foreign key to `messages`: the source row is usually
 * deleted right after archiving, and a CASCADE would delete the archive
 * with it. The FK points at `sessions` instead, so deleting a whole chat
 * still reclaims its archives.
 *
 * `bodyRef`/`bodySha` reference the same BodyStore file the live row used.
 * Safe because BodyStore never garbage-collects committed bodies (only
 * `discardTemps` runs, on uncommitted tmp files).
 */
@Entity(
    tableName = "message_versions",
    foreignKeys = [
        ForeignKey(
            entity = ChatSessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [
        Index(value = ["message_id", "version_index"], unique = true),
        Index(value = ["session_id"]),
    ]
)
data class MessageVersionEntity(
    @PrimaryKey val id: String,
    /** Id of the source row in `messages` (which may no longer exist). */
    @ColumnInfo(name = "message_id") val messageId: String,
    @ColumnInfo(name = "session_id") val sessionId: String,
    /** 1-based per-message archive ordinal; latest = max. */
    @ColumnInfo(name = "version_index") val versionIndex: Int,
    val role: String,
    /** parts_json AS STORED (no preview substitution). */
    @ColumnInfo(name = "parts_json") val partsJson: String?,
    @ColumnInfo(name = "body_ref") val bodyRef: String? = null,
    @ColumnInfo(name = "body_sha") val bodySha: String? = null,
    @ColumnInfo(name = "reasoning_content") val reasoningContent: String? = null,
    @ColumnInfo(name = "error_info") val errorInfo: String? = null,
    @ColumnInfo(name = "model_id") val modelId: String? = null,
    @ColumnInfo(name = "model_display_name") val modelDisplayName: String? = null,
    @ColumnInfo(name = "source_created_at") val sourceCreatedAt: Long,
    @ColumnInfo(name = "archived_at") val archivedAt: Long,
)
