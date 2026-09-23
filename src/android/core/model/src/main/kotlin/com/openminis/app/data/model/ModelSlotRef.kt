package com.openminis.app.data.model

/**
 * Slot values stored in the group-id fields may name one model entry.
 * The `entry:` prefix keeps those ids out of group lookups.
 */
object ModelSlotRef {
    const val ENTRY_PREFIX = "entry:"

    fun entry(id: String): String = ENTRY_PREFIX + id

    fun isEntry(raw: String?): Boolean = raw != null && raw.startsWith(ENTRY_PREFIX)

    fun entryId(raw: String?): String? {
        if (!isEntry(raw)) return null
        val id = raw!!.removePrefix(ENTRY_PREFIX)
        return id.takeIf { it.isNotEmpty() }
    }
}
