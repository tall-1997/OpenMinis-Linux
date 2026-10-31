package com.openminis.app.cards

import org.json.JSONObject

/**
 * [G-batch tool result cards] Render-time card derivation — the pure,
 * deterministic heart of the dual-channel design.
 *
 * `derive(toolName, argsJson, resultText)` inspects data the chat blocks
 * already carry and returns a [ToolCard] for the user channel, or null to
 * keep the plain tool row. The text the model reads is never touched: the
 * same bytes flow to the engine regardless of whether a card derives.
 *
 * Contract:
 *  - Deterministic: same inputs → same card. No clock, no I/O, no random.
 *  - Defensive: ANY format mismatch returns null; nothing here throws into
 *    the UI ([derive] wraps everything in a catch-all as a last resort).
 *  - Truncation-safe: [com.openminis.app.tools.ToolOutputPolicy] may cap or
 *    spill oversized results, and [com.openminis.app.tools.ToolOutputSpill]
 *    replaces the tail with a preview + guest path. Derivation only fires
 *    when the format it expects is still fully present in the text — a
 *    truncated tail simply fails the regex and degrades to null. That is
 *    the safety net by construction, not something to handle per tool.
 *  - `was:` line: the F batch appends a fixed `\nwas: <old>` line to the
 *    write-class device tool results (device/SystemTools.kt). The parsers
 *    below follow that convention; TODO(G) re-verify exact wording when
 *    the F batch lands.
 */
object CardDeriver {

    /** List cards never carry more than this many items (design-doc budget). */
    const val MAX_LIST_ITEMS = 20

    /** web_search result tail marker ("参考资料"); CJK kept escaped — match pattern. */
    private val CITATION_TAIL_MARKER = "---\u53c2\u8003\u8d44\u6599---"

    /** Display caps so one huge clipboard/SMS body cannot blow up a card. */
    private const val MAX_FACT_VALUE_CHARS = 300
    private const val MAX_ITEM_TITLE_CHARS = 90
    private const val MAX_LINK_TITLE_CHARS = 90

    /**
     * Derive a card from a tool call. Returns null when the tool has no card
     * mapping, the result text failed/is truncated, or the format does not
     * match what the tool really emits today.
     */
    fun derive(toolName: String, argsJson: String, resultText: String): ToolCard? =
        derive(toolName, argsJson, resultText, imagePath = null)

    /**
     * Overload used by the UI: [imagePath] is the block's saved screenshot
     * file (`AssistantBlock.imageFilePath`) — browser_use results carry the
     * screenshot path on the block, not in the result text, so the caller
     * threads it through. Still deterministic: it is part of the input.
     */
    fun derive(
        toolName: String,
        argsJson: String,
        resultText: String,
        imagePath: String?,
    ): ToolCard? = try {
        if (resultText.isBlank() || resultText.startsWith("Error")) {
            null
        } else {
            deriveInner(toolName, argsJson, resultText, imagePath)
        }
    } catch (_: Throwable) {
        null // never let derivation break the chat UI
    }

    private fun deriveInner(
        toolName: String,
        argsJson: String,
        resultText: String,
        imagePath: String?,
    ): ToolCard? = when (toolName) {
        // ── device group (device/) ──
        "device_status" -> deviceStatusCard(resultText)
        "setting_get" -> settingGetCard(argsJson, resultText)
        "clipboard_get" -> clipboardCard(resultText)
        "volume_set" -> volumeSetCard(argsJson, resultText)
        "brightness_set" -> brightnessSetCard(argsJson, resultText)
        "dnd_set" -> dndSetCard(argsJson, resultText)
        "sound_mode" -> soundModeCard(argsJson, resultText)
        "wifi_toggle" -> connectivityChangeCard("Wi-Fi", argsJson, resultText)
        "bluetooth_toggle" -> connectivityChangeCard("Bluetooth", argsJson, resultText)
        "mobile_data_toggle" -> connectivityChangeCard("Mobile data", argsJson, resultText)
        "alarm_set" -> alarmSetCard(argsJson, resultText)
        "calendar_add" -> calendarAddCard(argsJson, resultText)
        "contacts_search" -> contactsCard(resultText)
        "sms_read" -> smsReadCard(resultText)
        "call_log_read" -> callLogCard(resultText)
        "app_list" -> appListCard(resultText)
        "location_get" -> locationCard(resultText)
        // app_launch is a process step (and the F batch appends knowledge-pack
        // text to it) — deliberately never a card.
        "app_launch" -> null

        // ── engine / agent tools (tools/) ──
        "web_search" -> webSearchCard(resultText)
        "web_fetch" -> webFetchCard(resultText)
        "browser_use" -> browserUseCard(argsJson, resultText, imagePath)
        "generate_image" -> generateImageCard(resultText)
        "file_write" -> fileWriteCard(resultText)
        "file_edit" -> fileEditCard(resultText)
        "multi_edit" -> multiEditCard(resultText)
        "virtual_screen" -> virtualScreenCard(resultText)
        "cronjob" -> cronJobCard(argsJson, resultText)

        // Everything else (bash, ui_read, dispatchers, …) stays a plain row.
        else -> null
    }

    // ────────────────────────────────────────────────────────────────────────
    // device_status — "key: value" snapshot lines
    // ────────────────────────────────────────────────────────────────────────

    private fun deviceStatusCard(resultText: String): ToolCard? {
        val rows = resultText.lines().mapNotNull { line ->
            KEY_VALUE_LINE.matchEntire(line.trim())?.let { m ->
                ToolCard.FactRow(m.groupValues[1], m.groupValues[2].trim())
            }
        }
        if (rows.size < 4) return null // snapshot always yields ≥4 facts
        return ToolCard.Facts(
            title = "Device status",
            rows = rows,
            highlight = rows.firstOrNull { it.label == "battery" }?.value,
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // setting_get — "ns.key = value" (single) or "key=value" listing lines
    // ────────────────────────────────────────────────────────────────────────

    private fun settingGetCard(argsJson: String, resultText: String): ToolCard? {
        val ns = runCatching { JSONObject(argsJson).optString("namespace", "") }.getOrDefault("")
        // Single-key form: "system.screen_off_timeout = 30000" (+ optional
        // " (via Shizuku shell)" suffix), or "… is not set (null)."
        SETTING_GET_SINGLE.matchEntire(resultText.trim())?.let { m ->
            val value = m.groupValues[3].trim().removeSuffix(" (via Shizuku shell)")
            return ToolCard.Facts(
                title = "Setting",
                rows = listOf(ToolCard.FactRow("${m.groupValues[1]}.${m.groupValues[2]}", value)),
                highlight = value,
            )
        }
        SETTING_GET_NULL.matchEntire(resultText.trim())?.let { m ->
            return ToolCard.Facts(
                title = "Setting",
                rows = listOf(ToolCard.FactRow("${m.groupValues[1]}.${m.groupValues[2]}", "(not set)")),
            )
        }
        // Listing form (Shizuku `settings list`): "key=value" per line.
        val rows = resultText.lines().mapNotNull { line ->
            SETTINGS_LIST_LINE.matchEntire(line.trim())?.let { m ->
                ToolCard.FactRow(m.groupValues[1], m.groupValues[2])
            }
        }
        if (rows.isEmpty() || ns.isBlank()) return null
        return ToolCard.Facts(title = "Settings · $ns", rows = rows.take(MAX_LIST_ITEMS))
    }

    // ────────────────────────────────────────────────────────────────────────
    // clipboard_get — raw clipboard text (or the fixed empty-text notice)
    // ────────────────────────────────────────────────────────────────────────

    private fun clipboardCard(resultText: String): ToolCard? {
        val raw = resultText.trim()
        // "(the clipboard holds empty text)" is the tool's empty marker.
        val value = if (raw == "(the clipboard holds empty text)") "(empty)" else raw
        if (value.isEmpty()) return null
        val clipped = clip(value, MAX_FACT_VALUE_CHARS)
        return ToolCard.Facts(
            title = "Clipboard",
            rows = listOf(ToolCard.FactRow("text", clipped)),
            highlight = clipped,
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // write-class device tools → Change cards (undo via the `was:` line)
    // ────────────────────────────────────────────────────────────────────────

    private fun volumeSetCard(argsJson: String, resultText: String): ToolCard? {
        val m = VOLUME_SET.matchEntire(firstLineOf(resultText) ?: return null) ?: return null
        val stream = m.groupValues[1]
        val percent = m.groupValues[2]
        return changeCard(
            label = "Volume $stream",
            newText = "$percent%",
            resultText = resultText,
            toolName = "volume_set",
            argsJson = argsJson,
            valueKey = "percent",
            coerceOld = { it.toIntOrNull()?.let { v -> if (v in 0..100) v else null } },
        )
    }

    private fun brightnessSetCard(argsJson: String, resultText: String): ToolCard? {
        // Match the STATEMENT line only — the F batch may append `\nwas: <old>`
        // after it, and matchEntire on the whole text would then fail. The
        // FULL resultText still goes to [changeCard] so parseWasLine sees it.
        val firstLine = firstLineOf(resultText) ?: return null
        return when {
            firstLine.startsWith("Adaptive brightness enabled.") ->
                changeCard(
                    label = "Brightness",
                    newText = "adaptive",
                    resultText = resultText,
                    toolName = "brightness_set",
                    argsJson = argsJson,
                    valueKey = null, // adaptive restores as a boolean flag
                    coerceOld = { old ->
                        when {
                            old.equals("adaptive", true) || old.equals("auto", true) -> true
                            else -> null
                        }
                    },
                    undoKey = "adaptive",
                )
            else -> {
                val m = BRIGHTNESS_SET.matchEntire(firstLine) ?: return null
                changeCard(
                    label = "Brightness",
                    newText = "${m.groupValues[1]}/255",
                    resultText = resultText,
                    toolName = "brightness_set",
                    argsJson = argsJson,
                    valueKey = "level",
                    coerceOld = { it.toIntOrNull()?.let { v -> if (v in 0..255) v else null } },
                )
            }
        }
    }

    private fun dndSetCard(argsJson: String, resultText: String): ToolCard? {
        val text = firstLineOf(resultText) ?: return null
        val mode = when {
            text == "Do Not Disturb is off." -> "off"
            else -> DND_SET.matchEntire(text)?.groupValues?.get(1)?.trim('\'') ?: return null
        }
        return changeCard(
            label = "Do Not Disturb",
            newText = mode,
            resultText = resultText,
            toolName = "dnd_set",
            argsJson = argsJson,
            valueKey = "mode",
            coerceOld = { old -> DND_MODES.firstOrNull { it == old.lowercase() } },
        )
    }

    private fun soundModeCard(argsJson: String, resultText: String): ToolCard? {
        val m = SOUND_MODE_SET.matchEntire(firstLineOf(resultText) ?: return null) ?: return null
        val mode = m.groupValues[1]
        return changeCard(
            label = "Sound mode",
            newText = mode,
            resultText = resultText,
            toolName = "sound_mode",
            argsJson = argsJson,
            valueKey = "mode",
            coerceOld = { old -> listOf("normal", "vibrate", "silent").firstOrNull { it == old.lowercase() } },
        )
    }

    /** wifi_toggle / bluetooth_toggle / mobile_data_toggle via Shizuku shell. */
    private fun connectivityChangeCard(label: String, argsJson: String, resultText: String): ToolCard? {
        // Only the completed "… switched on/off via Shizuku shell." first line
        // is a real state change: "already on; nothing to do", the system-panel
        // fallback ("no Shizuku: opened the system …") and user-flip handoffs
        // all fail the match and degrade to the plain tool row.
        val firstLine = resultText.lineSequence().firstOrNull()?.trim() ?: return null
        val m = SWITCHED.matchEntire(firstLine) ?: return null
        if (!m.groupValues[1].equals(label, ignoreCase = true)) return null
        val now = m.groupValues[2].lowercase()
        return changeCard(
            label = label,
            newText = now,
            resultText = resultText.trim(),
            toolName = toolNameOfLabel(label),
            argsJson = argsJson,
            valueKey = "on",
            coerceOld = { old -> parseOnOff(old) },
        )
    }

    private fun toolNameOfLabel(label: String): String = when (label) {
        "Wi-Fi" -> "wifi_toggle"
        "Bluetooth" -> "bluetooth_toggle"
        else -> "mobile_data_toggle"
    }

    /**
     * Build a [ToolCard.Change] from the tool-reported new state plus the
     * F-batch `was: <old>` line. Undoable only when the old value coerces
     * back into [valueKey]'s JSON type; [undoKey] overrides the argument
     * name (brightness adaptive mode).
     */
    private fun changeCard(
        label: String,
        newText: String,
        resultText: String,
        toolName: String,
        argsJson: String,
        valueKey: String?,
        coerceOld: (String) -> Any?,
        undoKey: String? = null,
    ): ToolCard.Change {
        val old = parseWasLine(resultText)
        val coerced = old?.let { coerceOld(it) }
        val key = undoKey ?: valueKey
        val undoArgsJson = if (coerced != null && key != null) buildUndoArgs(argsJson, key, coerced) else null
        return ToolCard.Change(
            label = label,
            oldText = old?.let { clip(it, MAX_FACT_VALUE_CHARS) },
            newText = newText,
            undoable = undoArgsJson != null,
            tool = toolName,
            undoArgsJson = undoArgsJson,
        )
    }

    /**
     * The fixed F-batch undo line: the LAST line of the result that starts
     * with exactly `was: `. Everything after the marker is the old value.
     * TODO(G): align word-for-word with the F batch in device/SystemTools.kt.
     */
    internal fun parseWasLine(resultText: String): String? =
        resultText.lineSequence()
            .lastOrNull { it.startsWith("was: ") }
            ?.removePrefix("was: ")
            ?.trim()
            ?.takeIf { it.isNotEmpty() }

    /** Merge the original call args with the old value for the undo re-run. */
    private fun buildUndoArgs(argsJson: String, key: String, oldValue: Any): String? {
        val obj = runCatching { JSONObject(argsJson) }.getOrNull() ?: return null
        return try {
            when (oldValue) {
                is Int -> obj.put(key, oldValue)
                is Boolean -> obj.put(key, oldValue)
                is String -> obj.put(key, oldValue)
                else -> return null
            }.toString()
        } catch (_: Exception) {
            null
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // alarm_set / calendar_add / cronjob → Event
    // ────────────────────────────────────────────────────────────────────────

    private fun alarmSetCard(argsJson: String, resultText: String): ToolCard? {
        val args = runCatching { JSONObject(argsJson) }.getOrNull() ?: JSONObject()
        val text = firstLineOf(resultText) ?: return null
        ALARM_SET.matchEntire(text)?.let { m ->
            val label = args.optStringOrNull("label")
            return ToolCard.EventCard(
                title = label?.takeIf { it.isNotBlank() } ?: "Alarm",
                timeText = m.groupValues[1],
                repeat = repeatFromArgs(args),
            )
        }
        TIMER_SET.matchEntire(text)?.let { m ->
            val seconds = m.groupValues[1].toIntOrNull() ?: return null
            return ToolCard.EventCard(
                title = args.optStringOrNull("label")?.takeIf { it.isNotBlank() } ?: "Timer",
                timeText = "in ${formatDuration(seconds)}",
                repeat = null,
            )
        }
        return null
    }

    private fun repeatFromArgs(args: JSONObject): String? {
        val days = runCatching { args.optJSONArray("days") }.getOrNull() ?: return null
        if (days.length() == 0) return null
        val names = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
        val parsed = (0 until days.length()).mapNotNull { days.optInt(it, -1).takeIf { d -> d in 1..7 } }
        if (parsed.isEmpty()) return null
        return parsed.distinct().sorted().joinToString(", ") { names[it - 1] }
    }

    private fun calendarAddCard(argsJson: String, resultText: String): ToolCard? {
        val m = CALENDAR_ADD.matchEntire(firstLineOf(resultText) ?: return null) ?: return null
        val argsTitle = runCatching { JSONObject(argsJson).optStringOrNull("title") }.getOrNull()
        return ToolCard.EventCard(
            title = argsTitle ?: m.groupValues[1],
            timeText = "${m.groupValues[2]} – ${m.groupValues[3]}",
            repeat = null,
        )
    }

    /** cronjob create/list results are JSON — parse defensively. */
    private fun cronJobCard(argsJson: String, resultText: String): ToolCard? {
        val obj = runCatching { JSONObject(resultText.trim()) }.getOrNull() ?: return null
        // create → Event
        if (obj.optBoolean("ok", false) && obj.has("fireAtMs") && obj.has("name")) {
            val fireAt = obj.optLong("fireAtMs", -1L)
            val schedule = obj.optStringOrNull("schedule")
            return ToolCard.EventCard(
                title = obj.optStringOrNull("name") ?: return null,
                timeText = if (fireAt > 0) formatEpoch(fireAt) else obj.optStringOrNull("schedule") ?: "?",
                repeat = schedule?.takeIf { it.startsWith("every", ignoreCase = true) },
            )
        }
        // list → List of scheduled tasks
        val tasks = obj.optJSONArray("tasks")
        if (obj.has("count") && tasks != null) {
            val items = (0 until tasks.length()).take(MAX_LIST_ITEMS).mapNotNull { i ->
                val t = tasks.optJSONObject(i) ?: return@mapNotNull null
                val name = t.optStringOrNull("name") ?: return@mapNotNull null
                val sub = buildString {
                    append(t.optStringOrNull("repeat")?.lowercase() ?: "")
                    t.optStringOrNull("id")?.let { if (isNotEmpty()) append(" · "); append(shortId(it)) }
                    if (!t.optBoolean("enabled", true)) append(" · disabled")
                }.takeIf { it.isNotBlank() }
                ToolCard.CardItem(name, sub)
            }
            if (items.isEmpty()) return null
            return ToolCard.ListCard(
                title = "Scheduled tasks",
                items = items,
                total = obj.optInt("count", items.size),
            )
        }
        return null
    }

    // ────────────────────────────────────────────────────────────────────────
    // contacts / sms / call log / apps → List
    // ────────────────────────────────────────────────────────────────────────

    private fun contactsCard(resultText: String): ToolCard? {
        if (resultText.trim() == "No contacts found.") return null
        val items = resultText.lines().mapNotNull { line ->
            CONTACT_LINE.matchEntire(line.trim())?.let { m ->
                ToolCard.CardItem(m.groupValues[1], m.groupValues[2])
            }
        }.take(MAX_LIST_ITEMS)
        if (items.isEmpty()) return null
        return ToolCard.ListCard("Contacts", items, total = items.size)
    }

    private fun smsReadCard(resultText: String): ToolCard? {
        if (resultText.trim() == "No messages.") return null
        val items = resultText.lines().mapNotNull { line ->
            val trimmed = line.trim()
            val date = trimmed.take(SMS_DATE_LEN)
            if (!DATE_PREFIX.matches(date)) return@mapNotNull null
            // Line shape: "yyyy-MM-dd HH:mm <dir> <number>: <body>" — split on
            // the first ": " (numbers never contain one) so bodies with spaces
            // and colons survive intact.
            val body = trimmed.substringAfter(": ", missingDelimiterValue = "")
            if (body.isEmpty()) return@mapNotNull null
            val left = trimmed.substringBefore(": ").drop(SMS_DATE_LEN).trim()
            if (left.isEmpty()) return@mapNotNull null
            val number = left.substringAfterLast(' ')
            val dir = left.removeSuffix(number).trim()
            ToolCard.CardItem(
                clip(body, MAX_ITEM_TITLE_CHARS),
                subtitle = "$date $dir $number".replace("  ", " ").trim(),
            )
        }.take(MAX_LIST_ITEMS)
        if (items.isEmpty()) return null
        return ToolCard.ListCard("Messages", items, total = items.size)
    }

    private fun callLogCard(resultText: String): ToolCard? {
        if (resultText.trim() == "No calls.") return null
        val items = resultText.lines().mapNotNull { line ->
            CALL_LOG.matchEntire(line.trim())?.let { m ->
                val date = m.groupValues[1]
                val type = m.groupValues[2]
                val name = m.groupValues[3]
                val number = m.groupValues[4]
                val dur = m.groupValues[5]
                ToolCard.CardItem(
                    if (name == "?" || name == number) number else "$name $number",
                    subtitle = "$date · $type · ${dur}s",
                )
            }
        }.take(MAX_LIST_ITEMS)
        if (items.isEmpty()) return null
        return ToolCard.ListCard("Recent calls", items, total = items.size)
    }

    private fun appListCard(resultText: String): ToolCard? {
        var hidden = 0
        val items = resultText.lines().mapNotNull { line ->
            val trimmed = line.trim()
            MORE_MARKER.matchEntire(trimmed)?.let { m ->
                hidden = m.groupValues[1].toIntOrNull() ?: 0
                return@mapNotNull null
            }
            APP_LINE.matchEntire(trimmed)?.let { m ->
                ToolCard.CardItem(m.groupValues[1], m.groupValues[2])
            }
        }.take(MAX_LIST_ITEMS)
        if (items.isEmpty()) return null
        return ToolCard.ListCard("Installed apps", items, total = items.size + hidden)
    }

    // ────────────────────────────────────────────────────────────────────────
    // location_get → Facts (coordinate row)
    // ────────────────────────────────────────────────────────────────────────

    private fun locationCard(resultText: String): ToolCard? {
        val m = LOCATION.matchEntire(resultText.lineSequence().firstOrNull()?.trim() ?: return null)
            ?: return null
        val lat = m.groupValues[1]
        val lng = m.groupValues[2]
        val rows = mutableListOf(
            ToolCard.FactRow("Coordinates", "$lat, $lng"),
            ToolCard.FactRow("Accuracy", "${m.groupValues[3]}m"),
            ToolCard.FactRow("Age", "${m.groupValues[4]}s"),
            ToolCard.FactRow("Provider", m.groupValues[5]),
        )
        var address: String? = null
        resultText.lineSequence().drop(1).forEach { line ->
            val t = line.trim()
            if (t.startsWith("address: ")) address = t.removePrefix("address: ").trim()
        }
        address?.let { rows.add(ToolCard.FactRow("Address", clip(it, MAX_FACT_VALUE_CHARS))) }
        return ToolCard.Facts("Location", rows, highlight = "$lat, $lng")
    }

    // ────────────────────────────────────────────────────────────────────────
    // web_search / web_fetch / browser_use
    // ────────────────────────────────────────────────────────────────────────

    private fun webSearchCard(resultText: String): ToolCard? {
        val header = SEARCH_HEADER.matchEntire(
            resultText.lineSequence().firstOrNull()?.trim() ?: return null,
        ) ?: return null
        val total = header.groupValues[2].toIntOrNull() ?: 0
        val items = ArrayList<ToolCard.CardItem>()
        var title: String? = null
        var url: String? = null
        fun flush() {
            val t = title?.trim() ?: return
            val u = url?.trim() ?: return
            if (items.size < MAX_LIST_ITEMS) {
                items.add(ToolCard.CardItem(clip(t, MAX_ITEM_TITLE_CHARS), domainOf(u) ?: u))
            }
        }
        resultText.lineSequence()
            // Trailing citation-block marker in web_search output; CJK escaped
            // because it is a match pattern, not UI copy (literal ratchet).
            .takeWhile { !it.trim().startsWith(CITATION_TAIL_MARKER) }
            .forEach { raw ->
                val line = raw.trimEnd()
                when {
                    line.startsWith("   ") -> {
                        val v = line.trim()
                        if (v.startsWith("http") && title != null && url == null) url = v
                        // else: snippet line — the card keeps title + domain only
                    }
                    else -> SEARCH_ITEM_PREFIX.matchEntire(line.trim())?.let { m ->
                        if (title != null) flush()
                        title = m.groupValues[2]
                        url = null
                    }
                }
            }
        flush()
        if (items.isEmpty()) return null
        return ToolCard.ListCard(
            title = "Search · ${header.groupValues[1]}",
            items = items,
            total = if (total > 0) total else items.size,
        )
    }

    private fun webFetchCard(resultText: String): ToolCard? {
        if (!resultText.startsWith("URL: ")) return null
        val url = resultText.lineSequence().firstOrNull()?.removePrefix("URL: ")?.trim() ?: return null
        if (!url.startsWith("http")) return null
        val body = resultText.substringAfter("\n\n", missingDelimiterValue = "")
        val title = body.lineSequence()
            .firstOrNull { it.isNotBlank() }
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
            ?: domainOf(url) ?: url
        return ToolCard.Link(clip(title, MAX_LINK_TITLE_CHARS), url, domainOf(url))
    }

    private fun browserUseCard(argsJson: String, resultText: String, imagePath: String?): ToolCard? {
        val args = runCatching { JSONObject(argsJson) }.getOrNull() ?: JSONObject()
        val action = args.optStringOrNull("action") ?: return null
        return when (action) {
            // The screenshot bytes live on the block (imageFilePath), not in
            // the result text — degrade to null when the caller has no path.
            "screenshot" -> imagePath?.takeIf { it.isNotEmpty() }?.let {
                ToolCard.Media(path = it, mimeType = "image/jpeg", isImage = true)
            }
            "navigate", "new_tab", "fetch" -> {
                val url = args.optStringOrNull("url")?.takeIf { it.startsWith("http") }
                    ?: resultText.lineSequence()
                        .firstOrNull { it.trim().startsWith("URL: ") }
                        ?.trim()?.removePrefix("URL: ")?.trim()
                    ?: return null
                ToolCard.Link(
                    title = clip(
                        resultText.lineSequence().firstOrNull { it.isNotBlank() }?.trim() ?: url,
                        MAX_LINK_TITLE_CHARS,
                    ),
                    url = url,
                    domain = domainOf(url),
                )
            }
            else -> null
        }
    }

    // ────────────────────────────────────────────────────────────────────────
    // generate_image / virtual_screen → Media
    // ────────────────────────────────────────────────────────────────────────

    private fun generateImageCard(resultText: String): ToolCard? {
        val path = resultText.lineSequence()
            .firstOrNull { it.startsWith("Saved to ") }
            ?.removePrefix("Saved to ")?.trim()
            ?: return null
        if (!path.startsWith("/")) return null
        return ToolCard.Media(
            path = path,
            mimeType = mimeForExtension(path.substringAfterLast('.', "")),
            isImage = true,
        )
    }

    /** virtual_screen screenshot result is JSON: {"ok":true,"path":"/var/minis/offloads/…png"} */
    private fun virtualScreenCard(resultText: String): ToolCard? {
        val obj = runCatching { JSONObject(resultText.trim()) }.getOrNull() ?: return null
        if (!obj.optBoolean("ok", false)) return null
        val detail = obj.optStringOrNull("detail") ?: return null
        if (!detail.startsWith("screenshot")) return null
        val path = obj.optStringOrNull("path")?.takeIf { it.startsWith("/var/minis/") } ?: return null
        return ToolCard.Media(path, mimeType = "image/png", isImage = true)
    }

    // ────────────────────────────────────────────────────────────────────────
    // file_write / file_edit / multi_edit → File
    // ────────────────────────────────────────────────────────────────────────

    private fun fileWriteCard(resultText: String): ToolCard? {
        val m = FILE_WRITE.matchEntire(firstLineOf(resultText) ?: return null) ?: return null
        val path = m.groupValues[1]
        val bytes = m.groupValues[2].toLongOrNull()
        return ToolCard.FileCard(
            name = path.substringAfterLast('/'),
            path = path,
            sizeBytes = bytes,
            isNew = false, // the text does not distinguish create vs overwrite
        )
    }

    private fun fileEditCard(resultText: String): ToolCard? =
        editCard(EDITED, resultText)

    private fun multiEditCard(resultText: String): ToolCard? =
        editCard(APPLIED_EDITS, resultText)

    private fun editCard(regex: Regex, resultText: String): ToolCard? {
        val m = regex.matchEntire(firstLineOf(resultText) ?: return null) ?: return null
        val path = m.groupValues[1]
        return ToolCard.FileCard(
            name = path.substringAfterLast('/'),
            path = path,
            sizeBytes = null, // the reported byte count is the replacement payload, not the file size
            isNew = false,
        )
    }

    // ────────────────────────────────────────────────────────────────────────
    // shared small helpers (pure)
    // ────────────────────────────────────────────────────────────────────────

    private fun clip(s: String, max: Int): String =
        if (s.length <= max) s else s.take(max - 1).trimEnd() + "…"

    /** Trimmed first line — single-statement formats match this, so a
     *  trailing `\nwas: <old>` (F batch) can never break the match. */
    private fun firstLineOf(text: String): String? =
        text.lineSequence().firstOrNull()?.trim()?.takeIf { it.isNotEmpty() }

    private fun domainOf(url: String): String? = runCatching {
        java.net.URI(url).host?.removePrefix("www.")
    }.getOrNull()

    private fun mimeForExtension(ext: String): String = when (ext.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        "png" -> "image/png"
        else -> "application/octet-stream"
    }

    private fun shortId(id: String): String = if (id.length <= 8) id else id.take(8)

    private fun parseOnOff(raw: String): Boolean? = when (raw.lowercase()) {
        "on", "true", "enabled", "1" -> true
        "off", "false", "disabled", "0" -> false
        else -> null
    }

    private fun formatDuration(seconds: Int): String = when {
        seconds >= 3600 -> String.format("%dh %02dm", seconds / 3600, (seconds % 3600) / 60)
        seconds >= 60 -> String.format("%dm %02ds", seconds / 60, seconds % 60)
        else -> "${seconds}s"
    }

    private fun formatEpoch(ms: Long): String =
        java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(java.time.ZoneId.systemDefault())
            .format(java.time.Instant.ofEpochMilli(ms))

    // One regex per emitted format, named so tests read like the tool sources.
    private val KEY_VALUE_LINE = Regex("([a-z_]+): (.+)")
    private val SETTING_GET_SINGLE = Regex("([a-z]+)\\.([A-Za-z0-9_.]+) = (.+)")
    private val SETTING_GET_NULL = Regex("([a-z]+)\\.([A-Za-z0-9_.]+) is not set \\(null\\)\\.")
    private val SETTINGS_LIST_LINE = Regex("([A-Za-z0-9_.]+)=(.*)")
    private val VOLUME_SET = Regex("Volume (\\S+) set to (\\d+)% \\(\\d+/\\d+\\)\\.")
    private val BRIGHTNESS_SET = Regex("Brightness set to (\\d+)/255\\.")
    private val DND_SET = Regex("Do Not Disturb set to '?([a-z]+)'?.*")
    private val DND_MODES = listOf("off", "priority", "alarms", "all")
    private val SOUND_MODE_SET = Regex("Ringer mode set to (\\w+)\\.")
    private val SWITCHED =
        Regex("(Wi-Fi|Bluetooth|Mobile data) switched (on|off) via Shizuku shell\\.( Output: .*)?")
    private val ALARM_SET = Regex("Sent an alarm at (\\d{2}:\\d{2}) to the clock app\\..*")
    private val TIMER_SET = Regex("Sent a (\\d+)s timer to the clock app\\..*")
    private val CALENDAR_ADD =
        Regex("Event \"(.+)\" created (\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2})–(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}) in calendar \".+\".*")
    private val CONTACT_LINE = Regex("(.+): (.+)")
    private val CALL_LOG = Regex(
        "(\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}) (in|out|missed|other) (.+) (\\S+) (\\d+)s",
    )
    private val MORE_MARKER = Regex("…\\((\\d+) more.*\\)")
    private val APP_LINE = Regex("(.+) \\((.+)\\)")
    private val LOCATION = Regex(
        "lat=(-?\\d+\\.\\d+) lng=(-?\\d+\\.\\d+) accuracy=(\\d+)m age=(\\d+)s provider=(\\S+).*",
    )
    private val SEARCH_HEADER = Regex("web_search \\((\\w+)\\) results for \".*\" \\((\\d+)\\):")
    private val SEARCH_ITEM_PREFIX = Regex("\\[(\\d+)] (.+)")
    private val FILE_WRITE = Regex("Wrote to (\\S+) \\((\\d+) bytes\\)")
    private val EDITED = Regex("Edited (\\S+) \\(\\d+ replacement\\(s\\), \\d+ bytes\\)")
    private val APPLIED_EDITS = Regex("Applied \\d+ edits to (\\S+) \\(\\d+ replacement\\(s\\), \\d+ bytes\\)")

    private const val SMS_DATE_LEN = 16
    private val DATE_PREFIX = Regex("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")

    private fun JSONObject.optStringOrNull(key: String): String? =
        if (isNull(key)) null else optString(key, "").takeIf { it.isNotEmpty() }
}
