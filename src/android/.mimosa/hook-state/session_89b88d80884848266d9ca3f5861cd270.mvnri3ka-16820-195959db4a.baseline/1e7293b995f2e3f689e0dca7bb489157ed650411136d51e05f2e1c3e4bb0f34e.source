package com.openminis.app.ui.chat

/**
 * Single source of truth for LazyColumn row keys and their parsing.
 *
 * A row key is `"<kind>:<messageId>[:<extra>…]"` plus an optional defensive
 * dedupe suffix `#<n>` at the very end of the key (see
 * [FlatChatItem.withKeySuffix]). Building a key and reading a message id
 * back out of one must both go through here: hand-rolled `split(':')` /
 * `removePrefix(...)` copies at the consumption sites drifted from the
 * builder before, which is how the up-button target resolution ended up
 * matching nothing.
 *
 * [T-android-flatkeys] Historical failure this prevents: the dedupe path
 * used to rewrite `UserBubble.message.id` itself to `id#n`, so every parse
 * of a `user:` key returned an id that matched no [ChatMessage]. The
 * up-button seek then swept the whole list per tap (ANR on repeated taps)
 * and its restore landed the viewport back on the same spot — the "点多次
 * 向上翻页后视口退回固定位置" bug. The suffix now lives only inside the
 * key string; [ChatMessage.id] and every `messageId` field stay clean.
 */
internal object FlatKeys {
    const val KIND_USER = "user"
    const val KIND_HEADER = "header"
    const val KIND_TEXT = "text"
    const val KIND_MDBLOCK = "mdblock"
    const val KIND_THINKING = "thinking"
    const val KIND_PROCESS = "process"
    const val KIND_TOOL = "tool"
    const val KIND_INFO = "info"
    const val KIND_TYPING = "typing"
    const val KIND_ERROR = "error"
    const val KIND_LEGACY = "legacy"

    fun of(kind: String, messageId: String, vararg extras: Any?): String {
        // [T-flatkeys-invariants] parse() recovers the id as the segment
        // between the first two ':' with a trailing '#...' dedupe suffix
        // stripped. An id containing ':' or '#' would break that contract —
        // fail fast at the construction site instead of degrading key
        // consumers far away (the up-button walk once depended on it).
        require(messageId.isNotEmpty() && !messageId.contains(':') && !messageId.contains('#')) {
            "FlatKeys messageId must be non-empty and ':'/'#'-free: $messageId"
        }
        return buildString {
            append(kind).append(':').append(messageId)
            for (extra in extras) {
                append(':').append(extra)
            }
        }
    }

    data class Row(val kind: String, val messageId: String)

    /**
     * The kind and message id encoded in a row key, dedupe suffix stripped.
     * Null for synthetic rows (`__load_older__`) and malformed keys.
     */
    fun parse(key: String): Row? {
        val first = key.indexOf(':')
        if (first <= 0) return null
        val second = key.indexOf(':', first + 1)
        val rawId = if (second < 0) key.substring(first + 1) else key.substring(first + 1, second)
        val id = rawId.substringBefore('#')
        if (id.isEmpty()) return null
        return Row(kind = key.substring(0, first), messageId = id)
    }
}
