package com.openminis.app.util

/**
 * [T-safe-clip] Code-point-safe `take` for text that is persisted or sent to
 * a provider. Kotlin's `String.take(n)` counts UTF-16 code units, so a cut
 * between the halves of an emoji leaves a lone surrogate; org.json then writes
 * an unpaired `\uD800`, and Android's UTF-8 writer replaces it. Every new clip
 * site on a persisted string goes through this.
 */
object TextClip {

    fun safeTake(text: String, limit: Int): String {
        if (limit <= 0) return ""
        if (text.length <= limit) return text
        val cut = if (Character.isHighSurrogate(text[limit - 1])) limit - 1 else limit
        return text.substring(0, cut)
    }

    /** Safe cut plus an ellipsis when anything was removed. */
    fun safeEllipsize(text: String, limit: Int): String {
        if (limit <= 0) return ""
        if (text.length <= limit) return text
        return safeTake(text, limit) + "…"
    }
}
