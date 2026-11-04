package com.openminis.app.data.instruction

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query

/**
 * Room persistence for P1 instruction snapshots.
 *
 * - [InstructionStateEntity]: one row per session; holds the frozen values map
 *   (`currentValuesJson`) plus the event-stream cursor (`throughSeq`).
 * - [InstructionUpdateEntity]: the append-only frozen event stream. Every
 *   commit that changed something appends one row; rows are never updated or
 *   deleted by application code, so the stream is an audit log by itself.
 */
@Entity(tableName = "instruction_states")
data class InstructionStateEntity(
    @PrimaryKey val sessionId: String,
    val epochStartSeq: Long,
    val throughSeq: Long,
    val initialValuesJson: String,
    val currentValuesJson: String,
    val updatedAt: Long,
)

@Entity(
    tableName = "instruction_updates",
    indices = [Index(value = ["sessionId"])],
)
data class InstructionUpdateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: String,
    val seq: Long,
    val isInitial: Boolean,
    val deltaJson: String?,
    val renderedText: String,
    val createdAt: Long,
)

@Dao
interface InstructionStateDao {

    @Query("SELECT * FROM instruction_states WHERE sessionId = :sessionId")
    suspend fun stateFor(sessionId: String): InstructionStateEntity?

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    suspend fun upsertState(state: InstructionStateEntity)

    @Insert
    suspend fun insertUpdate(update: InstructionUpdateEntity)

    @Query(
        "SELECT * FROM instruction_updates WHERE sessionId = :sessionId " +
            "ORDER BY seq ASC LIMIT :limit",
    )
    suspend fun updatesFor(sessionId: String, limit: Int): List<InstructionUpdateEntity>

    @Query("SELECT MAX(seq) FROM instruction_updates WHERE sessionId = :sessionId")
    suspend fun latestSeqFor(sessionId: String): Long?
}
