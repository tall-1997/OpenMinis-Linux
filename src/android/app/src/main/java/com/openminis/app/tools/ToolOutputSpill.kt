package com.openminis.app.tools

import android.content.Context
import com.openminis.app.sandbox.SessionWorkspace
import com.openminis.app.security.ToolAliases
import com.openminis.app.security.tokenizeCommand
import com.openminis.app.ssh.SshWorkspaceBindings
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

/** Spill oversized tool text into the same session workspace that file_read sees. */
object ToolOutputSpill {
    const val LIMIT = 16_000
    const val PREVIEW_BODY_CHARS = 3_000

    // Explicit readback gets a larger page; ordinary previews (even on failure) stay short.
    private const val READBACK_BODY_CHARS = 10_000
    private const val READBACK_HEAD_CHARS = 6_000
    private const val MAX_LABEL_CHARS = 120

    private const val SPILL_DIR = "tool-spill"
    private const val GUEST_PREFIX = "/var/minis/workspace/$SPILL_DIR/"
    private const val MARKER = "[tool-output-spill]"
    private const val LEGACY_OFFLOADED_MARKER = "[tool output offloaded to save context"
    private const val LEGACY_TRIMMED_MARKER = "[tool output trimmed for context"

    fun isOffloadPlaceholder(content: String): Boolean = envelopeStart(content) >= 0

    private fun envelopeStart(content: String): Int {
        if (content.startsWith(MARKER) || content.startsWith(LEGACY_OFFLOADED_MARKER) ||
            content.startsWith(LEGACY_TRIMMED_MARKER)
        ) return 0
        // Older web_fetch results prefixed the shared envelope with the final URL.
        if (content.startsWith("URL: ")) {
            val lineEnd = content.take(MAX_HEADER_CHARS).indexOf('\n')
            if (lineEnd >= 0 && content.startsWith("\n$MARKER", lineEnd + 1)) return lineEnd + 2
        }
        return -1
    }

    fun isSpillGuestPath(guestPath: String?): Boolean = spillName(guestPath?.trim()) != null

    private fun spillName(guestPath: String?): String? {
        if (guestPath == null || !guestPath.startsWith(GUEST_PREFIX)) return null
        return guestPath.substring(GUEST_PREFIX.length).takeIf {
            it != "." && it != ".." && NAME.matches(it)
        }
    }

    fun maybeSpill(
        context: Context,
        sessionId: String,
        toolName: String,
        toolId: String,
        output: String,
        sourcePath: String? = null,
    ): String {
        if (output.length <= LIMIT) return output
        // SSH-bound chats explicitly refuse local file_read. A locally writable
        // file does not make it a recoverable result in that chat.
        if (!canReadInChat(context, sessionId)) return cappedPreview(toolName, output)
        return maybeSpill(context.filesDir, sessionId, toolName, toolId, output, sourcePath)
    }

    /** Dir-based entry uses the same session layout as the Android entry. */
    internal fun maybeSpill(
        filesDir: File,
        sessionId: String,
        toolName: String,
        toolId: String,
        output: String,
        sourcePath: String? = null,
    ): String = when (planFor(output, sessionId.isNotBlank(), sourcePath)) {
        SpillPlan.PASSTHROUGH -> output
        SpillPlan.ALREADY_SPILLED -> {
            val guestPath = sourcePath!!.trim()
            if (hostFile(filesDir, sessionId, guestPath) != null) {
                alreadySpilledPreview(toolName, guestPath, output)
            } else {
                cappedPreview(toolName, output)
            }
        }
        SpillPlan.CAPPED -> cappedPreview(toolName, output)
        SpillPlan.WRITE -> formatPreview(toolName, offloadInSession(filesDir, sessionId, toolId, output), output)
    }

    /** Persist the exact text, returning a path only after a verified write in this session. */
    fun offloadFullOutput(context: Context, sessionId: String, nameKey: String, content: String): String? =
        if (canReadInChat(context, sessionId)) offloadInSession(context.filesDir, sessionId, nameKey, content) else null

    internal fun canReadInChat(context: Context, sessionId: String): Boolean {
        val owner = SessionWorkspace.ownerSessionId(sessionId)
        return SessionWorkspace.isSafeId(owner) && runCatching {
            SshWorkspaceBindings.forContext(context).hostIdFor(owner) == null
        }.getOrDefault(false)
    }

    private fun offloadInSession(filesDir: File, sessionId: String, nameKey: String, content: String): String? {
        val dir = spillDir(filesDir, sessionId, create = true) ?: return null
        val guestPath = offloadFullOutput(dir, nameKey, content) ?: return null
        // Resolution can change for a filed session falling back to its private
        // workspace. A successful write alone does not prove the guest can read it.
        val pathInSession = hostFile(filesDir, sessionId, guestPath)
        return guestPath.takeIf { pathInSession == File(dir, guestPath.substringAfterLast('/')) }
    }

    /** [AtomicFileWrite] reads the entire file back and checks it against [content]. */
    internal fun offloadFullOutput(dir: File, nameKey: String, content: String): String? {
        val file = try {
            uniqueSpillFile(dir, nameKey)
        } catch (_: Exception) {
            return null
        }
        try {
            val temp = File.createTempFile(".minis-spill-", ".minis-tmp", dir)
            try {
                if (AtomicFileWrite.write(file, content, temp = temp) != null && file.isFile && file.canRead()) {
                    return "$GUEST_PREFIX${file.name}"
                }
            } finally {
                runCatching { temp.delete() }
            }
        } catch (_: Exception) {
            // Fall through to removing only this call's reserved file.
        }
        runCatching { file.delete() }
        return null
    }

    private fun spillDir(filesDir: File, sessionId: String, create: Boolean): File? {
        if (!SessionWorkspace.isSafeId(SessionWorkspace.ownerSessionId(sessionId))) return null
        return try {
            val workspace = SessionWorkspace.hostDir(filesDir, sessionId, "workspace")
            val dir = File(workspace, SPILL_DIR)
            // Do not publish through a spill directory redirected out of its workspace.
            if (dir.canonicalFile != File(workspace.canonicalFile, SPILL_DIR) ||
                !SessionWorkspace.staysInside(filesDir, dir) ||
                !SessionWorkspace.acceptsResolved(filesDir, sessionId, dir)
            ) return null
            dir.takeIf { it.isDirectory || (create && (it.mkdirs() || it.isDirectory)) }
        } catch (_: Exception) {
            null
        }
    }

    internal fun writeSpill(dir: File, toolName: String, toolId: String, output: String): String =
        formatPreview(toolName, offloadFullOutput(dir, toolId, output), output)

    /** Reserve a unique file without an exists-then-write race. */
    internal fun uniqueSpillFile(dir: File, toolId: String): File {
        val safeId = safeId(toolId)
        if (toolId.isNotBlank()) {
            val direct = File(dir, "$safeId.txt")
            if (direct.createNewFile()) return direct
        }
        repeat(8) {
            val suffix = UUID.randomUUID().toString().take(8)
            val candidate = File(dir, "$safeId-$suffix.txt")
            if (candidate.createNewFile()) return candidate
        }
        throw IOException("Could not reserve a spill file")
    }

    private fun safeId(id: String): String =
        SAFE_ID_RE.replace(id, "_").trim('_').take(200).ifBlank { "tool" }

    /** Decide on the raw result, before rendering a preview. */
    internal fun planFor(output: String, hasSession: Boolean, sourcePath: String?): SpillPlan {
        if (output.length <= LIMIT) return SpillPlan.PASSTHROUGH
        // Historical envelopes still parse on read. Newly rendered previews fit
        // under LIMIT, so a marker prefix alone must not bypass the output cap.
        if (hasSession && isSpillGuestPath(sourcePath)) return SpillPlan.ALREADY_SPILLED
        return if (hasSession) SpillPlan.WRITE else SpillPlan.CAPPED
    }

    /** Resolve a readback source without changing tool dispatch or permissions. */
    fun sourcePathForTool(rawToolName: String, argsJson: String): String? {
        val canonical = ToolAliases.canonical(rawToolName)
        if (canonical != "file_read" && canonical !in SHELL_TOOLS) return null
        val args = runCatching { JSONObject(argsJson) }.getOrNull() ?: return null
        return if (canonical == "file_read") {
            normalizeGuestPath(args.optString("path", ""))
        } else {
            commandSpillReadPath(args.optString("command", ""))
        }
    }

    internal fun normalizeGuestPath(path: String): String? {
        val raw = path.trim().removePrefix("./")
        val guest = if (raw.startsWith("$SPILL_DIR/")) "/var/minis/workspace/$raw" else raw
        return guest.takeIf { isSpillGuestPath(it) }
    }

    /** Read one literal spill operand, optionally followed by an stdin-only range filter. */
    internal fun commandSpillReadPath(command: String): String? {
        val trimmed = command.trim()
        if (!trimmed.contains("$SPILL_DIR/") || trimmed.any { it in NON_RANGE_SYNTAX }) return null
        val stages = trimmed.split('|', limit = 2)
        val (verb, args) = readCommand(stages.first()) ?: return null
        if (verb !in SPILL_READ_VERBS) return null
        val path = args.lastOrNull()?.let(::normalizeGuestPath) ?: return null
        val options = args.dropLast(1)
        if (verb == "cat" && !options.all { it == "--" || CAT_OPTION.matches(it) }) return null
        if (verb in RANGE_READERS && !rangeOptionsOnly(options)) return null
        if (options.any { normalizeGuestPath(it) != null }) return null
        if (stages.size > 1) {
            val (filter, filterArgs) = readCommand(stages[1]) ?: return null
            if (verb != "cat" && verb !in RANGE_READERS) return null
            if (filter !in RANGE_READERS || !rangeOptionsOnly(filterArgs)) return null
        }
        return path
    }

    private fun readCommand(stage: String): Pair<String, List<String>>? {
        val words = tokenizeCommand(stage)
        val first = words.firstOrNull()?.substringAfterLast('/') ?: return null
        val verbIndex = if (first == "busybox") 1 else 0
        val verb = words.getOrNull(verbIndex)?.substringAfterLast('/') ?: return null
        return verb to words.subList(verbIndex + 1, words.size)
    }

    private fun rangeOptionsOnly(args: List<String>): Boolean {
        var i = 0
        while (i < args.size) {
            val arg = args[i++]
            when {
                arg == "--" -> return i == args.size
                arg in RANGE_VALUE_OPTIONS -> if (!RANGE_AMOUNT.matches(args.getOrNull(i++) ?: return false)) return false
                !RANGE_OPTION.matches(arg) -> return false
            }
        }
        return true
    }

    private val SHELL_TOOLS = setOf("shell_execute", "env_exec", "su_exec")
    private val LOG_TOOLS = SHELL_TOOLS + setOf("execute_code", "execute_code_js", "ssh_exec", "agent_terminal", "sub_agent_report")
    private val RANGE_READERS = setOf("head", "tail")
    private val SPILL_READ_VERBS = RANGE_READERS + setOf("cat", "sed", "awk", "grep", "less", "more", "nl", "wc", "od", "xxd", "strings")
    private val RANGE_VALUE_OPTIONS = setOf("-n", "-c", "--lines", "--bytes")
    private val RANGE_AMOUNT = Regex("[+-]?\\d+[A-Za-z]*")
    private val RANGE_OPTION = Regex("(?:--[a-z-]+(?:=[+-]?\\d+[A-Za-z]*)?|-[A-Za-z0-9][A-Za-z0-9+-]*)")
    private val CAT_OPTION = Regex("--?[A-Za-z][A-Za-z-]*")
    private const val NON_RANGE_SYNTAX = ";&<>\r\n\$`#"
    private const val FILENAME = "[A-Za-z0-9._-]{1,255}"
    private val SAFE_ID_RE = Regex("[^A-Za-z0-9._-]")
    private val NAME = Regex(FILENAME)
    private val GUEST_PATH = Regex("""$GUEST_PREFIX$FILENAME(?=$|[\s"'\])`,;])""")

    fun cappedPreview(toolName: String, output: String): String = formatPreview(toolName, guestPath = null, output)

    /** [guestPath] must come from a verified write in the calling session. */
    internal fun formatPreview(toolName: String, guestPath: String?, output: String, originalBytes: Int? = null): String {
        val name = spillName(guestPath)
        val label = previewLabel(toolName)
        return renderPreview(
            output = output,
            toolName = toolName,
            bodyBudget = PREVIEW_BODY_CHARS,
            intro = "$MARKER $label produced ${output.length} chars.\n",
            originalBytes = originalBytes,
            instructions = if (name != null) {
                "Full output saved to $guestPath — use file_read (offset/lines) or a shell range read.\n" +
                    "Open in app: minis://workspace/$SPILL_DIR/$name\n"
            } else {
                "Full output could not be saved or verified in the session workspace, " +
                    "so only this bounded preview is available.\n"
            },
        )
    }

    /** Keep the larger readback budget; never send the model to a second spill. */
    internal fun alreadySpilledPreview(toolName: String, guestPath: String, output: String): String {
        if (spillName(guestPath) == null) return cappedPreview(toolName, output)
        return renderPreview(
            output = output,
            toolName = toolName,
            bodyBudget = READBACK_BODY_CHARS,
            intro = "$MARKER ${previewLabel(toolName)} returned ${output.length} chars from $guestPath, " +
                "which is already an offloaded tool-output file — it is NOT being offloaded again.\n",
            instructions = "For more of that file, page it with file_read (offset/lines) or a shell range read.\n",
        )
    }

    private fun renderPreview(
        output: String,
        toolName: String,
        bodyBudget: Int,
        intro: String,
        instructions: String,
        originalBytes: Int? = null,
    ): String {
        val headBudget = when {
            ToolAliases.canonical(toolName) in LOG_TOOLS -> bodyBudget / 4
            bodyBudget == READBACK_BODY_CHARS -> READBACK_HEAD_CHARS
            else -> bodyBudget / 2
        }
        val slices = ToolOutputTruncator.previewSlices(output, headBudget, bodyBudget - headBudget)
        return buildString {
            append(intro)
            if (slices.omittedChars > 0) {
                val bytes = originalBytes ?: ToolOutputTruncator.utf8ByteLength(output)
                val estimatedTokens = (bytes.toLong() + 3) / 4
                append("Warning: truncated output (original token count: $estimatedTokens)\n")
            }
            append("Total output lines: ${ToolOutputTruncator.totalLines(output)}\n")
            append(instructions).append('\n')
            append(slices.head)
            if (slices.omittedChars > 0) {
                append("\n\n…(${slices.omittedChars} chars omitted)…\n\n")
                append(slices.tail)
            }
        }
    }

    internal fun previewLabel(toolName: String): String = buildString {
        for (ch in ToolOutputTruncator.sliceHead(toolName, MAX_LABEL_CHARS)) {
            append(if (ch.code < 0x20 || ch in "\u0085\u2028\u2029") ' ' else ch)
        }
    }.ifBlank { "tool" }

    private const val MAX_HEADER_CHARS = 2_048
    private val SAVED_INTRO = Regex("""^\[tool-output-spill] [^\r\n]+ produced \d+ (?:chars|bytes)\.$""")
    private val SAVED_LINE = Regex("""(?m)^Full output saved to ($GUEST_PREFIX$FILENAME) — use file_read (?:if you need more|\(offset/lines\) or a shell range read)\.$""")
    private val LEGACY_MCP_FOOTER = Regex("""(?m)^\[Full output: $GUEST_PREFIX$FILENAME \(read it with offset/limit\)\]\r?\n?""")
    private val LEGACY_SAVED_HEADER = Regex("""^\[tool output offloaded to save context[^\r\n]*?\. Full output saved to ($GUEST_PREFIX$FILENAME)(?: — use file_read to retrieve it if needed\.)?]$""")
    private val READBACK_HEADER = Regex("""^\[tool-output-spill] [^\r\n]+? returned \d+ chars from ($GUEST_PREFIX$FILENAME), which is already an offloaded tool-output file""")

    /** A declared host envelope reference; not a filesystem or provenance check. */
    internal fun declaredSpillPath(content: String): String? {
        val start = envelopeStart(content)
        val path = when {
            start < 0 -> null
            content.startsWith(MARKER, start) -> {
                val header = previewHeader(content, start)
                if (!SAVED_INTRO.matches(header.substringBefore('\n'))) return null
                SAVED_LINE.find(header)?.groupValues?.get(1)
            }
            content.startsWith(LEGACY_OFFLOADED_MARKER) ->
                LEGACY_SAVED_HEADER.find(content.take(MAX_HEADER_CHARS).substringBefore('\n'))?.groupValues?.get(1)
            else -> null
        }
        return path?.takeIf { isSpillGuestPath(it) }
    }

    internal fun readBackSpillPath(content: String): String? {
        if (!content.startsWith(MARKER)) return null
        return READBACK_HEADER.find(previewHeader(content))?.groupValues?.get(1)?.takeIf { isSpillGuestPath(it) }
    }

    private fun previewHeader(content: String, start: Int = 0): String =
        content.substring(start, start + minOf(MAX_HEADER_CHARS, content.length - start)).substringBefore("\n\n")

    /** Slice the displayed text, not the framework's existing links and headings. */
    internal fun previewBody(content: String): String {
        val start = envelopeStart(content)
        return when {
            start < 0 -> content
            content.startsWith(MARKER, start) -> {
                val end = start + previewHeader(content, start).length
                if (!content.startsWith("\n\n", end)) return content
                val body = content.substring(end + 2)
                // The old MCP preview repeated the path after its body. The
                // projection must not copy that stale hint through its tail.
                if (content.substring(start, end).substringBefore('\n').endsWith(" bytes.")) {
                    LEGACY_MCP_FOOTER.replace(body, "")
                } else body
            }
            else -> content.substringAfter('\n', content)
        }
    }

    /** Candidate only; the UI still opens it through [hostFile]. */
    fun parseGuestPath(content: String): String? = declaredSpillPath(content) ?: readBackSpillPath(content)
        ?: if (isOffloadPlaceholder(content)) null else GUEST_PATH.find(content)?.value?.takeIf { isSpillGuestPath(it) }

    /** The detail view can also recover the path in a stored sub-agent report's old envelope. */
    internal fun parseGuestPath(content: String, storedToolName: String): String? =
        (if (SubAgentKind.isSpawnTool(storedToolName)) legacyReportPath(content) else null) ?: parseGuestPath(content)

    /**
     * Declared recovery reference only — no loose path scan. Shared by the two
     * request-time pruning tiers so a legacy sub-agent report keeps its verified
     * link after folding instead of losing it to the new-envelope-only parser.
     */
    internal fun declaredRecoveryPath(content: String, storedToolName: String): String? =
        declaredSpillPath(content)
            ?: if (SubAgentKind.isSpawnTool(storedToolName)) legacyReportPath(content) else null

    private const val LEGACY_REPORT_LIMIT = 64_000
    private const val LEGACY_REPORT_HEAD_CHARS = 8_000
    private val LEGACY_REPORT_INTRO = Regex("""\[tool-output-spill] sub_agent_report produced (\d+) chars\.""")
    private val LEGACY_REPORT_NOTE = Regex("""\n\n…\(truncated — full report at (${GUEST_PREFIX}subagent-report-[0-9a-f]{16}\.txt)\)…\n\n""")

    // The old writer inserted the note exactly at the end of its 8k head,
    // or one code unit earlier when it backed off a surrogate pair.
    private fun legacyReportPath(content: String): String? {
        if (content.length <= LIMIT || !content.startsWith("$MARKER sub_agent_report produced ")) return null
        val introEnd = content.indexOf('\n').takeIf { it in 1..MAX_LABEL_CHARS } ?: return null
        val size = LEGACY_REPORT_INTRO.matchEntire(content.substring(0, introEnd))
            ?.groupValues?.get(1)?.toIntOrNull() ?: return null
        if (size <= LEGACY_REPORT_LIMIT) return null
        val noteStart = introEnd + 1 + LEGACY_REPORT_HEAD_CHARS
        return (LEGACY_REPORT_NOTE.matchAt(content, noteStart) ?: LEGACY_REPORT_NOTE.matchAt(content, noteStart - 1))
            ?.groupValues?.get(1)
    }

    fun hostFile(context: Context, sessionId: String, guestPath: String): File? =
        if (canReadInChat(context, sessionId)) hostFile(context.filesDir, sessionId, guestPath) else null

    internal fun hostFile(filesDir: File, sessionId: String, guestPath: String): File? =
        hostFile(com.openminis.app.sandbox.PathResolver.forSessionLayout(filesDir, sessionId), filesDir, sessionId, guestPath)

    /**
     * Same verification with a prebuilt resolver: a request projection checks
     * many candidate paths and must not rebuild the session layout per path.
     */
    internal fun hostFile(
        resolver: com.openminis.app.sandbox.PathResolver,
        filesDir: File,
        sessionId: String,
        guestPath: String,
    ): File? {
        val name = spillName(guestPath) ?: return null
        val dir = spillDir(filesDir, sessionId, create = false) ?: return null
        return try {
            val file = File(dir, name)
            val resolved = resolver.toHost(guestPath) ?: return null
            file.takeIf {
                it == resolved && it.canonicalFile == File(dir.canonicalFile, name) && it.isFile && it.canRead()
            }
        } catch (_: Exception) {
            null
        }
    }

    /** Publish the same recoverable guest path format for progressive trimming. */
    fun offloadForTrim(context: Context, sessionId: String, toolId: String, content: String): String? {
        if (!canReadInChat(context, sessionId)) return null
        val filesDir = context.filesDir
        val existing = declaredSpillPath(content)
        if (isOffloadPlaceholder(content)) {
            return existing?.takeIf { hostFile(filesDir, sessionId, it) != null }
        }
        return offloadInSession(filesDir, sessionId, "trim-$toolId", content)
    }
}

internal enum class SpillPlan { PASSTHROUGH, ALREADY_SPILLED, CAPPED, WRITE }
