package com.openminis.app.ui

import com.openminis.app.R
import com.openminis.app.data.model.RoutingStrategy

/**
 * [T-routing-strategy-x] UI-side helpers for the five new scheduling
 * strategies. Everything here is a pure function so the picker badge, the
 * group-list labels and the settings editors share one source of truth that
 * plain JUnit (and the sandbox /tmp driver) can pin without Compose or the
 * Android runtime.
 *
 * - [shortBadge]: compact label for the picker sheet (FB/LB keep their
 *   pre-x values; the new strategies use two-letter badges).
 * - [parseWeight] / [parseTimeoutMs] / [quotaRemainingAfterSave]: tolerant
 *   parsing for the settings text fields. Garbage, zero, negatives and
 *   decimals never crash the screen and never store a broken value in the
 *   group config.
 * - [nextQuotaReset]: window arithmetic shared by the quota editor.
 */
object RoutingStrategyUi {

    /** Compact badge for picker/group rows. FB and LB are pre-x values. */
    fun shortBadge(strategy: RoutingStrategy): String = when (strategy) {
        RoutingStrategy.fallback -> "FB"
        RoutingStrategy.loadBalance -> "LB"
        RoutingStrategy.roundRobin -> "RR"
        RoutingStrategy.weighted -> "WT"
        RoutingStrategy.leastUsed -> "LU"
        RoutingStrategy.quotaWindow -> "QW"
        RoutingStrategy.smart -> "SM"
    }

    /**
     * Weight field parser: integer >= 1. Blank, junk, zero, negatives and
     * decimals all collapse to 1 (a member with weight 0 would be dead
     * weight in the roulette wheel; the picker already coerces >= 1, the
     * editor must agree).
     */
    fun parseWeight(text: String): Int =
        (text.trim().toIntOrNull() ?: 1).coerceAtLeast(1)

    /**
     * First-token timeout parser (seconds in, ms out). Blank and <= 0 mean
     * "no override" — null restores the default budget. 0 must NOT become a
     * zero-millisecond timeout, which would kill every first-token wait.
     */
    fun parseTimeoutMs(text: String): Int? {
        val seconds = text.trim().toIntOrNull() ?: return null
        if (seconds <= 0) return null
        return seconds * 1000
    }

    /** Remaining-quota parser: never above [total], junk collapses to 1. */
    fun quotaRemainingAfterSave(text: String, total: Int): Int =
        parseWeight(text).coerceAtMost(total)

    /** Window arithmetic: the reset deadline is "now + period". */
    fun nextQuotaReset(nowMs: Long, periodMs: Long): Long = nowMs + periodMs

    /**
     * [T-routing-strategy-x] Editor/row title per strategy. FB and LB keep
     * their pre-x string keys; the group-list rows reuse the same mapping so
     * the picker, the rows and the editor can never disagree.
     */
    fun titleRes(strategy: RoutingStrategy): Int = when (strategy) {
        RoutingStrategy.fallback -> R.string.model_group_detail_fallback
        RoutingStrategy.loadBalance -> R.string.model_group_detail_load_balance
        RoutingStrategy.roundRobin -> R.string.model_group_detail_round_robin
        RoutingStrategy.weighted -> R.string.model_group_detail_weighted
        RoutingStrategy.leastUsed -> R.string.model_group_detail_least_used
        RoutingStrategy.quotaWindow -> R.string.model_group_detail_quota_window
        RoutingStrategy.smart -> R.string.model_group_detail_smart
    }

    /**
     * [T-routing-strategy-x] Editor footer hint per strategy. FB and LB keep
     * their pre-x string keys.
     */
    fun footerRes(strategy: RoutingStrategy): Int = when (strategy) {
        RoutingStrategy.fallback -> R.string.model_group_detail_routing_fallback_footer
        RoutingStrategy.loadBalance -> R.string.model_group_detail_routing_load_balance_footer
        RoutingStrategy.roundRobin -> R.string.model_group_detail_routing_round_robin_footer
        RoutingStrategy.weighted -> R.string.model_group_detail_routing_weighted_footer
        RoutingStrategy.leastUsed -> R.string.model_group_detail_routing_least_used_footer
        RoutingStrategy.quotaWindow -> R.string.model_group_detail_routing_quota_window_footer
        RoutingStrategy.smart -> R.string.model_group_detail_routing_smart_footer
    }
}
