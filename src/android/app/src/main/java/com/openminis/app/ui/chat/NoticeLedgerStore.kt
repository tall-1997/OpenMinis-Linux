package com.openminis.app.ui.chat

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * [T-once-notices] Durable per-session bookkeeping for one-shot notices.
 *
 * The in-memory set dies with the process, so a restart re-delivered every
 * recalled memory (the exact repeated-injection bug this branch removes) and
 * re-announced the goal. Keys are session-scoped and reloaded with the
 * session — the transcript may be trimmed, the delivery record may not.
 */
internal object NoticeLedgerStore {

    data class State(
        val recallKeys: Set<String> = emptySet(),
        val goalSha: String? = null,
    )

    private fun dir(filesDir: File): File = File(filesDir, "notices")

    private fun file(filesDir: File, sessionId: String): File =
        File(dir(filesDir), "${sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_")}.json")

    fun load(filesDir: File, sessionId: String): State {
        val file = file(filesDir, sessionId)
        if (!file.exists()) return State()
        return runCatching {
            val json = JSONObject(file.readText())
            val keys = json.optJSONArray("recallKeys") ?: JSONArray()
            val set = buildSet {
                for (i in 0 until keys.length()) keys.optString(i).takeIf { it.isNotBlank() }?.let(::add)
            }
            State(set, json.optString("goalSha").takeIf { it.isNotBlank() })
        }.getOrDefault(State())
    }

    fun save(filesDir: File, sessionId: String, state: State) {
        runCatching {
            dir(filesDir).mkdirs()
            val json = JSONObject().apply {
                put("recallKeys", JSONArray(state.recallKeys.toList()))
                put("goalSha", state.goalSha ?: "")
            }
            file(filesDir, sessionId).writeText(json.toString())
        }
    }
}
