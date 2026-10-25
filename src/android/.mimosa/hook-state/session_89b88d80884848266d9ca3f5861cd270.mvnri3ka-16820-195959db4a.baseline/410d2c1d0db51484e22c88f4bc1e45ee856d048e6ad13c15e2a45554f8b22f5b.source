package com.openminis.app.ui.chat.retention

import com.openminis.app.ui.chat.IdleSessionBudget

/**
 * Counts pinned ViewModels and their resident text, including agent history
 * that [com.openminis.app.ui.chat.ChatViewModel.retainedTextBytes] reports.
 * A pin is not an exemption once the process is over the byte budget.
 */
internal object RetentionLedger {
    fun pinnedCount(entries: List<IdleSessionBudget.Entry>): Int = entries.count { it.pinned }

    fun residentBytes(entries: List<IdleSessionBudget.Entry>): Long =
        entries.sumOf { it.bytes.coerceAtLeast(0L) }

    fun over(bytes: Long, budget: Long): Boolean = bytes > budget
}
