package com.openminis.app.ui.chat.share

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

// [T-multi-select-share] Pure model for sharing selected messages as one long image
// or a PDF: which rows form one selectable unit and cell, how a reply's markdown
// splits into prose and tables (for adaptive table width), where PDF pages break, and
// how large a long image may get. Port of iOS ChatShareModel.swift; no Android types,
// so ChatShareModelTest runs it on the JVM. Lengths are dp (iOS points).

/** One selectable unit of the chat: a user message, a callback card, or a whole reply. */
data class ChatShareUnit(val kind: Kind, val messageIds: List<String>) {
    enum class Kind { USER, CALLBACK, REPLY }

    /** The messages the unit covers, in chat order; never empty. */
    val id: String get() = messageIds[0]
}

/** What the grouping needs to know about one chat message. */
data class ChatShareUnitSource(
    val id: String,
    val role: Role,
    /** A user-role message that is really a sub agent reporting back. */
    val isCallback: Boolean,
    /**
     * Not shown as its own row (queued): skipped without splitting the reply
     * around it.
     */
    val isHidden: Boolean,
) {
    enum class Role { USER, ASSISTANT, OTHER }
}

object ChatShareUnits {
    /**
     * A user message is one unit, a callback card is one unit, and a reply is one
     * unit: every consecutive assistant message up to the next visible non-assistant
     * row, the same span the chat groups under one assistant header. Dividers and
     * system rows are not selectable and end the reply before them.
     */
    fun group(sources: List<ChatShareUnitSource>): List<ChatShareUnit> {
        val units = mutableListOf<ChatShareUnit>()
        val reply = mutableListOf<String>()
        fun closeReply() {
            if (reply.isNotEmpty()) units += ChatShareUnit(ChatShareUnit.Kind.REPLY, reply.toList())
            reply.clear()
        }
        for (s in sources) {
            if (s.isHidden) continue
            when (s.role) {
                ChatShareUnitSource.Role.ASSISTANT -> reply += s.id
                ChatShareUnitSource.Role.USER -> {
                    closeReply()
                    units += ChatShareUnit(
                        if (s.isCallback) ChatShareUnit.Kind.CALLBACK else ChatShareUnit.Kind.USER,
                        listOf(s.id),
                    )
                }
                ChatShareUnitSource.Role.OTHER -> closeReply()
            }
        }
        closeReply()
        return units
    }

    fun unitContaining(messageId: String, units: List<ChatShareUnit>): ChatShareUnit? =
        units.firstOrNull { messageId in it.messageIds }
}

/**
 * One selectable row of the share picker: a user message, a callback card, or one
 * piece of a reply (a text block, a tool call, a thinking block, the error line), so
 * a long tool log can be left out while the answer around it is kept.
 */
data class ChatShareCell(
    val kind: Kind,
    val messageId: String,
    /** The block for text / tool / thinking cells; null for whole-message cells. */
    val blockId: String?,
    /** The unit the cell belongs to; selected cells of one unit render together. */
    val unitId: String,
) {
    enum class Kind { USER, CALLBACK, TEXT, TOOL, THINKING, NOTE }

    val id: String
        get() = when {
            blockId != null -> "$messageId/$blockId"
            kind == Kind.NOTE -> "$messageId/error"
            else -> messageId
        }
}

/** What the cell split needs to know about one reply block. */
data class ChatShareBlockSource(val id: String, val kind: Kind, val isEmpty: Boolean) {
    enum class Kind { TEXT, TOOL, THINKING, HIDDEN }
}

object ChatShareCells {
    /**
     * Cells in chat order: a user or callback unit is one cell; a reply is its blocks
     * in order (empty and info blocks dropped), each message followed by its error line.
     */
    fun cells(
        units: List<ChatShareUnit>,
        blocks: Map<String, List<ChatShareBlockSource>>,
        errors: Set<String>,
    ): List<ChatShareCell> {
        val out = mutableListOf<ChatShareCell>()
        for (unit in units) {
            when (unit.kind) {
                ChatShareUnit.Kind.USER ->
                    out += ChatShareCell(ChatShareCell.Kind.USER, unit.id, null, unit.id)
                ChatShareUnit.Kind.CALLBACK ->
                    out += ChatShareCell(ChatShareCell.Kind.CALLBACK, unit.id, null, unit.id)
                ChatShareUnit.Kind.REPLY -> for (mid in unit.messageIds) {
                    for (b in blocks[mid].orEmpty()) {
                        if (b.isEmpty) continue
                        val kind = when (b.kind) {
                            ChatShareBlockSource.Kind.TEXT -> ChatShareCell.Kind.TEXT
                            ChatShareBlockSource.Kind.TOOL -> ChatShareCell.Kind.TOOL
                            ChatShareBlockSource.Kind.THINKING -> ChatShareCell.Kind.THINKING
                            ChatShareBlockSource.Kind.HIDDEN -> continue
                        }
                        out += ChatShareCell(kind, mid, b.id, unit.id)
                    }
                    if (mid in errors) out += ChatShareCell(ChatShareCell.Kind.NOTE, mid, null, unit.id)
                }
            }
        }
        return out
    }

    /**
     * Opening the picker from a message selects its whole ROUND: the user message
     * that starts it and everything up to the next user message — the reply, its
     * thinking and tool calls, callback cards, errors. A reply with no user message
     * before it starts its round.
     */
    fun roundSelection(unit: String?, cells: List<ChatShareCell>): Set<String> {
        if (unit == null) return emptySet()
        val idx = cells.indexOfFirst { it.unitId == unit }
        if (idx < 0) return emptySet()
        var start = idx
        while (start > 0 && cells[start].kind != ChatShareCell.Kind.USER) start--
        if (cells[start].kind != ChatShareCell.Kind.USER) start = idx
        var end = start + 1
        while (end < cells.size && cells[end].kind != ChatShareCell.Kind.USER) end++
        return cells.subList(start, end).mapTo(LinkedHashSet()) { it.id }
    }

    /** WeChat-style "select everything from here down" (and its undo). */
    fun setBelow(id: String, cells: List<ChatShareCell>, selected: Set<String>, on: Boolean): Set<String> {
        val start = cells.indexOfFirst { it.id == id }
        if (start < 0) return selected
        val ids = cells.subList(start, cells.size).map { it.id }
        return if (on) selected + ids else selected - ids.toSet()
    }

    /**
     * The selected cells in chat order, grouped into runs that belong to one unit —
     * what the renderer lays out as one block (and keeps apart from the next by a gap).
     */
    fun renderGroups(cells: List<ChatShareCell>, selected: Set<String>): List<List<ChatShareCell>> {
        val groups = mutableListOf<MutableList<ChatShareCell>>()
        for (cell in cells) {
            if (cell.id !in selected) continue
            val last = groups.lastOrNull()?.lastOrNull()
            if (last != null && last.unitId == cell.unitId) groups.last() += cell else groups += mutableListOf(cell)
        }
        return groups
    }
}

/**
 * [T9b-share-card-spacing] Vertical spacing between the export's rows, taken from the
 * chat list rather than from a gap of the export's own. The list puts only its
 * `spacedBy(2.dp)` between rows; each row brings its own vertical padding (a task card
 * 4dp above and below, a tool capsule 3dp, a user bubble 10dp above and 4dp below, the
 * error banner 4dp above), and the export draws those same composables. A fixed 8dp
 * between rows and 18dp between groups on top of that padding gave every
 * scheduled-task and callback card, and the tool capsule after it, two to three times
 * the chat's gap.
 *
 * Two rows of the chat are not drawn by the export, and each keeps its space:
 * - the assistant header ("Minis Ultra" and its icon) above a reply, which [TURN_GAP] stands
 *   in for. The chat draws it by its own rule ([headerAbove], AssistantHeaderRule): a
 *   reply to a scheduled-task fire or a callback card that follows a reply has none,
 *   so it sits right under its card;
 * - the usage row closing a finished reply: zero height while its capsule is hidden,
 *   but still a list item, so one more row gap ([gapBetween]'s usageRowAbove).
 */
object ChatShareSpacing {
    /** How a group (one unit) meets its neighbours. */
    enum class Edge { BUBBLE, CARD, REPLY }

    /**
     * What the chat's header rule sees in a message: a system row or a system-written
     * turn (scheduled-task fire, callback) is looked past.
     */
    enum class Speaker { USER, ASSISTANT, SKIPPED }

    /** The chat list's `verticalArrangement = spacedBy(2.dp)`. */
    const val ROW_GAP = 2f

    /** Stand-in for the assistant header the export leaves out. */
    const val TURN_GAP = 16f

    /** A user row is a card when it is a scheduled-task fire; callbacks are always cards. */
    fun edge(kind: ChatShareCell.Kind, isScheduledCard: Boolean): Edge = when (kind) {
        ChatShareCell.Kind.USER -> if (isScheduledCard) Edge.CARD else Edge.BUBBLE
        ChatShareCell.Kind.CALLBACK -> Edge.CARD
        else -> Edge.REPLY
    }

    /**
     * Whether the chat draws the assistant header above the reply starting at
     * [index] of the chat's messages: not when the nearest earlier message that is
     * neither a system row nor a system-written turn is an assistant reply
     * (flattenMessages' resume-continuation rule).
     */
    fun headerAbove(speakers: List<Speaker>, index: Int): Boolean {
        for (i in index - 1 downTo 0) {
            when (speakers[i]) {
                Speaker.ASSISTANT -> return false
                Speaker.USER -> return true
                Speaker.SKIPPED -> {}
            }
        }
        return true
    }

    /**
     * The gap between two consecutive groups of the export, in dp: the row gap, one
     * more when the group above ends with a finished reply (its hidden usage row), and
     * the header stand-in when the chat draws the header above the group below.
     */
    fun gapBetween(usageRowAbove: Boolean, headerAbove: Boolean): Float =
        ROW_GAP + (if (usageRowAbove) ROW_GAP else 0f) + (if (headerAbove) TURN_GAP else 0f)

    /**
     * The chat's `precededByUser` for a user bubble: the row above is a user-role
     * message (a bubble, a scheduled-task card or a callback card), which gives the
     * bubble its larger top padding.
     */
    fun followsUserRow(previous: Edge?): Boolean = previous == Edge.BUBBLE || previous == Edge.CARD
}

/**
 * Splits a reply's markdown into prose and GFM tables, so an adaptive-table export can
 * give each table its own (wider) canvas while prose keeps the reading width.
 */
object ChatShareMarkdownChunker {
    data class Chunk(val text: String, val isTable: Boolean)

    fun split(markdown: String): List<Chunk> {
        val lines = markdown.split("\n")
        val chunks = mutableListOf<Chunk>()
        val prose = mutableListOf<String>()
        var fence: String? = null
        fun flushProse() {
            val text = prose.joinToString("\n")
            if (text.isNotBlank()) chunks += Chunk(text, false)
            prose.clear()
        }
        var i = 0
        while (i < lines.size) {
            val line = lines[i]
            val lead = line.trimStart(' ')
            val indent = line.length - lead.length
            val marker = if (indent <= 3) fenceMarker(lead) else null
            if (marker != null) {
                if (fence == null) fence = marker else if (lead.startsWith(fence)) fence = null
                prose += line; i++; continue
            }
            if (fence == null && indent <= 3 && line.contains("|") &&
                i + 1 < lines.size && isSeparator(lines[i + 1])
            ) {
                flushProse()
                val table = mutableListOf(line, lines[i + 1])
                i += 2
                while (i < lines.size && lines[i].contains("|") && lines[i].isNotBlank()) {
                    table += lines[i]; i++
                }
                // Ends with a newline: rendered on its own, a table's last row is the
                // document's last line, and without one it came out as raw "| … |"
                // text under a table one row short (seen on iOS devices).
                chunks += Chunk(table.joinToString("\n") + "\n", true)
                continue
            }
            prose += line; i++
        }
        flushProse()
        return chunks
    }

    private fun fenceMarker(lead: String): String? = when {
        lead.startsWith("```") -> "```"
        lead.startsWith("~~~") -> "~~~"
        else -> null
    }

    /** `| --- | :---: |`, `---|--:`: cells of dashes with optional colons. */
    fun isSeparator(line: String): Boolean {
        var core = line.trim(' ', '\t')
        if (!core.contains("-")) return false
        if (core.startsWith("|")) core = core.drop(1)
        if (core.endsWith("|")) core = core.dropLast(1)
        val cells = core.split("|")
        if (cells.isEmpty()) return false
        for (cell in cells) {
            var c = cell.trim(' ', '\t')
            if (c.startsWith(":")) c = c.drop(1)
            if (c.endsWith(":")) c = c.dropLast(1)
            if (c.isEmpty() || !c.all { it == '-' }) return false
        }
        return true
    }
}

/**
 * Page breaks for the PDF. Units are placed one after another; a unit that does not
 * fit the rest of the page moves to a new page only when little of the page is left,
 * otherwise (and when taller than a page) it is cut at the lowest break point (a line
 * or block edge) in the lower part of the page, or at the page edge when there is none.
 */
object ChatSharePagination {
    data class Cursor(
        var page: Int = 0,
        /** Content-area y of the next free point on [page]. */
        var y: Float = 0f,
    )

    data class Slice(
        val page: Int,
        /** Top of the slice in the unit's own coordinates. */
        val unitY: Float,
        val height: Float,
        /** Where the slice's top lands in the page's content area. */
        val pageY: Float,
    )

    /**
     * A break is only taken if it keeps at least this share of the space on the page;
     * otherwise a hard cut at the page edge wastes less paper than an early break.
     */
    const val MIN_FILL = 0.5f

    /**
     * A unit that does not fit the rest of the page moves whole to the next page only
     * when less than this share of the page is left; otherwise it is split.
     */
    const val MOVE_WHOLE_BELOW = 0.3f

    /**
     * [breaks] without the ones strictly inside a [solid] span (top to bottom, dp): a
     * card's or a tool capsule's own text lines are not places to end a page, so it
     * moves to the next page whole (or, taller than a page, is cut at the edge).
     */
    fun withoutCutsThrough(breaks: List<Float>, solid: List<Pair<Float, Float>>): List<Float> {
        if (solid.isEmpty()) return breaks
        return breaks.filter { b -> solid.none { (top, bottom) -> b > top + EDGE_SLOP && b < bottom - EDGE_SLOP } }
    }

    /** Break positions this close to a span's edge are its edge (rounding of dp). */
    private const val EDGE_SLOP = 0.5f

    fun place(height: Float, breaks: List<Float>, pageHeight: Float, gap: Float, cursor: Cursor): List<Slice> {
        val h = height
        if (h <= 0f || pageHeight <= 0f) return emptyList()
        if (cursor.y > 0f) {
            cursor.y += gap
            if (cursor.y >= pageHeight) { cursor.page += 1; cursor.y = 0f }
        }
        if (h <= pageHeight - cursor.y) {
            val slice = Slice(cursor.page, 0f, h, cursor.y)
            cursor.y += h
            return listOf(slice)
        }
        // Moving a whole unit to the next page is only worth it when little of this page
        // is left; with a large remainder (a short question at the top of a page, then a
        // long reply) that left most of the page blank, so the unit is split at a clean
        // break instead (below; it still moves when no break fits the remainder).
        if (h <= pageHeight && pageHeight - cursor.y < pageHeight * MOVE_WHOLE_BELOW) {
            cursor.page += 1
            cursor.y = h
            return listOf(Slice(cursor.page, 0f, h, 0f))
        }
        val sorted = breaks.filter { it > 0f && it < h }.sorted()
        val slices = mutableListOf<Slice>()
        var s = 0f
        while (h - s > 0.5f) {
            val avail = pageHeight - cursor.y
            if (h - s <= avail) {
                slices += Slice(cursor.page, s, h - s, cursor.y)
                cursor.y += h - s
                break
            }
            val best = sorted.lastOrNull { it > s + avail * MIN_FILL && it <= s + avail }
            if (best == null && cursor.y > 0f) {
                // No clean break in what is left of this page: start the unit afresh.
                cursor.page += 1
                cursor.y = 0f
                continue
            }
            val end = best ?: (s + avail)
            slices += Slice(cursor.page, s, end - s, cursor.y)
            s = end
            cursor.page += 1
            cursor.y = 0f
        }
        return slices
    }
}

object ChatShareImageBudget {
    /** Pixel ceiling for one long image (~240 MB at 4 bytes per pixel, as on iOS). */
    const val MAX_PIXELS = 60_000_000f

    /**
     * Tallest long image offered, in pixels: beyond this the image is not made (a
     * picture that tall is unwieldy to view or send) and the selection goes to PDF.
     */
    const val MAX_PIXEL_HEIGHT = 50_000f

    /** The scale long images are rendered at (pixels per dp). */
    const val PREFERRED_SCALE = 3f

    /**
     * The render scale for a long image of [width] x [height] dp: [preferred] when it
     * fits both budgets, lower when not, null below 1x (share as PDF).
     */
    fun scale(width: Float, height: Float, preferred: Float = PREFERRED_SCALE): Float? {
        if (width <= 0f || height <= 0f) return preferred
        val fit = min(sqrt(MAX_PIXELS / (width * height)), MAX_PIXEL_HEIGHT / height)
        if (fit >= preferred) return preferred
        val floored = floor(fit * 4f) / 4f
        return if (floored >= 1f) floored else null
    }
}

/**
 * A cheap estimate of an export's height, made before rendering so the preview can
 * say up front that a selection is too long for one image. Text is wrapped by
 * character width (CJK one em, other scripts about half), tables and code count
 * rows, the rest are fixed-height rows; it errs a little high rather than low.
 */
data class ChatShareEstimateInput(val kind: ChatShareCell.Kind, val text: String)

object ChatShareHeightEstimate {
    const val FONT_SIZE = 16.5f
    val lineHeight: Float get() = FONT_SIZE * 1.45f

    /** Header, margins and the gaps between pieces. */
    const val HEADER_HEIGHT = 80f
    const val PER_CELL_GAP = 10f

    /**
     * Estimated export height in dp for rows laid out at [width] (the page width;
     * rows keep 16dp side padding).
     */
    fun points(inputs: List<ChatShareEstimateInput>, width: Float): Float {
        val content = max(width - 32f, 100f)
        return inputs.fold(HEADER_HEIGHT) { total, input -> total + PER_CELL_GAP + height(input, content) }
    }

    /** Whether a long image of that height would pass the pixel-height ceiling. */
    fun tooTallForImage(points: Float): Boolean =
        points * ChatShareImageBudget.PREFERRED_SCALE > ChatShareImageBudget.MAX_PIXEL_HEIGHT

    fun height(input: ChatShareEstimateInput, contentWidth: Float): Float = when (input.kind) {
        ChatShareCell.Kind.USER -> wrappedHeight(input.text, contentWidth * 0.8f - 28f) + 20f
        ChatShareCell.Kind.CALLBACK -> 72f
        ChatShareCell.Kind.TOOL -> 36f
        ChatShareCell.Kind.THINKING -> 40f
        ChatShareCell.Kind.NOTE -> 30f
        ChatShareCell.Kind.TEXT -> {
            var total = 0f
            var inFence = false
            for (line in input.text.split("\n")) {
                val trimmed = line.trim(' ', '\t')
                if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
                    inFence = !inFence; total += 12f; continue
                }
                if (inFence) { total += 20f; continue }
                if (trimmed.isEmpty()) { total += 8f; continue }
                if (trimmed.startsWith("|") || (trimmed.contains("|") && trimmed.contains("---"))) {
                    total += if (trimmed.contains("---")) 0f else 44f
                    continue
                }
                total += wrappedHeight(trimmed, contentWidth) * (if (trimmed.startsWith("#")) 1.3f else 1f)
            }
            total
        }
    }

    fun wrappedHeight(text: String, width: Float): Float {
        var lines = 0f
        for (paragraph in text.split("\n")) {
            var visual = 0f
            var i = 0
            while (i < paragraph.length) {
                val cp = paragraph.codePointAt(i)
                visual += if (cp >= 0x2E80) FONT_SIZE else FONT_SIZE * 0.55f
                i += Character.charCount(cp)
            }
            lines += max(1f, ceil(visual / max(width, 1f)))
        }
        return max(1f, lines) * lineHeight
    }
}

/**
 * Canvas widths for adaptive tables. Rows keep the chosen reading width (phone or
 * tablet); a table gets its natural width and the page widens to the widest one, up
 * to a readable cap that scales with the reading width.
 */
object ChatShareGeometry {
    /** A chat row's side padding (16dp each side) around its content. */
    const val ROW_PADDING = 32f

    /**
     * A readable page, not an unbounded one: a table wider than this wraps its cells
     * to fit instead of stretching the page (pages are A4-proportioned at whatever
     * width they end up). 1000dp from a phone base, 1.4x a tablet one.
     */
    fun maxCanvasWidth(base: Float): Float = max(1000f, (base * 1.4f).roundToInt().toFloat())

    /**
     * The width a table chunk is laid out at: its natural width (+2 so the table's
     * usable width, which is 1dp under the line, still fits every column), never
     * narrower than prose and never so wide that its row passes the page cap.
     */
    fun tableWidth(natural: Float, contentWidth: Float, base: Float): Float =
        min(max(contentWidth, ceil(natural + 2f)), maxCanvasWidth(base) - ROW_PADDING)

    fun canvasWidth(base: Float, tableRowWidths: List<Float>): Float =
        min(max(base, tableRowWidths.maxOrNull() ?: 0f), maxCanvasWidth(base))
}
