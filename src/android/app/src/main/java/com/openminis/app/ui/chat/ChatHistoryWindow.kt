package com.openminis.app.ui.chat

internal data class OlderWindowPlan(
    val nextVisibleCap: Int,
    val fetchOffset: Int? = null,
    val fetchCount: Int = 0,
)

/** Pure planning logic for the UI tail window and the persisted DB prefix. */
internal object ChatHistoryWindow {
    fun planOlderLoad(
        loadedVisible: Int,
        visibleCap: Int,
        loadedOffset: Int,
        step: Int,
    ): OlderWindowPlan {
        require(loadedVisible >= 0 && visibleCap >= 0 && loadedOffset >= 0 && step > 0)
        if (loadedVisible > visibleCap) {
            return OlderWindowPlan(nextVisibleCap = (visibleCap + step).coerceAtMost(loadedVisible))
        }
        if (loadedOffset == 0) return OlderWindowPlan(nextVisibleCap = visibleCap)
        val newOffset = (loadedOffset - step).coerceAtLeast(0)
        return OlderWindowPlan(
            nextVisibleCap = visibleCap + (loadedOffset - newOffset),
            fetchOffset = newOffset,
            fetchCount = loadedOffset - newOffset,
        )
    }
}
