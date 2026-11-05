package com.openminis.app.ui.sessions

/**
 * [T-attention-order] The order the session list shows.
 *
 * The list used to be "pinned first, then newest", which put a chat that had
 * stopped to ask a question in the same place as one that was simply idle. The
 * prototype's panel answers "does anything need me now?" before anything else
 * (`docs/005-panel.md:7-11`); this is that question applied to a chat list: rows
 * that need a person float up, everything else keeps its familiar order.
 *
 * Takes a projected [Key] rather than the session row itself: `ui` must not touch
 * Room entities (architecture gate), and the ordering does not need anything but
 * three fields. Pure, so the tiers are testable without a database or Compose.
 */
object SessionOrdering {

    /** The only fields the order depends on. */
    data class Key(val id: String, val updatedAt: Long, val pinnedAt: Long?)

    /**
     * Attention, then pinned, then the rest — each tier newest-first.
     *
     * A row that needs a person still gets a stable position inside its tier, and
     * pinning cannot bury work that waits on an answer: that is the whole point of
     * the first tier. Ties keep the input order, so the sort is stable.
     */
    fun <T> sorted(
        items: List<T>,
        keyOf: (T) -> Key,
        attentionSessionIds: Set<String>,
    ): List<T> {
        if (items.size < 2) return items
        return items.sortedWith(
            compareBy<T> { if (keyOf(it).id in attentionSessionIds) 0 else 1 }
                .thenBy { if (keyOf(it).pinnedAt != null) 0 else 1 }
                .thenByDescending { keyOf(it).pinnedAt ?: 0L }
                .thenByDescending { keyOf(it).updatedAt },
        )
    }

    /** True when [id] currently needs a person. */
    fun needsPerson(id: String, attentionSessionIds: Set<String>): Boolean =
        id in attentionSessionIds
}
