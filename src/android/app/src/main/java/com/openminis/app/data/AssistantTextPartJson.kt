package com.openminis.app.data

import org.json.JSONObject

/** Optional presentation metadata on an existing text part. Old rows default to reply text. */
internal object AssistantTextPartJson {
    fun encode(text: String, isProcessText: Boolean): JSONObject = JSONObject().apply {
        put("type", "text")
        put("value", text)
        if (isProcessText) put("display", "process")
    }

    fun isProcessText(part: JSONObject): Boolean = part.optString("display") == "process"
}
