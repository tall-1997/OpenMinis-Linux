package com.openminis.app.data.repository

import com.openminis.app.data.model.ModelGroup

/**
 * [T-routing-strategy-x-fix] Result of a quota-window edit, with the window
 * already re-armed: [remaining] refills to [total] and [resetAt] moves to the
 * edit time plus [periodMs].
 */
data class EditedQuotaWindow(
    val total: Int?,
    val periodMs: Long?,
    val resetAt: Long,
    val remaining: Int?,
)

/**
 * [T-routing-strategy-x-fix] Quota-window editor math, pure and JVM-testable.
 * Parses the two editor buffers, merges them onto the group's current window
 * values, and returns the re-armed window only when something actually
 * changed — null means "nothing to persist". Editing either value re-arms
 * the whole window (remaining = total, resetAt = now + period).
 */
fun editedQuotaWindow(
    current: ModelGroup,
    totalText: String,
    periodHoursText: String,
    nowMs: Long,
): EditedQuotaWindow? {
    val parsedTotal = totalText.toIntOrNull()?.takeIf { it > 0 }
    val parsedHours = periodHoursText.toIntOrNull()?.takeIf { it > 0 }
    if (parsedTotal == null && parsedHours == null) return null
    val newTotal = parsedTotal ?: current.quotaWindowTotal
    val newPeriodMs = parsedHours?.times(3_600_000L)
        ?: (current.quotaWindowPeriodMs ?: 0L).takeIf { it > 0 }
    if (newTotal == current.quotaWindowTotal && newPeriodMs == current.quotaWindowPeriodMs) return null
    return EditedQuotaWindow(
        total = newTotal,
        periodMs = newPeriodMs,
        resetAt = nowMs + (newPeriodMs ?: 3_600_000L),
        remaining = newTotal,
    )
}
