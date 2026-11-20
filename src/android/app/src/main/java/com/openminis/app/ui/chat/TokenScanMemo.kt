package com.openminis.app.ui.chat

import com.openminis.app.data.ContextTokenMeter
import org.json.JSONObject
import java.util.WeakHashMap

internal object TokenScanMemo {
    private val neutralizedTexts = NeutralizedTextMemo()

    /** Only the fixed request projection is cacheable. Never key a history or a caller's transform. */
    fun neutralized(text: String): String = neutralizedTexts.get(text)

    /** Legacy callers with a still-mutable input must reserialize even after nested changes. */
    fun toolUseInputJson(input: JSONObject): String = input.toString() ?: "{}"

    fun mixedTokens(text: String): Long = ContextTokenMeter.rawTextTokens(text).toLong()
}

internal class NeutralizedTextMemo(
    private val maxEntries: Int = 128,
    private val maxOutputChars: Int = 16_384,
) {
    private sealed interface Outcome {
        data object Unchanged : Outcome
        data class Changed(val text: String) : Outcome
    }

    private val entries = WeakHashMap<String, Outcome>()

    fun get(text: String): String {
        when (val hit = synchronized(entries) { entries[text] }) {
            Outcome.Unchanged -> return text
            is Outcome.Changed -> return hit.text
            null -> Unit
        }
        val result = neutralizeUntrustedTags(text)
        val outcome = when {
            // The original string as a strong value would make its weak key immortal.
            result == text -> Outcome.Unchanged
            result.length <= maxOutputChars -> Outcome.Changed(result)
            else -> return result
        }
        if (maxEntries > 0) synchronized(entries) {
            if (entries.size >= maxEntries && !entries.containsKey(text)) {
                val keys = entries.keys.iterator()
                if (keys.hasNext()) {
                    keys.next()
                    keys.remove()
                }
            }
            entries[text] = outcome
        }
        return if (outcome === Outcome.Unchanged) text else result
    }

    internal val size: Int get() = synchronized(entries) { entries.size }
}
