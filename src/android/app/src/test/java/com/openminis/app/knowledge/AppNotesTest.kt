package com.openminis.app.knowledge

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files

/**
 * App experience library: persistence roundtrip, duplicate re-confirmation, the privacy
 * filter (email / long digit runs / SMS-code style text), version staleness and the
 * per-app cap of 12 (lowest uses evicted).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], application = android.app.Application::class)
class AppNotesTest {

    private lateinit var dir: File
    private var now = 1_000_000L
    private var version: String? = "100"

    private fun notes(persist: Boolean = true): AppNotes =
        AppNotes(
            if (persist) File(dir, "app_notes.json") else File(dir, "unused-${System.nanoTime()}.json"),
            { version },
            { now },
        )

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("appnotes").toFile()
        now = 1_000_000L
        version = "100"
    }

    private fun bumpClock() {
        now += 60_000L
    }

    @Test
    fun saveAndReloadRoundtrip() {
        val n = notes()
        assertTrue(n.add("com.a", "设置入口在右上角齿轮", "entry") is AppNotes.Outcome.Saved)
        val reloaded = notes()
        val all = reloaded.all.value
        assertEquals(1, all.size)
        assertEquals("com.a", all[0].pkg)
        assertEquals("设置入口在右上角齿轮", all[0].text)
        assertEquals(AppNotes.Kind.entry, all[0].kind)
        assertEquals("100", all[0].appVersion)
        assertEquals(0, all[0].uses)
    }

    @Test
    fun unknownKindFallsBackToFact() {
        val n = notes(persist = false)
        val outcome = n.add("com.a", "some fact", "bogus-kind") as AppNotes.Outcome.Saved
        assertEquals(AppNotes.Kind.fact, outcome.note.kind)
    }

    @Test
    fun duplicateReconfirmsInsteadOfStoringTwice() {
        val n = notes(persist = false)
        val first = n.add("com.a", "打开设置：我 -> 设置", "entry") as AppNotes.Outcome.Saved
        bumpClock()
        val second = n.add("com.a", "打开设置：我 ->  设置 ", "trap")
        assertTrue(second is AppNotes.Outcome.Duplicate)
        val dup = (second as AppNotes.Outcome.Duplicate).note
        assertEquals(first.note.id, dup.id)
        assertEquals(1, dup.uses)
        assertTrue(dup.lastConfirmedAt > first.note.lastConfirmedAt)
        assertEquals(1, n.all.value.size)
    }

    @Test
    fun dedupeIsPerPackage() {
        val n = notes(persist = false)
        assertTrue(n.add("com.a", "same text", "fact") is AppNotes.Outcome.Saved)
        assertTrue(n.add("com.b", "same text", "fact") is AppNotes.Outcome.Saved)
        assertEquals(2, n.all.value.size)
    }

    @Test
    fun emailRejected() {
        val n = notes(persist = false)
        val r = n.add("com.a", "反馈邮箱 feedback@example.com", "fact")
        assertTrue(r is AppNotes.Outcome.Rejected)
    }

    @Test
    fun longDigitRunRejected() {
        val n = notes(persist = false)
        assertTrue(n.add("com.a", "订单号 1381234567890 在详情页", "fact") is AppNotes.Outcome.Rejected)
    }

    @Test
    fun smsCodeStyleRejected() {
        val n = notes(persist = false)
        assertTrue(n.add("com.a", "验证码 482913 输入后进入首页", "fact") is AppNotes.Outcome.Rejected)
        assertTrue(n.add("com.a", "enter the code 482913 to continue", "fact") is AppNotes.Outcome.Rejected)
    }

    @Test
    fun emptyAndOverlongRejected() {
        val n = notes(persist = false)
        assertTrue(n.add("com.a", "   ", "fact") is AppNotes.Outcome.Rejected)
        assertTrue(n.add("com.a", "x".repeat(201), "fact") is AppNotes.Outcome.Rejected)
    }

    @Test
    fun staleWhenInstalledVersionChanges() {
        version = "100"
        val n = notes(persist = false)
        val saved = n.add("com.a", "fresh note", "fact") as AppNotes.Outcome.Saved
        assertFalse(n.isStale(saved.note))
        version = "200"
        assertTrue(n.isStale(saved.note))
    }

    @Test
    fun capOfTwelveKeepsHighestUses() {
        val n = notes(persist = false)
        for (i in 1..12) {
            val r = n.add("com.a", "note number $i", "fact")
            assertTrue(r is AppNotes.Outcome.Saved)
            // Confirm note 1 three times and note 2 once so they are worth keeping.
            if (i == 1) { n.add("com.a", "note number 1", "fact"); n.add("com.a", "note number 1", "fact") }
            if (i == 2) n.add("com.a", "note number 2", "fact")
        }
        assertEquals(12, n.all.value.size)
        // The 13th note evicts the least-used survivor (note 3, zero uses, oldest).
        assertTrue(n.add("com.a", "note number 13", "fact") is AppNotes.Outcome.Saved)
        assertEquals(12, n.all.value.size)
        assertTrue("lowest-uses note evicted", n.all.value.none { it.text == "note number 3" })
        assertTrue("high-use notes survive", n.all.value.any { it.text == "note number 1" })
    }

    @Test
    fun noticePutsStaleLastWithMarker() {
        version = "100"
        val n = notes(persist = false)
        val old = n.add("com.a", "older note", "fact") as AppNotes.Outcome.Saved
        bumpClock()
        n.add("com.a", "current note", "fact")
        bumpClock()
        version = "999"
        // Re-confirm the current note under the new version so only the older one is stale.
        n.add("com.a", "current note", "fact")
        n.add("com.b", "other app note", "fact")
        val notice = n.notice(listOf("com.a"), "App A")!!
        assertTrue("fresh note sorts first", notice.indexOf("current note") < notice.indexOf("older note"))
        assertTrue(n.line(old.note).contains("may be outdated"))
        assertFalse(notice.contains("other app note"))
    }

    @Test
    fun clearAllRemovesEverything() {
        val n = notes()
        n.add("com.a", "one", "fact")
        n.add("com.b", "two", "fact")
        n.clearAll()
        assertTrue(n.all.value.isEmpty())
        assertTrue(notes().all.value.isEmpty())
    }
}
