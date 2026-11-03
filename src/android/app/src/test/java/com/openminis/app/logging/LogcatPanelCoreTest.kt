package com.openminis.app.logging

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-logcat-panel] Pins the live panel's parsing / filtering / buffering
 * contract.
 *
 * These are the parts a user actually notices when they go wrong: a dropped
 * level column makes `W`/`E` lines invisible, a greedy pid matcher eats the
 * message, an unbounded ring leaks memory in a process that is already
 * struggling. All of it is plain Kotlin, so it is pinned here rather than on a
 * device.
 */
class LogcatPanelCoreTest {

    // ── Parsing ────────────────────────────────────────────────────────────

    @Test
    fun `parses a standard time-format line`() {
        val line = LogcatLineParser.parse("11-03 14:21:07.123 I/Minis.Chat( 4321): token refresh ok")
        assertEquals("11-03 14:21:07.123", line.time)
        assertEquals('I', line.level)
        assertEquals("Minis.Chat", line.tag)
        assertEquals("token refresh ok", line.message)
    }

    @Test
    fun `tolerates unpadded pid and long tags`() {
        val line = LogcatLineParser.parse("11-03 14:21:07.123 W/RIL-Socket-2(12345): send failed")
        assertEquals('W', line.level)
        assertEquals("RIL-Socket-2", line.tag)
        assertEquals("send failed", line.message)
    }

    @Test
    fun `keeps parentheses slash and colon inside the message`() {
        val line = LogcatLineParser.parse(
            "11-03 14:21:07.123 E/OkHttp( 77): POST /v1/chat (attempt 2): timeout after 30s",
        )
        assertEquals("OkHttp", line.tag)
        // The first "(digits)" after the tag is the pid group; everything past
        // its closing paren belongs to the message verbatim.
        assertEquals("POST /v1/chat (attempt 2): timeout after 30s", line.message)
    }

    @Test
    fun `line without a pid group keeps the whole tail as message`() {
        val line = LogcatLineParser.parse("11-03 14:21:07.123 D/MyTag plain text payload")
        assertEquals("MyTag", line.tag)
        assertEquals("plain text payload", line.message)
    }

    @Test
    fun `non-logcat line survives as an unknown-level row`() {
        val line = LogcatLineParser.parse("Kotlin/Native: some raw banner")
        // "Kotlin/Native:" looks like a level/tag pair but 'N' is not a level,
        // so it must not be shredded into columns.
        assertEquals(LogcatLineParser.UNKNOWN_LEVEL, line.level)
        assertEquals("Kotlin/Native: some raw banner", line.message)
        assertEquals("Kotlin/Native: some raw banner", line.raw)
    }

    @Test
    fun `empty line does not throw`() {
        assertEquals(LogcatLineParser.UNKNOWN_LEVEL, LogcatLineParser.parse("").level)
    }

    // ── Levels ─────────────────────────────────────────────────────────────

    @Test
    fun `verbosity ordering drives the threshold`() {
        assertFalse(LogcatLevels.passes('V', 'I'))
        assertFalse(LogcatLevels.passes('D', 'W'))
        assertTrue(LogcatLevels.passes('I', 'I'))
        assertTrue(LogcatLevels.passes('E', 'W'))
        assertTrue(LogcatLevels.passes('F', 'E'))
    }

    @Test
    fun `unknown level is never filtered out`() {
        // A raw line has no severity we can rank; hiding it at "W only" would
        // make real output disappear with no way to bring it back.
        assertTrue(LogcatLevels.passes(LogcatLineParser.UNKNOWN_LEVEL, 'E'))
    }

    // ── Ring ───────────────────────────────────────────────────────────────

    @Test
    fun `ring evicts oldest and counts drops`() {
        val ring = LogcatRing(capacity = 3)
        listOf("a", "b", "c").forEach { ring.add(line(it)) }
        assertEquals(0, ring.dropped)
        assertTrue(ring.add(line("d")))
        assertEquals(1, ring.dropped)
        assertEquals(listOf("b", "c", "d"), ring.snapshot().map { it.message })
    }

    @Test
    fun `clear resets content and drop counter`() {
        val ring = LogcatRing(capacity = 2)
        ring.add(line("a")); ring.add(line("b")); ring.add(line("c"))
        ring.clear()
        assertEquals(0, ring.size)
        assertEquals(0, ring.dropped)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `zero capacity is rejected`() {
        LogcatRing(capacity = 0)
    }

    // ── Filtering ──────────────────────────────────────────────────────────

    private val sample = listOf(
        LogcatLine("t", 'V', "Trace", "verbose detail", "raw-v"),
        LogcatLine("t", 'I', "Minis.Chat", "token refresh ok", "raw-i"),
        LogcatLine("t", 'W', "OkHttp", "slow response 2.1s", "raw-w"),
        LogcatLine("t", 'E', "Minis.Net", "HTTP 500 from upstream", "raw-e"),
    )

    @Test
    fun `level threshold narrows the stream`() {
        assertEquals(listOf("slow response 2.1s", "HTTP 500 from upstream"),
            LogcatFilter.visible(sample, 'W', "").map { it.message })
    }

    @Test
    fun `query matches tag or message case-insensitively`() {
        assertEquals(listOf("Minis.Chat", "Minis.Net"),
            LogcatFilter.visible(sample, 'V', "minis").map { it.tag })
        assertEquals(listOf("token refresh ok"),
            LogcatFilter.visible(sample, 'V', "REFRESH").map { it.message })
    }

    @Test
    fun `tag prefix scopes the query to the tag column`() {
        // "500" appears only in a message, so a tag-scoped query must miss it.
        assertEquals(emptyList<LogcatLine>(), LogcatFilter.visible(sample, 'V', "tag:500"))
        assertEquals(listOf("OkHttp"),
            LogcatFilter.visible(sample, 'V', "tag:okhttp").map { it.tag })
    }

    @Test
    fun `level and text filters combine`() {
        assertEquals(listOf("HTTP 500 from upstream"),
            LogcatFilter.visible(sample, 'W', "minis").map { it.message })
    }

    @Test
    fun `clipboard keeps the original line verbatim`() {
        assertEquals("raw-v\nraw-i", LogcatFilter.toClipboard(sample.take(2)))
    }

    // ── Live buffer ────────────────────────────────────────────────────────

    private class FakeTailSource : LogcatTailSource {
        var started = 0
        var stopped = 0
        var sink: ((String) -> Unit)? = null
        override fun start() { started++ }
        override fun stop() { stopped++ }
    }

    private fun bufferWith(fake: FakeTailSource, capacity: Int = 100) = LogcatLiveBuffer(
        capacity = capacity,
        tailSourceFactory = { sink ->
            fake.sink = sink
            fake
        },
    )

    @Test
    fun `start is idempotent and stop releases the source`() {
        val fake = FakeTailSource()
        val buffer = bufferWith(fake)
        buffer.start()
        buffer.start()
        assertEquals(1, fake.started)
        assertTrue(buffer.isRunning)
        buffer.stop()
        assertEquals(1, fake.stopped)
        assertFalse(buffer.isRunning)
    }

    @Test
    fun `ingest assigns increasing seq and bumps version`() {
        val fake = FakeTailSource()
        val buffer = bufferWith(fake)
        buffer.start()
        val sink = requireNotNull(fake.sink)
        sink("11-03 14:21:07.123 I/Tag( 1): one")
        sink("11-03 14:21:07.124 I/Tag( 1): two")
        val rows = buffer.snapshot()
        assertEquals(listOf("one", "two"), rows.map { it.message })
        assertEquals(listOf(1L, 2L), rows.map { it.seq })
        assertEquals(2L, buffer.version)
    }

    @Test
    fun `attach banner lines are dropped`() {
        val fake = FakeTailSource()
        val buffer = bufferWith(fake)
        buffer.start()
        val sink = requireNotNull(fake.sink)
        sink("--------- beginning of main")
        sink("--------- beginning of system")
        sink("11-03 14:21:07.123 I/Tag( 1): real")
        assertEquals(listOf("real"), buffer.snapshot().map { it.message })
        assertEquals(1L, buffer.version)
    }

    @Test
    fun `clear resets rows and restarts sequence numbering`() {
        val fake = FakeTailSource()
        val buffer = bufferWith(fake)
        buffer.start()
        val sink = requireNotNull(fake.sink)
        sink("11-03 14:21:07.123 I/Tag( 1): one")
        buffer.clear()
        assertEquals(0, buffer.snapshot().size)
        assertEquals(0, buffer.droppedCount)
        sink("11-03 14:21:07.124 I/Tag( 1): two")
        // seq restarts at 1 after clear: the LazyColumn is empty at that point,
        // so there is no key collision to worry about.
        assertEquals(listOf(1L), buffer.snapshot().map { it.seq })
    }

    @Test
    fun `capacity overflow is reported to the UI`() {
        val fake = FakeTailSource()
        val buffer = bufferWith(fake, capacity = 2)
        buffer.start()
        val sink = requireNotNull(fake.sink)
        sink("11-03 14:21:07.123 I/Tag( 1): one")
        sink("11-03 14:21:07.124 I/Tag( 1): two")
        sink("11-03 14:21:07.125 I/Tag( 1): three")
        assertEquals(listOf("two", "three"), buffer.snapshot().map { it.message })
        assertEquals(1, buffer.droppedCount)
    }
}

private fun line(msg: String) = LogcatLine("t", 'I', "T", msg, msg)
