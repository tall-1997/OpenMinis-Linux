package com.openminis.app.ui.chat

import com.openminis.app.data.model.LLMMessage

/**
 * [T-android-agenthistory-cme] Threading contract and sanctioned bulk
 * operations for [ChatViewModel.agentHistory].
 *
 * `agentHistory` is a [java.util.concurrent.CopyOnWriteArrayList], not a
 * plain ArrayList, because it is read from arbitrary threads (main-thread
 * UI paths such as clearChat/resume, loop coroutines on IO, retry and
 * compaction paths) while the agent loop appends from a background
 * coroutine. A plain ArrayList's iterators and subList views throw
 * `ConcurrentModificationException` the moment a write lands mid-read —
 * observed in production as `ArrayList$Itr.next` on a main-thread Handler
 * callback (crash-2026-10-22 / crash-2026-10-23, obfuscated frames).
 *
 * Rules that keep this correct:
 *
 * 1. **Writes follow the single-writer discipline.** All mutations happen
 *    from the session's loop lifecycle (append / trim / rebuild / sanitize /
 *    retry). `clearChat()` on main only runs after that session's loop has
 *    been cancelled. COWAL makes each individual operation atomic so a
 *    discipline violation degrades to a stale read, never a crash.
 * 2. **Never hold a subList view across a mutation.** COWAL's subList views
 *    carry a comodification check and throw CME on ANY later op if the
 *    parent array was swapped in between. Read a snapshot first
 *    (`agentHistory.toList()`), then slice the immutable copy.
 * 3. **Iteration is snapshot-safe.** COWAL's iterator walks the array as it
 *    existed when the iterator was created, so `for (m in agentHistory)`
 *    and `agentHistory.iterator()` never throw and never see a half-applied
 *    mutation.
 * 4. **Bulk head-removal goes through [removeAgentHistoryHead].** COWAL has
 *    no atomic removeRange; sequential `removeAt(0)` calls are individually
 *    atomic and index-stable under tail appends, and the bounds guard makes
 *    a concurrent `clear()` a no-op instead of an IndexOutOfBounds.
 *
 * Cost model: every mutation copies the backing array (≤
 * [ChatViewModel.MAX_AGENT_HISTORY_MESSAGES] references ≈ 32 KB memcpy at
 * the 4 000 cap). Mutations per agent turn are tens, not thousands, so the
 * total is microseconds — cheap insurance against a crash class that took
 * out the app twice in two days.
 */
internal fun ChatViewModel.agentHistorySnapshot(): List<LLMMessage> =
    agentHistory.toList()

/**
 * Read the first [count] entries as one coherent snapshot (the COWAL
 * iterator walks a single array generation). Used by [ChatViewModel.trimAgentHistory]
 * to collect the messages it is about to drop for digest bookkeeping.
 */
internal fun ChatViewModel.agentHistoryHeadSnapshot(count: Int): List<LLMMessage> {
    if (count <= 0) return emptyList()
    val out = ArrayList<LLMMessage>(count)
    val iter = agentHistory.iterator()
    var taken = 0
    while (taken < count && iter.hasNext()) {
        out.add(iter.next())
        taken++
    }
    return out
}

/**
 * Remove the first [count] entries. Individually atomic `removeAt(0)`
 * calls; stops early if a concurrent `clear()` emptied the list so a
 * discipline violation cannot turn into an IndexOutOfBounds crash.
 */
internal fun ChatViewModel.removeAgentHistoryHead(count: Int) {
    var remaining = count
    while (remaining > 0 && agentHistory.isNotEmpty()) {
        agentHistory.removeAt(0)
        remaining--
    }
}
