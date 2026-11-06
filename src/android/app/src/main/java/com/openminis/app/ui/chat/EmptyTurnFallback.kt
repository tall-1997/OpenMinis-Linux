package com.openminis.app.ui.chat

import com.openminis.app.data.model.FallbackStrategy

/**
 * [T-b13-empty-fallback] Where an EMPTY model turn goes in a model group: the
 * same model again (the one-shot reminder round), the next member, or nowhere
 * (the empty-response hint). Port of iOS B13 (f1d0c3beb, 488fb0128,
 * 1086b19a9) and the B15 wording (6ba931405).
 *
 * Before, the agent loop's empty-turn branch never looked at the group: it
 * sent the reminder round to the model that had just come back empty and then
 * ended the run with a hint, so one member answering with nothing (or with
 * thinking only) ended the turn although the group had others. Only a stream
 * that closed with no stop reason at all reached the fallback, as an error.
 *
 * Rules (iOS owner decisions, adapted to Android's loop):
 * - Always strategy: an empty response is a failure, so the member that came
 *   back empty is not asked again. The reminder round, when one is still due,
 *   goes to the next member; otherwise the plain request does.
 * - Default (limited) strategy: the reminder round stays on the same model,
 *   as before; once it is spent (or not possible) the next member answers
 *   instead of the run ending.
 * - No other member to go to, or a session pinned to one model (no group, as
 *   iOS never leaves a pinned model): exactly the old behaviour.
 * - A DSML leak never reaches this (the loop resends it first), and a
 *   refusal or a max-tokens stop is not an empty turn.
 *
 * Which members count as "other" is the loop's business: one that already
 * came back empty in this streak, or failed with an error, is never picked
 * again, so the walk visits each member at most once.
 */
internal object EmptyTurnFallback {

    enum class Route {
        /** The one-shot reminder round, on the model that came back empty. */
        SAME_MODEL_REMINDER,

        /** The same request (reminder spent or not possible) to the next member. */
        NEXT_MEMBER,

        /** The reminder round, sent to the next member instead of this one. */
        NEXT_MEMBER_WITH_REMINDER,

        /** Nowhere to go: the empty-response hint with Resume. */
        GIVE_UP,
    }

    /**
     * @param inGroup the session is bound to a model group (not a pinned entry).
     * @param reminderDue the reminder round is still possible this run
     *   ([EmptyTurnRecovery.plan] is not GiveUp).
     * @param hasNextMember the group has a member that is neither the current
     *   one nor already spent in this streak.
     */
    fun route(
        strategy: FallbackStrategy,
        inGroup: Boolean,
        reminderDue: Boolean,
        hasNextMember: Boolean,
    ): Route {
        if (!inGroup || !hasNextMember) {
            return if (reminderDue) Route.SAME_MODEL_REMINDER else Route.GIVE_UP
        }
        return when (strategy) {
            FallbackStrategy.always ->
                if (reminderDue) Route.NEXT_MEMBER_WITH_REMINDER else Route.NEXT_MEMBER
            FallbackStrategy.default ->
                if (reminderDue) Route.SAME_MODEL_REMINDER else Route.NEXT_MEMBER
        }
    }

    /** Whether [route] moves to another member (and so needs a candidate). */
    fun movesMember(route: Route): Boolean =
        route == Route.NEXT_MEMBER || route == Route.NEXT_MEMBER_WITH_REMINDER

    /**
     * [T-ios-reasoning-only-wording] The reason recorded for the member in the
     * fallback trail ("⚠️ <model>: <reason>"). A model that answered with its
     * thinking only did not return "an empty response" in the user's eyes, and
     * is not an overloaded server: say what happened. Internal, verbatim text
     * like the trail's other reasons ("Rate limited", provider details).
     */
    fun trailReason(reasoningOnly: Boolean, afterReminder: Boolean): String = when {
        reasoningOnly && afterReminder ->
            "The model returned only its thinking after a tool result, even after a reminder — no reply and no tool call."
        reasoningOnly -> "The model returned only its thinking — no reply and no tool call."
        afterReminder -> "Empty response, even after a reminder"
        else -> "Empty response"
    }
}
