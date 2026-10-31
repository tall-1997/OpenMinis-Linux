package com.openminis.app.cards

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
 * [P2-convergence] The hand-rolled org.json serialization layer
 * (`ToolCardJson`) was deleted: nothing ever serialized a card to disk or
 * across the wire — cards live only in memory between [CardDeriver.derive]
 * and the renderers, so the (de)serializer had no production reader.
 */
sealed class ToolCard {

    /** Stable per-variant tag; one value per variant, never renamed. */
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
