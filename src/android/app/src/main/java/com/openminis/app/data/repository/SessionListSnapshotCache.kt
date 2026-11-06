package com.openminis.app.data.repository

import android.content.Context
import android.util.Log
import com.openminis.app.data.db.ChatSessionEntity
import com.openminis.app.data.db.FolderEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * [T-android-sessionlist-snapshot-restore] Session-list snapshot cache: the
 * last rendered (sessions, folders) pair, persisted off-main so a cold start
 * can paint the REAL previous list on the first frame instead of a blank
 * content area while the first Room emission queues behind the DB startup
 * gate (quick_check + open). Same pattern as the provider-row snapshot cache
 * that already ships for the provider page.
 *
 * Why a dedicated DTO instead of @Serializable on the Room entities: the
 * project maps Room rows to serializable snapshots by hand (see
 * ProviderConfigMapping) — that keeps serialization concerns out of the
 * entity files and makes the persisted field set explicit. The mapping is
 * full-fidelity (every column), so when the live Room emission lands it
 * replaces the restored list with zero visual delta.
 *
 * Threading: [SessionListSnapshotCache.readSnapshot] blocks on disk — callers
 * must be on Dispatchers.IO. [SessionListSnapshotCache.writeAsync] merges
 * concurrent writes by cancelling the previous write job (latest-wins; a
 * blocked-on-IO write finishes its ms-scale pass, which is fine because the
 * payload is idempotent) and writes atomically (tmp + rename), so a torn file
 * can never become the restored snapshot.
 */
private const val SNAPSHOT_TAG = "SessionListSnapshot"

@Serializable
data class SessionSnapshotRow(
    val id: String = "",
    val title: String? = null,
    val modelId: String = "",
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
    val category: String? = null,
    val lastMessage: String? = null,
    val modelBinding: String? = null,
    val source: String? = null,
    val memoryEnabled: Int = 1,
    val pinnedAt: Long? = null,
    val editCount: Int = 0,
    val thinkingOverride: String? = null,
    val folderId: String? = null,
    val permissionMode: String? = "ASK",
)

@Serializable
data class FolderSnapshotRow(
    val id: String = "",
    val name: String = "",
    val icon: String? = null,
    val color: String? = null,
    val origin: String = FolderEntity.ORIGIN_MANUAL,
    val sortIndex: Int = 0,
    val pinnedAt: Long? = null,
    val description: String? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

@Serializable
data class SessionListSnapshotPayload(
    val version: Int = SNAPSHOT_VERSION,
    val savedAtMs: Long = 0L,
    val sessions: List<SessionSnapshotRow> = emptyList(),
    val folders: List<FolderSnapshotRow> = emptyList(),
) {
    companion object {
        /**
         * Bump when [SessionSnapshotRow] / [FolderSnapshotRow] fields change
         * shape in a way [ignoreUnknownKeys] cannot absorb; older snapshots
         * then fail the version check in [decodeSessionListSnapshot] and are
         * dropped instead of restoring half-mapped rows.
         */
        const val SNAPSHOT_VERSION = 1
    }
}

internal fun ChatSessionEntity.toSnapshotRow() = SessionSnapshotRow(
    id = id,
    title = title,
    modelId = modelId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    category = category,
    lastMessage = lastMessage,
    modelBinding = modelBinding,
    source = source,
    memoryEnabled = memoryEnabled,
    pinnedAt = pinnedAt,
    editCount = editCount,
    thinkingOverride = thinkingOverride,
    folderId = folderId,
    permissionMode = permissionMode,
)

internal fun SessionSnapshotRow.toEntity() = ChatSessionEntity(
    id = id,
    title = title,
    modelId = modelId,
    createdAt = createdAt,
    updatedAt = updatedAt,
    category = category,
    lastMessage = lastMessage,
    modelBinding = modelBinding,
    source = source,
    memoryEnabled = memoryEnabled,
    pinnedAt = pinnedAt,
    editCount = editCount,
    thinkingOverride = thinkingOverride,
    folderId = folderId,
    permissionMode = permissionMode,
)

internal fun FolderEntity.toSnapshotRow() = FolderSnapshotRow(
    id = id,
    name = name,
    icon = icon,
    color = color,
    origin = origin,
    sortIndex = sortIndex,
    pinnedAt = pinnedAt,
    description = description,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

internal fun FolderSnapshotRow.toEntity() = FolderEntity(
    id = id,
    name = name,
    icon = icon,
    color = color,
    origin = origin,
    sortIndex = sortIndex,
    pinnedAt = pinnedAt,
    description = description,
    createdAt = createdAt,
    updatedAt = updatedAt,
)

private val snapshotJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

internal fun encodeSessionListSnapshot(snapshot: SessionListSnapshotPayload): String =
    snapshotJson.encodeToString(SessionListSnapshotPayload.serializer(), snapshot)

/**
 * Fault-tolerant decode: null on absent/blank input, malformed JSON, a type
 * mismatch, or a version the current schema cannot interpret. A snapshot the
 * decoder drops must degrade to the pre-fix blank-frame behavior, never to a
 * crash loop.
 */
internal fun decodeSessionListSnapshot(json: String?): SessionListSnapshotPayload? {
    if (json.isNullOrBlank()) return null
    return try {
        val payload = snapshotJson.decodeFromString(SessionListSnapshotPayload.serializer(), json)
        if (payload.version != SessionListSnapshotPayload.SNAPSHOT_VERSION) {
            Log.w(
                SNAPSHOT_TAG,
                "snapshot version ${payload.version} != ${SessionListSnapshotPayload.SNAPSHOT_VERSION}, dropping",
            )
            null
        } else {
            payload
        }
    } catch (t: Throwable) {
        Log.w(SNAPSHOT_TAG, "snapshot decode failed: ${t.message}")
        null
    }
}

internal enum class SnapshotRestoreDecision {
    RESTORE,
    SKIP_NO_SNAPSHOT,
    SKIP_EMPTY,
    SKIP_DB_ALREADY_LANDED,
}

/**
 * Pure restore decision. [dbEmissionLanded] is deliberately NOT
 * isInitialLoadComplete: safe-mode flips that flag before any Room emission,
 * so it cannot serve as the "real DB data is on screen" signal the restore
 * decision needs.
 */
internal fun snapshotRestoreDecision(
    dbEmissionLanded: Boolean,
    snapshot: SessionListSnapshotPayload?,
): SnapshotRestoreDecision = when {
    dbEmissionLanded -> SnapshotRestoreDecision.SKIP_DB_ALREADY_LANDED
    snapshot == null -> SnapshotRestoreDecision.SKIP_NO_SNAPSHOT
    snapshot.sessions.isEmpty() -> SnapshotRestoreDecision.SKIP_EMPTY
    else -> SnapshotRestoreDecision.RESTORE
}

object SessionListSnapshotCache {
    private const val FILE_NAME = "session_list_snapshot.json"

    /** Sanity cap (chars ≈ bytes upper bound is loose but the intent is just to stop a pathological list from growing the file unbounded). */
    private const val MAX_CHARS = 5 * 1024 * 1024

    private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var writeJob: Job? = null

    /**
     * Latest-wins persist. Call from the collectors AFTER the state flows are
     * updated, so the snapshot always describes what is on screen. The write
     * itself runs on [Dispatchers.IO]; cancelling the in-flight job drops any
     * queued-but-not-started write and lets an in-flight ms-scale write finish
     * (idempotent payload, so a redundant write is harmless).
     */
    fun writeAsync(
        context: Context,
        sessions: List<ChatSessionEntity>,
        folders: List<FolderEntity>,
    ) {
        val payload = SessionListSnapshotPayload(
            savedAtMs = System.currentTimeMillis(),
            sessions = sessions.map { it.toSnapshotRow() },
            folders = folders.map { it.toSnapshotRow() },
        )
        val appContext = context.applicationContext
        writeJob?.cancel()
        writeJob = ioScope.launch {
            runCatching {
                val json = encodeSessionListSnapshot(payload)
                if (json.length > MAX_CHARS) return@launch
                val file = File(appContext.filesDir, FILE_NAME)
                val tmp = File(appContext.filesDir, "$FILE_NAME.tmp")
                tmp.writeText(json)
                if (!tmp.renameTo(file)) {
                    // Same-directory rename effectively never fails, but a
                    // fallback beats silently losing the snapshot.
                    file.writeText(json)
                    tmp.delete()
                }
            }.onFailure {
                Log.w(SNAPSHOT_TAG, "snapshot write failed: ${it.message}")
            }
        }
    }

    /** Blocking read — call from Dispatchers.IO. Null when absent or unreadable. */
    fun readSnapshot(context: Context): SessionListSnapshotPayload? = runCatching {
        val file = File(context.applicationContext.filesDir, FILE_NAME)
        if (!file.exists()) null else decodeSessionListSnapshot(file.readText())
    }.getOrNull()
}

/**
 * [T-android-sessionlist-snapshot-restore] Entry gate for the session-list
 * pane (render side of the restore policy, kept next to the snapshot it
 * gates). Previously the pane rendered nothing until the first Room emission
 * landed (or the 3s stall timeout fired) — a fully blank content area on
 * every cold start while the DB startup gate ran. A restored snapshot reveals
 * the pane immediately with the previous run's list.
 */
fun sessionListEntryRevealed(
    initialComplete: Boolean,
    timedOut: Boolean,
    snapshotRestored: Boolean,
): Boolean = initialComplete || timedOut || snapshotRestored

/**
 * Stall-spinner guard, snapshot-aware: with a restored snapshot the pane
 * already shows real content, so the spinner must not cover it. The 3s
 * timeout path only surfaces for true first runs (no snapshot) where the
 * pane would otherwise stay blank indefinitely.
 */
fun sessionListStallSpinner(
    initialComplete: Boolean,
    snapshotRestored: Boolean,
    sessionsNonEmpty: Boolean,
): Boolean = !initialComplete && !(snapshotRestored && sessionsNonEmpty)
