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
 * Run records for Supervisor-Worker chats
 * (spec: docs/superpowers/specs/2026-10-24-supervisor-worker-loop-design.md §3.8).
 *
 * A session used to vanish with the process: if the app died mid-loop there
 * was no way to tell "finished" from "killed at round 7". This store keeps one
 * record per session (in memory and on disk) with per-round digests, so a cold
 * start can offer recovery: "last run reached round 7, here is the last report".
 *
 * Digests are capped hard (instruction 200 / report 500 chars): fifty rounds
 * must stay a small config file, not a transcript. The full text lives in the
 * chat transcript, same trade-off as [SubAgentBatchRegistry].
 */
object SupervisorRunStore {

    private const val TAG = "SupervisorRunStore"
    private const val DIR_NAME = "supervisor-runs"
    private const val FILE_NAME = "runs.json"

    /** Records kept per session, newest first. */
    private const val MAX_RUNS = 10

    /** Digest caps — see class KDoc. */
    private const val MAX_INSTRUCTION_DIGEST = 200
    private const val MAX_REPORT_DIGEST = 500

    /** Objective cap — the record header, not the working prompt. */
    private const val MAX_OBJECTIVE_CHARS = 500

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val locks = ConcurrentHashMap<String, ReentrantLock>()
    private val memory = ConcurrentHashMap<String, MutableList<RunRecord>>()

    const val STATE_RUNNING = "running"
    const val STATE_DONE = "done"
    const val STATE_FAILED = "failed"
    const val STATE_INTERRUPTED = "interrupted"
    const val STATE_USER_HALTED = "user_halted"

    @Serializable
    data class RoundRecord(
        val n: Int,
        val instructionDigest: String = "",
        val reportDigest: String = "",
        val atMs: Long = 0L,
    )

    @Serializable
    data class RunRecord(
        val id: String = UUID.randomUUID().toString(),
        val sessionId: String = "",
        val objective: String = "",
        val state: String = STATE_RUNNING,
        val workerTurns: Int = 0,
        val rounds: List<RoundRecord> = emptyList(),
        val doneSummary: String? = null,
        val createdAt: Long = System.currentTimeMillis(),
        val updatedAt: Long = createdAt,
    )

    /**
     * When this process first touched the store. A RUNNING record created
     * before this instant belongs to a dead process and reads as INTERRUPTED;
     * one created after it may be mid-flight and is never touched on read.
     * Injectable for tests.
     */
    internal var processStartedAt: Long = System.currentTimeMillis()

    private fun key(sessionId: String): String = sessionId.ifBlank { "__default__" }

    private fun lock(sessionId: String): ReentrantLock = locks.getOrPut(key(sessionId)) { ReentrantLock() }

    /** Session-private directory, mirroring [SubAgentBatchRegistry]. */
    private fun file(context: Context?, sessionId: String): File? {
        context ?: return null
        val owner = ExecutionCoordinator.ownerSessionId(key(sessionId))
        return File(File(SessionWorkspace.base(context.filesDir, owner), DIR_NAME), FILE_NAME)
    }

    internal fun decode(text: String): MutableList<RunRecord> =
        json.decodeFromString<List<RunRecord>>(text).toMutableList()

    internal fun encode(runs: List<RunRecord>): String =
        json.encodeToString(runs.take(MAX_RUNS))

    private fun loadLocked(sessionId: String, context: Context?): MutableList<RunRecord> {
        val k = key(sessionId)
        memory[k]?.let { return it }
        val f = file(context, k)
        val loaded: MutableList<RunRecord> = if (f == null || !f.isFile) {
            mutableListOf()
        } else {
            val text = AtomicFileWrite.read(f)
            if (text == null) {
                AppLogger.error(TAG, "cannot read run store ${f.absolutePath}")
                mutableListOf()
            } else {
                runCatching { decode(text) }.getOrElse { e ->
                    AppLogger.error(TAG, "run store corrupt at ${f.absolutePath}: ${e.message}")
                    mutableListOf()
                }
            }
        }
        memory[k] = loaded
        return loaded
    }

    private fun saveLocked(sessionId: String, context: Context?, runs: List<RunRecord>) {
        val k = key(sessionId)
        val trimmed = runs.take(MAX_RUNS)
        val f = file(context, k)
        if (f == null) {
            // No context (unit tests / headless callers): memory-only store.
            memory[k] = trimmed.toMutableList()
            return
        }
        if (AtomicFileWrite.write(f, encode(trimmed)) == null) {
            AppLogger.error(TAG, "run store persist failed for $k (${f.absolutePath})")
        }
        memory[k] = trimmed.toMutableList()
    }

    /** Begin a run: replaces any prior record for this session. */
    fun start(sessionId: String, context: Context?, objective: String, maxWorkerTurns: Int): RunRecord {
        val k = key(sessionId)
        return lock(k).withLock {
            val record = RunRecord(
                sessionId = k,
                objective = objective.trim().take(MAX_OBJECTIVE_CHARS),
                workerTurns = 0,
                rounds = emptyList(),
                state = STATE_RUNNING,
                doneSummary = null,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
            )
            saveLocked(k, context, listOf(record))
            record
        }
    }

    fun appendRound(
        sessionId: String,
        context: Context?,
        n: Int,
        instructionDigest: String,
        reportDigest: String,
    ) {
        val k = key(sessionId)
        lock(k).withLock {
            val runs = loadLocked(k, context)
            val head = runs.firstOrNull() ?: return@withLock
            val round = RoundRecord(
                n = n,
                instructionDigest = instructionDigest.trim().take(MAX_INSTRUCTION_DIGEST),
                reportDigest = reportDigest.trim().take(MAX_REPORT_DIGEST),
                atMs = System.currentTimeMillis(),
            )
            val updated = head.copy(
                rounds = head.rounds + round,
                workerTurns = head.workerTurns + 1,
                updatedAt = System.currentTimeMillis(),
            )
            runs[0] = updated
            saveLocked(k, context, runs)
        }
    }

    fun markTerminal(sessionId: String, context: Context?, state: String, summary: String? = null) {
        val k = key(sessionId)
        lock(k).withLock {
            val runs = loadLocked(k, context)
            val head = runs.firstOrNull() ?: return@withLock
            runs[0] = head.copy(state = state, doneSummary = summary, updatedAt = System.currentTimeMillis())
            saveLocked(k, context, runs)
        }
    }

    /**
     * The interrupted run for recovery, or null. Reconciles on read: a RUNNING
     * record whose `createdAt` predates [processStartedAt] was written by a
     * dead process, flips to INTERRUPTED (persisted), and is returned. A record
     * created by this process is never touched — it may be mid-flight.
     */
    fun interrupted(sessionId: String, context: Context?): RunRecord? {
        val k = key(sessionId)
        return lock(k).withLock {
            val runs = loadLocked(k, context)
            val head = runs.firstOrNull() ?: return@withLock null
            if (head.state != STATE_RUNNING) return@withLock null
            if (head.createdAt >= processStartedAt) return@withLock null
            val flipped = head.copy(state = STATE_INTERRUPTED, updatedAt = System.currentTimeMillis())
            runs[0] = flipped
            saveLocked(k, context, runs)
            flipped
        }
    }

    /**
     * The head record as stored — no reconciliation, no state filtering.
     * [interrupted] answers "is there a dead run to recover"; this answers
     * "what does the record say right now" (terminal summaries, live
     * progress, test round-trips).
     */
    fun latest(sessionId: String, context: Context?): RunRecord? {
        val k = key(sessionId)
        return lock(k).withLock {
            loadLocked(k, context).firstOrNull()
        }
    }

    fun clear(sessionId: String, context: Context?) {
        val k = key(sessionId)
        lock(k).withLock {
            memory.remove(k)
            val f = file(context, k)
            if (f != null && f.isFile) f.delete()
        }
    }

    /** Test seam: replace the head record without touching state logic. */
    internal fun internalReplaceForTest(sessionId: String, record: RunRecord) {
        val k = key(sessionId)
        lock(k).withLock {
            memory[k] = mutableListOf(record)
        }
    }

    /** Test seam: read the head record as-is. */
    internal fun internalSnapshotForTest(sessionId: String): RunRecord? {
        val k = key(sessionId)
        return lock(k).withLock { loadLocked(k, null).firstOrNull() }
    }
}
