package com.openminis.app.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * JVM tests for the render-time card derivation. Every positive case feeds
 * the EXACT result-text shape the corresponding tool emits today (see the
 * cited tool source); every negative case must degrade to null, never throw.
 */
class CardDeriverTest {

    // ── device_status ────────────────────────────────────────────────────────

    @Test
    fun deviceStatusParsesSnapshotIntoFacts() {
        val text = """
            battery: 85% (charging)
            screen: on, locked
            brightness: manual (128/255)
            network: wifi
            storage: 42 GB free of 128 GB
            ringer: normal (volume music 5/15, ring 5/7)
            do_not_disturb: off
            location_enabled: true
            time: 2026-10-31T10:00:00+08:00
        """.trimIndent()
        val card = CardDeriver.derive("device_status", "{}", text)
        assertTrue(card is ToolCard.Facts)
        card as ToolCard.Facts
        assertEquals("85% (charging)", card.highlight)
        assertEquals("wifi", card.rows.first { it.label == "network" }.value)
        assertEquals(9, card.rows.size)
    }

    @Test
    fun deviceStatusTooShortDegradesToNull() {
        assertNull(CardDeriver.derive("device_status", "{}", "battery: 85%"))
    }

    // ── setting_get ──────────────────────────────────────────────────────────

    @Test
    fun settingGetSingleKeyFacts() {
        val card = CardDeriver.derive(
            "setting_get",
            """{"namespace":"system","key":"screen_off_timeout"}""",
            "system.screen_off_timeout = 30000",
        )
        assertTrue(card is ToolCard.Facts)
        card as ToolCard.Facts
        assertEquals("system.screen_off_timeout", card.rows[0].label)
        assertEquals("30000", card.rows[0].value)
    }

    @Test
    fun settingGetShizukuSuffixStrippedAndNullValueMapped() {
        val via = CardDeriver.derive(
            "setting_get", """{"namespace":"global","key":"wifi_on"}""",
            "global.wifi_on = 1 (via Shizuku shell)",
        ) as ToolCard.Facts
        assertEquals("1", via.rows[0].value)

        val unset = CardDeriver.derive(
            "setting_get", """{"namespace":"secure","key":"nope"}""",
            "secure.nope is not set (null).",
        ) as ToolCard.Facts
        assertEquals("(not set)", unset.rows[0].value)
    }

    @Test
    fun settingGetListingLinesBecomeFactsRows() {
        val card = CardDeriver.derive(
            "setting_get", """{"namespace":"global","filter":"wifi"}""",
            "wifi_on=1\nwifi_scan_always_enabled=1\nbad line without equals",
        )
        assertTrue(card is ToolCard.Facts)
        assertEquals(2, (card as ToolCard.Facts).rows.size)
    }

    // ── clipboard_get ────────────────────────────────────────────────────────

    @Test
    fun clipboardTextBecomesFactsWithHighlight() {
        val card = CardDeriver.derive("clipboard_get", "{}", "hello clipboard") as ToolCard.Facts
        assertEquals("hello clipboard", card.highlight)
        assertEquals("text", card.rows[0].label)
    }

    @Test
    fun clipboardEmptyMarkerMappedAndGarbageNull() {
        assertEquals(
            "(empty)",
            (CardDeriver.derive("clipboard_get", "{}", "(the clipboard holds empty text)")
                as ToolCard.Facts).highlight,
        )
        assertNull(CardDeriver.derive("clipboard_get", "{}", ""))
    }

    // ── Change family (volume / brightness / dnd / sound / toggles) ──────────

    @Test
    fun volumeSetWithWasLineIsUndoable() {
        val args = """{"tool_title":"t","stream":"music","percent":30}"""
        val card = CardDeriver.derive("volume_set", args, "Volume music set to 30% (5/15).\nwas: 50")
        assertTrue(card is ToolCard.Change)
        card as ToolCard.Change
        assertEquals("Volume music", card.label)
        assertEquals("30%", card.newText)
        assertEquals("50", card.oldText)
        assertTrue(card.undoable)
        assertEquals("volume_set", card.tool)
        val undoArgs = org.json.JSONObject(card.undoArgsJson!!)
        assertEquals("music", undoArgs.getString("stream"))
        assertEquals(50, undoArgs.getInt("percent"))
    }

    @Test
    fun volumeSetWithoutWasLineIsNotUndoable() {
        val card = CardDeriver.derive(
            "volume_set", """{"stream":"ring","percent":60}""",
            "Volume ring set to 60% (7/15).",
        ) as ToolCard.Change
        assertFalse(card.undoable)
        assertNull(card.oldText)
        assertNull(card.undoArgsJson)
    }

    @Test
    fun brightnessSetLevelAndAdaptiveVariants() {
        val level = CardDeriver.derive(
            "brightness_set", """{"level":128}""", "Brightness set to 128/255.\nwas: 200",
        ) as ToolCard.Change
        assertEquals("128/255", level.newText)
        assertTrue(level.undoable)
        assertEquals(200, org.json.JSONObject(level.undoArgsJson!!).getInt("level"))

        val adaptive = CardDeriver.derive(
            "brightness_set", """{"adaptive":true}""", "Adaptive brightness enabled.\nwas: adaptive",
        ) as ToolCard.Change
        assertEquals("adaptive", adaptive.newText)
        assertTrue(adaptive.undoable)
        assertEquals(true, org.json.JSONObject(adaptive.undoArgsJson!!).getBoolean("adaptive"))

        val notRestorable = CardDeriver.derive(
            "brightness_set", """{"adaptive":true}""", "Adaptive brightness enabled.\nwas: manual",
        ) as ToolCard.Change
        assertEquals("manual", notRestorable.oldText)
        assertFalse(notRestorable.undoable)
    }

    @Test
    fun dndAndSoundModeChangesParseOldMode() {
        val dnd = CardDeriver.derive(
            "dnd_set", """{"mode":"priority"}""", "Do Not Disturb set to priority.\nwas: off",
        ) as ToolCard.Change
        assertEquals("priority", dnd.newText)
        assertTrue(dnd.undoable)
        assertEquals("off", org.json.JSONObject(dnd.undoArgsJson!!).getString("mode"))

        val sound = CardDeriver.derive(
            "sound_mode", """{"mode":"silent"}""", "Ringer mode set to silent.\nwas: normal",
        ) as ToolCard.Change
        assertEquals("silent", sound.newText)
        assertTrue(sound.undoable)
    }

    @Test
    fun wifiToggleChangeAndAlreadyOnDegrade() {
        val on = CardDeriver.derive(
            "wifi_toggle", """{"on":true}""", "Wi-Fi switched on via Shizuku shell.\nwas: off",
        ) as ToolCard.Change
        assertEquals("on", on.newText)
        assertTrue(on.undoable)
        assertEquals(false, org.json.JSONObject(on.undoArgsJson!!).getBoolean("on"))

        val withOutput = CardDeriver.derive(
            "wifi_toggle", """{"on":false}""",
            "Wi-Fi switched off via Shizuku shell. Output: ok\nwas: on",
        ) as ToolCard.Change
        assertTrue(withOutput.undoable)
        assertEquals(true, org.json.JSONObject(withOutput.undoArgsJson!!).getBoolean("on"))

        assertNull(
            CardDeriver.derive(
                "wifi_toggle", """{"on":true}""", "Wi-Fi is already on; nothing to do.",
            ),
        )
        assertNull(
            CardDeriver.derive(
                "wifi_toggle", """{"on":true}""",
                "no Shizuku: opened the system internet; ask the user to toggle it there",
            ),
        )
    }

    // ── alarm / calendar / cronjob → Event ───────────────────────────────────

    @Test
    fun alarmSetEventWithLabelAndRepeat() {
        val args = """{"tool_title":"t","hour":7,"minute":30,"label":"Standup","days":[1,3,5]}"""
        val card = CardDeriver.derive("alarm_set", args, ALARM_SENT_CONFIRMATION) as ToolCard.EventCard
        assertEquals("Standup", card.title)
        assertEquals("07:30", card.timeText)
        assertEquals("Mon, Wed, Fri", card.repeat)
    }

    @Test
    fun alarmTimerVariant() {
        val card = CardDeriver.derive(
            "alarm_set", """{"timer_seconds":90}""", ALARM_TIMER_CONFIRMATION,
        ) as ToolCard.EventCard
        assertEquals("Timer", card.title)
        assertEquals("in 1m 30s", card.timeText)
        assertNull(card.repeat)
    }

    @Test
    fun calendarAddEvent() {
        val args = """{"tool_title":"t","title":"Standup","start":"2026-10-31T09:00"}"""
        val card = CardDeriver.derive(
            "calendar_add", args,
            "Event \"Standup\" created 2026-10-31 09:00–2026-10-31 10:00 in calendar \"Personal\" (me@gmail.com). id=42",
        ) as ToolCard.EventCard
        assertEquals("Standup", card.title)
        assertEquals("2026-10-31 09:00 – 2026-10-31 10:00", card.timeText)
    }

    @Test
    fun cronJobCreateEventAndListCard() {
        val created = CardDeriver.derive(
            "cronjob", "{}",
            """{"ok":true,"id":"abc123","name":"Nightly review","schedule":"every 1d",""" +
                """"fireAtMs":1793539200000,"repeat":"INTERVAL"}""",
        ) as ToolCard.EventCard
        assertEquals("Nightly review", created.title)
        assertEquals("every 1d", created.repeat)

        val listed = CardDeriver.derive(
            "cronjob", """{"action":"list"}""",
            """{"count":2,"tasks":[{"id":"a1b2c3d4e5f6","name":"T1","enabled":true,"repeat":"INTERVAL"},""" +
                """{"id":"f6e5","name":"T2","enabled":false,"repeat":"ONCE"}]}""",
        ) as ToolCard.ListCard
        assertEquals(2, listed.total)
        assertEquals("T1", listed.items[0].title)
        assertTrue(listed.items[1].subtitle!!.contains("disabled"))
    }

    // ── contacts / sms / call log / apps → List ──────────────────────────────

    @Test
    fun contactsSearchList() {
        val card = CardDeriver.derive(
            "contacts_search", """{"query":"ali"}""",
            "Alice: +8613800000000\nAlice Wang: 13900139000",
        ) as ToolCard.ListCard
        assertEquals(2, card.total)
        assertEquals("Alice", card.items[0].title)
        assertEquals("+8613800000000", card.items[0].subtitle)
    }

    @Test
    fun smsReadList() {
        val card = CardDeriver.derive(
            "sms_read", "{}",
            "2026-10-31 09:12 <- +8613800: hello there\n2026-10-31 08:01 -> 10086: hi",
        ) as ToolCard.ListCard
        assertEquals(2, card.total)
        assertEquals("hello there", card.items[0].title)
        assertTrue(card.items[0].subtitle!!.contains("+8613800"))
    }

    @Test
    fun callLogList() {
        val card = CardDeriver.derive(
            "call_log_read", "{}",
            "2026-10-31 09:12 in Alice +8613800 45s\n2026-10-30 22:10 missed ? 10010 0s",
        ) as ToolCard.ListCard
        assertEquals("Alice +8613800", card.items[0].title)
        assertEquals("10010", card.items[1].title)
        assertTrue(card.items[1].subtitle!!.contains("missed"))
    }

    @Test
    fun appListParsesHiddenTailIntoTotal() {
        val card = CardDeriver.derive(
            "app_list", "{}",
            "WeChat (com.tencent.mm)\nMaps (com.map.app)\n…(3 more; narrow with query)",
        ) as ToolCard.ListCard
        assertEquals("com.tencent.mm", card.items[0].subtitle)
        assertEquals(5, card.total)
    }

    @Test
    fun listCardsCapAtTwentyItems() {
        val text = (1..25).joinToString("\n") { "Contact $it: +861380000$it" }
        val card = CardDeriver.derive("contacts_search", "{}", text) as ToolCard.ListCard
        assertEquals(20, card.items.size)
    }

    // ── location_get ─────────────────────────────────────────────────────────

    @Test
    fun locationFactsWithCoordinateHighlight() {
        val card = CardDeriver.derive(
            "location_get", "{}",
            "lat=39.904200 lng=116.407400 accuracy=20m age=5s provider=gps (permission: ACCESS_FINE_LOCATION)\n" +
                "address: 北京市东城区东长安街",
        ) as ToolCard.Facts
        assertEquals("39.904200, 116.407400", card.highlight)
        assertEquals("北京市东城区东长安街", card.rows.first { it.label == "Address" }.value)
        assertEquals("gps", card.rows.first { it.label == "Provider" }.value)
    }

    // ── web_search / web_fetch / browser_use ─────────────────────────────────

    @Test
    fun webSearchListParsesEntriesIgnoringCitationTail() {
        val text = """
            web_search (ddg) results for "kotlin coroutine" (2):
            When citing sources in your answer, use the [N] numbering below.

            [1] Kotlin Coroutines Guide
               https://kotlinlang.org/docs/coroutines-guide.html
               A guide to coroutines.
            [2] Second Result
               https://example.org/page

            ---参考资料---
            [1] Kotlin Coroutines Guide — https://kotlinlang.org/docs/coroutines-guide.html
            [2] Second Result — https://example.org/page
        """.trimIndent()
        val card = CardDeriver.derive("web_search", "{}", text) as ToolCard.ListCard
        assertEquals(2, card.total)
        assertEquals(2, card.items.size) // the trailing citation block must not duplicate entries
        assertEquals("kotlinlang.org", card.items[0].subtitle)
        assertEquals("Kotlin Coroutines Guide", card.items[0].title)
    }

    @Test
    fun webSearchBadHeaderDegrades() {
        assertNull(CardDeriver.derive("web_search", "{}", "some random output"))
    }

    @Test
    fun webFetchLinkCard() {
        val card = CardDeriver.derive(
            "web_fetch", """{"url":"https://example.com/page"}""",
            "URL: https://example.com/page\n\nWelcome to Example\nBody text",
        ) as ToolCard.Link
        assertEquals("https://example.com/page", card.url)
        assertEquals("example.com", card.domain)
        assertEquals("Welcome to Example", card.title)
    }

    @Test
    fun browserUseScreenshotUsesBlockImagePath() {
        val card = CardDeriver.derive(
            "browser_use", """{"action":"screenshot"}""", SCREENSHOT_META,
            imagePath = "/data/user/0/x/cache/browser_screenshots/screenshot_1.jpg",
        ) as ToolCard.Media
        assertTrue(card.isImage)
        assertEquals("image/jpeg", card.mimeType)
    }

    @Test
    fun browserUseNavigateLinkAndOtherActionsNull() {
        val nav = CardDeriver.derive(
            "browser_use", """{"action":"navigate","url":"https://example.org/x"}""",
            "Navigated to https://example.org/x",
        ) as ToolCard.Link
        assertEquals("example.org", nav.domain)

        assertNull(CardDeriver.derive("browser_use", """{"action":"screenshot"}""", SCREENSHOT_META))
        assertNull(CardDeriver.derive("browser_use", """{"action":"click","selector":"#a"}""", "clicked"))
    }

    // ── generate_image / virtual_screen → Media ──────────────────────────────

    @Test
    fun generateImageMediaFromSavedToLine() {
        val card = CardDeriver.derive(
            "generate_image", """{"prompt":"a cat"}""",
            "![image](minis://attachments/generated/image-123.png)\n" +
                "Saved to /var/minis/attachments/generated/image-123.png\nNice image.",
        ) as ToolCard.Media
        assertEquals("/var/minis/attachments/generated/image-123.png", card.path)
        assertEquals("image/png", card.mimeType)
        assertTrue(card.isImage)
    }

    @Test
    fun virtualScreenScreenshotMediaFromJson() {
        val card = CardDeriver.derive(
            "virtual_screen", """{"action":"screenshot","display_id":1}""",
            """{"ok":true,"display_id":1,"detail":"screenshot 102400 bytes via ImageReader",""" +
                """"path":"/var/minis/offloads/vscreen_1_123.png"}""",
        ) as ToolCard.Media
        assertEquals("/var/minis/offloads/vscreen_1_123.png", card.path)
        assertEquals("image/png", card.mimeType)
    }

    @Test
    fun virtualScreenNonScreenshotDegrades() {
        assertNull(
            CardDeriver.derive(
                "virtual_screen", """{"action":"launch"}""",
                """{"ok":true,"display_id":1,"detail":"launched com.example"}""",
            ),
        )
    }

    // ── file_write / file_edit / multi_edit → File ───────────────────────────

    @Test
    fun fileWriteFileCard() {
        val card = CardDeriver.derive(
            "file_write", """{"path":"/var/minis/workspace/notes.md"}""",
            "Wrote to /var/minis/workspace/notes.md (1234 bytes)",
        ) as ToolCard.FileCard
        assertEquals("notes.md", card.name)
        assertEquals(1234L, card.sizeBytes)
        assertFalse(card.isNew)
    }

    @Test
    fun fileEditAndMultiEditFileCards() {
        val edit = CardDeriver.derive(
            "file_edit", "{}",
            "Edited /var/minis/workspace/notes.md (2 replacement(s), 1300 bytes)",
        ) as ToolCard.FileCard
        assertEquals("notes.md", edit.name)

        val multi = CardDeriver.derive(
            "multi_edit", "{}",
            "Applied 3 edits to /var/minis/workspace/a.txt (3 replacement(s), 900 bytes)",
        ) as ToolCard.FileCard
        assertEquals("a.txt", multi.name)
    }

    // ── `was:` line parsing + global guards ──────────────────────────────────

    @Test
    fun parseWasLineTakesLastWasLineOnly() {
        assertNull(CardDeriver.parseWasLine("Volume music set to 30% (5/15)."))
        assertEquals(
            "50",
            CardDeriver.parseWasLine("Volume music set to 30% (5/15).\nwas: 50"),
        )
        assertEquals(
            "55",
            CardDeriver.parseWasLine("was: 40\nmid line\nVolume set.\nwas: 55"),
        )
    }

    @Test
    fun globalGuardsErrorBlankUnknownAndAppLaunch() {
        assertNull(CardDeriver.derive("volume_set", "{}", "Error: invalid_args — missing 'stream'"))
        assertNull(CardDeriver.derive("volume_set", "{}", "  "))
        assertNull(CardDeriver.derive("app_launch", """{"package":"x"}""", "Launched com.example.app."))
        assertNull(CardDeriver.derive("totally_unknown_tool", "{}", "anything"))
    }

    @Test
    fun derivationIsDeterministic() {
        val text = "Volume music set to 30% (5/15).\nwas: 50"
        val a = CardDeriver.derive("volume_set", """{"stream":"music","percent":30}""", text)
        val b = CardDeriver.derive("volume_set", """{"stream":"music","percent":30}""", text)
        assertEquals(a, b)
        assertNotNull(a)
    }

    @Test
    fun malformedArgsNeverThrow() {
        // Garbage args JSON must still derive what the result text alone supports.
        val card = CardDeriver.derive(
            "volume_set", "{not json", "Volume music set to 30% (5/15).\nwas: 50",
        ) as ToolCard.Change
        // No stream in args → cannot build undo args → undo degrades gracefully.
        assertFalse(card.undoable)
        assertEquals("30%", card.newText)
    }

    companion object {
        /** Exact tail of AlarmSetTool.execute's success text (SystemTools.kt). */
        val ALARM_SENT_CONFIRMATION =
            "Sent an alarm at 07:30 to the clock app. Some clock apps show a confirmation page " +
                "instead of saving silently; verify in the clock app if it matters."
        val ALARM_TIMER_CONFIRMATION =
            "Sent a 90s timer to the clock app. Some clock apps show a confirmation page instead " +
                "of saving silently; verify in the clock app if it matters."

        /** Exact screenshot metadata block from BrowserUseManager.viewportMetadata. */
        val SCREENSHOT_META = """
            Screenshot captured
              URL: https://example.com/page
              Title: Example
              Image: 1080x2340 (240KB)
              Viewport: 412x915
              Page size: 1080x5200
              Image may be downscaled; use viewport coordinates for actions.
              Scroll position: (0, 120)
        """.trimIndent()
    }
}
