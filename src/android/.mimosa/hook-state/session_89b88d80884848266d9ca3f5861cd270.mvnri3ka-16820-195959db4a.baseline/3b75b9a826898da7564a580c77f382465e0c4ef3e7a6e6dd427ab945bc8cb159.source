package com.openminis.app.data.body

/**
 * Byte budget for a session preview. Row count is not a limit.
 */
object PreviewBudget {
    fun canTake(used: Long, rowBytes: Long, budget: Long): Boolean {
        if (rowBytes < 0 || budget < 0) return false
        if (rowBytes > budget) return false
        return used + rowBytes <= budget
    }

    fun fittingCount(rowBytes: Long, rows: Long, budget: Long): Long {
        if (rowBytes <= 0 || rows < 0 || budget < 0) return 0
        return minOf(rows, budget / rowBytes)
    }

    /**
     * A page must stay a contiguous slice. Rows that do not fit are not skipped;
     * the walk stops. The boundary row is still kept, so one oversized message
     * cannot blank the newest tail or open a hole in the middle of the page.
     */
    fun decide(used: Long, rowBytes: Long, budget: Long, alreadyTaken: Int): BudgetDecision {
        if (canTake(used, rowBytes, budget)) return BudgetDecision.TAKE
        if (alreadyTaken == 0 && rowBytes >= 0 && budget >= 0) return BudgetDecision.TAKE_AND_STOP
        return BudgetDecision.STOP
    }
}

enum class BudgetDecision {
    TAKE,
    TAKE_AND_STOP,
    STOP,
}
