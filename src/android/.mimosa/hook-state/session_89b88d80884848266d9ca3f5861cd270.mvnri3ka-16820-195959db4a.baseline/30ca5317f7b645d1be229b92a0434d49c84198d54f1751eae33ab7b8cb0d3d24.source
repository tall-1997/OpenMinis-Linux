package com.openminis.app.ui.chat

/** Select only reconstructible, unpinned sessions; oldest access first. */
internal object IdleSessionBudget {
    data class Entry(val id: String, val lastAccess: Long, val bytes: Long, val pinned: Boolean)

    fun victims(entries: List<Entry>, maxIdle: Int = 3, maxBytes: Long = 32L * 1024 * 1024): List<String> {
        val idle = entries.filterNot { it.pinned }.sortedBy { it.lastAccess }
        var count = idle.size
        var bytes = idle.sumOf { it.bytes.coerceAtLeast(0) }
        return buildList {
            for (entry in idle) {
                if (count <= maxIdle && bytes <= maxBytes) break
                add(entry.id)
                count--
                bytes -= entry.bytes.coerceAtLeast(0)
            }
        }
    }

    /**
     * Pressure path. Unpinned sessions go first. Pinned sessions are eligible
     * only after they have been frozen, and the newest one is kept so the
     * screen the user is on is not the first victim.
     */
    fun pressureVictims(
        entries: List<Entry>,
        maxBytes: Long,
        frozenIds: Set<String>,
    ): List<String> {
        val unpinned = victims(entries, maxIdle = 0, maxBytes = maxBytes)
        val left = entries.filter { it.id !in unpinned }
        if (left.sumOf { it.bytes.coerceAtLeast(0L) } <= maxBytes) return unpinned
        val pinned = left.filter { it.pinned && it.id in frozenIds }.sortedBy { it.lastAccess }
        if (pinned.size <= 1) return unpinned
        return unpinned + pinned.dropLast(1).map { it.id }
    }
}
