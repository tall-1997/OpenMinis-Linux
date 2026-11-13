package com.openminis.app.ui.chat

import android.net.Uri
import android.util.Log
import com.openminis.app.R
import com.openminis.app.data.db.MessageEntity
import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.NonFiniteJson
import org.json.JSONObject

// [T-android-split-chat-mapping] DB row → UI/LLM mapping extracted verbatim
// from ChatViewModel.kt (mechanical split, zero behavior change). Member
// extensions became ChatViewModel receivers with an explicit entity/list
// parameter; call sites updated mechanically (`rows.toChatMessages()` →
// `toChatMessages(rows)`). The cancelled-marker constants moved with their
// only consumer ([toChatMessages]); nothing outside ChatViewModel.kt
// referenced them.

// Sentinel prefix for user-cancelled tool output: ChatViewModel.CANCELLED_MARKER
// (companion, referenced by ChatViewModelStopRunExt and the UI). The pre-T13
// legacy marker below is private here — only toChatMessages still recognises it.

/**
 * Pre-T13 cancelled marker. Kept only so [toLLMMessage]'s
 * tool-block restore can still recognise rows persisted by
 * earlier app versions and surface them as CANCELLED instead
 * of FAILED. Never emitted by this version.
 */
private const val LEGACY_CANCELLED_MARKER = "[cancelled by user]"

/**
 * Matches a `<system-reminder>...</system-reminder>` block, including any
 * surrounding whitespace / newlines, so a part that is *only* a reminder
 * collapses to empty text instead of leaving a blank gap. DOTALL so `.`
 * spans newlines (reminders run multi-line in the cancel/resume paths).
 *
 * Only applied at the UI-render transform — agentHistory + DB rows keep
 * the raw text so the LLM continues to see the reminder on subsequent
 * turns (matches iOS, where system-reminder text is appended to
 * agentHistory/AgentMessage parts but never to the chat-list ChatMessage).
 */
private val systemReminderRegex =
    Regex("\\s*<system-reminder>.*?</system-reminder>\\s*", RegexOption.DOT_MATCHES_ALL)

internal fun stripSystemReminders(text: String): String {
    var out = text
    if (out.contains("<system-reminder>")) out = systemReminderRegex.replace(out, "")
    // Legacy #339 rows carry the no-reply head OUTSIDE the reminder block
    // (the framing itself no longer produces it); strip it for display so the
    // bookkeeping line never renders as user speech. agentHistory + DB keep
    // the raw text — this is the UI-render transform only.
    if (out.contains(HOST_NOTE_NO_REPLY_HEAD)) out = out.replace(HOST_NOTE_NO_REPLY_HEAD, "")
    return out
}

/**
 * [T-android-retry-attachment-loss] Remove the `<user-attached-files>` XML
 * inventory from a persisted text part for DISPLAY only. The XML is now
 * persisted (iOS parity) so the model keeps the file paths across retry /
 * reload, but it must never render in the user bubble — the file chips are
 * rebuilt from the mediaRef parts instead. Mirrors the index-based strip
 * already used by editMessage / the title-fallback path.
 */
internal fun stripAttachedFilesXml(text: String): String {
    val startIdx = text.indexOf("<user-attached-files>")
    if (startIdx < 0) return text
    val endTag = "</user-attached-files>"
    val endIdx = text.indexOf(endTag, startIdx)
    return if (endIdx >= 0) {
        text.substring(0, startIdx) + text.substring(endIdx + endTag.length)
    } else {
        text.substring(0, startIdx)
    }
}

private data class ToolResultData(val output: String, val success: Boolean)

private fun buildToolResultMap(entities: List<MessageEntity>): Map<String, ToolResultData> {
    val result = mutableMapOf<String, ToolResultData>()
    for (entity in entities) {
        if (entity.role != "user") continue
        runCatching {
            val array = org.json.JSONArray(entity.partsJson)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                if (obj.optString("type") != "toolResult") continue
                val value = obj.getJSONObject("value")
                val toolUseId = value.optString("toolUseId", "")
                if (toolUseId.isNotEmpty()) {
                    result[toolUseId] = ToolResultData(
                        output = value.optString("output", ""),
                        success = value.optBoolean("success", true),
                    )
                }
            }
        }
    }
    return result
}

/**
 * Convert a flat list of MessageEntity into ChatMessages, merging toolResult
 * data from user-role messages back into their corresponding AssistantBlocks.
 * This mirrors iOS's toChatMessage() which reads both toolUse and toolResult parts.
 */
internal fun ChatViewModel.toChatMessages(
    entities: List<MessageEntity>,
    toolResultContext: List<MessageEntity> = emptyList(),
): List<ChatMessage> {
    // Build the result index from this page plus a small boundary context,
    // but convert UI rows from this page only. A toolUse and its toolResult
    // commonly land on opposite sides of a page boundary; decoding the
    // context without emitting it prevents the middle row from being
    // filtered or merged away.
    val toolResultMap = buildToolResultMap(entities + toolResultContext)

    // Second pass: convert messages, merging tool results into blocks
    // Filter out user messages that only contain toolResult parts (no visible text)
    return entities.mapNotNull { entity ->
        var text = ""
        val blocks = mutableListOf<AssistantBlock>()
        // T128: media attachments persisted under user messages as `mediaRef`
        // parts. Restored to file:// URIs (stable across app restarts) and
        // their original filenames so UserAttachmentList renders the same
        // tiles after a session reload.
        val restoredImageUris = mutableListOf<Uri>()
        // [T-android-paste-mediaref] Names are collected PER COLUMN and
        // concatenated image-first at the end, instead of appended to one
        // list in part order.
        //
        // UserAttachmentList splits with `allFileNames.drop(imageUris.size)`,
        // so the names list must be images-then-files regardless of the
        // order the parts appear in. That used to be automatic: attachment
        // mediaRefs were always written images-first. A pasted block breaks
        // it — it lives in the BODY, so it can precede an image part, and a
        // single in-order list would then start with a file name and shift
        // every image caption onto the wrong tile.
        val restoredImageNames = mutableListOf<String>()
        val restoredFileNames = mutableListOf<String>()
        // T150: file:// URIs of restored non-image attachments, in the
        // same order as the non-image suffix of the joined name list.
        // Powers the user-bubble file chip → FilePreviewScreen tap after
        // a session reload.
        val restoredAttachmentUris = mutableListOf<Uri>()
        var speakerName: String? = null
        var speakerVendor: String? = null

        if (entity.role == "assistant" && !entity.reasoningContent.isNullOrEmpty()) {
            blocks.add(AssistantBlock(
                id = "thinking_restored_${entity.id}",
                kind = "thinking",
                content = entity.reasoningContent,
                toolTitle = "Thinking",
                toolStatus = ToolBlockStatus.SUCCESS,
            ))
        }

        try {
            val array = org.json.JSONArray(entity.partsJson)
            var textBlockCounter = 0
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                when (obj.optString("type")) {
                    com.openminis.app.tools.GroupChat.SPEAKER_PART -> {
                        speakerName = obj.optString("value", "").takeIf { it.isNotBlank() }
                        speakerVendor = obj.optString("vendor", "").takeIf { it.isNotBlank() }
                            ?: com.openminis.app.tools.GroupChat.vendorKey(null, speakerName)
                                .takeIf { it != com.openminis.app.tools.GroupChat.VENDOR_UNKNOWN }
                    }
                    "text" -> {
                        val raw = obj.optString("value", "")
                        // Strip <system-reminder>...</system-reminder> blocks
                        // here only — agentHistory in memory and the DB row
                        // both keep the raw text, so the LLM still sees the
                        // reminder on subsequent turns. UI just hides it.
                        // If a part was *only* a reminder, the cleaned
                        // string is empty and we skip it so we don't render
                        // a phantom blank text block.
                        // [T-android-retry-attachment-loss] Also strip the
                        // now-persisted <user-attached-files> XML so it
                        // doesn't render in the user bubble (file chips come
                        // from mediaRef parts). The DB row + agentHistory
                        // keep the raw XML so the model still sees paths.
                        val t = stripAttachedFilesXml(stripSystemReminders(raw)).let {
                            // [T-universal-think-tag-history] Last-resort UI
                            // strip: a reasoning tag that leaked into the DB
                            // (unterminated provider stream, tool-loop splice,
                            // vendor spelling the stream parser pre-dated) must
                            // never render in the bubble. DB + agentHistory keep
                            // the raw text; this only cleans the painted copy.
                            // [T-display-strip-unterminated] display mode: a
                            // bare tag mention must not lose its tail.
                            if (entity.role == "assistant") {
                                com.openminis.app.agent.MessageTransformerChain.applyForDisplay(it)
                            } else it
                        }.let {
                            if (it != raw) it.trim() else it
                        }
                        if (t.isEmpty()) continue
                        text += t
                        // For assistant messages, also push the text as a block so
                        // the renderer can preserve the original text↔tool ordering.
                        // For user messages we keep using the `text` field only.
                        if (entity.role == "assistant") {
                            blocks.add(AssistantBlock(
                                id = "text_restored_${entity.id}_${textBlockCounter++}",
                                kind = "text",
                                content = t,
                            ))
                        }
                    }
                    "toolUse" -> {
                        val value = obj.getJSONObject("value")
                        val toolId = value.optString("toolUseId", "")
                        if (toolId.startsWith("thinking_")) continue
                        val toolInput = value.optString("input", "")
                        // Merge tool result output (iOS: block.content = tr.output)
                        val result = toolResultMap[toolId]
                        val pageURL = value.optString("pageURL", "").ifEmpty { null }
                        val imgPath = value.optString("imageFilePath", "").ifEmpty { null }
                        blocks.add(AssistantBlock(
                            id = toolId,
                            kind = "tool_use",
                            toolName = value.optString("name", ""),
                            toolTitle = value.optString("description", ""),
                            toolArgs = toolInput,
                            content = result?.output?.lines()?.takeLast(80)?.joinToString("\n") ?: "",
                                toolStatus = when {
                                    result == null -> ToolBlockStatus.SUCCESS
                                    !result.success && (
                                        result.output.startsWith(ChatViewModel.CANCELLED_MARKER) ||
                                            result.output.startsWith(LEGACY_CANCELLED_MARKER)
                                    ) -> ToolBlockStatus.CANCELLED
                                result.success -> ToolBlockStatus.SUCCESS
                                else -> ToolBlockStatus.FAILED
                            },
                            browserURL = pageURL,
                            imageFilePath = imgPath,
                            // [P-process-card] Restore the persisted
                            // per-block timing (written by
                            // buildAssistantPartsJson since the process
                            // cards landed). Rows written before the
                            // field existed parse as 0 — same as before.
                            durationMs = value.optLong("durationMs", 0L),
                            startTimeMs = value.optLong("startTimeMs", 0L),
                            // [T-android-gemini3-thoughtsig / #179] Restore the
                            // persisted signature onto the rebuilt block.
                            thoughtSignature = value.optString("thoughtSignature", "").ifEmpty { null },
                        ))
                    }
                    "mediaRef" -> {
                        if (entity.role != "user") continue
                        val value = obj.optJSONObject("value") ?: continue
                        val rel = value.optString("relativePath", "")
                        if (rel.isEmpty()) continue
                        val file = java.io.File(mediaStore.mediaBaseDir, rel)
                        if (!file.exists()) continue
                        val mime = value.optString("mimeType", "")
                        val name = value.optString("originalFileName", "").ifEmpty { file.name }
                        // T150: branch on mime so non-image mediaRefs land
                        // in the file-chip column instead of polluting
                        // imageUris (which feeds the image gallery).
                        //
                        // [T-android-paste-mediaref] A pasted block needs no
                        // case of its own: it is text/plain, so it takes the
                        // non-image branch and renders as the same file card
                        // as an attached document — which is exactly the
                        // requested behaviour. Note this ALSO relies on
                        // parts being written images-first; a pasted ref
                        // sits in the body (possibly before an image part),
                        // so the names/uris pairing here is positional per
                        // COLUMN, not per part index, and stays consistent
                        // because each column is appended in part order.
                        if (mime.startsWith("image/")) {
                            restoredImageUris.add(Uri.fromFile(file))
                            restoredImageNames.add(name)
                        } else {
                            restoredAttachmentUris.add(Uri.fromFile(file))
                            restoredFileNames.add(name)
                        }
                    }
                    // toolResult in user messages handled in first pass above
                }
            }
        } catch (e: Exception) {
            // T-PARTS-FALLBACK: previously this catch dumped the entire
            // partsJson into `text` as a degraded fallback. That meant
            // any malformed (or unexpectedly large) row rendered its
            // raw JSON — including any inlined base64 — as a plain
            // user/assistant bubble, which then locked up Compose's
            // StaticLayout for tens of seconds (see HangDetector report
            // for session e84882d7 / 820 KB partsJson). Replace with a
            // short, fixed-size placeholder so the row still appears
            // (so the user can delete or scroll past it) but no longer
            // pulls megabytes through the layout pass.
            Log.w(
                ChatViewModel.TAG,
                "toChatMessages: failed to parse partsJson for id=${entity.id} " +
                    "len=${entity.partsJson.length} role=${entity.role}: ${e.javaClass.simpleName}: ${e.message}",
            )
            text = "(message could not be parsed: ${e.javaClass.simpleName}, " +
                "${entity.partsJson.length} bytes)"
        }

        // Skip user messages with no visible content (toolResult-only internal messages,
        // or messages that were entirely a system-reminder). A user message that is
        // *only* an image attachment (no caption) still has visible content and must
        // not be skipped — restoredImageUris carries it.
        if (entity.role == "user" && text.isBlank() && restoredImageUris.isEmpty()) return@mapNotNull null
        // Skip assistant messages that became empty after stripping system-reminders
        // and have no tool / thinking blocks to fall back on — would otherwise
        // render as a phantom blank assistant bubble.
        if (entity.role == "assistant" && text.isBlank() && blocks.isEmpty()) return@mapNotNull null
        ChatMessage(
            id = entity.id,
            role = entity.role,
            content = text,
            imageUris = restoredImageUris,
            // Image names first, then file names — the invariant
            // UserAttachmentList's `drop(imageUris.size)` relies on.
            attachmentNames = restoredImageNames + restoredFileNames,
            attachmentUris = restoredAttachmentUris,
            toolBlocks = blocks,
            sourceDbIds = listOf(entity.id),
            speakerName = speakerName,
            speakerVendor = speakerVendor,
            // [T-error-persist-android] Restore the persisted terminal error
            // so the inline error banner + Retry button survive a reload.
            // Coalesce a blank value to null: the UI gate is `error?.let`, so
            // a non-null "" would render an empty banner. Defends against any
            // legacy/other-writer "" row.
            error = entity.errorInfo?.let { status ->
                when (status) {
                    com.openminis.app.data.db.PersistedMessageStatus.INTERRUPTED_INFO -> context.getString(R.string.chat_response_interrupted)
                    else -> status
                }
            }?.takeIf { it.isNotBlank() },
        )
    }.let { messages ->
        // Merge consecutive assistant messages into one:
        // agent loop persists each turn separately, but UI should show them as a single message.
        val merged = mutableListOf<ChatMessage>()
        for (msg in messages) {
            val prev = merged.lastOrNull()
            if (msg.role == "assistant" && prev?.role == "assistant" &&
                com.openminis.app.tools.GroupChat.shouldMergeAssistantTurns(prev.speakerName, msg.speakerName)
            ) {
                // Merge: combine tool blocks, append text, keep the last id.
                // Deduplicate by block.id — the agent loop may persist the same tool
                // use in multiple consecutive turns (as it carries tool state across),
                // and duplicated ids would crash LazyColumn's key uniqueness check.
                // Keep the LAST occurrence so the most recent status (e.g. SUCCESS with
                // output) wins over an earlier STREAMING placeholder.
                val seen = mutableSetOf<String>()
                val combinedBlocks = (prev.toolBlocks + msg.toolBlocks)
                    .asReversed()
                    .filter { seen.add(it.id) }
                    .asReversed()
                val combinedText = when {
                    prev.content.isBlank() -> msg.content
                    msg.content.isBlank() -> prev.content
                    else -> prev.content + "\n\n" + msg.content
                }
                merged[merged.lastIndex] = prev.copy(
                    id = msg.id,
                    content = combinedText,
                    toolBlocks = combinedBlocks,
                    // T126-marker: keep every source dbId so Phase 2.5
                    // can resolve markers that point at any of the
                    // pre-merge rows (lastCompactedMessageId is often
                    // an assistant row that gets folded into a later
                    // assistant turn).
                    sourceDbIds = prev.sourceDbIds + msg.sourceDbIds,
                    // [T-error-persist-android] The error sticker is written
                    // to the LAST assistant row of the turn, so the later row
                    // (`msg`) wins; fall back to `prev` if only it carried one.
                    error = msg.error ?: prev.error,
                )
            } else {
                merged.add(msg)
            }
        }
        merged
    }
}

/**
 * Rebuild model context from the newest persisted rows only. The database
 * remains the complete transcript; no operation may parse the whole session
 * merely to continue, retry, edit, or delete it.
 */
internal fun ChatViewModel.toLLMMessage(entity: MessageEntity): LLMMessage {
    val r = if (entity.role == "user") LLMMessage.Role.USER else LLMMessage.Role.ASSISTANT
    val contentParts = mutableListOf<AgentContentPart>()
    val imageParts = mutableListOf<LLMMessage.ImagePart>()
    var textContent = ""

    try {
        val array = org.json.JSONArray(entity.partsJson)
        for (i in 0 until array.length()) {
            val obj = array.getJSONObject(i)
            when (obj.optString("type")) {
                "text" -> {
                    val value = obj.optString("value", "")
                    // [T-android-retry-attachment-loss] The persisted
                    // <user-attached-files> XML must reach the model via a
                    // contentPart (provider prefers contentParts), but it
                    // must NOT fold into `content`. On a FRESH send the
                    // `content` field is the clean caption (`trimmed`) and
                    // the XML lives only in contentParts; keep restored
                    // messages byte-identical so `.content` consumers
                    // (summary, title fallback, edit) see the same string
                    // as a fresh turn and don't get the XML twice.
                    if (value.contains("<user-attached-files>")) {
                        contentParts.add(AgentContentPart.Text(value))
                    } else {
                        textContent += value
                        contentParts.add(AgentContentPart.Text(value))
                    }
                }
                "toolUse" -> {
                    val v = obj.getJSONObject("value")
                    val inputStr = v.optString("input", "{}")
                    val inputJson = NonFiniteJson.parseOrNull(inputStr) ?: JSONObject()
                    contentParts.add(AgentContentPart.ToolUse(
                        id = v.optString("toolUseId", ""),
                        name = v.optString("name", ""),
                        input = inputJson,
                        // [T-android-gemini3-thoughtsig / #179] Restore the
                        // persisted Gemini 3.x signature so a reloaded session
                        // replays it (else the next gemini-3 turn 400s).
                        thoughtSignature = v.optString("thoughtSignature", "").ifEmpty { null },
                    ))
                }
                "toolResult" -> {
                    val v = obj.getJSONObject("value")
                    contentParts.add(AgentContentPart.ToolResult(
                        id = v.optString("toolUseId", ""),
                        name = v.optString("name", ""),
                        content = v.optString("output", ""),
                        isError = !v.optBoolean("success", true),
                    ))
                }
                "mediaRef" -> {
                    // T128: load persisted user-message images so the model
                    // sees them on subsequent turns after a session reload.
                    // T150: skip non-image mediaRefs here — their bytes
                    // shouldn't be re-inlined into the LLM payload (parity
                    // with the on-send path, which only inlines images).
                    // The original turn's <user-attached-files> XML stayed
                    // in the persisted text part, and the file is still
                    // on disk under attachments/uploads, so the agent can
                    // re-fetch via shell tools.
                    val v = obj.optJSONObject("value") ?: continue
                    val rel = v.optString("relativePath", "")
                    if (rel.isEmpty()) continue
                    val mime = v.optString("mimeType", "image/jpeg")
                    // [T-android-paste-mediaref] A pasted block is stored as
                    // a mediaRef but is CONTENT, not an attachment: read it
                    // back off disk and inline it here, restoring exactly
                    // the text the original send put in the prompt.
                    //
                    // This branch is what makes every history-replay path
                    // correct at once — session reload, retry, rerun,
                    // edit-resend and compaction all rebuild through this
                    // one converter, so none of them needs its own handling.
                    //
                    // An unreadable file degrades to skipping the part
                    // rather than aborting the message: losing one pasted
                    // block is recoverable, failing to build the request is
                    // not.
                    if (PastedMedia.isPastedRef(mime, v.optString("originalFileName", null))) {
                        val pf = java.io.File(mediaStore.mediaBaseDir, rel)
                        val body = try {
                            if (pf.exists()) pf.readText(Charsets.UTF_8) else null
                        } catch (e: Exception) {
                            AppLogger.warning(ChatViewModel.TAG, "[Paste] restore failed for $rel: ${e.message}")
                            null
                        }
                        // [T-android-paste-missing-file] An unreadable
                        // pasted file degrades to an explicit marker, never
                        // to silence.
                        //
                        // The file can genuinely disappear — the user clears
                        // app storage, a sync pass prunes it as an orphan,
                        // the disk fills mid-write. Dropping the part on the
                        // floor would leave the model reading a sentence
                        // with a hole in the middle and no way to know
                        // content was ever there, so it would answer
                        // confidently about text it never saw. Saying so
                        // lets it ask, and leaves a searchable trace when a
                        // user reports a strange reply.
                        val resolved = body ?: PastedMedia.MISSING_PLACEHOLDER
                        if (body == null) {
                            AppLogger.warning(
                                ChatViewModel.TAG,
                                "[Paste] missing pasted file for $rel — substituting placeholder",
                            )
                        }
                        textContent += resolved
                        contentParts.add(AgentContentPart.Text(resolved))
                        continue
                    }
                    if (!mime.startsWith("image/")) continue
                    val file = java.io.File(mediaStore.mediaBaseDir, rel)
                    if (!file.exists()) continue
                    val bytes = try { file.readBytes() } catch (_: Exception) { continue }
                    val restoredPath = v.optString("linuxPath", "").ifEmpty { null }
                    // [T-android-vision-group / GH#182] Seed the read_image
                    // hint on restored images too, so a non-vision main model
                    // with a Vision Group configured gets steered to read_image
                    // on subsequent turns after a session reload (not the bare
                    // "can't see it" literal).
                    val restoredPlaceholder = visionPlaceholderFor(restoredPath)
                    imageParts.add(LLMMessage.ImagePart(bytes, mime, linuxPath = restoredPath, noVisionPlaceholder = restoredPlaceholder))
                    contentParts.add(AgentContentPart.ImageData(bytes, mime, linuxPath = restoredPath, noVisionPlaceholder = restoredPlaceholder))
                }
            }
        }
    } catch (_: Exception) {
        textContent = entity.partsJson
        contentParts.add(AgentContentPart.Text(entity.partsJson))
    }

    return LLMMessage(
        role = r,
        content = textContent,
        imageParts = imageParts,
        contentParts = contentParts,
        dbMessageId = entity.id,
        reasoningContent = entity.reasoningContent,
    )
}

/**
 * Appended rows stay. Dropping the older side here recreated the missing
 * middle: the next page would start after a hole the user never asked to
 * close. Model context is trimmed by [trimAgentHistory], not this list.
 */
internal fun ChatViewModel.trimLoadedWindow(messages: List<ChatMessage>): List<ChatMessage> = messages
