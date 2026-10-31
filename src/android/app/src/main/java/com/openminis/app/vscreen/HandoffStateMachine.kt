package com.openminis.app.vscreen

/**
 * [T-android-vscreen2] Pure three-state handoff machine for the
 * `hand_to_user` flow (semantics per DSH docs/AGENT-SCREEN-V2.md §2).
 *
 * States seen by the model:
 *  - [State.WAITING]: the app's task was moved to the user's main screen and
 *    a "needs you" notification is up. The user picks one of the three
 *    notification actions.
 *  - [State.RETURNED]: "done" — the user finished and the task was moved
 *    back to the agent screen (muted again). The model should re-read the
 *    screen and continue.
 *  - [State.KEPT]: "kept" — the user finished the step themselves on
 *    the main screen; the app STAYS there. Verify-only: the model may read
 *    the screen but must not operate.
 *  - [State.ABORTED]: "aborted" — the user declined; the app stays on the main
 *    screen and the model ends the step.
 *
 * [State.IDLE] is the no-handoff baseline. A new handoff can start from IDLE
 * or any terminal state, never while WAITING (one live handoff per display).
 * A failed move-back does NOT advance the state — the handoff stays WAITING
 * and the failure is carried in the record's note so the tool result can
 * report it honestly.
 *
 * Pure: no Android imports, fully JVM-testable.
 */
object HandoffStateMachine {

    enum class State { IDLE, WAITING, RETURNED, KEPT, ABORTED }

    /** The three notification actions plus the initial handoff event. */
    enum class Action { START, MOVE_BACK_DONE, KEEP, ABORT }

    sealed class Transition {
        /** State after the event plus a model-facing note (null = silent). */
        data class Allowed(val state: State, val note: String? = null) : Transition()
        data class Rejected(val reason: String) : Transition()
    }

    /**
     * Apply [action] from [from]. [moveBackOk] is only meaningful for
     * [Action.MOVE_BACK_DONE] (the move-back to the agent screen succeeded).
     */
    fun transition(from: State, action: Action, moveBackOk: Boolean = true): Transition = when {
        action == Action.START ->
            if (from == State.WAITING) {
                Transition.Rejected("a handoff is already waiting for the user on this display")
            } else {
                Transition.Allowed(State.WAITING)
            }
        from != State.WAITING ->
            Transition.Rejected("no handoff is waiting (state=$from); start one with action=hand_to_user")
        action == Action.MOVE_BACK_DONE ->
            if (moveBackOk) {
                Transition.Allowed(State.RETURNED)
            } else {
                Transition.Allowed(State.WAITING, "move-back to the agent screen failed — still waiting for the user")
            }
        action == Action.KEEP -> Transition.Allowed(State.KEPT)
        action == Action.ABORT -> Transition.Allowed(State.ABORTED)
        else -> Transition.Rejected("unreachable: action $action from state $from")
    }

    /**
     * Model-facing guidance for a display in [state], phrased for tool
     * results. Returns null for IDLE (nothing to annotate).
     */
    fun guidance(state: State, packageName: String?, kind: String?): String? = when (state) {
        State.IDLE -> null
        State.WAITING ->
            "handoff WAITING (kind=$kind): ${packageName ?: "the app"} is on the user's main " +
                "screen and a 'needs you' notification is up. Do not operate the main screen; " +
                "the user will pick one of the three actions and later results here will report it."
        State.RETURNED ->
            "handoff RETURNED: the user handled it and ${packageName ?: "the app"} was moved back " +
                "to the agent screen — re-read the screen (action=screenshot) and continue from there."
        State.KEPT ->
            "handoff KEPT: the user finished the step themselves on their screen. Verify with " +
                "action=screenshot only — do not operate this app any more."
        State.ABORTED ->
            "handoff ABORTED: the user declined to handle this step. Skip it if you can, " +
                "otherwise stop and summarize what is blocked."
    }

    /** Wire values accepted for the tool's `kind` parameter. */
    val KINDS = listOf("login", "captcha", "payment", "secure_page", "choose", "other")

    /** Parses a wire kind, rejecting anything outside [KINDS]. */
    fun parseKind(raw: String?): String? {
        val k = raw?.trim()?.lowercase().orEmpty()
        return if (k.isEmpty()) "other" else if (k in KINDS) k else null
    }
}
