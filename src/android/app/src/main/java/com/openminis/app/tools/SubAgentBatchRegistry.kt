package com.openminis.app.tools

import android.content.Context
import com.openminis.app.logging.AppLogger
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.sandbox.SessionWorkspace
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

/**
 * Registry of sub-agent dispatch waves.
 *
 * A wave used to exist only as the local variables of one `spawn_agent` call:
 * when the call returned, the only trace was the tool block text. If the model
 * never wrote a plan item, or the process died mid-wave, there was no way to
 * answer "what was dispatched, how far did each lane get, which one failed".
 * This registry keeps that state per session, in memory and on disk, and links
 * each lane to its [AgentPlanStore] item so progress survives a cold start.
 *
 * Persistence holds metadata plus a short result digest; the full report stays
 * in memory only (and in the chat transcript), because eight lanes of 64 KB
 * reports are not a config file.
 */
object SubAgentBatchRegistry {

    private const val TAG = "SubAgentBatchRegistry"
    private const val DIR_NAME = "subagent-batches"
    private const val FILE_NAME = "batches.json"

    /** Batches kept per session, newest first. */
    private const val MAX_BATCHES = 20

    /** Result text persisted per lane. Full output lives in the transcript. */
    private const val MAX_DIGEST_CHARS = 2_000

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val memory = ConcurrentHashMap<String, MutableList<Batch>>()

    const val STATE_PENDING = "pending"
    const val STATE_RUNNING = "running"
    const val STATE_DONE = "done"
    const val STATE_FAILED = "failed"
    const val STATE_STOPPED = "stopped"

    private val STATES = setOf(STATE_PENDING, STATE_RUNNING, STATE_DONE, STATE_FAILED, STATE_STOPPED)

    @Serializable
    data class Lane(
        val index: Int,
        val kind: String = SubAgentKind.WORKER,
        val title: String = "",
        /** First characters of the brief, so a lane can be re-dispatched later. */
        val promptDigest: String = "",
        val state: String = STATE_PENDING,
        /** Linked [AgentPlanStore.Plan.id]; null when no board item was created. */
        val planId: String? = null,
        val trackerId: String? = null,
        val model: String = "",
        val attempts: Int = 0,
        val error: String? = null,
        val digest: String = "",
        val startedAt: Long = 0L,
        val finishedAt: Long = 0L,
    ) {
        val finished: Boolean get() = state == STATE_DONE || state == STATE_FAILED || state == STATE_STOPPED
    }

    @Serializable
    data class Batch(
        val id: String = UUID.randomUUID().toString(),
        val sessionId: String = "",
        val toolId: String = "",
        val assistantId: String = "",
        /** True when dispatched detached: the parent loop did not wait for it. */
        val background: Boolean = false,
        val createdAt: Long = System.currentTimeMillis(),
        val finishedAt: Long = 0L,
        /**
         * When the parent loop actually took the results (check_agent
         * collect/await). A background batch that finished but was never
         * collected is exactly the "task silently lost" case the agent loop
         * nudges about, so it needs to be distinguishable from one that was.
         * 0 = nobody has read the outcome yet.
         */
        val collectedAt: Long = 0L,
        val lanes: List<Lane> = emptyList(),
    ) {
        val done: Int get() = lanes.count { it.state == STATE_DONE }
        val failed: Int get() = lanes.count { it.state == STATE_FAILED || it.state == STATE_STOPPED }
        val running: Int get() = lanes.count { !it.finished }
        val complete: Boolean get() = lanes.isNotEmpty() && running == 0
    }

    /** What the caller knows at dispatch time; the rest fills in as lanes run. */
    data class LaneSeed(
        val index: Int,
        val kind: String,
        val title: String,
        val prompt: String,
        val planId: String? = null,
    )

    /**
     * When this process first touched the registry, i.e. the start of "now".
     * A batch created before this instant was written by a process that has
     * since died, so its unfinished lanes are orphans. A batch created after it
     * may well have lanes mid-flight — or still sitting in their pre-launch
     * `pending` window — and must be left alone. Injectable for tests.
     */
    internal var processStartedAt: Long = System.currentTimeMillis()

    private fun key(sessionId: String): String = sessionId.ifBlank { "__default__" }
    private fun lock(sessionId: String): ReentrantLock = locks.getOrPut(key(sessionId)) { ReentrantLock() }

    /**
     * Session-private directory, mirroring [AgentPlanStore]: `workspace` is a
     * shared subdir, so two chats in one project folder would clobber a single
     * registry file.
     */
    private fun file(context: Context?, sessionId: String): File? {
        context ?: return null
        val owner = ExecutionCoordinator.ownerSessionId(key(sessionId))
        return File(File(SessionWorkspace.base(context.filesDir, owner), DIR_NAME), FILE_NAME)
    }

    internal fun decode(text: String): MutableList<Batch> =
        json.decodeFromString<List<Batch>>(text).toMutableList()

    internal fun encode(batches: List<Batch>): String =
        json.encodeToString(batches.take(MAX_BATCHES))

    private fun loadLocked(sessionId: String, context: Context?): MutableList<Batch> {
        val k = key(sessionId)
        memory[k]?.let { return it }
        val f = file(context, k)
        val loaded: MutableList<Batch> = if (f == null || !f.isFile) {
            mutableListOf()
        } else {
            // A corrupt registry must not decode to an empty list: the next
            // dispatch would then overwrite the file and erase the evidence.
            val text = AtomicFileWrite.read(f)
            if (text == null) {
                AppLogger.error(TAG, "cannot read batch registry ${f.absolutePath}")
                mutableListOf()
            } else {
                runCatching { decode(text) }.getOrElse { e ->
                    AppLogger.error(TAG, "batch registry corrupt at ${f.absolutePath}: ${e.message}")
                    mutableListOf()
                }
            }
        }
        memory[k] = loaded
        return loaded
    }

    private fun saveLocked(sessionId: String, context: Context?, batches: List<Batch>) {
        val k = key(sessionId)
        val trimmed = batches.take(MAX_BATCHES)
        val f = file(context, k)
        if (f == null) {
            // No context (unit tests / headless callers): memory-only registry.
            memory[k] = trimmed.toMutableList()
            return
        }
        // Persist failure is logged, not thrown: the wave is already running and
        // killing a dispatch because a status file could not be written would
        // trade a real result for bookkeeping. Memory still advances.
        if (AtomicFileWrite.write(f, encode(trimmed)) == null) {
            AppLogger.error(TAG, "batch registry persist failed for $k (${f.absolutePath})")
        }
        memory[k] = trimmed.toMutableList()
    }

    /** Register a wave and return its id. */
    fun begin(
        sessionId: String,
        context: Context?,
        toolId: String,
        assistantId: String,
        background: Boolean,
        seeds: List<LaneSeed>,
    ): Batch {
        val batch = Batch(
            sessionId = key(sessionId),
            toolId = toolId,
            assistantId = assistantId,
            background = background,
            lanes = seeds.map { s ->
                Lane(
                    index = s.index,
                    kind = s.kind,
                    title = s.title,
                    promptDigest = s.prompt.trim().take(MAX_DIGEST_CHARS),
                    planId = s.planId,
                )
            },
        )
        lock(sessionId).withLock {
            val all = loadLocked(sessionId, context)
            saveLocked(sessionId, context, listOf(batch) + all)
        }
        return batch
    }

    private fun mutateLane(
        sessionId: String,
        context: Context?,
        batchId: String,
        index: Int,
        transform: (Lane) -> Lane,
    ) {
        lock(sessionId).withLock {
            val all = loadLocked(sessionId, context)
            val at = all.indexOfFirst { it.id == batchId }
            if (at < 0) return@withLock
            val batch = all[at]
            val laneAt = batch.lanes.indexOfFirst { it.index == index }
            if (laneAt < 0) return@withLock
            val lanes = batch.lanes.toMutableList().also { it[laneAt] = transform(it[laneAt]) }
            val next = batch.copy(
                lanes = lanes,
                finishedAt = if (lanes.all { it.finished } && batch.finishedAt == 0L) {
                    System.currentTimeMillis()
                } else {
                    batch.finishedAt
                },
            )
            val out = all.toMutableList().also { it[at] = next }
            saveLocked(sessionId, context, out)
        }
    }

    fun markRunning(
        sessionId: String,
        context: Context?,
        batchId: String,
        index: Int,
        trackerId: String?,
        model: String,
        attempt: Int,
    ) = mutateLane(sessionId, context, batchId, index) { lane ->
        lane.copy(
            state = STATE_RUNNING,
            trackerId = trackerId ?: lane.trackerId,
            model = model.ifBlank { lane.model },
            attempts = attempt,
            startedAt = if (lane.startedAt == 0L) System.currentTimeMillis() else lane.startedAt,
        )
    }

    fun markFinished(
        sessionId: String,
        context: Context?,
        batchId: String,
        index: Int,
        success: Boolean,
        output: String,
        error: String?,
        attempts: Int,
        stopped: Boolean = false,
    ) = mutateLane(sessionId, context, batchId, index) { lane ->
        lane.copy(
            state = when {
                stopped -> STATE_STOPPED
                success -> STATE_DONE
                else -> STATE_FAILED
            },
            digest = output.trim().take(MAX_DIGEST_CHARS),
            error = error?.take(400),
            attempts = maxOf(attempts, lane.attempts),
            finishedAt = System.currentTimeMillis(),
        )
    }

    fun attachPlan(sessionId: String, context: Context?, batchId: String, index: Int, planId: String) =
        mutateLane(sessionId, context, batchId, index) { it.copy(planId = planId) }

    fun get(sessionId: String, context: Context?, batchId: String): Batch? =
        lock(sessionId).withLock { loadLocked(sessionId, context).find { it.id == batchId } }

    fun list(sessionId: String, context: Context?): List<Batch> =
        lock(sessionId).withLock { loadLocked(sessionId, context).toList() }

    /**
     * Background batches that still owe the parent loop a result: lanes in
     * flight, or a wave that finished and was never collected. Inline batches
     * are excluded by design — their reports were returned synchronously, so
     * there is nothing left to pick up and nudging about them would be noise.
     */
    fun unfinished(sessionId: String, context: Context?): List<Batch> {
        if (sessionId.isBlank()) return emptyList()
        runCatching { reconcileAfterRestart(sessionId, context) }
        return list(sessionId, context).filter { b ->
            b.background && (!b.complete || b.collectedAt == 0L)
        }
    }

    /** Record that the parent loop took this batch's outcome. Idempotent. */
    fun markCollected(sessionId: String, context: Context?, batchId: String) =
        lock(sessionId).withLock {
            val all = loadLocked(sessionId, context)
            val idx = all.indexOfFirst { it.id == batchId }
            if (idx < 0 || all[idx].collectedAt != 0L) return@withLock
            all[idx] = all[idx].copy(collectedAt = System.currentTimeMillis())
            saveLocked(sessionId, context, all)
        }

    /**
     * Drop finished waves. A background wave whose reports nobody collected is
     * kept unless [includeUncollected] is set: `check_agent op=clear` is
     * housekeeping, and deleting the only copy of a finished lane report would
     * quietly turn "clean up" into "lose the work". Returns
     * (removed, keptUncollected).
     */
    fun clearFinished(
        sessionId: String,
        context: Context?,
        includeUncollected: Boolean = false,
    ): Pair<Int, Int> = lock(sessionId).withLock {
        val all = loadLocked(sessionId, context)
        fun isUncollected(b: Batch) = b.background && b.complete && b.collectedAt == 0L
        val doomedIds = all
            .filter { it.complete && (includeUncollected || !isUncollected(it)) }
            .map { it.id }
            .toSet()
        val keep = all.filterNot { it.id in doomedIds }
        val removed = all.size - keep.size
        val keptUncollected = if (includeUncollected) 0 else all.count { isUncollected(it) }
        if (removed > 0) saveLocked(sessionId, context, keep)
        removed to keptUncollected
    }

    /**
     * Drop in-flight lanes of a session whose process died. Called on session
     * open: a lane marked `running` with no live tracker is a lie, and leaving
     * it there would make the loop warn about unfinished work forever.
     */
    fun reconcileAfterRestart(sessionId: String, context: Context?): Int = lock(sessionId).withLock {
        val all = loadLocked(sessionId, context)
        var touched = 0
        val out = all.map { batch ->
            // Guard: only a batch from a PREVIOUS process can hold orphans.
            // Reconciling this process's own batches would make every
            // `check_agent op=status` poll report "the wave died" while it is
            // still running, and a lane waiting to be launched would not
            // survive a single status call.
            if (batch.createdAt >= processStartedAt) return@map batch
            if (batch.lanes.none { it.state == STATE_RUNNING || it.state == STATE_PENDING }) return@map batch
            touched++
            batch.copy(
                lanes = batch.lanes.map { lane ->
                    if (lane.state == STATE_RUNNING || lane.state == STATE_PENDING) {
                        lane.copy(
                            state = STATE_STOPPED,
                            error = lane.error ?: "interrupted: process restarted before this lane finished",
                            finishedAt = System.currentTimeMillis(),
                        )
                    } else {
                        lane
                    }
                },
                finishedAt = if (batch.finishedAt == 0L) System.currentTimeMillis() else batch.finishedAt,
            )
        }
        if (touched > 0) saveLocked(sessionId, context, out)
        touched
    }

    fun render(batch: Batch, verbose: Boolean = false): String = buildString {
        append("dispatch ").append(batch.id.take(8))
        append(if (batch.background) " (background)" else " (inline)")
        append(": ").append(batch.lanes.size).append(" lane(s) — ")
        append(batch.done).append(" done, ").append(batch.failed).append(" failed, ")
        append(batch.running).append(" running\n")
        batch.lanes.forEach { lane ->
            val mark = when (lane.state) {
                STATE_DONE -> "x"
                STATE_FAILED -> "!"
                STATE_STOPPED -> "s"
                STATE_RUNNING -> ">"
                else -> " "
            }
            append("[$mark] ").append(lane.index).append(". ")
            append(lane.title.ifBlank { lane.kind })
            append(" {").append(lane.state).append("}")
            if (lane.model.isNotBlank()) append(" · ").append(lane.model)
            if (lane.attempts > 1) append(" · attempts=").append(lane.attempts)
            append('\n')
            if (lane.error != null) append("    error: ").append(lane.error).append('\n')
            if (verbose && lane.digest.isNotBlank()) {
                append("    ").append(lane.digest.replace("\n", "\n    ")).append('\n')
            }
        }
    }

    /** One-line summary used to nudge the model before a turn ends. */
    fun renderPending(batches: List<Batch>): String {
        if (batches.isEmpty()) return ""
        val inFlight = batches.filterNot { it.complete }
        val uncollected = batches.filter { it.complete }
        val parts = buildList {
            if (inFlight.isNotEmpty()) {
                add(
                    inFlight.joinToString(", ") { it.id.take(8) } +
                        " with " + inFlight.sumOf { it.running } + " lane(s) still in flight",
                )
            }
            if (uncollected.isNotEmpty()) {
                add(
                    uncollected.joinToString(", ") { it.id.take(8) } +
                        " finished with reports nobody collected",
                )
            }
        }
        return "Unfinished sub-agent dispatch(es): " + parts.joinToString("; ") + "."
    }

    internal fun requireState(state: String) {
        require(state in STATES) { "state must be one of $STATES" }
    }
}
