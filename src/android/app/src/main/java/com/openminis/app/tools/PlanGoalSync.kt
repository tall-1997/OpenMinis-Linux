package com.openminis.app.tools

import com.openminis.app.data.db.SessionGoalEntity
import com.openminis.app.data.repository.ChatRepository
import com.openminis.app.goal.GoalManager
import com.openminis.app.logging.AppLogger
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-goal-created-with-plan] Creating the task board creates the task
 * objective, in the same step.
 *
 * The harness this app mirrors has `create_goal`: a long-running objective is
 * its own durable record, not a transient argument that disappears with the
 * tool call. Here the board (`agent_plan`) carries the steps and the Goal
 * carries the objective, so opening a board for a real objective must leave a
 * Goal behind — otherwise the objective exists only in the model's context,
 * the Goal card stays empty, and once the turn ends nothing knows what the
 * board was for.
 *
 * Called from the tool dispatcher after a successful `agent_plan` mutation.
 * It never fails the tool: a Goal that cannot be written must not turn a
 * successful board update into an error the model then retries.
 */
internal object PlanGoalSync {

    /** Ops that (re)define the board, i.e. the moments an objective is due. */
    private val BOARD_WRITE_OPS = setOf("add", "replace")

    /**
     * The objective to record, in descending order of how explicitly the model
     * stated it: the dedicated `objective` argument, then the human-facing
     * `tool_title`, then the first board item's title. Returns null when none of
     * them carries text, so a board of blank items cannot mint an empty Goal.
     */
    internal fun objectiveFrom(args: JSONObject): String? {
        args.optString("objective").trim().takeIf { it.isNotEmpty() }?.let { return it }
        args.optString("tool_title").trim().takeIf { it.isNotEmpty() }?.let { return it }
        val plans = args.optJSONArray("plans")
            ?: runCatching { JSONArray(args.optString("plans")) }.getOrNull()
            ?: return args.optString("title").trim().takeIf { it.isNotEmpty() }
        for (i in 0 until plans.length()) {
            val title = plans.optJSONObject(i)?.optString("title").orEmpty().trim()
            if (title.isNotEmpty()) return title
        }
        return args.optString("title").trim().takeIf { it.isNotEmpty() }
    }

    /** True when [argsJson] is a board write that should also establish the Goal. */
    internal fun applies(argsJson: String): Boolean {
        val op = runCatching { JSONObject(argsJson).optString("op", "list") }.getOrDefault("list")
        return op in BOARD_WRITE_OPS
    }

    /**
     * Ensure the session Goal exists, deriving its objective from [argsJson].
     * Returns the Goal that ended up in place, or null when nothing could be
     * recorded (no objective text, or persistence refused).
     */
    suspend fun ensureGoal(argsJson: String, sessionId: String, manager: GoalManager): SessionGoalEntity? {
        if (!applies(argsJson)) return null
        val objective = runCatching { JSONObject(argsJson) }.getOrNull()
            ?.let { objectiveFrom(it) }
            ?: return null
        return runCatching { manager.createByModel(sessionId, objective) }
            .onFailure { AppLogger.warning("PlanGoalSync", "could not record the goal: ${it.message}") }
            .getOrNull()
    }

    /**
     * Dispatcher entry point: derive the objective from the SAME arguments the
     * board tool just accepted. [succeeded] gates on the board update having
     * actually landed, so a rejected board never leaves an orphan Goal.
     */
    suspend fun onBoardWritten(
        argsJson: String,
        succeeded: Boolean,
        sessionId: String,
        repository: ChatRepository,
    ): SessionGoalEntity? {
        if (!succeeded) return null
        return ensureGoal(argsJson, sessionId, GoalManager(repository))
    }
}
