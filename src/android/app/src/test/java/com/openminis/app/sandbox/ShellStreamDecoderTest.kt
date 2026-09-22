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
        val framer = MarkerFramer(marker)
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
        val framer = MarkerFramer("abcd1234")
        val held = framer.push("tail __MINIS")
        assertFalse(held.output.endsWith("__MINIS"))
        val released = framer.push(" not a marker")
        assertTrue((held.output + released.output).endsWith("__MINIS not a marker"))
        assertFalse(released.completed)
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
        val framer = MarkerFramer(marker)
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
