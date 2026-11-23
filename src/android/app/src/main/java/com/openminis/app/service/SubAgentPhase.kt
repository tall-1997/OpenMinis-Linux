package com.openminis.app.service

/**
 * [T-subagent-phase-key] State keys for the sub-agent phase state machine.
 *
 * The runner emits and the tracker stores/compares these ASCII keys; the
 * display layer (ChatSubAgentBar) turns them into localized labels via
 * string resources. Keys, not display text, so a locale switch never breaks
 * the state machine and no Chinese display strings ride the service layer.
 *
 * The Chinese literals in [fromKey] are NOT labels — they are compatibility
 * mapping for data written by older builds that stored display strings as
 * state (in-flight roster entries during an app upgrade). Nothing may write
 * them any more.
 */
enum class SubAgentPhase(val key: String) {
    THINKING("thinking"),
    CALLING("calling"),
    RUNNING("running"),
    REPLYING("replying"),
    ;

    companion object {
        /**
         * Parse a stored phase value. Blank and unknown values fall back to
         * [THINKING] — the previous display default for "no phase yet" was
         * the thinking label, so behaviour is preserved.
         */
        /**
         * [T-cjk-escaped-legacy] The four legacy values are the pre-rename
         * Chinese labels that are still sitting in in-flight tracker state and
         * persisted journals. They are written with \u escapes on purpose: the
         * CJK-literal ratchet exists to keep USER-VISIBLE Chinese out of
         * source (every label must come from strings.xml), and these are
         * parse-only compatibility keys, never rendered. Escaping keeps that
         * invariant enforceable instead of punching a hole in the gate.
         */
        private val LEGACY_LABELS = mapOf(
            "\u8c03\u7528\u5de5\u5177" to CALLING,   // tool call in progress
            "\u6267\u884c\u4e2d" to RUNNING,           // executing
            "\u56de\u590d\u4e2d" to REPLYING,          // replying
            "\u601d\u8003\u4e2d" to THINKING,          // thinking
        )

        fun fromKey(raw: String?): SubAgentPhase {
            val key = raw?.trim().orEmpty()
            for (p in entries) if (p.key == key) return p
            return LEGACY_LABELS[key] ?: THINKING
        }
    }
}
