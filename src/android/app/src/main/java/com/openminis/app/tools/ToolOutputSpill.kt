package com.openminis.app.tools

import android.content.Context
import com.openminis.app.security.ToolAliases
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Spill oversized tool results out of the LLM context into the session
 * workspace. Inspired by long-running Android agents that otherwise freeze
 * Compose and blow the context window when `shell_execute` dumps megabytes.
 *
 * The UI still shows a short tail; the model gets a preview plus a guest
 * path it can `file_read` if it actually needs more.
 *
 * [T-tool-output-single-mechanism] This is THE oversized-output mechanism:
 * it resolves the spill directory PER SESSION through
 * `SessionWorkspace.hostDir(filesDir, sessionId, "workspace")`, i.e. the HOST
 * path the app process can actually write, and hands the model the matching
 * GUEST path. [ToolOutputPolicy] used to carry a second, parallel copy of this
 * logic writing to `ToolOutputPolicy.workspaceRoot = "/var/minis/workspace/…"`
 * — a GUEST path the app process cannot write, whose write therefore always
 * failed, after which `apply()` returned the text UNCAPPED (defeating the whole
 * limit). That duplicate is gone; ToolOutputPolicy now redacts and delegates
 * here, so one threshold, one preview format and one working path remain.
 */
object ToolOutputSpill {
    /** The single oversized-output threshold (chars). */
    const val LIMIT = 16_000
    const val HEAD = 6_000
    const val TAIL = 4_000

    /**
     * [T-tool-output-offload-loop GH#343] Every marker this object (and
     * ProgressiveTrim's L2 stub, which writes through here) puts at the start
     * of a bounded placeholder. A result that already starts with one has
     * ALREADY been offloaded: re-spilling it produces a placeholder pointing
     * at a copy of a placeholder, and the model that asked for the content
     * never receives it — the observed ping-pong loop.
     */
    private val PLACEHOLDER_PREFIXES = listOf(
        "[tool-output-spill]",
        "[tool output offloaded to save context",
        "[tool output trimmed for context",
    )

    fun isOffloadPlaceholder(content: String): Boolean =
        PLACEHOLDER_PREFIXES.any { content.startsWith(it) }

    /**
     * [T-tool-output-offload-loop GH#343] True when [guestPath] points INTO the
     * spill directory — i.e. the model is trying to read back an offload file
     * it was handed. Offloading that read a second time is never useful: the
     * content is already the bounded payload the first pass decided the model
     * may have, and the new placeholder would just point at another copy.
     */
    fun isSpillGuestPath(guestPath: String?): Boolean {
        val p = guestPath?.trim().orEmpty()
        return p.startsWith("/var/minis/workspace/tool-spill/")
    }

    fun maybeSpill(
        context: Context,
        sessionId: String,
        toolName: String,
        toolId: String,
        output: String,
        /**
         * [T-tool-output-offload-loop GH#343] For `file_read`, the guest path it
         * read; for a shell `cat/head/tail … tool-spill/…`, that spill path.
         * When the source is already a spill file, the read-back must not spawn
         * another spill (see [alreadySpilledPreview]).
         */
        sourcePath: String? = null,
    ): String {
        // [T-tool-output-offload-loop GH#343] The decision is a pure function so
        // it is pinned by a JVM test, and it is applied to the ORIGINAL tool
        // output — never to an already-capped preview (the old execution-entry
        // pre-cap made every chat-path output < LIMIT here, so no spill file was
        // ever written; see ChatViewModelExecuteToolExt).
        return when (planFor(output, sessionId.isNotBlank(), sourcePath)) {
            SpillPlan.PASSTHROUGH -> output
            SpillPlan.ALREADY_SPILLED -> alreadySpilledPreview(toolName, sourcePath!!, output)
            SpillPlan.CAPPED -> cappedPreview(toolName, output)
            SpillPlan.WRITE -> {
                val dir = spillDir(context, sessionId)
                if (dir == null) {
                    // [T-tool-output-single-mechanism] CAPPED even when the spill
                    // cannot happen. Returning the raw text here is what let a
                    // megabyte tool result reach the model intact.
                    cappedPreview(toolName, output)
                } else {
                    writeSpill(dir, toolName, toolId, output)
                }
            }
        }
    }

    private fun spillDir(context: Context, sessionId: String): File? {
        val dir = File(
            com.openminis.app.sandbox.SessionWorkspace.hostDir(context.filesDir, sessionId, "workspace"),
            "tool-spill",
        )
        return if (dir.mkdirs() || dir.isDirectory) dir else null
    }

    /**
     * [GH#343] Write one spill file into [dir] and return the bounded preview.
     * Internal so a plain-JVM test can exercise raw → spill → readback without
     * a Context (the production dir comes from [spillDir]).
     *
     * 中文：文件名绝不能只由 toolId 决定 —— 群聊/主管曾传 ""，子代理传
     * parentSession，两种情况下同一会话（甚至同一文件夹内多个会话共享的
     * workspace）里的所有调用都会写同一个 `tool.txt` / `<parentSession>.txt`，
     * 后一次调用静默覆盖前一次，模型随后 `file_read` 读到的可能是别的调用的
     * 内容。唯一名由 [uniqueSpillFile] 负责；落盘走 [AtomicFileWrite]，避免
     * 并发写与崩溃留下半截文件。
     */
    internal fun writeSpill(dir: File, toolName: String, toolId: String, output: String): String {
        val file = uniqueSpillFile(dir, toolId)
        return try {
            // [F1 GH#343] 原子写：临时文件 + rename（同目录），并做短写/回读校验。
            // 旧实现直接 file.writeText，两个 lane 并发写同一名字时内容会交错，
            // 进程被杀也会留下半截 spill。
            if (AtomicFileWrite.write(file, output) == null) {
                // 名字已原子占位（createNewFile）但内容没写成：删掉空占位，
                // 免得模型 file_read 到一个 0 字节的"完整输出"。
                runCatching { file.delete() }
                return cappedPreview(toolName, output)
            }
            formatPreview(
                toolName = toolName,
                guestPath = "/var/minis/workspace/tool-spill/${file.name}",
                output = output,
            )
        } catch (_: Exception) {
            runCatching { file.delete() }
            cappedPreview(toolName, output)
        }
    }

    /**
     * [F1 GH#343] 为一次 spill 原子地"占住"一个不会覆盖别人内容的文件名。
     *
     * - toolId 非空且 `createNewFile()` 成功 → 保持 `<safeId>.txt`（既有回读
     *   路径/测试不变）。
     * - toolId 为空（群聊/主管旧调用点、任何无 id 的调用）→ 永远走
     *   `<base>-<短UUID>.txt`。
     * - 同 id 已被占用（并行 lane / 文件夹内多会话共享 workspace / provider
     *   复用 call id）→ 同样追加短 UUID，绝不覆盖已有 spill。
     *
     * 中文：判断"是否已存在"必须用 `createNewFile()` 的原子语义，而不是
     * `exists()` + `writeText()` —— 后者在并发下两个线程可以同时看到"不存在"
     * 然后写同一个文件。这里是防御层：即使调用点又把 id 传空/传重，也只多一个
     * 文件名，不会让两个调用互相覆盖。短 UUID 是 8 位十六进制，满足
     * [SAFE_ID_RE]/[GUEST_PATH] 的字符集，回读时 [parseGuestPath] 仍能命中。
     */
    internal fun uniqueSpillFile(dir: File, toolId: String): File {
        val safeId = SAFE_ID_RE.replace(toolId, "_").trim('_').ifBlank { "tool" }
        if (toolId.isNotBlank()) {
            val direct = File(dir, "$safeId.txt")
            if (claim(direct)) return direct
        }
        repeat(8) {
            val candidate = File(dir, "$safeId-${shortUid()}.txt")
            if (claim(candidate)) return candidate
        }
        // 8 次随机都撞上（实际不可能）也不再覆盖：用单调时间戳兜底。
        val fallback = File(dir, "$safeId-${System.nanoTime().toString(16)}.txt")
        claim(fallback)
        return fallback
    }

    /** [F1 GH#343] Atomically reserve [file] iff it does not exist yet. */
    private fun claim(file: File): Boolean =
        try {
            file.createNewFile()
        } catch (_: Exception) {
            false
        }

    /** [F1 GH#343] 8 位十六进制随机后缀；每次调用都不同。 */
    private fun shortUid(): String =
        UUID.randomUUID().toString().replace("-", "").take(8)

    /**
     * [T-tool-output-offload-loop GH#343] Pure decision for one result body.
     *
     * 中文：raw → spill → readback 不能成环 —— 已落盘的占位符和从 spill 目录
     * 读回的内容都必须原样/受限返回，不能再次落盘。
     */
    internal fun planFor(output: String, hasSession: Boolean, sourcePath: String?): SpillPlan {
        if (output.length <= LIMIT) return SpillPlan.PASSTHROUGH
        // Never wrap a placeholder in another placeholder (idempotency).
        if (isOffloadPlaceholder(output)) return SpillPlan.PASSTHROUGH
        // Reading a spill file back must answer from the existing file.
        if (isSpillGuestPath(sourcePath)) return SpillPlan.ALREADY_SPILLED
        // Nowhere to hang the file — still cap, the model just gets no path.
        if (!hasSession) return SpillPlan.CAPPED
        return SpillPlan.WRITE
    }

    /**
     * [T-tool-output-offload-loop GH#343] Resolve the read-back source for a
     * tool call. Covers every spelling that can read a spill file:
     *  - `file_read` with the guest spill path (absolute or workspace-relative);
     *  - `shell_execute`/`env_exec`/`su_exec` running a read program
     *    (`cat`, `head`, `tail`, `sed`, `grep`, …) against `tool-spill/…`.
     *
     * 中文：#343 的去环不能只修 file_read —— 模型拿到 guest 路径后经常直接
     * `cat /var/minis/workspace/tool-spill/x.txt`，shell 路径同样必须命中。
     */
    fun sourcePathForTool(rawToolName: String, argsJson: String): String? {
        val canonical = ToolAliases.canonical(rawToolName)
        // Cheap gate first: only these tools can read a spill file, so the
        // args parse below is skipped for every other tool result.
        if (canonical != "file_read" && canonical != "shell_execute" &&
            canonical != "env_exec" && canonical != "su_exec"
        ) {
            return null
        }
        val args = runCatching { JSONObject(argsJson) }.getOrNull() ?: return null
        return when (canonical) {
            "file_read" -> {
                val raw = args.optString("path", "").trim()
                val guest = if (raw.startsWith("tool-spill/")) "/var/minis/workspace/$raw" else raw
                guest.takeIf { isSpillGuestPath(it) }
            }
            "shell_execute", "env_exec", "su_exec" ->
                commandSpillReadPath(args.optString("command", ""))
            else -> null
        }
    }

    /** [GH#343] A read program against a tool-spill path, or null. */
    internal fun commandSpillReadPath(command: String): String? {
        val trimmed = command.trim()
        if (trimmed.isEmpty() || !trimmed.contains("tool-spill/")) return null
        var verb = trimmed.substringBefore(' ').substringAfterLast('/')
        if (verb == "busybox") {
            verb = trimmed.substringAfter(' ', "").substringBefore(' ').substringAfterLast('/')
        }
        if (verb !in SPILL_READ_VERBS) return null
        val match = SPILL_REF_IN_COMMAND.find(trimmed) ?: return null
        return "/var/minis/workspace/tool-spill/${match.groupValues[1]}"
    }

    /** [GH#343] Command verbs that READ their file operand. */
    private val SPILL_READ_VERBS = setOf(
        "cat", "head", "tail", "sed", "awk", "grep", "less", "more",
        "nl", "wc", "od", "xxd", "strings",
    )

    /** Matches absolute and workspace-relative spill references in a command. */
    private val SPILL_REF_IN_COMMAND = Regex("(?:/var/minis/workspace/)?tool-spill/([A-Za-z0-9._-]+)")

    /**
     * Bounded preview with NO spill file: the model is told the truth (the full
     * output is gone) instead of silently receiving an uncapped dump.
     */
    fun cappedPreview(toolName: String, output: String): String =
        formatPreview(toolName, guestPath = null, output = output)

    /**
     * [guestPath] is null when the full text could NOT be persisted, in which
     * case the preview says so instead of pointing at a file that does not exist.
     */
    fun formatPreview(toolName: String, guestPath: String?, output: String): String {
        // Surrogate-safe slicing (ToolOutputPolicy.safeSubstring): take/takeLast
        // could cut a emoji in half and poison the message with a lone surrogate.
        val head = ToolOutputPolicy.safeSubstring(output, 0, HEAD)
        val tail = if (output.length > HEAD + TAIL)
            ToolOutputPolicy.safeSubstring(output, output.length - TAIL, output.length) else ""
        return buildString {
            append("[tool-output-spill] $toolName produced ${output.length} chars. ")
            if (guestPath != null) {
                append("Full output saved to $guestPath — use file_read if you need more.\n")
                append("Open in app: minis://workspace/tool-spill/${guestPath.substringAfterLast('/')}\n\n")
            } else {
                append("Full output could not be saved to the session workspace, ")
                append("so only this bounded preview is available.\n\n")
            }
            append(head)
            if (tail.isNotEmpty()) {
                append("\n\n…(${output.length - head.length - tail.length} chars omitted)…\n\n")
                append(tail)
            }
        }
    }

    /**
     * [T-tool-output-offload-loop GH#343] Bounded answer for a READ-BACK of an
     * existing spill file. It must not create a new spill: the file it names
     * already holds the same bytes, so a second hop only burns disk and hands
     * the model a path chain it can never finish walking. The model is told
     * the read was already at an offload target and how to page the rest
     * (`file_read` offset/limit, or a shell range read).
     */
    fun alreadySpilledPreview(toolName: String, guestPath: String, output: String): String {
        val head = ToolOutputPolicy.safeSubstring(output, 0, HEAD)
        val tail = if (output.length > HEAD + TAIL)
            ToolOutputPolicy.safeSubstring(output, output.length - TAIL, output.length) else ""
        return buildString {
            append("[tool-output-spill] $toolName returned ${output.length} chars from $guestPath, ")
            append("which is already an offloaded tool-output file — it is NOT being offloaded again.\n")
            append("Page it with file_read offset/limit (or a shell range read) instead of re-reading it whole.\n\n")
            append(head)
            if (tail.isNotEmpty()) {
                append("\n\n…(${output.length - head.length - tail.length} chars omitted)…\n\n")
                append(tail)
            }
        }
    }

    /** [T-hoisted-regex] Constant pattern, compiled once instead of per call. */
    private val SAFE_ID_RE = Regex("[^A-Za-z0-9._-]")

    private val GUEST_PATH = Regex("/var/minis/workspace/tool-spill/([A-Za-z0-9._-]+)")

    fun parseGuestPath(content: String): String? {
        val m = GUEST_PATH.find(content) ?: return null
        return "/var/minis/workspace/tool-spill/${m.groupValues[1]}"
    }

    fun hostFile(context: Context, sessionId: String, guestPath: String): File? {
        val name = parseGuestPath(guestPath)?.substringAfterLast('/') ?: return null
        if (sessionId.isBlank() || name.isBlank()) return null
        val file = File(
            com.openminis.app.sandbox.SessionWorkspace.hostDir(context.filesDir, sessionId, "workspace"),
            "tool-spill/$name",
        )
        return file.takeIf { it.isFile }
    }

    /**
     * [T-wave-e-progressive-reclaim] Offload an OLD oversized tool output
     * during progressive context trimming (ProgressiveTrim L2): write the
     * FULL text into the session's tool-spill workspace and return the guest
     * path the model can `file_read` back, or null when there is nowhere to
     * write (the trim stub then says the text is gone instead of pointing at
     * a nonexistent file).
     *
     * Same directory as the live-spill path, but keyed by the RESULT id so
     * re-trims of the same row overwrite their own file instead of the one a
     * fresh spill wrote.
     */
    fun offloadForTrim(
        context: Context,
        sessionId: String,
        toolId: String,
        content: String,
    ): String? {
        if (sessionId.isBlank()) return null
        val safeId = SAFE_ID_RE.replace(toolId, "_").ifBlank { "tool" }
        val dir = File(
            com.openminis.app.sandbox.SessionWorkspace.hostDir(context.filesDir, sessionId, "workspace"),
            "tool-spill",
        )
        if (!dir.mkdirs() && !dir.isDirectory) return null
        val file = File(dir, "trim-$safeId.txt")
        return try {
            // [F1 GH#343] 与 writeSpill 一样走原子写：trim 文件也会被并发 trim /
            // 其它会话读到，半截内容比"没有文件"更糟（模型会把它当完整正文）。
            if (AtomicFileWrite.write(file, content) == null) return null
            "/var/minis/workspace/tool-spill/${file.name}"
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * [T-tool-output-offload-loop GH#343] The pure outcome of inspecting one tool
 * result body: pass it through untouched, answer from an existing spill file,
 * cap it without a file (no session), or write a new spill file.
 */
internal enum class SpillPlan { PASSTHROUGH, ALREADY_SPILLED, CAPPED, WRITE }
