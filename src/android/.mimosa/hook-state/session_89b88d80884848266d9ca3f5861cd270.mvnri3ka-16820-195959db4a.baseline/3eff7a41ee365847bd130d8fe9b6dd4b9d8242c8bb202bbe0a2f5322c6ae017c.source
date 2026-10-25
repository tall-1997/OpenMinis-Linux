package com.openminis.app.sandbox

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.charset.StandardCharsets

class ShellStreamDecoderTest {

    @Test
    fun utf8SplitOn4096BoundaryRoundTrips() {
        val text = "测".repeat(3000)
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        val decoded = decodeInChunks(bytes, 4096)
        assertEquals(text, decoded)
        assertFalse(decoded.contains('\uFFFD'))
    }

    @Test
    fun cjkCharacterSplitByteByByteRoundTrips() {
        val text = "中文输出不能变乱码"
        val bytes = text.toByteArray(StandardCharsets.UTF_8)
        assertEquals(text, decodeInChunks(bytes, 1))
        assertEquals(text, decodeInChunks(bytes, 2))
    }

    @Test
    fun markerSplitAcrossChunksStillCompletes() {
        val marker = "abcd1234"
        val markerLine = "__MINIS_DONE_${marker}_EXIT_0__"
        val payload = "hello 中文\n$markerLine\n"
        val bytes = payload.toByteArray(StandardCharsets.UTF_8)
        val broken = (0 until markerLine.length - 1).count { splitAt ->
            val cut = "hello 中文\n".toByteArray(StandardCharsets.UTF_8).size + splitAt
            !framed(marker, bytes, cut).completed
        }
        assertEquals(0, broken)
    }

    @Test
    fun markerHeldBackIsNotEmittedAsOutput() {
        val marker = "abcd1234"
        val prefix = "__MINIS_DONE_${marker}_EXIT_"
        val framer = armedFramer(marker)
        val first = framer.push("out\n$prefix")
        assertEquals("out\n", first.output)
        assertFalse(first.completed)
        val second = framer.push("7__")
        assertEquals("", second.output)
        assertTrue(second.completed)
        assertEquals(7, second.exitCode)
    }

    @Test
    fun falseMarkerPrefixIsReleasedOnNextChunk() {
        val framer = armedFramer("abcd1234")
        val held = framer.push("tail __MINIS")
        assertFalse(held.output.endsWith("__MINIS"))
        val released = framer.push(" not a marker")
        assertTrue((held.output + released.output).endsWith("__MINIS not a marker"))
        assertFalse(released.completed)
    }

    // ——— [T-android-stale-stream-gate] BEGIN 门 ———

    @Test
    fun bytesBeforeBeginMarkerAreDropped() {
        val marker = "abcd1234"
        val framer = MarkerFramer(marker)
        // Field evidence shape: the PREVIOUS command's full output (watchdog
        // kill notification + payload) still sitting in the pty when the new
        // command's callback is installed.
        val stale = framer.push(
            "/bin/bash: line 80: 18417 Killed  ( sleep 1200; kill -TERM -$$ )\n" +
                "-rw------- old body dump\n"
        )
        assertEquals("", stale.output)
        assertFalse(stale.completed)
        val done = framer.push(
            "__MINIS_GO_${marker}__\nreal output\n__MINIS_DONE_${marker}_EXIT_0__\n"
        )
        assertEquals("real output\n", done.output)
        assertTrue(done.completed)
        assertEquals(0, done.exitCode)
        assertTrue(framer.preBeginDroppedChars() > 0)
        assertTrue(framer.preBeginDroppedSample().contains("18417 Killed"))
    }

    @Test
    fun beginMarkerSplitAcrossChunksStillGates() {
        val marker = "abcd1234"
        val framer = MarkerFramer(marker)
        val a = framer.push("noise\n__MINIS")
        assertEquals("", a.output)
        val b = framer.push("_GO_${marker}__\nreal out\n")
        assertEquals("real out\n", b.output)
        val c = framer.push("__MINIS_DONE_${marker}_EXIT_3__\n")
        assertTrue(c.completed)
        assertEquals(3, c.exitCode)
        assertEquals("real out\n", b.output)
    }

    @Test
    fun partialBeginSuffixHeldThenReleasedAsNoise() {
        val marker = "abcd1234"
        val framer = MarkerFramer(marker)
        // Output that merely CONTAINS a prefix of the begin line must not arm
        // the framer, and must be dropped (it is pre-begin noise).
        val held = framer.push("tail says __MINIS_GO_")
        assertEquals("", held.output)
        val released = framer.push(" but never completes\n__MINIS_GO_${marker}__\nhi\n__MINIS_DONE_${marker}_EXIT_0__\n")
        assertEquals("hi\n", released.output)
        assertTrue(released.completed)
    }

    @Test
    fun shellDeathBeforeBeginFlushesBufferInsteadOfDropping() {
        val marker = "abcd1234"
        val framer = MarkerFramer(marker)
        val death = framer.push("proot error: cannot load ELF\n", endOfInput = true)
        // Death diagnostics must survive: proot's last words are the whole
        // point of the death path.
        assertTrue(death.output.contains("proot error"))
        assertFalse(death.completed)
    }

    @Test
    fun cleanStreamAfterBeginUnchanged() {
        val marker = "abcd1234"
        val framer = MarkerFramer(marker)
        framer.push("__MINIS_GO_${marker}__\n")
        val step = framer.push("数据 output\n__MINIS")
        assertEquals("数据 output\n", step.output)
        assertFalse(step.completed)
        val end = framer.push("_DONE_${marker}_EXIT_7__\n")
        assertTrue(end.completed)
        assertEquals(7, end.exitCode)
    }

    private fun armedFramer(marker: String): MarkerFramer {
        val framer = MarkerFramer(marker)
        framer.push("__MINIS_GO_${marker}__\n")
        return framer
    }

    private fun decodeInChunks(bytes: ByteArray, chunk: Int): String {
        val decoder = Utf8ChunkDecoder()
        val out = StringBuilder()
        var i = 0
        while (i < bytes.size) {
            val n = minOf(chunk, bytes.size - i)
            out.append(decoder.decode(bytes.copyOfRange(i, i + n)))
            i += n
        }
        out.append(decoder.finish())
        return out.toString()
    }

    private fun framed(marker: String, bytes: ByteArray, cut: Int): MarkerFramer.Step {
        val decoder = Utf8ChunkDecoder()
        val framer = armedFramer(marker)
        val first = decoder.decode(bytes.copyOfRange(0, cut.coerceIn(0, bytes.size)))
        val step1 = framer.push(first)
        if (step1.completed) return step1
        val second = decoder.decode(bytes.copyOfRange(cut.coerceIn(0, bytes.size), bytes.size))
        val step2 = framer.push(second)
        if (step2.completed) {
            return step2.copy(output = step1.output + step2.output)
        }
        val tail = framer.push(decoder.finish(), endOfInput = true)
        return tail.copy(output = step1.output + step2.output + tail.output)
    }
}
