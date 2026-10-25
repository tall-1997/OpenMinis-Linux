package com.openminis.app.ui.chat

import org.json.JSONObject

/**
 * Parse the JSON tool-arguments string into a plain Map for the loop detector.
 * Malformed JSON degrades to an empty map — the detector still hashes the tool name.
 */
internal fun parseToolParams(argsJson: String): Map<String, Any?> {
    if (argsJson.isBlank()) return emptyMap()
    return try {
        val obj = JSONObject(argsJson)
        val out = HashMap<String, Any?>(obj.length())
        val keys = obj.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            val v = obj.get(k)
            out[k] = if (v == JSONObject.NULL) null else v
        }
        out
    } catch (_: Exception) {
        emptyMap()
    }
}
