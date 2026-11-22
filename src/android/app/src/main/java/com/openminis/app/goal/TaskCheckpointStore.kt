package com.openminis.app.goal

import android.content.Context
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.sandbox.SessionWorkspace
import com.openminis.app.tools.AgentPlanStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * [T-task-checkpoint] File-based recovery points for long tasks.
 *
 * A checkpoint is a plan-board snapshot plus the goal contract as it was
 * rendered for the model (`goalPrompt` carries objective + acceptance +
 * constraints + next_step). The host writes one before every drift check
 * (every 10 tool rounds) and `update_goal(op=rollback)` restores the newest
 * one, so a drifted run can return to a known-good plan instead of trying to
 * untangle itself in place.
 *
 * Storage is per-owner-session under `task-checkpoints/checkpoints.json`;
 * the newest [MAX] are kept (a checkpoint is tiny — plan titles, not
 * transcripts). All disk IO runs on [Dispatchers.IO]: callers are coroutines
 * on the chat path and must never block the main thread on a JSON rewrite.
 */
object TaskCheckpointStore {
    private const val DIR = "task-checkpoints"
    private const val FILE = "checkpoints.json"

    /** Hard cap on retained recovery points; older ones fall off the tail. */
    const val MAX = 20

    data class PlanRow(val id: String, val title: String, val description: String, val status: String)

    data class Snapshot(
        val id: String,
        val createdAt: Long,
        val objective: String?,
        val goalPrompt: String?,
        val plans: List<PlanRow>,
    )

    private fun file(context: Context, sessionId: String): File {
        val owner = ExecutionCoordinator.ownerSessionId(sessionId.ifBlank { "__default__" })
        return File(File(SessionWorkspace.base(context.filesDir, owner), DIR), FILE)
    }

    suspend fun save(
        context: Context?,
        sessionId: String,
        objective: String?,
        goalPrompt: String?,
    ): Boolean = withContext(Dispatchers.IO) {
        context ?: return@withContext false
        runCatching {
            val plans = AgentPlanStore.list(sessionId, context).map {
                PlanRow(it.id, it.title, it.description, it.status)
            }
            val list = load(context, sessionId).toMutableList()
            list.add(
                0,
                Snapshot(
                    id = UUID.randomUUID().toString(),
                    createdAt = System.currentTimeMillis(),
                    objective = objective?.trim()?.takeIf { it.isNotEmpty() },
                    goalPrompt = goalPrompt?.takeIf { it.isNotBlank() },
                    plans = plans,
                ),
            )
            write(context, sessionId, list.take(MAX))
            true
        }.getOrDefault(false)
    }

    fun latest(context: Context?, sessionId: String): Snapshot? {
        context ?: return null
        return load(context, sessionId).firstOrNull()
    }

    /** Restores the newest checkpoint's plan board. Returns restored item count. */
    suspend fun restoreLatest(context: Context?, sessionId: String): Int = withContext(Dispatchers.IO) {
        context ?: return@withContext 0
        val snapshot = latest(context, sessionId) ?: return@withContext 0
        val inputs = snapshot.plans.map {
            AgentPlanStore.PlanInput(
                id = it.id,
                title = it.title,
                description = it.description,
                status = it.status,
            )
        }
        runCatching { AgentPlanStore.replace(sessionId, context, inputs) }
            .map { inputs.size }
            .getOrDefault(0)
    }

    private fun load(context: Context, sessionId: String): List<Snapshot> = runCatching {
        val f = file(context, sessionId)
        if (!f.isFile) return@runCatching emptyList()
        val root = JSONObject(f.readText())
        val arr = root.optJSONArray("checkpoints") ?: return@runCatching emptyList()
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val plans = o.optJSONArray("plans") ?: JSONArray()
                val rows = buildList {
                    for (j in 0 until plans.length()) {
                        val p = plans.getJSONObject(j)
                        add(
                            PlanRow(
                                p.optString("id"),
                                p.optString("title"),
                                p.optString("description"),
                                p.optString("status"),
                            ),
                        )
                    }
                }
                add(
                    Snapshot(
                        id = o.optString("id"),
                        createdAt = o.optLong("createdAt"),
                        objective = o.optString("objective").takeIf { it.isNotEmpty() },
                        goalPrompt = o.optString("goalPrompt").takeIf { it.isNotEmpty() },
                        plans = rows,
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun write(context: Context, sessionId: String, snapshots: List<Snapshot>) {
        val f = file(context, sessionId)
        val parent = f.parentFile ?: return
        parent.mkdirs()
        val arr = JSONArray()
        for (s in snapshots) {
            val o = JSONObject()
            o.put("id", s.id)
            o.put("createdAt", s.createdAt)
            o.put("objective", s.objective ?: "")
            o.put("goalPrompt", s.goalPrompt ?: "")
            val plans = JSONArray()
            for (p in s.plans) {
                plans.put(
                    JSONObject()
                        .put("id", p.id)
                        .put("title", p.title)
                        .put("description", p.description)
                        .put("status", p.status),
                )
            }
            o.put("plans", plans)
            arr.put(o)
        }
        val root = JSONObject().put("checkpoints", arr)
        val tmp = File(parent, "${f.name}.tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(f)) {
            f.writeText(root.toString())
            tmp.delete()
        }
    }
}
