package com.openminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Test

/** [T-stall-echo-strip] byte-exact seed alignment and echo drop. */
class StallEchoStripperTest {

    private val seed =
        "A 的全貌清楚了——ZenDisguise 是伪造 opencode CLI 身份，再看 B 的关键文件和两份补丁能不能干净打上。" +
            "A 两路 apply 全挂，B 一次干净打上。补丁文件是 CRLF 行尾加 BOM，git apply 直接拒绝。" +
            "最后验证 B 能编译加测试，编译过了，是我打的类名不对。"

    @Test
    fun noEchoDivergesImmediatelyAndPassesThrough() {
        val s = StallEchoStripper(seed)
        val stream = "Completely unrelated continuation text. ".repeat(10)
        assertEquals(stream, s.feed(stream))
        assertEquals("", s.flush())
    }

    @Test
    fun fullEchoDroppedAndFreshContentPasses() {
        val fresh = "现在真打并编译跑测试，二十个用例全过。"
        val s = StallEchoStripper(seed)
        assertEquals(fresh, s.feed(seed + fresh))
        assertEquals("", s.flush())
    }

    @Test
    fun fragmentedEchoFedByteByByteStillStripped() {
        val fresh = "低级失误——之前跑的是 check，只验证不落盘。"
        val s = StallEchoStripper(seed)
        val sb = StringBuilder()
        (seed + fresh).forEach { sb.append(s.feed(it.toString())) }
        sb.append(s.flush())
        assertEquals(fresh, sb.toString())
    }

    @Test
    fun seedPrefixHeldBackUntilDivergence() {
        val s = StallEchoStripper(seed)
        // inside the seed echo: nothing may reach consumers yet
        assertEquals("", s.feed(seed.take(80)))
        assertEquals("", s.feed(seed.substring(80))) // buffer now == seed
        // the model keeps writing new content right after the seed —
        // everything past the seed passes through untouched
        val fresh = "后续内容从这里开始。"
        assertEquals(fresh, s.feed(fresh))
    }

    @Test
    fun divergenceInsideSeedStripsAlignedPrefix() {
        // deterministic seed: 100 aligned chars then a guaranteed mismatch
        val customSeed = "M".repeat(100) + "END"
        val s = StallEchoStripper(customSeed)
        assertEquals("", s.feed("M".repeat(80))) // aligned, buffered
        // diverges at char 80 (>= MIN_ECHO): aligned span dropped, rest passes
        assertEquals("rest", s.feed("rest"))
    }

    @Test
    fun streamEndsInsideSeedEchoFlushDropsAll() {
        val s = StallEchoStripper(seed)
        assertEquals("", s.feed(seed.take(100)))
        // matched=100 >= MIN_ECHO: the whole buffered span is the echo
        assertEquals("", s.flush())
    }

    @Test
    fun flushBelowThresholdHandsBufferBack() {
        val s = StallEchoStripper(seed)
        assertEquals("", s.feed(seed.take(10)))
        // matched=10 < MIN_ECHO: never an echo — the buffer is not ours to keep
        assertEquals(seed.take(10), s.flush())
    }

    @Test
    fun divergenceBelowMinEchoPassesEverythingThrough() {
        val head = seed.take(30) // below MIN_ECHO
        val s = StallEchoStripper(seed)
        val out = s.feed(head + "与种子分叉的内容" + "x".repeat(200))
        assertEquals(head + "与种子分叉的内容" + "x".repeat(200), out)
    }

    @Test
    fun divergenceAboveMinEchoStripsOnlyAlignedSpan() {
        val head = seed.take(100) // above MIN_ECHO
        val fresh = "真实的新内容"
        val s = StallEchoStripper(seed)
        assertEquals(fresh, s.feed(head + fresh))
    }

    @Test
    fun emptySeedAlwaysPassesThrough() {
        val s = StallEchoStripper("")
        val stream = "anything at all ".repeat(30)
        assertEquals(stream, s.feed(stream))
        assertEquals("", s.flush())
    }

    @Test
    fun seedExactlyConsumedThenNextDeltaPasses() {
        val fresh = "seed 后面的第一句话。"
        val s = StallEchoStripper(seed)
        assertEquals("", s.feed(seed)) // buffered: seed complete, nothing after yet
        assertEquals(fresh, s.feed(fresh))
    }

    @Test
    fun flushAfterResolutionReturnsEmpty() {
        val s = StallEchoStripper(seed)
        s.feed(seed + "resolved already")
        assertEquals("", s.flush())
    }

    @Test
    fun resolutionIsOneShot() {
        val s = StallEchoStripper(seed)
        s.feed(seed + "resolved")
        assertEquals("more", s.feed("more"))
        assertEquals("even more", s.feed("even more"))
        assertEquals("", s.flush())
    }

    @Test
    fun emptyChunkFeedsAreInert() {
        val s = StallEchoStripper(seed)
        assertEquals("", s.feed(""))
        assertEquals("", s.feed(seed.take(10)))
        assertEquals("", s.feed(""))
        // below threshold at stream end: buffer handed back untouched
        assertEquals(seed.take(10), s.flush())
    }
}
