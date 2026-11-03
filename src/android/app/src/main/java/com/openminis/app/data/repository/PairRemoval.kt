package com.openminis.app.data.repository

import com.openminis.app.provider.ToolPairing
import org.json.JSONArray

/**
 * [T-m6-pair-removal] dsh P1-5 "成对移除同步骤工具调用与结果".  A
 * truncation (delete-from-here / edit) removes the tool-result carrier
 * row while the assistant row containing the matching tool_use part
 * survives — an orphan tool_use, replaying on the next agentHistory build,
 * feeds the model a dangling tool call without its result; some providers
 * 400 on that.
 *
 * Pure: takes (rowId, partsJson) snapshots — no DAO.  Callers apply the
 * plan through [ChatRepository.updateMessageParts] / row deletion.
 */
object PairRemoval {

    /** Structured part we care about.  Every other part type is ignored. */
    private data class PartRef(
        val index: Int,
        val type: String,     // "toolUse" or "toolResult"
        val key: String,      // ToolPairing.key(toolUseId), never blank
    )

    data class PlanRow(
        val rowId: String,
        val partsJson: String,  // new, with orphaned parts stripped
    )

    data class Plan(
        /** Rows whose every part is dropped — must be deleted wholesale. */
        val removeIds: List<String>,
        /** Rows whose parts must be rewritten. */
        val updateParts: List<PlanRow>,
    ) {
        val isEmpty: Boolean get() = removeIds.isEmpty() && updateParts.isEmpty()

        companion object {
            val EMPTY = Plan(emptyList(), emptyList())
        }
    }

    // ── public entry ────────────────────────────────────────────────

    /**
     * [kept]    — surviving rows: (rowId, partsJson).  The plan trims these.
     * [deleted] — doomed rows that were (or will be) removed.  The plan
     *             collects pair keys from them.
     */
    fun plan(
        kept: List<Pair<String, String>>,
        deleted: List<Pair<String, String>>,
    ): Plan {
        // 1  Collect pairing keys on the DELETED side.
        val deadUses = mutableSetOf<String>()
        val deadResults = mutableSetOf<String>()
        for ((_, json) in deleted) {
            if (!json.contains("\"toolUse\"") && !json.contains("\"toolResult\"")) continue
            collectKeys(json, deadUses, deadResults)
        }
        if (deadUses.isEmpty() && deadResults.isEmpty()) return Plan.EMPTY

        // 2  Scan kept side: gather alive keys AND candidate rows that
        //    might carry orphaned parts.
        val aliveUses = mutableSetOf<String>()
        val aliveResults = mutableSetOf<String>()

        data class Scan(
            val id: String,
            val partsJson: String,
            val refs: List<PartRef>,
            val totalParts: Int,
        )
        val scans = mutableListOf<Scan>()

        for ((id, json) in kept) {
            if (!json.contains("\"toolUse\"") && !json.contains("\"toolResult\"")) continue
            val arr = safeParse(json) ?: continue
            val total = arr.length()
            if (total == 0) continue
            val refs = mutableListOf<PartRef>()
            for (i in 0 until total) {
                val obj = arr.optJSONObject(i) ?: continue
                when (obj.optString("type")) {
                    "toolUse" -> {
                        val key = pairingKeyOf(obj)
                        if (key != null) {
                            aliveUses.add(key)
                            refs.add(PartRef(i, "toolUse", key))
                        }
                    }
                    "toolResult" -> {
                        val key = pairingKeyOf(obj)
                        if (key != null) {
                            aliveResults.add(key)
                            refs.add(PartRef(i, "toolResult", key))
                        }
                    }
                }
            }
            if (refs.isNotEmpty()) scans.add(Scan(id, json, refs, total))
        }

        // 3  Decide orphans.  A kept part is orphaned when its counterpart
        //    exists on the deleted side AND does NOT exist on the kept side.
        val removeIds = mutableListOf<String>()
        val updateParts = mutableListOf<PlanRow>()

        for (scan in scans) {
            val dropIndexes = mutableSetOf<Int>()
            for (ref in scan.refs) {
                when (ref.type) {
                    "toolUse" -> if (ref.key in deadResults && ref.key !in aliveResults) dropIndexes.add(ref.index)
                    "toolResult" -> if (ref.key in deadUses && ref.key !in aliveUses) dropIndexes.add(ref.index)
                }
            }
            if (dropIndexes.isEmpty()) continue

            if (dropIndexes.size == scan.totalParts) {
                removeIds.add(scan.id)
            } else {
                val newJson = dropParts(scan.partsJson, dropIndexes)
                updateParts.add(PlanRow(scan.id, newJson))
            }
        }

        return Plan(removeIds, updateParts)
    }

    // ── helpers ─────────────────────────────────────────────────────

    /** Parse [json] as a parts array, or null when it is not valid JSON. */
    internal fun safeParse(json: String): JSONArray? {
        return try {
            JSONArray(json)
        } catch (_: Exception) {
            null
        }
    }

    private fun collectKeys(partsJson: String, uses: MutableSet<String>, results: MutableSet<String>) {
        val arr = safeParse(partsJson) ?: return
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val key = pairingKeyOf(obj) ?: continue
            when (obj.optString("type")) {
                "toolUse" -> uses.add(key)
                "toolResult" -> results.add(key)
            }
        }
    }

    /** Pairing key for a toolUse / toolResult part, or null when the part
     *  has no usable toolUseId.  Blank ids never pair (dsh rule: the repair
     *  must not invent matches). */
    private fun pairingKeyOf(obj: org.json.JSONObject): String? {
        val id = obj.optJSONObject("value")
            ?.optString("toolUseId", "")
            ?.takeIf { it.isNotBlank() }
            ?: return null
        return ToolPairing.key(id)
    }

    /** Rebuild [partsJson] without the given part indexes, in descending
     *  order so earlier indexes stay valid across removals. */
    private fun dropParts(partsJson: String, dropIndexes: Set<Int>): String {
        val arr = JSONArray(partsJson)
        for (i in dropIndexes.sortedDescending()) {
            arr.remove(i)
        }
        return arr.toString()
    }
}
