package com.openminis.app.ui.chat

import com.openminis.app.tools.receiptDigest
import org.json.JSONObject
import java.io.File

/**
 * [T-receipt-notice-gate] Fingerprint dedup for the tool-receipt notice.
 *
 * The receipt check runs before EVERY request, so without a gate an unchanged
 * pending count re-announces itself each round — the repeating "pending: N"
 * nag users read as duplicated output. The gate persists the last announced
 * fingerprint per generation, so it survives restarts (in-memory state would
 * re-announce after every process death — the restart-replay bug class this
 * branch removes elsewhere). Fingerprints are keyed by the user-turn
 * generation, so different questions/projects never bleed into each other's
 * books. A clean state clears the stored fingerprint so a later debt
 * announces again; the "could not be checked" failure branch is gated by the
 * same mechanism — a persistent store failure announces once, not every round.
 */
internal object ReceiptNoticeGate {
    private const val MAX_GENERATIONS = 8

    /**
     * @param parentSha the announcement's generation anchor (null/blank passes
     *   the line through untouched — the caller owns the no-scope decision).
     * @param line the computed notice; null means a clean state and clears the
     *   stored fingerprint.
     * @return the line to announce, or null when unchanged.
     */
    @Synchronized
    fun resolve(filesDir: File, sessionId: String, parentSha: String?, line: String?): String? {
        if (parentSha.isNullOrBlank() || sessionId.isBlank()) return line
        val file = file(filesDir, sessionId)
        val state = load(file)
        if (line == null) {
            if (state.remove(parentSha) != null) save(file, state)
            return null
        }
        val fingerprint = receiptDigest(parentSha, line)
        if (state[parentSha] == fingerprint) return null
        state.remove(parentSha)
        state[parentSha] = fingerprint
        while (state.size > MAX_GENERATIONS) state.remove(state.keys.first())
        save(file, state)
        return line
    }

    private fun dir(filesDir: File): File = File(filesDir, "receipt-notice")

    private fun file(filesDir: File, sessionId: String): File =
        File(dir(filesDir), "${sessionId.replace(Regex("[^A-Za-z0-9._-]"), "_")}.json")

    private fun load(file: File): LinkedHashMap<String, String> {
        if (!file.exists()) return LinkedHashMap()
        return runCatching {
            val json = JSONObject(file.readText())
            val map = LinkedHashMap<String, String>()
            for (key in json.keys()) json.optString(key).takeIf { it.isNotBlank() }?.let { map[key] = it }
            map
        }.getOrDefault(LinkedHashMap())
    }

    private fun save(file: File, state: LinkedHashMap<String, String>) {
        runCatching {
            file.parentFile?.mkdirs()
            val json = JSONObject()
            for ((k, v) in state) json.put(k, v)
            // Atomic tmp+rename: a truncated file would reset the fingerprint
            // and re-announce the same count after a crash — the nag again.
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(json.toString())
            if (!tmp.renameTo(file)) {
                file.writeText(json.toString())
                tmp.delete()
            }
        }
    }
}
