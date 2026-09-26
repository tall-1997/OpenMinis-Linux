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
}
