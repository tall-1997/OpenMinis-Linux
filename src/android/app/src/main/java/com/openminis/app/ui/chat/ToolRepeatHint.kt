package com.openminis.app.ui.chat

import com.openminis.app.util.Digest
import org.json.JSONObject

/**
 * [T-frozen-repeat-hint] ZCode model-anomaly shape: when the SAME calls keep
 * returning the SAME results, attach ONE hint to the tool result and carry on.
 *
 * It deliberately does not stop the run, does not speak to the user, and does
 * not throttle the tool: the agent-loop contract is that only context pressure,
 * an explicit stop, permission and timeouts end a turn.
 */
internal class ToolRepeatTracker(
    /** Consecutive frozen repeats that arm the hint (ZCode uses 2-3). */
    private val hintAt: Int = 2,
) {

    private var lastDispatchSig: String? = null
    private var lastResultSig: String? = null
    private var streak = 0
    private var emitted = false

    /** Returns the hint exactly once per frozen streak, or null. */
    fun observe(dispatchSig: String, resultSig: String?): String? {
        if (dispatchSig == lastDispatchSig && resultSig != null && resultSig == lastResultSig) {
            streak += 1
        } else {
            streak = 1
            emitted = false
        }
        lastDispatchSig = dispatchSig
        lastResultSig = resultSig
        if (streak >= hintAt && !emitted) {
            emitted = true
            return "[LOOP HINT] These calls returned the same result $streak times in a row. " +
                "Keep waiting if the work is still running; otherwise change strategy. " +
                "This call still runs — no user-facing summary is needed."
        }
        return null
    }

    fun reset() {
        lastDispatchSig = null
        lastResultSig = null
        streak = 0
        emitted = false
    }

    companion object {

        /**
         * Observe this turn and attach the one-shot hint INSIDE the first tool
         * result. Never a USER turn — a synthetic user message makes the model
         * stop and write prose mid-task.
         */
        fun applyHint(
            tracker: ToolRepeatTracker,
            calls: List<Triple<String, String, JSONObject>>,
            parts: MutableList<com.openminis.app.data.model.AgentContentPart>,
        ) {
            if (parts.isEmpty()) {
                tracker.reset()
                return
            }
            val hint = tracker.observe(
                dispatchSigOf(calls),
                resultSigOf(parts.mapNotNull {
                    (it as? com.openminis.app.data.model.AgentContentPart.ToolResult)?.content
                }),
            )
            if (hint == null) return
            val idx = parts.indexOfFirst { it is com.openminis.app.data.model.AgentContentPart.ToolResult }
            if (idx < 0) return
            val part = parts[idx] as com.openminis.app.data.model.AgentContentPart.ToolResult
            parts[idx] = part.copy(content = part.content + "\n\n" + hint)
        }


        /** Canonical signature of a turn's dispatch set. */
        fun dispatchSigOf(calls: List<Triple<String, String, JSONObject>>): String =
            calls.map { "${it.second}:${Digest.sha256Hex(it.third.toString())}" }
                .sorted()
                .joinToString("|")

        /** Result signature with accounting noise collapsed. */
        fun resultSigOf(contents: List<String>): String? {
            if (contents.isEmpty()) return null
            return Digest.sha256Hex(contents.joinToString("\u0000") { scrubVolatile(it) })
        }

        private val volatileNoise = listOf(
            Regex("""tokens=\d+"""),
            Regex("""elapsed=\d+ms"""),
            Regex("""timestamp_ms=\d+"""),
            Regex("""nextTriggerMs=\d+"""),
            Regex("""\d{4}-\d{2}-\d{2}[T ]\d{2}:\d{2}(:\d{2})?(\.\d+)?(Z|[+-]\d{2}:?\d{2})?"""),
        )

        private fun scrubVolatile(text: String): String =
            volatileNoise.fold(text) { acc, pattern -> acc.replace(pattern, "\u0001") }
    }
}
