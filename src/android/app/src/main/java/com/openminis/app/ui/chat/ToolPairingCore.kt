package com.openminis.app.ui.chat

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.provider.ToolPairing

/**
 * [T-tool-pairing-single-authority] The ONE implementation of "does this tool
 * result answer that tool call", and therefore of "where may this list be cut".
 *
 * WHY this exists: three independent implementations had grown apart —
 *  - `retention/ResidentWindow.legalBoundaries` (byte / message trimming),
 *  - `PostAnchorTail.splitRounds` (post-anchor tail budget),
 *  - `ChatViewModelHistoryExt.dropOrphanedToolParts` (request repair).
 * A change in one never reached the others, and both P0 defects fixed in this
 * branch came from a cut point one implementation considered legal and another
 * did not. Consumers now delegate here.
 *
 * The rule is a pure extraction of the shipped ResidentWindow behaviour:
 *  - a ToolResult joins the waiting set; it can only be satisfied by a ToolUse
 *    to its LEFT with the same pairing key;
 *  - an unmatched ToolUse is tolerated only inside the FINAL assistant message
 *    (an in-flight turn whose result has not been produced yet);
 *  - a cut is legal iff nothing is still waiting and no non-final use is
 *    unmatched.
 *
 * Deliberately NOT memoized: a per-request history is a fresh list, so an
 * identity cache would miss on the hot path while pinning whole histories in
 * memory. The fold is O(messages x parts) and runs a handful of times per
 * request.
 *
 * Pairing identity is `ToolPairing.key(id) + name`: the id half normalizes the
 * OpenAI Responses combined form (`call_id|fc_id`), and the name half keeps the
 * stricter of the three shipped checks (a result whose id matches but whose
 * tool name differs is NOT the same call). Both halves were already present in
 * `ResidentWindow.legalBoundaries` and `dropOrphanedToolParts`; only
 * `PostAnchorTail` matched on id alone, and it now agrees.
 *
 * Pure JVM: no ViewModel, no IO, no tokenizer.
 */
internal object ToolPairingCore {

    /** Pairing identity of one call half: normalized id plus tool name. */
    fun pairKey(id: String, name: String): String =
        ToolPairing.key(id) + "\u0000" + name

    /**
     * `legal[i]` is true when cutting BEFORE index `i` keeps every call/result
     * pair on the same side. `legal[n]` (cut after the tail) is always false:
     * an assistant final message may still owe a result.
     */
    fun legalCuts(history: List<LLMMessage>): BooleanArray {
        val n = history.size
        val legal = BooleanArray(n + 1)
        if (n == 0) {
            legal[0] = true
            return legal
        }
        val waiting = HashMap<String, Int>()
        var unmatchedUses = 0
        for (i in n - 1 downTo 0) {
            val message = history[i]
            val inFlightTurn = i == n - 1 && message.role == LLMMessage.Role.ASSISTANT
            for (p in message.contentParts.asReversed()) {
                when (p) {
                    is AgentContentPart.ToolResult -> {
                        if (p.id.isEmpty()) continue
                        val key = pairKey(p.id, p.name)
                        waiting[key] = (waiting[key] ?: 0) + 1
                    }
                    is AgentContentPart.ToolUse -> {
                        if (p.id.isEmpty()) continue
                        val key = pairKey(p.id, p.name)
                        val pending = waiting[key] ?: 0
                        if (pending > 0) {
                            if (pending == 1) waiting.remove(key) else waiting[key] = pending - 1
                        } else if (!inFlightTurn) {
                            unmatchedUses++
                        }
                    }
                    else -> Unit
                }
            }
            legal[i] = waiting.isEmpty() && unmatchedUses == 0
        }
        legal[n] = false
        return legal
    }

    fun isBalancedBefore(history: List<LLMMessage>, index: Int): Boolean {
        val legal = legalCuts(history)
        return index in legal.indices && legal[index]
    }

    /** Nearest legal cut at or after [from], or null when none exists. */
    fun firstLegalCutAtOrAfter(history: List<LLMMessage>, from: Int): Int? {
        val legal = legalCuts(history)
        var i = from.coerceIn(0, legal.size - 1)
        while (i < legal.size) {
            if (legal[i]) return i
            i++
        }
        return null
    }

    /** Nearest legal cut at or before [from], or null when none exists. */
    fun lastLegalCutAtOrBefore(history: List<LLMMessage>, from: Int): Int? {
        val legal = legalCuts(history)
        var i = from.coerceIn(0, legal.size - 1)
        while (i >= 0) {
            if (legal[i]) return i
            i--
        }
        return null
    }

    /**
     * True when the trailing group still owes a result, i.e. a new group may
     * NOT be started here without orphaning a call. This is the predicate the
     * post-anchor budget uses to decide whether an assistant step boundary is
     * a safe split point.
     */
    fun hasPendingUse(group: List<LLMMessage>): Boolean {
        if (group.isEmpty()) return false
        // [T-tool-pairing-single-authority] COUNT semantics, matching the
        // `waiting` map in [legalCuts]. The former set-based version asked
        // "is this key answered anywhere", so two calls sharing a key with one
        // result looked fully answered and the budget split between them; the
        // count comparison below is the same rule [legalCuts] applies.
        //
        // Deliberately NOT `!legalCuts(group)[group.size]`: `legal[n]` is
        // unconditionally false by design (a trailing assistant may still owe
        // a result), so that shortcut answered "pending" for every group and
        // disabled the intra-round split entirely.
        val uses = HashMap<String, Int>()
        val results = HashMap<String, Int>()
        for (m in group) {
            for (p in m.contentParts) {
                when (p) {
                    is AgentContentPart.ToolUse -> {
                        if (p.id.isEmpty()) continue
                        val key = pairKey(p.id, p.name)
                        uses[key] = (uses[key] ?: 0) + 1
                    }
                    is AgentContentPart.ToolResult -> {
                        if (p.id.isEmpty()) continue
                        val key = pairKey(p.id, p.name)
                        results[key] = (results[key] ?: 0) + 1
                    }
                    else -> Unit
                }
            }
        }
        return uses.any { (key, count) -> count > (results[key] ?: 0) }
    }
}
