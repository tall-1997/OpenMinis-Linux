package com.openminis.app.provider

import com.openminis.app.provider.openai.ThinkPrefixStreamParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-think-prefix-stream] Port of iOS 22ca4285's algorithm tests.
 *
 * Each case drives the parser the way the SSE loop does — one or more `feed()`
 * calls followed by `finishTurn()` — and asserts the concatenated visible/
 * thinking output.
 */
class ThinkPrefixStreamParserTest {

    /** Run [chunks] through a fresh parser and return (visible, thinking). */
    private fun run(vararg chunks: String): Pair<String, String> {
        val p = ThinkPrefixStreamParser()
        val vis = StringBuilder()
        val think = StringBuilder()
        for (c in chunks) {
            val o = p.feed(c)
            vis.append(o.visible); think.append(o.thinking)
        }
        val fin = p.finishTurn()
        vis.append(fin.visible); think.append(fin.thinking)
        return vis.toString() to think.toString()
    }

    // ── Core MiniMax M3 shape ────────────────────────────────────────────────

    @Test
    fun `think prefix is split and the trailing newlines are dropped`() {
        val (vis, think) = run("<think>reasoning here</think>\n\nActual body text.")
        assertEquals("Actual body text.", vis)
        assertEquals("reasoning here", think)
    }

    @Test
    fun `think-only turn yields a completely empty body`() {
        // The tool-turn case: old code could leave "\n\n", which renders as a
        // blank band because empty-block guards test isEmpty().
        val (vis, think) = run("<think>just planning</think>\n\n")
        assertEquals("", vis)
        assertTrue("body must be empty, not whitespace", vis.isEmpty())
        assertEquals("just planning", think)
    }

    @Test
    fun `leading whitespace before the think tag is tolerated and dropped`() {
        val (vis, think) = run("\n  <think>r</think>\n\nbody")
        assertEquals("body", vis)
        assertEquals("r", think)
    }

    // ── The mid-text regression (worst old defect) ───────────────────────────

    @Test
    fun `mid-text think tag stays verbatim in the body`() {
        // Old behaviour: visible "Use the ", thinking " tag to mark re" — prose
        // silently swallowed into the thinking bubble.
        val input = "Use the <think> tag to mark reasoning."
        val (vis, think) = run(input)
        assertEquals(input, vis)
        assertEquals("", think)
    }

    @Test
    fun `a full think block mid-text is not treated as a prefix`() {
        val input = "Intro text <think>not a prefix</think> tail"
        val (vis, think) = run(input)
        assertEquals(input, vis)
        assertEquals("", think)
    }

    // ── Chunk-splitting (where stream parsers usually break) ─────────────────

    @Test
    fun `open tag split across chunks still enters thinking`() {
        val (vis, think) = run("<thi", "nk>abc</think>\n\nbody")
        assertEquals("body", vis)
        assertEquals("abc", think)
    }

    @Test
    fun `close tag split across chunks still exits thinking`() {
        val (vis, think) = run("<think>abc</thi", "nk>\n\nbody")
        assertEquals("body", vis)
        assertEquals("abc", think)
    }

    @Test
    fun `thinking streams incrementally rather than only at the end`() {
        // Defect 1 on iOS was "no live thinking bubble". Android already streamed
        // deltas; this pins that the parser keeps emitting mid-thinking so the
        // behaviour cannot regress into end-of-turn-only.
        val p = ThinkPrefixStreamParser()
        val first = p.feed("<think>partial reasoning")
        assertTrue("thinking must stream before </think> arrives", first.thinking.isNotEmpty())
        assertEquals("", first.visible)
    }

    @Test
    fun `body whitespace is preserved when interior`() {
        val (vis, _) = run("<think>r</think>\n\nline one\n\nline two")
        assertEquals("line one\n\nline two", vis)
    }

    // ── Plain models must be unaffected ──────────────────────────────────────

    @Test
    fun `plain text without any think tag passes through verbatim`() {
        val input = "Hello, this is a normal reply."
        val (vis, think) = run(input)
        assertEquals(input, vis)
        assertEquals("", think)
    }

    @Test
    fun `plain text arriving in many chunks is reassembled exactly`() {
        val (vis, think) = run("Hel", "lo ", "world", "!")
        assertEquals("Hello world!", vis)
        assertEquals("", think)
    }

    @Test
    fun `text with leading whitespace and no think tag keeps that whitespace`() {
        // Whitespace is only dropped when it precedes a <think>; otherwise it is
        // genuine body content.
        val (vis, _) = run("  indented start")
        assertEquals("  indented start", vis)
    }

    // ── Robustness ───────────────────────────────────────────────────────────

    @Test
    fun `unterminated think block is reported as thinking not body`() {
        val (vis, think) = run("<think>reasoning that never closes")
        assertEquals("", vis)
        assertEquals("reasoning that never closes", think)
    }

    @Test
    fun `finishTurn is idempotent across the finish_reason and DONE double flush`() {
        val p = ThinkPrefixStreamParser()
        p.feed("<think>r</think>\n\nbody")
        val a = p.finishTurn()
        val b = p.finishTurn()
        assertEquals("", b.visible)
        assertEquals("", b.thinking)
        // (a may legitimately be empty too — everything already streamed.)
        assertEquals("", a.thinking)
    }

    @Test
    fun `ordinary text is released by feed itself, leaving nothing to resolve`() {
        // "Some text" can never become "<think>", so feed() commits straight to
        // BODY and returns it immediately — resolveAtToolBoundary has nothing
        // left to do. (Asserting the opposite would be wrong: it would imply
        // text is needlessly withheld until a tool boundary.)
        val p = ThinkPrefixStreamParser()
        assertEquals("Some text", p.feed("Some text").visible)
        assertEquals("", p.resolveAtToolBoundary().visible)
    }

    @Test
    fun `resolveAtToolBoundary releases whitespace held in the undecided state`() {
        // Pure whitespace is genuinely withheld: it could still be the padding
        // before a <think>. If a tool boundary arrives first, it must be
        // released so the pre-tool snapshot isn't missing it.
        val p = ThinkPrefixStreamParser()
        assertEquals("", p.feed("  ").visible)
        assertEquals("  ", p.resolveAtToolBoundary().visible)
    }

    @Test
    fun `resolveAtToolBoundary does not flush a possible partial open tag`() {
        val p = ThinkPrefixStreamParser()
        p.feed("<thi")
        val out = p.resolveAtToolBoundary()
        assertEquals("", out.visible)
        // …and the tag still resolves once the rest arrives.
        val o2 = p.feed("nk>abc</think>\n\nbody")
        assertEquals("abc", o2.thinking)
    }

    // ── [T-universal-think-tag] Vendor spelling variants ─────────────────────

    @Test
    fun `kimi thinking tag prefix is split into the thinking bubble`() {
        val (vis, think) = run("<thinking>plan the edit</thinking>\n\nDone.")
        assertEquals("Done.", vis)
        assertEquals("plan the edit", think)
    }

    @Test
    fun `kimi thinking tag split across chunks still works`() {
        val (vis, think) = run("<think", "ing>step one</think", "ing>\n\nbody")
        assertEquals("body", vis)
        assertEquals("step one", think)
    }

    @Test
    fun `claude antThinking bridge variant is split`() {
        val (vis, think) = run("<antThinking>careful reasoning</antThinking>\n\nanswer")
        assertEquals("answer", vis)
        assertEquals("careful reasoning", think)
    }

    @Test
    fun `glm special token thinking variant is split`() {
        val (vis, think) = run("<|thinking|>reasoning<|/thinking|>\n\nbody")
        assertEquals("body", vis)
        assertEquals("reasoning", think)
    }

    @Test
    fun `reasoning analysis and scratchpad variants are split`() {
        val cases = listOf(
            "reasoning" to "answer A",
            "analysis" to "answer B",
            "scratchpad" to "answer C",
        )
        for ((tag, body) in cases) {
            val (vis, think) = run("<$tag>inside $tag</$tag>\n\n$body")
            assertEquals("$tag: body must survive", body, vis)
            assertEquals("$tag: thinking must be captured", "inside $tag", think)
        }
    }

    @Test
    fun `chinese 思考 variant is split`() {
        val (vis, think) = run("<思考>想一下</思考>\n\n正文")
        assertEquals("正文", vis)
        assertEquals("想一下", think)
    }

    @Test
    fun `unterminated kimi thinking block is thinking not body`() {
        // The exact screenshot failure: provider never closed <thinking>, so the
        // raw tag + reasoning rendered in the bubble. Must go to thinking.
        val (vis, think) = run("<thinking>reasoning tail never closed")
        assertEquals("", vis)
        assertEquals("reasoning tail never closed", think)
    }

    @Test
    fun `mid-stream think block stays verbatim in the body`() {
        // Design: only the turn-INITIAL think tag enters THINKING. Once the
        // state machine is in BODY, later tags are passed through verbatim —
        // this is the deliberate "mid-text tags stay" rule from the iOS port.
        // The screenshot leak is an UNTERMINATED tag at turn START, which is
        // covered by the unterminated tests above.
        val input = "body one\n\n<think>second</think>\n\nbody two"
        val (vis, think) = run(input)
        assertEquals(input, vis)
        assertEquals("", think)
    }

    @Test
    fun `second turn starts a fresh parser and splits again`() {
        // The agent loop creates one parser per turn (provider instantiates a
        // new ThinkPrefixStreamParser per request), so each turn's initial
        // think tag independently routes to the thinking bubble.
        val p1 = ThinkPrefixStreamParser()
        val o1 = p1.feed("<thinking>first</thinking>\n\nbody one")
        p1.finishTurn()
        assertEquals("body one", o1.visible)
        assertEquals("first", o1.thinking)

        val p2 = ThinkPrefixStreamParser()
        val o2 = p2.feed("<think>second</think>\n\nbody two")
        p2.finishTurn()
        assertEquals("body two", o2.visible)
        assertEquals("second", o2.thinking)
    }
}
