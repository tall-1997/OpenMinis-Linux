package com.openminis.app.data.repository

/**
 * Text-preview pipeline for the session list, the re-run message picker and
 * the restore path — everything that turns a stored `parts_json` blob into a
 * short human-readable line.
 *
 * Extracted from ChatRepository's companion object (T-android-preview-
 * reminder-leak follow-up): the repository file was at its architecture
 * ceiling and the preview pipeline grew past it. The functions here are pure
 * — no DAO, no entities — so they live on their own.
 *
 * Callers outside this package go through the two one-line delegates kept on
 * [ChatRepository] ([ChatRepository.extractTextPreview] /
 * [ChatRepository.stripSystemReminders]) so no call site had to change.
 */
internal object PreviewText {

    // Session-list preview only needs ~100 chars. Regex.replace on a
    // 500 KB parts_json body was a leftover ICU Matcher.reset path.
    private const val PREVIEW_CLEAN_CHARS = 400

    private fun cleanPreview(raw: String): String {
        // [T-android-preview-reminder-leak] Strip reminders on the FULL
        // text before capping: a reminder longer than PREVIEW_CLEAN_CHARS
        // lost its closing tag under the old cap-first order, the strip
        // regex missed, and raw <system-reminder> markup leaked into the
        // session-list and picker previews. The contains() guard keeps the
        // large-body property the cap was added for — bodies without the
        // opening tag never touch the regex.
        val stripped = if (raw.contains("<system-reminder>")) stripSystemReminders(raw) else raw
        val src = if (stripped.length <= PREVIEW_CLEAN_CHARS) stripped else stripped.take(PREVIEW_CLEAN_CHARS)
        return src
                .replace(Regex("[\r\n]+"), " ")      // newlines → space
                .replace(Regex("#{1,6}\\s"), "")      // headings: ## Title → Title
                // [T-android-preview-underscore-eaten] `_{1,3}` stripped EVERY
                // underscore, so "Count_1_to_30" previewed as "Count1to30".
                // Only remove PAIRED emphasis markers wrapping text
                // (___bold___ / *italic*), never lone underscores inside words.
                .replace(Regex("\\*{1,3}(?=\\S)(.*?\\S)\\*{1,3}"), "$1") // paired * emphasis
                .replace(Regex("_{2,3}(?=\\S)(.*?\\S)_{2,3}"), "$1")     // paired __ emphasis
                .replace(Regex("~~"), "")              // strikethrough
                .replace(Regex("`{1,3}"), "")          // inline/fenced code markers
                .replace(Regex("^\\s*[-*+]\\s", RegexOption.MULTILINE), "") // list bullets
                .replace(Regex("^\\s*\\d+\\.\\s", RegexOption.MULTILINE), "") // ordered list
                .replace(Regex("^>\\s?", RegexOption.MULTILINE), "")       // blockquote
                .replace(Regex("\\[([^]]+)]\\([^)]+\\)"), "$1") // [text](url) → text
                .replace(Regex("!\\[([^]]*)]\\([^)]+\\)"), "$1") // ![alt](url) → alt
                .replace(Regex("\\s{2,}"), " ")        // collapse whitespace
                .trim()
                .take(100)
    }

    /**
     * Build a short preview string for a `toolUse` value block. Used by the
     * session list when an assistant turn is mid-tool-call and has no text
     * part yet. Strategy mirrors iOS ChatStore.summarizeToolUse (T-ios-
     * session-last-message-tool-call):
     *   1. Prefer model-supplied `tool_title` (carried in the on-disk shape
     *      as `value.description`, or inside the embedded `input` JSON).
     *   2. Else pick the most meaningful arg per known tool family.
     *   3. Else fall back to `🔧 <toolName>`.
     * Output capped at 100 chars to match cleanPreview's text ceiling.
     */
    private fun summarizeToolUse(value: org.json.JSONObject): String {
        val toolName = value.optString("name", "")
        // `description` is where ChatViewModel persists the captured
        // tool_title (see writeAssistantParts / writeAssistantPartsForLive
        // in ChatViewModel.kt — both pass block.toolTitle into "description").
        val description = value.optString("description", "").trim()

        // `input` is stored as an escaped JSON STRING, not a nested object
        // (see ChatViewModel.kt:6491 / :6539). Parse defensively.
        val input: org.json.JSONObject = try {
            val raw = value.opt("input")
            when (raw) {
                is org.json.JSONObject -> raw
                is String -> if (raw.isBlank()) org.json.JSONObject() else org.json.JSONObject(raw)
                else -> org.json.JSONObject()
            }
        } catch (_: Exception) {
            org.json.JSONObject()
        }

        fun str(key: String): String? {
            val v = input.optString(key, "").trim()
            return if (v.isEmpty()) null else v
        }
        fun cap(s: String, n: Int = 100): String =
            if (s.length > n) s.substring(0, n) + "…" else s

        // 1. tool_title — checked both on the outer `description` field and
        //    inside `input` (the model writes it into args; we mirror what
        //    iOS does and accept either location).
        val title = str("tool_title") ?: description.takeIf { it.isNotEmpty() }
        if (title != null) return cap(cleanPreview(title))

        // 2. per-tool key argument
        when (toolName) {
            "shell_execute" -> str("command")?.let { return cap(cleanPreview("$ $it")) }
            "file_read" -> str("path")?.let { return cap(cleanPreview("Reading $it")) }
            "file_write" -> str("path")?.let { return cap(cleanPreview("Writing $it")) }
            "file_edit" -> str("path")?.let { return cap(cleanPreview("Editing $it")) }
            "browser_use" -> {
                val action = str("action") ?: "browse"
                val url = str("url")
                return if (url != null) cap(cleanPreview("$action $url"))
                else cap(cleanPreview("browser_use $action"))
            }
            "memory_write" -> str("content")?.let { return cap(cleanPreview("memory_write: $it")) }
            "memory_get" -> {
                val arr = input.optJSONArray("keywords")
                if (arr != null && arr.length() > 0) {
                    val joined = buildString {
                        for (i in 0 until arr.length()) {
                            if (i > 0) append(", ")
                            append(arr.optString(i))
                        }
                    }
                    if (joined.isNotBlank()) return cap(cleanPreview("memory_get: $joined"))
                }
                str("keywords")?.let { return cap(cleanPreview("memory_get: $it")) }
            }
        }

        // 3. final fallback
        return cap("🔧 ${toolName.ifBlank { "tool" }}")
    }

    // `internal` so the restore path can rebuild a session's preview from the
    // messages it just imported — see BackupImporter [T-android-restore-preview].
    fun extractTextPreview(partsJson: String): String? {
        try {
            val array = org.json.JSONArray(partsJson)
            var hasMedia = false
            // T-android-session-last-message-tool-call: also track the most
            // recent tool_use so a mid-tool-call assistant turn (no text yet)
            // renders as a short tool summary instead of falling through to
            // "No messages yet" in the session list.
            var lastToolUse: org.json.JSONObject? = null
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val type = obj.optString("type")
                if (type == "text") {
                    val text = obj.optString("value", "")
                    if (text.isNotBlank()) {
                        // [T-android-preview-reminder-leak] A user turn
                        // opens with a system-reminder text part; after
                        // cleaning it is blank. Returning that blank hid
                        // the real text part behind it — the session list
                        // showed an empty line and the re-run picker
                        // showed "(empty)". Skip blanks, keep scanning.
                        val cleaned = cleanPreview(text)
                        if (cleaned.isNotBlank()) {
                            return cleaned
                        }
                    }
                } else if (type == "mediaRef") {
                    hasMedia = true
                } else if (type == "toolUse") {
                    val v = obj.optJSONObject("value")
                    if (v != null) lastToolUse = v
                }
            }
            if (hasMedia) return "[Image]"
            if (lastToolUse != null) return summarizeToolUse(lastToolUse)
        } catch (_: Exception) {
            if (partsJson.isNotBlank()) return cleanPreview(partsJson)
        }
        return null
    }

    // <system-reminder>...</system-reminder> blocks are runtime nudges
    // injected into user-role messages by the harness (e.g. task-tracker
    // reminders). They never represent what the user actually typed, so
    // they must not show up in the session-list "last message" preview.
    // DOTALL flag covers multi-line reminder bodies; reluctant
    // quantifier so back-to-back reminders don't merge into one match.
    private val SYSTEM_REMINDER_RE =
        Regex("""<system-reminder>.*?</system-reminder>""", RegexOption.DOT_MATCHES_ALL)

    fun stripSystemReminders(raw: String): String =
        SYSTEM_REMINDER_RE.replace(raw, "").trim()
}
