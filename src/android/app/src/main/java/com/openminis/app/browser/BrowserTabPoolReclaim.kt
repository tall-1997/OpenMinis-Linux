package com.openminis.app.browser

import android.util.Log
import java.util.Date

/**
 * Encapsulates the registry-driven reclaim logic for BrowserTabPool.
 * Separated to keep BrowserTabPool under its architecture ceiling.
 */
internal class BrowserTabPoolReclaim(
    private val getTabs: () -> List<BrowserTabPool.Tab>,
    private val setTabs: (List<BrowserTabPool.Tab>) -> Unit,
    private val saveUrl: (Int, String) -> Unit,
    private val isRunning: (Int) -> Boolean,
    private val removeTab: (BrowserTabPool.Tab) -> Unit,
) {
    private val preemptedTabIds = mutableSetOf<Int>()
    private val TAG = "BrowserTabPoolReclaim"

    /** Tabs preempted while inUse — consumed on next model call to produce a retry message. */
    fun consumePreemptNotice(id: Int): String? {
        if (!preemptedTabIds.remove(id)) return null
        return "Browser tab $id was reclaimed under memory pressure (another " +
            "session needed the slot). Please retry — the page URL was saved " +
            "and will be restored."
    }

    /** Snapshot for the global registry to decide reclaim targets. */
    fun snapshot(): List<BrowserTabPoolRegistry.TabInfo> =
        getTabs().map { BrowserTabPoolRegistry.TabInfo(it.id, it.inUse, it.lastActivityDate) }

    /** Evict an idle tab at registry's request. */
    fun evictTab(id: Int) {
        val current = getTabs().toMutableList()
        val idx = current.indexOfFirst { it.id == id }
        if (idx < 0) return
        val tab = current[idx]
        if (tab.inUse) return
        val url = tab.manager.currentURL.value
        if (url.isNotEmpty()) saveUrl(tab.id, url)
        current.removeAt(idx)
        setTabs(current)
        removeTab(tab)
        Log.i(TAG, "Evicted tab $id at registry request (url=${url.take(60)})")
    }

    /** Preempt an in-use tab at registry's request — will trigger retry on next model call. */
    fun preemptTab(id: Int) {
        val current = getTabs().toMutableList()
        val idx = current.indexOfFirst { it.id == id }
        if (idx < 0) return
        val tab = current[idx]
        val url = tab.manager.currentURL.value
        if (url.isNotEmpty()) saveUrl(tab.id, url)
        current.removeAt(idx)
        setTabs(current)
        removeTab(tab)
        preemptedTabIds.add(id)
        Log.i(TAG, "Preempted in-use tab $id at registry request (url=${url.take(60)})")
    }

    /** Evict all idle tabs under memory pressure. */
    fun evictAllIdleForMemoryPressure(): Int {
        val current = getTabs().toMutableList()
        val toRemove = current.filter { !it.inUse && !isRunning(it.id) }
        if (toRemove.isEmpty()) return 0
        for (tab in toRemove) {
            val url = tab.manager.currentURL.value
            if (url.isNotEmpty()) saveUrl(tab.id, url)
            current.remove(tab)
            removeTab(tab)
        }
        setTabs(current)
        Log.i(TAG, "Evicted ${toRemove.size} idle tab(s) under memory pressure")
        return toRemove.size
    }
}
