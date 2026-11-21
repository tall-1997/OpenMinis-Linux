package com.openminis.app.goal

import android.content.Context
import com.openminis.app.data.db.SessionGoalEntity
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.sandbox.SessionWorkspace
import com.openminis.app.tools.AgentPlanStore
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * [T-task-checkpoint] File-based recovery points for long tasks.
 *
 * A checkpoint is a plan-board snapshot plus the goal contract fields. The
 * host writes one before every drift check (every 10 tool rounds) and
 * `update_goal(op=rollback)` restores the newest one, so a drifted run can
 * return to a known-good plan instead of trying to untangle itself in place.
 *
 * Storage is per-owner-session under `task-checkpoints/checkpoints.json`;
 * the newest 20 are kept (a checkpoint is tiny — plan titles, not transcripts).
 */
object TaskCheckpointStore {
    private const val DIR = "task-checkpoints"
    private const val FILE = "checkpoints.json"
    private const val MAX = 20

    data class PlanRow(val id: String, val title: String, val description: String, val status: String)

    data class Snapshot(
        val id: String,
        val createdAt: Long,
        val objective: String?,
        val acceptance: String?,
        val constraints: String?,
        val nextStep: String?,
        val plans: List<PlanRow>,
    )

    private fun file(context: Context, sessionId: String): File {
        val owner = ExecutionCoordinator.ownerSessionId(sessionId.ifBlank { "__default__" })
        return File(File(SessionWorkspace.base(context.filesDir, owner), DIR), FILE)
    }

    fun save(context: Context?, sessionId: String, goal: SessionGoalEntity?): Boolean {
        context ?: return false
        return runCatching {
            val plans = AgentPlanStore.list(sessionId, context).map {
                PlanRow(it.id, it.title, it.description, it.status)
            }
            val list = load(context, sessionId).toMutableList()
            list.add(
                0,
                Snapshot(
                    id = UUID.randomUUID().toString(),
                    createdAt = System.currentTimeMillis(),
                    objective = goal?.objective,
                    acceptance = goal?.acceptance,
                    constraints = goal?.constraints,
                    nextStep = goal?.nextStep,
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
    fun restoreLatest(context: Context?, sessionId: String): Int {
        context ?: return 0
        val snapshot = latest(context, sessionId) ?: return 0
        val inputs = snapshot.plans.map {
            AgentPlanStore.PlanInput(
                id = it.id,
                title = it.title,
                description = it.description,
                status = it.status,
            )
        }
        AgentPlanStore.replace(sessionId, context, inputs)
        return inputs.size
    }

    private fun load(context: Context, sessionId: String): List<Snapshot> = runCatching {
        val f = file(context, sessionId)
        if (!f.isFile) return emptyList()
        val root = JSONObject(f.readText())
        val arr = root.optJSONArray("checkpoints") ?: return emptyList()
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val plans = o.optJSONArray("plans") ?: JSONArray()
                val rows = buildList {
                    for (j in 0 until plans.length()) {
                        val p = plans.getJSONObject(j)
                        add(PlanRow(p.optString("id"), p.optString("title"), p.optString("description"), p.optString("status")))
                    }
                }
                add(
                    Snapshot(
                        id = o.optString("id"),
                        createdAt = o.optLong("createdAt"),
                        objective = o.optString("objective").takeIf { it.isNotEmpty() },
                        acceptance = o.optString("acceptance").takeIf { it.isNotEmpty() },
                        constraints = o.optString("constraints").takeIf { it.isNotEmpty() },
                        nextStep = o.optString("nextStep").takeIf { it.isNotEmpty() },
                        plans = rows,
                    ),
                )
            }
        }
    }.getOrDefault(emptyList())

    private fun write(context: Context, sessionId: String, snapshots: List<Snapshot>) {
        val f = file(context, sessionId)
        f.parentFile?.mkdirs()
        val arr = JSONArray()
        for (s in snapshots) {
            val o = JSONObject()
            o.put("id", s.id)
            o.put("createdAt", s.createdAt)
            o.put("objective", s.objective ?: "")
            o.put("acceptance", s.acceptance ?: "")
            o.put("constraints", s.constraints ?: "")
            o.put("nextStep", s.nextStep ?: "")
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
        val tmp = File(f.parentFile, "${f.name}.tmp")
        tmp.writeText(root.toString())
        if (!tmp.renameTo(f)) {
            f.writeText(root.toString())
            tmp.delete()
        }
    }
}
