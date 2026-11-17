package com.openminis.app.guard

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Spacing of outward actions (send, submit, post, follow...) within one target app, so an
 * automated agent never fires machine-speed requests at an external service
 * (anti-risk-control). Semantics follow DeepseekHarness-Android's OutwardPacing:
 *
 *  - the first outward action for a key runs immediately (wait 0);
 *  - every later one waits the remainder of a random 8-20 s gap measured from the previous
 *    outward action's completion - the clock is passed in per call, so a slow tool call
 *    already eats into the gap;
 *  - the gap grows with the number of outward actions already recorded for the same key
 *    inside the last [WINDOW_MS] (lower bound +[STEP_LO_MS] ms, upper bound +[STEP_HI_MS] ms
 *    per earlier action) and never exceeds [MAX_GAP_MS];
 *  - this class only computes durations; [ActionGuard] performs the actual sleep through its
 *    injected sleeper before the tool runs and reports the wait to the model.
 *
 * Pure JVM: the random source is a constructor-injected `(lo, hi) -> Long` function with
 * INCLUSIVE bounds. Everything else is deterministic.
 */
class OutwardPacing(private val random: (Long, Long) -> Long) {

    private class Done(val key: String, val atMs: Long, val gapMs: Long)

    private val done = ArrayDeque<Done>()

    /** Milliseconds the next outward action for [key] must still wait at [nowMs]; 0 = run now. */
    @Synchronized
    fun waitBefore(key: String, nowMs: Long): Long {
        prune(nowMs)
        val last = done.lastOrNull { it.key == key } ?: return 0
        return (last.gapMs - (nowMs - last.atMs)).coerceIn(0, MAX_GAP_MS)
    }

    /** Records one completed outward action for [key] and picks the gap the NEXT one must keep. */
    @Synchronized
    fun noteDone(key: String, nowMs: Long) {
        prune(nowMs)
        val extra = done.count { it.key == key }.toLong()
        val lo = (FIRST_GAP_MS + extra * STEP_LO_MS).coerceAtMost(MAX_GAP_MS)
        val hi = (LAST_GAP_MS + extra * STEP_HI_MS).coerceAtMost(MAX_GAP_MS)
        done.addLast(Done(key, nowMs, if (lo >= hi) hi else random(lo, hi)))
    }

    /** Clears the history of one key (the session spoke: see [ActionGuard.onUserTurn]). */
    @Synchronized
    fun reset(key: String) {
        done.removeAll { it.key == key }
    }

    /** Clears everything (tests, process-wide resets). */
    @Synchronized
    fun resetAll() {
        done.clear()
    }

    private fun prune(nowMs: Long) {
        while (done.isNotEmpty() && nowMs - done.first().atMs > WINDOW_MS) done.removeFirst()
    }

    companion object {
        /** Random wait range (ms) before the second outward action of one key. */
        const val FIRST_GAP_MS = 8_000L
        const val LAST_GAP_MS = 20_000L

        /** Upper bound (ms) no matter how many outward actions piled up. */
        const val MAX_GAP_MS = 30_000L

        /** Per earlier outward action of the same key: lower bound grows by this, upper by [STEP_HI_MS]. */
        const val STEP_LO_MS = 500L
        const val STEP_HI_MS = 1_000L

        /** Only outward actions within this window (ms) raise the gap and count as "same app". */
        const val WINDOW_MS = 30 * 60_000L

        /**
         * The configurable outward-keyword table. Keywords: send / submit / post / reply /
         * publish / comment / follow and their Chinese counterparts. Add entries here to
         * configure; keeping it a substring match is deliberate (covers "resend", "提交表单"...).
         */
        val KEYWORDS: List<String> = listOf(
            "send", "submit", "post", "reply", "publish", "comment", "follow",
            "发送", "提交", "发布", "评论", "回复", "关注", "打招呼", "投递",
        )

        /**
         * [T-outward-tool-scoped] Tools that are outward BY NAME: every call
         * transmits something toward the outside world (a message, a share, an
         * Intent handed to another app). Always paced, regardless of payload.
         */
        val OUTWARD_TOOLS: Set<String> = setOf(
            "sms_send", "notification_reply", "share_to_app", "app_send_intent",
        )

        /**
         * [T-outward-tool-scoped] The text-transmitting category: tools that
         * type/share text INTO the target app, where the `text` payload IS the
         * outward content (the DSH semantics the payload matching was written
         * for). Keyword matching against the payload applies ONLY to these —
         * an arbitrary tool's text argument (clipboard_set "please submit the
         * form") never triggers pacing.
         */
        val TEXT_TRANSMITTING_TOOLS: Set<String> = setOf("ui_action", "virtual_screen") + OUTWARD_TOOLS

        /** Args fields (first present wins) whose string value names the target app for pacing. */
        val APP_KEYS = listOf("package", "packageName", "appId", "app", "targetApp")

        /**
         * [T-outward-tool-scoped] True when the tool is outward:
         *  (a) the tool names a dedicated [OUTWARD_TOOLS] entry, or
         *  (b) the TOOL NAME (not the payload) contains a keyword and the tool
         *      is in the [TEXT_TRANSMITTING_TOOLS] category, or
         *  (c) the tool is a payload-typing tool and its `text` argument
         *      contains a keyword — the payload is what gets typed into the
         *      target app, so it is the outward content.
         * Arbitrary text payload content never matches on its own.
         */
        fun isOutward(tool: String, args: JsonObject): Boolean {
            val toolName = tool.lowercase()
            if (toolName in OUTWARD_TOOLS) return true
            if (toolName !in TEXT_TRANSMITTING_TOOLS) return false
            if (KEYWORDS.any { toolName.contains(it) }) return true
            val text = (args["text"] as? JsonPrimitive)?.content?.lowercase().orEmpty()
            return KEYWORDS.any { text.contains(it) }
        }

        /** Target-app pacing key taken from the args, or null when the caller falls back to the session key. */
        fun appKey(args: JsonObject): String? =
            APP_KEYS.firstNotNullOfOrNull { key ->
                (args[key] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }
            }
    }
}
