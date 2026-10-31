package com.openminis.app.cards

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Serialization round-trips and the total-read contract of [ToolCardJson]. */
class ToolCardJsonTest {

    @Test
    fun factsRoundTrip() {
        val card = ToolCard.Facts(
            title = "Device status",
            rows = listOf(
                ToolCard.FactRow("battery", "85% (charging)"),
                ToolCard.FactRow("network", "wifi"),
            ),
            highlight = "85% (charging)",
        )
        assertEquals(card, ToolCardJson.read(ToolCardJson.write(card)))
    }

    @Test
    fun listCardRoundTrip() {
        val card = ToolCard.ListCard(
            title = "Contacts",
            items = listOf(ToolCard.CardItem("Alice", "+8613"), ToolCard.CardItem("Bob")),
            total = 5,
        )
        assertEquals(card, ToolCardJson.read(ToolCardJson.write(card)))
    }

    @Test
    fun mediaFileEventRoundTrip() {
        val media = ToolCard.Media("/var/minis/attachments/a.png", "image/png", true)
        val file = ToolCard.FileCard("a.png", "/var/minis/attachments/a.png", 1234L, isNew = true)
        val event = ToolCard.EventCard("Standup", "07:30", "Mon, Wed")
        assertEquals(media, ToolCardJson.read(ToolCardJson.write(media)))
        assertEquals(file, ToolCardJson.read(ToolCardJson.write(file)))
        assertEquals(event, ToolCardJson.read(ToolCardJson.write(event)))
    }

    @Test
    fun changeAndLinkRoundTrip() {
        val change = ToolCard.Change(
            label = "Volume music", oldText = "50", newText = "30%",
            undoable = true, tool = "volume_set",
            undoArgsJson = """{"stream":"music","percent":50}""",
        )
        val link = ToolCard.Link("Example", "https://example.com", "example.com")
        assertEquals(change, ToolCardJson.read(ToolCardJson.write(change)))
        assertEquals(link, ToolCardJson.read(ToolCardJson.write(link)))
    }

    @Test
    fun nullableFieldsSurviveRoundTrip() {
        val change = ToolCard.Change(label = "Brightness", newText = "adaptive")
        val parsed = ToolCardJson.read(ToolCardJson.write(change)) as ToolCard.Change
        assertNull(parsed.oldText)
        assertFalse(parsed.undoable)
        assertNull(parsed.undoArgsJson)
    }

    @Test
    fun unknownTypeDegradesToNull() {
        assertNull(ToolCardJson.read("""{"v":1,"type":"hologram","title":"x"}"""))
    }

    @Test
    fun malformedAndVersionMismatchDegradeToNull() {
        assertNull(ToolCardJson.read("not json at all"))
        assertNull(ToolCardJson.read("""{"v":2,"type":"link","title":"t","url":"https://x"}"""))
        assertNull(ToolCardJson.read("""{"v":1,"type":"media"}""")) // missing required path/mime
        assertNull(ToolCardJson.read(""))
        assertNull(ToolCardJson.read("""{"v":1,"type":"facts","title":"t","rows":"nope"}"""))
    }

    @Test
    fun writeNeverThrowsAndStaysSmall() {
        val big = ToolCard.ListCard(
            title = "Big",
            items = (1..CardDeriver.MAX_LIST_ITEMS).map { ToolCard.CardItem("item $it", "sub $it") },
            total = 500,
        )
        val json = ToolCardJson.write(big)
        assertTrue(json.isNotEmpty())
        assertTrue(json.toByteArray(Charsets.UTF_8).size < 16 * 1024) // design-doc budget
    }
}
