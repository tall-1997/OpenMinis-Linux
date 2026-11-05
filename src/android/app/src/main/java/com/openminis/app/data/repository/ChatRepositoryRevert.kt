package com.openminis.app.data.repository

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.sandbox.SessionWorkspace
import com.openminis.app.tools.FileCheckpointStore
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-p9-revert] opencode-style three-stage revert for any message point
 * (core/src/session/revert.ts semantics on this app's checkpoint base).
 *
 * stage(session, messageID, files?) — the boundary message is the fork the
 * user wants to return to. Every file touched by a checkpoint captured
 * strictly AFTER the boundary (file_write / file_edit / multi_edit
 * auto-capture BEFORE each write) is restored to its OLDEST post-boundary
 * snapshot — the state of the file at the boundary. The CURRENT bytes of
 * those files are captured first into an "original" checkpoint so
 * clear() can put them back. Messages are NOT touched: the tail stays in
 * the DB and is hidden in memory by the ViewModel until commit.
 *
 * clear(session) — undo: restore the original snapshot, drop the record.
 * The tail reappears (it never left the DB).
 *
 * commit(session) — finalize: deleteAfterWithPairRepair cuts the tail
 * (archiving it into message_versions first, so a committed revert stays
 * recoverable through the version history), the record is dropped, the
 * file rollback stands. The next user send triggers this automatically
 * (ChatViewModelSendExt), mirroring opencode's "commit on next prompt".
 *
 * Any OTHER pair-repair cut at or before the staged boundary supersedes
 * the stage: deleteAfterWithPairRepair's supersede hook drops the record
 * (the file rollback stands). Cuts strictly after boundary+1 only
 * shorten the hidden window and leave the record alone.
 *
 * Coverage and limits (inherited from the checkpoint net):
 * - Only tool-mediated writes are revertible (file_write / file_edit /
 *   multi_edit auto-capture). A shell redirect (`echo > file`) leaves no
 *   checkpoint and stays invisible to the planner.
 * - Checkpoints are pruned to the newest MAX_CHECKPOINTS=50 per session;
 *   a boundary older than that window reports its files as unrestorable
 *   (the plan simply finds no post-boundary checkpoint for them).
 * - The correlation anchor is wall-clock: checkpoint.createdAt strictly
 *   AFTER boundary.createdAt. A same-millisecond capture counts as
 *   pre-boundary — the fail-safe direction: an incomplete rollback is
 *   visible and recoverable, a wrong-direction rollback silently
 *   destroys pre-boundary work.
 * - The record lives in the session's PRIVATE base dir
 *   (sessions/<owner>/.revert-stage.json), next to .checkpoints — never
 *   inside it (list() scans that root).
 */

/** The staged revert record: everything clear()/commit() need to finish or undo a stage. */
internal class StagedRevert(
    val sessionId: String,
    val boundaryMessageId: String,
    val boundarySortOrder: Int,
    val boundaryCreatedAt: Long,
    val originalCheckpointId: String?,
    val plan: List<PlannedRestore>,
    val stagedAt: Long,
) {
    /** Restore [paths] from checkpoint [checkpointId] (the oldest post-boundary capture of each). */
    class PlannedRestore(val checkpointId: String, val paths: List<String>)

    val fileCount: Int get() = plan.sumOf { it.paths.size }
}

/**
 * Pure planning: for every path touched by a checkpoint captured strictly
 * AFTER the boundary, pick the OLDEST such checkpoint — the file's state
 * at the boundary. First-wins mirrors opencode's plan() (!files.has(file))
 * semantics: the first turn after the boundary that touched a file owns
 * its restore target.
 */
internal object RevertPlanner {

    fun plan(
        boundaryCreatedAt: Long,
        checkpoints: List<FileCheckpointStore.Checkpoint>,
        files: Set<String>? = null,
    ): List<StagedRevert.PlannedRestore> {
        val byPath = LinkedHashMap<String, String>()
        checkpoints
            .sortedBy { it.createdAt }
            .filter { it.createdAt > boundaryCreatedAt }
            .forEach { cp ->
                cp.entries
                    .filter { !it.isDir }
                    .filter { files == null || it.path in files }
                    .forEach { e -> byPath.putIfAbsent(e.path, cp.id) }
            }
        return byPath.entries
            .groupBy({ it.value }, { it.key })
            .map { (cpId, paths) -> StagedRevert.PlannedRestore(cpId, paths) }
    }
}

/** Disk persistence of the staged record (one per session, fail-closed on corruption). */
internal object RevertStageStore {
    private const val FILE_NAME = ".revert-stage.json"

    fun fileFor(filesDir: File, sessionId: String): File =
        File(SessionWorkspace.base(filesDir, ExecutionCoordinator.ownerSessionId(sessionId)), FILE_NAME)

    fun save(filesDir: File, sessionId: String, record: StagedRevert) {
        try {
            val dst = fileFor(filesDir, sessionId)
            dst.parentFile?.mkdirs()
            val tmp = File(dst.parentFile, ".revert-stage.tmp")
            tmp.writeText(encode(record))
            if (!tmp.renameTo(dst)) {
                dst.delete()
                check(tmp.renameTo(dst)) { "cannot persist revert record" }
            }
        } catch (e: Exception) {
            AppLogger.error("RevertStageStore", "save failed: ${e.message}")
        }
    }

    fun load(filesDir: File, sessionId: String): StagedRevert? {
        val f = fileFor(filesDir, sessionId)
        if (!f.isFile) return null
        return try {
            decode(JSONObject(f.readText()), sessionId)
        } catch (e: Exception) {
            // Fail-closed: a corrupt record is "no stage" — files stay
            // wherever they are and the tail stays visible.
            AppLogger.warning("RevertStageStore", "record corrupt, ignoring: ${e.message}")
            null
        }
    }

    fun delete(filesDir: File, sessionId: String) {
        fileFor(filesDir, sessionId).delete()
    }

    private fun encode(record: StagedRevert): String {
        val root = JSONObject()
        root.put("v", 1)
        root.put("sessionId", record.sessionId)
        root.put("boundaryMessageId", record.boundaryMessageId)
        root.put("boundarySortOrder", record.boundarySortOrder)
        root.put("boundaryCreatedAt", record.boundaryCreatedAt)
        record.originalCheckpointId?.let { root.put("originalCheckpointId", it) }
        val plan = JSONArray()
        record.plan.forEach { pr ->
            val o = JSONObject()
            o.put("cp", pr.checkpointId)
            o.put("paths", JSONArray(pr.paths))
            plan.put(o)
        }
        root.put("plan", plan)
        root.put("stagedAt", record.stagedAt)
        return root.toString()
    }

    private fun decode(root: JSONObject, sessionId: String): StagedRevert? {
        if (root.optString("sessionId") != sessionId) return null
        val plan = mutableListOf<StagedRevert.PlannedRestore>()
        val arr = root.optJSONArray("plan") ?: return null
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val paths = mutableListOf<String>()
            val pa = o.optJSONArray("paths") ?: continue
            for (j in 0 until pa.length()) paths += pa.optString(j)
            plan += StagedRevert.PlannedRestore(o.optString("cp"), paths)
        }
        return StagedRevert(
            sessionId = sessionId,
            boundaryMessageId = root.optString("boundaryMessageId"),
            boundarySortOrder = root.optInt("boundarySortOrder"),
            boundaryCreatedAt = root.optLong("boundaryCreatedAt"),
            originalCheckpointId = root.optString("originalCheckpointId").takeIf { it.isNotEmpty() },
            plan = plan,
            stagedAt = root.optLong("stagedAt"),
        )
    }
}

/**
 * In-memory mirror of the staged records, one StateFlow per session — the
 * single source of truth for the UI (banner) and for the repository-level
 * supersede hook, so a cut driven by retry/edit/delete drops the banner
 * without any ViewModel plumbing.
 */
internal object RevertStageRegistry {
    private val flows = ConcurrentHashMap<String, MutableStateFlow<StagedRevert?>>()
    private val recordFiles = ConcurrentHashMap<String, File?>()

    fun flowFor(sessionId: String): MutableStateFlow<StagedRevert?> =
        flows.getOrPut(sessionId) { MutableStateFlow(null) }

    fun get(sessionId: String): StagedRevert? = flowFor(sessionId).value

    fun set(sessionId: String, record: StagedRevert, recordFile: File?) {
        flowFor(sessionId).value = record
        recordFiles[sessionId] = recordFile
    }

    fun forceDrop(sessionId: String) {
        recordFiles.remove(sessionId)?.delete()
        flowFor(sessionId).value = null
    }

    /**
     * [T-p9-revert-supersede] A pair-repair cut at or before the staged
     * boundary removes the very tail the stage is hiding — the record must
     * go with it (the file rollback stands). A cut strictly after
     * boundary+1 only shortens the hidden window; the record survives.
     */
    fun supersede(sessionId: String, cutoffSortOrder: Int) {
        val rec = get(sessionId) ?: return
        if (cutoffSortOrder <= rec.boundarySortOrder + 1) forceDrop(sessionId)
    }
}

internal sealed class RevertStageOutcome {
    class Ok(val record: StagedRevert, val lines: List<String>) : RevertStageOutcome()

    /** Nothing after the boundary: no tail, no post-boundary file writes. */
    object Empty : RevertStageOutcome()

    class Failed(val reason: String) : RevertStageOutcome()
}

/**
 * Stage a revert at [boundaryMessageId]. See the file KDoc for the
 * three-stage semantics. [files] limits the rollback to the given guest
 * paths (null = every touched path, the opencode default).
 */
internal suspend fun ChatRepository.stageSessionRevert(
    context: Context,
    sessionId: String,
    boundaryMessageId: String,
    files: Set<String>? = null,
): RevertStageOutcome = withContext(Dispatchers.IO) {
    val boundary = runCatching { dao.getMessage(sessionId, boundaryMessageId) }.getOrNull()
        ?: return@withContext RevertStageOutcome.Failed("boundary message not found: $boundaryMessageId")
    if (boundary.sessionId != sessionId) {
        return@withContext RevertStageOutcome.Failed("boundary belongs to session ${boundary.sessionId}")
    }
    // Chained stage: undo the active one first (files back to its
    // original), then stage fresh — exactly one record, one undo basis,
    // holding the TRUE pre-stage bytes. opencode reuses the first
    // snapshot (session.revert?.snapshot ?? capture()); we cannot — our
    // originals only cover the planned paths, and a new plan can touch
    // files the old original never captured — so clear-then-stage. The
    // dropped original also keeps it OUT of the new plan: it is a
    // post-boundary checkpoint whose bytes are the already-rolled-back
    // state, not the boundary state.
    val existing = RevertStageRegistry.get(sessionId)
        ?: RevertStageStore.load(context.filesDir, sessionId)
    if (existing != null) {
        existing.originalCheckpointId?.let { orig ->
            runCatching { FileCheckpointStore.restore(context, sessionId, orig) }
            runCatching { FileCheckpointStore.drop(context.filesDir, sessionId, orig) }
        }
        RevertStageStore.delete(context.filesDir, sessionId)
        RevertStageRegistry.forceDrop(sessionId)
    }
    val hasTail = runCatching { dao.firstRowsFrom(sessionId, boundary.sortOrder + 1, 1) }
        .getOrNull()?.isNotEmpty() == true
    val checkpoints = runCatching { FileCheckpointStore.list(context, sessionId) }
        .getOrDefault(emptyList())
    val plan = RevertPlanner.plan(boundary.createdAt, checkpoints, files)
    if (plan.isEmpty() && !hasTail) return@withContext RevertStageOutcome.Empty

    // The "original" snapshot is the undo basis: the CURRENT bytes of every
    // planned path, captured BEFORE the rollback overwrites them. Refuse to
    // stage without it — a revert that cannot be cleared is a footgun.
    val original = if (plan.isEmpty()) null else runCatching {
        FileCheckpointStore.capture(
            context, sessionId, plan.flatMap { it.paths },
            label = "revert-stage original", source = "revert-stage",
        ).checkpoint
    }.getOrNull() ?: return@withContext RevertStageOutcome.Failed(
        "cannot snapshot current state — refusing to stage without an undo basis",
    )

    val lines = mutableListOf<String>()
    plan.forEach { pr ->
        val r = runCatching {
            FileCheckpointStore.restoreTargets(context, sessionId, pr.checkpointId, pr.paths.toSet())
        }.getOrNull()
        if (r == null) {
            lines += "checkpoint ${pr.checkpointId} unavailable (pruned?)"
        } else {
            lines += r.lines
        }
    }

    val record = StagedRevert(
        sessionId = sessionId,
        boundaryMessageId = boundaryMessageId,
        boundarySortOrder = boundary.sortOrder,
        boundaryCreatedAt = boundary.createdAt,
        originalCheckpointId = original?.id,
        plan = plan,
        stagedAt = System.currentTimeMillis(),
    )
    RevertStageStore.save(context.filesDir, sessionId, record)
    RevertStageRegistry.set(sessionId, record, RevertStageStore.fileFor(context.filesDir, sessionId))
    RevertStageOutcome.Ok(record, lines)
}

/**
 * Undo a staged revert: restore the original snapshot, drop the record.
 * No-op (false) when nothing is staged.
 */
internal suspend fun ChatRepository.clearSessionRevert(context: Context, sessionId: String): Boolean = withContext(Dispatchers.IO) {
    val record = RevertStageRegistry.get(sessionId)
        ?: RevertStageStore.load(context.filesDir, sessionId)
        ?: return@withContext false
    var ok = true
    record.originalCheckpointId?.let { orig ->
        val r = runCatching { FileCheckpointStore.restore(context, sessionId, orig) }.getOrNull()
        if (r == null || !r.success) ok = false
        // restore() drops the original on full success; on partial failure
        // drop it here — clear is terminal for the stage either way.
        runCatching { FileCheckpointStore.drop(context.filesDir, sessionId, orig) }
    }
    RevertStageStore.delete(context.filesDir, sessionId)
    RevertStageRegistry.forceDrop(sessionId)
    ok
}

/**
 * Finalize a staged revert: cut the tail (pair repair + version archive),
 * drop the record, keep the file rollback. Returns false when the tail
 * survived the cut — the caller must drop the send (fail-closed, the
 * same rule as truncateBeforeEdit's [T-anchor-lookup-fail-closed]).
 */
internal suspend fun ChatRepository.commitSessionRevert(
    context: Context,
    sessionId: String,
    record: StagedRevert,
): Boolean = withContext(Dispatchers.IO) {
    runCatching { deleteAfterWithPairRepair(sessionId, record.boundarySortOrder + 1) }
    val cutOk = runCatching { dao.firstRowsFrom(sessionId, record.boundarySortOrder + 1, 1) }
        .getOrNull()?.isEmpty() == true
    if (cutOk) {
        // The cut's supersede hook already dropped the record when the
        // window was covered; be explicit for the idempotent re-cut case.
        RevertStageRegistry.forceDrop(sessionId)
        RevertStageStore.delete(context.filesDir, sessionId)
    }
    cutOk
}
