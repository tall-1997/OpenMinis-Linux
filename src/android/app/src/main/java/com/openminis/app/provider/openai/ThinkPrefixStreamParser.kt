package com.openminis.app.provider.openai

/**
 * [T-android-think-prefix-stream] Splits a streamed `content` field into live
 * thinking deltas and visible body text, for models that embed reasoning as a
 * `<think>…</think>` PREFIX of `content` instead of using the
 * `reasoning_content` field (MiniMax M3, some Qwen/DeepSeek deployments).
 *
 * Port of iOS `ThinkPrefixStreamParser` (22ca4285). Replaces the previous
 * `extractThinkTags` scanner, which had two defects reproduced on Android:
 *
 *  1. **Leading whitespace leaked into the body.** M3 always emits "\n\n" after
 *     `</think>`; the old scanner passed it straight through, so every such
 *     message body began with a blank line. A think-only tool turn could also
 *     leave a whitespace-only text block, which renders as a blank band because
 *     every empty-block guard checks `isEmpty()` and "\n\n" passes that.
 *  2. **Mid-text tags were stripped anywhere.** The old scanner searched for
 *     `<think>` at ANY offset, so a message merely *explaining* the tag —
 *     "Use the `<think>` tag to mark reasoning" — had its prose silently
 *     swallowed into the thinking bubble. Verified against the old
 *     implementation: `"Use the <think> tag to mark reasoning."` produced
 *     visible `"Use the "` and thinking `" tag to mark re"`.
 *
 * Design: an explicit `UNDECIDED → THINKING → BODY` state machine.
 *  - Only a `<think>` at the very START of a turn (leading whitespace tolerated
 *    and dropped) enters THINKING. Anything else commits the turn to BODY, and
 *    from then on tags are passed through verbatim.
 *  - After `</think>`, leading whitespace is dropped, and a trailing-whitespace
 *    run is withheld until proven interior — so a think-only turn ends with an
 *    empty body rather than "\n\n".
 *
 * Not thread-safe; one instance per streaming turn (the provider creates one
 * per request).
 */
internal class ThinkPrefixStreamParser {

    private enum class State { UNDECIDED, THINKING, BODY }

    /** One chunk's worth of split output. Either side may be empty. */
    data class Output(val visible: String, val thinking: String)

    private var state = State.UNDECIDED

    /**
     * Holds bytes we cannot classify yet: a partial `<think>`/`</think>` tag
     * split across chunks, or leading whitespace before we know whether a
     * `<think>` follows.
     */
    private val pending = StringBuilder()

    /**
     * Trailing whitespace in BODY state, withheld until we see a non-space
     * character after it (proving it is interior). Dropped at [finishTurn].
     */
    private val heldWhitespace = StringBuilder()

    private companion object {
        // [T-universal-think-tag] Universal reasoning-tag prefix set, built
        // from the SHARED table (com.openminis.app.harness.text.ReasoningTagVariants)
        // — the history stripper and this parser carried two diverging copies
        // once (6 vs 9 spellings), so paired blocks of the missing spellings
        // survived history replay while the live stream stripped them. Each
        // vendor spelling is documented there.
        // Longest-first so e.g. "<thinking>" wins over "<think>" when both could
        // prefix-match the same incoming bytes ("<thinkin…").
        val OPEN_VARIANTS: List<String> = com.openminis.app.harness.text.ReasoningTagVariants.OPEN_TAGS
        val CLOSE_VARIANTS: List<String> = com.openminis.app.harness.text.ReasoningTagVariants.CLOSE_TAGS
        init {
            require(OPEN_VARIANTS.size == CLOSE_VARIANTS.size) { "tag variant table must stay aligned" }
        }
        // Precomputed for the partial-tag matcher below.
        val MAX_OPEN_LEN = OPEN_VARIANTS.maxOf { it.length }
    }

    /** Which variant was matched at turn start (null = not yet decided). */
    private var activeOpen: String? = null
    private var activeClose: String? = null

    /** Feed one streamed `content` delta. */
    fun feed(text: String): Output {
        if (text.isEmpty()) return Output("", "")
        val visible = StringBuilder()
        val thinking = StringBuilder()
        pending.append(text)

        loop@ while (pending.isNotEmpty()) {
            when (state) {
                State.UNDECIDED -> {
                    // Tolerate (and drop) leading whitespace before a think tag.
                    val firstNonSpace = pending.indexOfFirst { !it.isWhitespace() }
                    if (firstNonSpace < 0) break@loop
                    val rest = pending.substring(firstNonSpace)
                    val matched = matchOpenVariant(rest)
                    when {
                        // Full match → enter THINKING with that variant's close tag.
                        matched > 0 -> {
                            val idx = OPEN_VARIANTS.indexOfFirst {
                                it.length == matched && rest.startsWith(it, ignoreCase = true)
                            }
                            check(idx >= 0) { "matchOpenVariant returned length of unknown variant" }
                            state = State.THINKING
                            activeOpen = OPEN_VARIANTS[idx]
                            activeClose = CLOSE_VARIANTS[idx]
                            pending.delete(0, firstNonSpace + matched)
                        }
                        // Partial match (tag split across chunks) → keep buffering.
                        matched == -1 -> break@loop
                        // No think prefix at all → the whole turn is body.
                        else -> state = State.BODY
                    }
                }

                State.THINKING -> {
                    val close = activeClose
                        ?: error("THINKING without an active variant — impossible via matchOpenVariant")
                    // [T-think-tag-case] Case-insensitive: the history stripper
                    // is IGNORE_CASE, so `<THINKING>` must not leak to the body
                    // live and then vanish after a reload.
                    val closeIdx = pending.indexOfIgnoreCase(close)
                    if (closeIdx < 0) {
                        val safe = safeEmitLength(pending, close)
                        if (safe <= 0) break@loop
                        thinking.append(pending, 0, safe)
                        pending.delete(0, safe)
                        break@loop
                    }
                    thinking.append(pending, 0, closeIdx)
                    pending.delete(0, closeIdx + close.length)
                    state = State.BODY
                    while (pending.isNotEmpty() && pending[0].isWhitespace()) pending.deleteCharAt(0)
                }

                State.BODY -> {
                    // Withhold a trailing whitespace run until proven interior, so
                    // a think-only turn doesn't end with a whitespace-only block.
                    var cut = pending.length
                    while (cut > 0 && pending[cut - 1].isWhitespace()) cut--
                    if (cut > 0) {
                        // Anything held from earlier is now interior — release it.
                        visible.append(heldWhitespace)
                        heldWhitespace.setLength(0)
                        visible.append(pending, 0, cut)
                    }
                    heldWhitespace.append(pending, cut, pending.length)
                    pending.setLength(0)
                    break@loop
                }
            }
        }
        return Output(visible.toString(), thinking.toString())
    }

    /**
     * Flush whatever is still buffered at end of turn. Idempotent — the caller
     * may invoke it on both `finish_reason` and `[DONE]`.
     *
     * Withheld trailing whitespace is intentionally DROPPED, not emitted: that
     * is the whole point of holding it.
     */
    fun finishTurn(): Output {
        val visible = StringBuilder()
        val thinking = StringBuilder()
        if (pending.isNotEmpty()) {
            when (state) {
                // An unterminated <think> — treat the remainder as thinking
                // rather than dumping raw reasoning into the body.
                State.THINKING -> thinking.append(pending)
                // Undecided at end of turn means no think prefix ever arrived,
                // so the buffered bytes are ordinary body text.
                State.UNDECIDED, State.BODY -> visible.append(pending)
            }
            pending.setLength(0)
        }
        heldWhitespace.setLength(0)
        return Output(visible.toString(), thinking.toString())
    }

    /**
     * Flush a short UNDECIDED buffer before a tool boundary, so the ViewModel's
     * pre-tool snapshot isn't missing text. Cross-chunk tag tails and withheld
     * whitespace stay buffered. Mirrors iOS `resolveAtToolBoundary()`.
     */
    fun resolveAtToolBoundary(): Output {
        if (state != State.UNDECIDED || pending.isEmpty()) return Output("", "")
        // Pure whitespace is NOT a partial tag — flush it so the pre-tool
        // snapshot isn't missing it. Only hold back actual tag prefixes.
        val trimmed = pending.trimStart().toString()
        if (trimmed.isNotEmpty() && matchOpenVariant(trimmed) == -1) return Output("", "")
        state = State.BODY
        val out = pending.toString()
        pending.setLength(0)
        return Output(out, "")
    }

    /**
     * Match [rest] (leading-whitespace-trimmed) against the open-variant table.
     * Returns:
     *   >0  — length of a FULL open-tag match (caller consumes that many chars)
     *   -1  — rest is a proper PREFIX of at least one variant (keep buffering)
     *    0  — no variant can ever match (commit to body)
     */
    private fun matchOpenVariant(rest: String): Int {
        if (rest.isEmpty()) return -1  // nothing yet — could still become a tag
        // Longest full match first (table is already longest-first).
        // [T-think-tag-case] Case-insensitive — `<THINKING>` / `<antthinking>`
        // are the same tag with different casing; the history stripper treats
        // them IGNORE_CASE, so the live split must too.
        for (variant in OPEN_VARIANTS) {
            if (rest.startsWith(variant, ignoreCase = true)) return variant.length
        }
        // Partial: rest shorter than a variant and equal to its head.
        if (rest.length < MAX_OPEN_LEN) {
            for (variant in OPEN_VARIANTS) {
                if (rest.length < variant.length && variant.startsWith(rest, ignoreCase = true)) return -1
            }
        }
        return 0
    }

    /**
     * How many chars are safe to emit without splitting a potential [tag]
     * occurrence. Keeps the longest suffix of [buf] that is a proper prefix of
     * [tag] buffered. Comparison is case-insensitive, matching [matchOpenVariant].
     */
    private fun safeEmitLength(buf: StringBuilder, tag: String): Int {
        val maxKeep = minOf(tag.length - 1, buf.length)
        for (keep in maxKeep downTo 1) {
            val suffixStart = buf.length - keep
            var matches = true
            for (k in 0 until keep) {
                if (!buf[suffixStart + k].equals(tag[k], ignoreCase = true)) { matches = false; break }
            }
            if (matches) return suffixStart
        }
        return buf.length
    }

    /** Case-insensitive [indexOf] against this buffer (chars compared lowercased). */
    private fun StringBuilder.indexOfIgnoreCase(other: String, from: Int = 0): Int {
        val last = length - other.length
        for (i in from..last) {
            if (regionMatchesIgnoreCase(i, other)) return i
        }
        return -1
    }

    private fun StringBuilder.regionMatchesIgnoreCase(offset: Int, other: String): Boolean {
        if (offset < 0 || offset + other.length > length) return false
        for (k in other.indices) {
            if (!this[offset + k].equals(other[k], ignoreCase = true)) return false
        }
        return true
    }
}

private inline fun CharSequence.indexOfFirst(predicate: (Char) -> Boolean): Int {
    for (i in indices) if (predicate(this[i])) return i
    return -1
}
