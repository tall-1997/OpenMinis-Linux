package com.openminis.app.cards

import org.json.JSONArray
import org.json.JSONObject

/**
 * [G-batch tool result cards] Typed card model for tool results — the
 * "dual channel" design: the model keeps reading the ORIGINAL tool result
 * text byte-for-byte, while the user additionally sees a structured card
 * derived at render time from that same text.
 *
 * Architecture contract (differs from the DSH design doc on purpose):
 *  - Cards are NOT stored in the database and NOT in the event stream. They
 *    are derived on the fly by [CardDeriver.derive] from data the chat
 *    blocks already carry (toolName + argsJson + resultText), so there is
 *    zero Room migration and zero dispatcher change.
 *  - Derivation is deterministic: same input → same card; a mismatch or
 *    unparseable format yields null and the plain tool row remains.
 *  - Types only ever GROW (new variants / new optional fields); existing
 *    field semantics never change. An unknown type tag decodes to null and
 *    the UI degrades to the plain tool row.
 *
 * Serialization is hand-rolled on org.json (no new dependency): see
 * [ToolCardJson]. A serialized card stays far below the 16KB budget from
 * the design doc because list cards cap at [CardDeriver.MAX_LIST_ITEMS].
 */
sealed class ToolCard {

    /** Stable wire tag for [ToolCardJson]; one value per variant, never renamed. */
    abstract val typeTag: String

    /** Key–value snapshot card: device status, settings read, clipboard. */
    data class Facts(
        val title: String,
        val rows: List<FactRow>,
        /** Optional emphasized value (e.g. the battery percentage). */
        val highlight: String? = null,
    ) : ToolCard() {
        override val typeTag: String get() = TYPE_FACTS
        companion object { const val TYPE_FACTS = "facts" }
    }

    /** Result list card: contacts, SMS, calls, apps, search hits, cron tasks. */
    data class ListCard(
        val title: String,
        val items: List<CardItem>,
        /** Total result count even when [items] is truncated for display. */
        val total: Int,
    ) : ToolCard() {
        override val typeTag: String get() = TYPE_LIST
        companion object { const val TYPE_LIST = "list" }
    }

    /** Media file card: generated images, screenshots. */
    data class Media(
        /** Host-absolute path when known, else the guest (`/var/minis/...`) path. */
        val path: String,
        val mimeType: String,
        val isImage: Boolean,
    ) : ToolCard() {
        override val typeTag: String get() = TYPE_MEDIA
        companion object { const val TYPE_MEDIA = "media" }
    }

    /** File artifact card: file_write / file_edit / multi_edit. */
    data class FileCard(
        val name: String,
        val path: String,
        val sizeBytes: Long? = null,
        /** True when the tool reported creating a new file rather than editing one. */
        val isNew: Boolean = false,
    ) : ToolCard() {
        override val typeTag: String get() = TYPE_FILE
        companion object { const val TYPE_FILE = "file" }
    }

    /** Schedule card: alarm_set / calendar_add / cronjob create. */
    data class EventCard(
        val title: String,
        val timeText: String,
        /** Human repeat rule ("Mon, Wed", "every 30m"); null for one-shot. */
        val repeat: String? = null,
    ) : ToolCard() {
        override val typeTag: String get() = TYPE_EVENT
        companion object { const val TYPE_EVENT = "event" }
    }

    /**
     * State-change card: volume / brightness / DND / sound mode / toggles.
     * Undoable only when the result carried the fixed `was: <old>` line
     * (F-batch convention in device/SystemTools.kt — TODO(G) align the
     * exact wording once that batch lands) AND the old value could be
     * coerced back into the tool's own argument shape.
     */
    data class Change(
        val label: String,
        val oldText: String? = null,
        val newText: String,
        val undoable: Boolean = false,
        // G-batch additions (fields only grow): what an undo re-executes.
        val tool: String? = null,
        val undoArgsJson: String? = null,
    ) : ToolCard() {
        override val typeTag: String get() = TYPE_CHANGE
        companion object { const val TYPE_CHANGE = "change" }
    }

    /** Web link card: web_fetch, browser_use navigate/fetch. */
    data class Link(
        val title: String,
        val url: String,
        val domain: String? = null,
    ) : ToolCard() {
        override val typeTag: String get() = TYPE_LINK
        companion object { const val TYPE_LINK = "link" }
    }

    /** One label/value row of [Facts]. */
    data class FactRow(val label: String, val value: String)

    /** One entry of [ListCard]. */
    data class CardItem(val title: String, val subtitle: String? = null)
}

/**
 * org.json based (de)serialization for [ToolCard]. Format:
 * `{"v":1,"type":"facts","title":...,"rows":[{"label":..,"value":..}]}`
 *
 * Reading is total: any malformed input, unknown type tag or wrong field
 * shape returns null instead of throwing, so an older/newer build degrades
 * to the plain tool row rather than crashing.
 */
object ToolCardJson {

    private const val KEY_VERSION = "v"
    private const val KEY_TYPE = "type"
    private const val VERSION = 1

    /** Serialize [card]; never throws for the v1 shapes. */
    fun write(card: ToolCard): String = try {
        when (card) {
            is ToolCard.Facts -> JSONObject()
                .put(KEY_TYPE, card.typeTag)
                .put("title", card.title)
                .put("highlight", card.highlight ?: JSONObject.NULL)
                .put("rows", JSONArray().apply {
                    card.rows.forEach {
                        put(JSONObject().put("label", it.label).put("value", it.value))
                    }
                })

            is ToolCard.ListCard -> JSONObject()
                .put(KEY_TYPE, card.typeTag)
                .put("title", card.title)
                .put("total", card.total)
                .put("items", JSONArray().apply {
                    card.items.forEach {
                        put(
                            JSONObject().put("title", it.title)
                                .put("subtitle", it.subtitle ?: JSONObject.NULL),
                        )
                    }
                })

            is ToolCard.Media -> JSONObject()
                .put(KEY_TYPE, card.typeTag)
                .put("path", card.path)
                .put("mimeType", card.mimeType)
                .put("isImage", card.isImage)

            is ToolCard.FileCard -> JSONObject()
                .put(KEY_TYPE, card.typeTag)
                .put("name", card.name)
                .put("path", card.path)
                .put("sizeBytes", card.sizeBytes ?: JSONObject.NULL)
                .put("isNew", card.isNew)

            is ToolCard.EventCard -> JSONObject()
                .put(KEY_TYPE, card.typeTag)
                .put("title", card.title)
                .put("timeText", card.timeText)
                .put("repeat", card.repeat ?: JSONObject.NULL)

            is ToolCard.Change -> JSONObject()
                .put(KEY_TYPE, card.typeTag)
                .put("label", card.label)
                .put("oldText", card.oldText ?: JSONObject.NULL)
                .put("newText", card.newText)
                .put("undoable", card.undoable)
                .put("tool", card.tool ?: JSONObject.NULL)
                .put("undoArgsJson", card.undoArgsJson ?: JSONObject.NULL)

            is ToolCard.Link -> JSONObject()
                .put(KEY_TYPE, card.typeTag)
                .put("title", card.title)
                .put("url", card.url)
                .put("domain", card.domain ?: JSONObject.NULL)
        }.put(KEY_VERSION, VERSION).toString()
    } catch (_: Exception) {
        ""
    }

    /** Parse [json] back to a [ToolCard]; null on ANY mismatch. */
    fun read(json: String): ToolCard? {
        return try {
            val obj = JSONObject(json)
            if (obj.optInt(KEY_VERSION, -1) != VERSION) null
            else when (obj.optString(KEY_TYPE)) {
                ToolCard.Facts.TYPE_FACTS -> readFacts(obj)
                ToolCard.ListCard.TYPE_LIST -> readList(obj)
                ToolCard.Media.TYPE_MEDIA -> ToolCard.Media(
                    path = requireText(obj, "path") ?: return null,
                    mimeType = requireText(obj, "mimeType") ?: return null,
                    isImage = obj.optBoolean("isImage", false),
                )
                ToolCard.FileCard.TYPE_FILE -> ToolCard.FileCard(
                    name = requireText(obj, "name") ?: return null,
                    path = requireText(obj, "path") ?: return null,
                    sizeBytes = if (obj.isNull("sizeBytes")) null else obj.optLong("sizeBytes"),
                    isNew = obj.optBoolean("isNew", false),
                )
                ToolCard.EventCard.TYPE_EVENT -> ToolCard.EventCard(
                    title = requireText(obj, "title") ?: return null,
                    timeText = requireText(obj, "timeText") ?: return null,
                    repeat = obj.optStringOrNull("repeat"),
                )
                ToolCard.Change.TYPE_CHANGE -> ToolCard.Change(
                    label = requireText(obj, "label") ?: return null,
                    oldText = obj.optStringOrNull("oldText"),
                    newText = requireText(obj, "newText") ?: return null,
                    undoable = obj.optBoolean("undoable", false),
                    tool = obj.optStringOrNull("tool"),
                    undoArgsJson = obj.optStringOrNull("undoArgsJson"),
                )
                ToolCard.Link.TYPE_LINK -> ToolCard.Link(
                    title = requireText(obj, "title") ?: return null,
                    url = requireText(obj, "url") ?: return null,
                    domain = obj.optStringOrNull("domain"),
                )
                // Unknown type tag → degrade to null (the tool row stays).
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun readFacts(obj: JSONObject): ToolCard.Facts? {
        val rowsJson = obj.optJSONArray("rows") ?: return null
        val rows = ArrayList<ToolCard.FactRow>(rowsJson.length())
        for (i in 0 until rowsJson.length()) {
            val row = rowsJson.optJSONObject(i) ?: return null
            val label = row.optStringOrNull("label") ?: return null
            val value = row.optStringOrNull("value") ?: return null
            rows.add(ToolCard.FactRow(label, value))
        }
        return ToolCard.Facts(
            title = requireText(obj, "title") ?: return null,
            rows = rows,
            highlight = obj.optStringOrNull("highlight"),
        )
    }

    private fun readList(obj: JSONObject): ToolCard.ListCard? {
        val itemsJson = obj.optJSONArray("items") ?: return null
        val items = ArrayList<ToolCard.CardItem>(itemsJson.length())
        for (i in 0 until itemsJson.length()) {
            val item = itemsJson.optJSONObject(i) ?: return null
            items.add(
                ToolCard.CardItem(
                    title = item.optStringOrNull("title") ?: return null,
                    subtitle = item.optStringOrNull("subtitle"),
                ),
            )
        }
        return ToolCard.ListCard(
            title = requireText(obj, "title") ?: return null,
            items = items,
            total = obj.optInt("total", items.size),
        )
    }

    private fun requireText(obj: JSONObject, key: String): String? {
        if (obj.isNull(key)) return null
        val v = obj.optString(key, "")
        return v.takeIf { it.isNotEmpty() }
    }

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key, "").takeIf { it.isNotEmpty() }
}
