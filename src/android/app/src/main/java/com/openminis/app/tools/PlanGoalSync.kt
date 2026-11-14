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
 *
 * [T-goal-not-evicted-by-fallback] The harness contract defines `tool_title`
 * as a 5-10 word summary of what THIS CALL does — an action phrase, not an
 * objective. Minting the session Goal from it produced goals like
 * "update the board progress" that no work can ever complete, and the
 * replacement semantics of [GoalManager.createByModel] re-minted one on every
 * objective-less board rewrite. Two rules close that loop: `tool_title` is
 * never an objective source, and a fallback (first board item) may only FILL
 * an empty seat — a Goal already in place survives objective-less board
 * writes untouched. Only an explicit `objective` argument replaces a Goal.
 */
internal object PlanGoalSync {

    /** Ops that (re)define the board, i.e. the moments an objective is due. */
    private val BOARD_WRITE_OPS = setOf("add", "replace")

    /**
     * The objective to record, in descending order of how explicitly the model
     * stated it: the dedicated `objective` argument, then the first board
     * item's title. `tool_title` is deliberately absent — the tool contract
     * defines it as a summary of THIS CALL's action, never of the session's
     * work — see [T-goal-not-evicted-by-fallback] in the class KDoc. Returns
     * null when nothing carries text, so a board of blank items cannot mint an
     * empty Goal.
     */
    internal fun objectiveFrom(args: JSONObject): String? {
        args.optString("objective").trim().takeIf { it.isNotEmpty() }?.let { return it }
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
     * Ensure the session Goal reflects [argsJson]. Layered by how explicitly
     * the model stated the objective ([T-goal-not-evicted-by-fallback]):
     *
     * 1. explicit `objective` argument → recorded via [GoalManager.createByModel]
     *    (its replacement semantics are the model re-scoping its own Goal);
     * 2. no explicit objective, Goal already in place → returned untouched —
     *    a fallback (first board item) may only FILL an empty seat, never
     *    evict a sitting Goal, so objective-less board progress rewrites are
     *    inert with respect to the standing objective;
     * 3. no explicit objective, no Goal → minted from [objectiveFrom]'s
     *    fallback chain.
     *
     * Returns the Goal that ended up in place, or null when nothing could be
     * recorded (no objective text, or persistence refused).
     */
    suspend fun ensureGoal(argsJson: String, sessionId: String, manager: GoalManager): SessionGoalEntity? {
        if (!applies(argsJson)) return null
        val args = runCatching { JSONObject(argsJson) }.getOrNull() ?: return null
        val explicit = args.optString("objective").trim().takeIf { it.isNotEmpty() }
        if (explicit != null) {
            return runCatching { manager.createByModel(sessionId, explicit) }
                .onFailure { AppLogger.warning("PlanGoalSync", "could not record the goal: ${it.message}") }
                .getOrNull()
        }
        manager.get(sessionId)?.let { return it }
        // [T-goal-mint-guard] The first objective-less board write on an empty
        // seat must NOT mint a Goal from a step title ("read the config file"
        // as a session objective can never complete). Minting from the
        // fallback chain requires the board to look like a real plan
        // (≥2 items) — a single-item board write is progress logging, not
        // goal-setting. Explicit objectives bypass this guard entirely.
        val board = runCatching { JSONArray(args.optString("plans")) }.getOrNull()
            ?: JSONArray().put(JSONObject().put("title", args.optString("title")))
        val mintable = board.length() >= 2
        val objective = objectiveFrom(args) ?: return null
        if (!mintable) {
            AppLogger.info("PlanGoalSync", "objective-less single-item board write — no Goal minted")
            return null
        }
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
