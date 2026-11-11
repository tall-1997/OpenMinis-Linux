package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ToolJsonRepair
import com.openminis.app.security.ToolAliases
import org.json.JSONObject

/**
 * [GH#61/#83/#146][GH#119] 工具调用参数流的唯一 preflight 层。
 *
 * [GH#61/#83/#146] 根因：providers 在 `arguments` 无法解析成 JSON 时会静默
 * 下发 `{}`，而执行入口各自处理（或干脆不处理）这个空对象：
 *  - 主循环 [com.openminis.app.ui.chat.preflightToolTurn] 有 ToolJsonRepair +
 *    非法流拒绝；
 *  - [SubAgentRunner] 只取 `chunk.args`，把 `{}` 当合法的无参数调用执行；
 *  - 群聊/主管通道 `speakModel` 直接 `executeTool(args.toString())`。
 * 于是同一个坏参数流在主会话被拒绝，在子代理/群聊里却静默执行了 —— 正是
 * #61 报告的“所有工具都收到空参数”的可见症状。
 *
 * 中文：所有“模型工具调用执行入口”（主循环、子代理、群聊/主管、任何以后新增
 * 的流式入口）都必须调用本对象：
 *  1. 先用 [ToolJsonRepair] 尝试修复（截断补闭合 / 类型纠正 / 字段名纠错）；
 *  2. 修复不了（`INVALID_JSON_MARKER`）→ 拒绝执行，把“参数流未完整/非法 +
 *     收到字节数 + 尾部样本”回写模型，并让调用方把工具块标 FAILED；
 *  3. 修复策略是截断且工具属于 [WriteTools] → 拒绝执行（半截写入是静默的
 *     用户数据损坏）；
 *  4. 整轮 finish_reason=length/max_tokens → 拒绝执行（流尾参数不可信）。
 *
 * Returns a sealed [Outcome]: [Outcome.Ready] carries the repaired args (the
 * same JSONObject, mutated in place) so every caller executes exactly what the
 * shared layer validated; [Outcome.Rejected] carries the model-visible and
 * UI-visible messages so no caller can invent a weaker one.
 */
internal object ToolCallPreflight {

    /**
     * [T-truncated-tool-call-reject] finish_reason=length / max_tokens 意味着
     * 输出预算耗尽，流尾的工具调用参数极可能被截断成半截 JSON。
     * Kept here (not in the UI loop) so every stream consumer applies the same
     * set — the group-chat and sub-agent streams see the same finish reasons.
     */
    private val TRUNCATED_FINISH_REASONS = setOf("length", "max_tokens")

    /** True when this turn's stream was cut by the output-token budget. */
    fun isTruncatedFinishReason(reason: String?): Boolean =
        reason != null && reason in TRUNCATED_FINISH_REASONS

    sealed class Outcome {
        /**
         * The call may execute. [args] is the repaired object; [argsStr] is its
         * serialization (callers must execute THIS, not a stale pre-repair copy);
         * [truncationRepairTag] is non-null when the argument payload was
         * auto-closed, so post-execution UI/model notes can name it.
         */
        data class Ready(
            val args: JSONObject,
            val argsStr: String,
            val repairs: List<String>,
            val truncationRepairTag: String?,
        ) : Outcome()

        /**
         * The call must NOT execute. [modelMessage] is the tool_result content the
         * model sees (it names the byte count / repair strategy and says nothing
         * ran); [uiMessage] is the short tool-block status; [rawBytes] is the
         * received argument-stream size for logs and diagnostics.
         */
        data class Rejected(
            val modelMessage: String,
            val uiMessage: String,
            val repairs: List<String>,
            val truncationRepairTag: String?,
            val rawBytes: Int,
        ) : Outcome()
    }

    /** Convenience overload for the three stream loops: one line at the call site. */
    fun finalize(
        complete: LLMStreamChunk.ToolCallComplete,
        advertisedTools: List<AgentToolDefinition>,
        turnFinishReason: String? = null,
    ): Outcome = finalize(
        toolName = complete.name,
        args = complete.args,
        rawArgumentTail = complete.rawArgs,
        advertisedTools = advertisedTools,
        turnFinishReason = turnFinishReason,
    )

    /**
     * Shared preflight core.
     *
     * @param rawArgumentTail the provider's exact unparsed `arguments` text when
     *   it failed to parse (or a last-resort accumulated delta tail). Preferred
     *   by [ToolJsonRepair] over the parsed `args`, which is `{}` in that case.
     * @param turnFinishReason the turn's finish reason; `length`/`max_tokens`
     *   rejects the call because the tail may be a half-written parameter.
     */
    fun finalize(
        toolName: String,
        args: JSONObject,
        rawArgumentTail: String?,
        advertisedTools: List<AgentToolDefinition>,
        turnFinishReason: String? = null,
    ): Outcome {
        val rawTail = rawArgumentTail?.takeIf { it.isNotBlank() }
        // [GH#61/#83/#146] Repair against the CANONICAL tool definition: models
        // emit historical aliases (shell_exec, save_memory, checkpoint …) that
        // are absent from the advertised list, and an alias must receive the
        // same repair as its canonical name instead of silently skipping it.
        val canonical = ToolAliases.canonical(toolName)
        val repairs = ToolJsonRepair.repair(canonical, args, rawTail, advertisedTools)
        if (repairs.isNotEmpty()) {
            AppLogger.warning(
                "ToolPreflight",
                "[ToolRepair] REPAIRED tool=$toolName strategies=[${repairs.joinToString(", ")}] " +
                    "argsKeys=[${args.keys().asSequence().toList().sorted().joinToString(",")}] " +
                    "rawTail=<<<${rawTail?.take(500) ?: ""}>>>",
            )
        }
        // [GH#61/#83/#146] 非空参数尾既不是 JSON、任何闭合后缀也救不回来。
        // 拿 `{}` 去执行就是 #61 的静默损坏（无必填字段的工具会变成隐形空操作）。
        // 拒绝执行，并把“参数流未完整”的事实和字节数回写模型。
        if (repairs.any { it == ToolJsonRepair.INVALID_JSON_MARKER }) {
            val rawBytes = (rawTail ?: "").toByteArray(Charsets.UTF_8).size
            val tailPreview = (rawTail ?: "").take(200).replace("\n", "\\n")
            AppLogger.warning(
                "ToolPreflight",
                "BLOCKED incomplete argument stream tool=$toolName bytes=$rawBytes " +
                    "tail=<<<$tailPreview>>>",
            )
            return Outcome.Rejected(
                modelMessage = "Error: The argument stream for this tool call did not arrive " +
                    "as valid JSON ($rawBytes bytes received, tail=<<<$tailPreview>>>). The call " +
                    "was NOT executed — no tool ran and nothing was written. This usually means " +
                    "the model response was cut off mid-arguments or the gateway corrupted the " +
                    "parameter stream. Re-issue the call with complete arguments; do not retry " +
                    "with an empty or partial payload.",
                uiMessage = "Blocked: arguments stream was incomplete or invalid",
                repairs = repairs,
                truncationRepairTag = null,
                rawBytes = rawBytes,
            )
        }
        // [T-truncated-args-visibility #119] Non-null when THIS call's arguments
        // arrived truncated and were auto-closed. Only the truncation strategy
        // means the VALUE was cut short; coercion and fuzzy-name repairs fix the
        // shape of a complete argument.
        val truncationRepairTag: String? = repairs.firstOrNull { it.startsWith("truncation+") }

        // [GH#119] Refuse truncated WRITES. Auto-closing an unterminated JSON
        // string is indistinguishable from the model ending `content` there, so
        // a half file lands on disk while UI and tool result both report success.
        // The write-tool set is shared ([WriteTools]) because the old check only
        // covered file_write/file_edit and let multi_edit/memory_write/
        // file_checkpoint/skill_manage write partial data.
        // 中文：截断写必须拒绝——半截内容落盘是静默的用户数据损坏，比不写更糟。
        if (truncationRepairTag != null && WriteTools.isWriteTool(toolName)) {
            val path = args.optString("path", "").ifBlank { args.optString("file_path", "") }
            val rawBytes = args.toString().toByteArray(Charsets.UTF_8).size
            AppLogger.warning(
                "ToolPreflight",
                "[ToolRepair] REFUSED truncated write tool=$toolName strategy=$truncationRepairTag path=$path",
            )
            return Outcome.Rejected(
                modelMessage = buildString {
                    append("Error: This call was NOT executed. Its argument stream was truncated ")
                    append("in transit (repair strategy: $truncationRepairTag), so the payload your ")
                    append("client sent was cut short and executing it would have written an ")
                    append("incomplete or corrupted result")
                    if (path.isNotBlank()) append(" to $path")
                    append(". Nothing was written — the target is unchanged.\n\n")
                    append("The most likely cause is the response hitting its output-token limit ")
                    append("mid-argument. Re-issue this write in smaller pieces: write the first ")
                    append("part, then append the rest with follow-up calls, rather than repeating ")
                    append("the same oversized call.")
                },
                uiMessage = "Blocked: arguments were truncated in transit",
                repairs = repairs,
                truncationRepairTag = truncationRepairTag,
                rawBytes = rawBytes,
            )
        }

        // [T-truncated-tool-call-reject] 整轮被输出预算切断：即使本调用解析成功，
        // 流尾参数也不可信。主循环、子代理、群聊在这里统一拒绝。
        if (isTruncatedFinishReason(turnFinishReason)) {
            val reason = turnFinishReason
            val msg = "Error: This tool call was rejected because the model output was " +
                "truncated (finish_reason=$reason). The arguments are likely incomplete. " +
                "Re-read any relevant context and re-issue the call with complete arguments."
            AppLogger.warning(
                "ToolPreflight",
                "BLOCKED truncated turn tool=$toolName finishReason=$reason",
            )
            return Outcome.Rejected(
                modelMessage = msg,
                uiMessage = msg,
                repairs = repairs,
                truncationRepairTag = truncationRepairTag,
                rawBytes = args.toString().toByteArray(Charsets.UTF_8).size,
            )
        }

        return Outcome.Ready(
            args = args,
            argsStr = args.toString(),
            repairs = repairs,
            truncationRepairTag = truncationRepairTag,
        )
    }
}

/**
 * [GH#119] 所有会持久化用户数据的写工具（canonical name + 历史别名）。
 *
 * 中文：截断的写参数必须拒绝执行；判定必须覆盖全部写类工具，而不是只认
 * file_write/file_edit 两个名字。新增写工具时把它加入本集合，所有执行入口
 * （主循环 / 子代理 / 群聊 / 主管）自动生效，无需逐入口补 if。
 *
 * Names are matched after [ToolAliases.canonical], so historical labels
 * (save_memory, checkpoint, rollback, undo) follow their canonical tool.
 */
internal object WriteTools {

    private val WRITE_TOOL_NAMES: Set<String> = setOf(
        "file_write",
        "file_edit",
        "multi_edit",
        "memory_write",
        "file_checkpoint",
        // [T-agent-skill-library] Creates/patches skill markdown in the library —
        // a truncated `content` writes a half skill exactly like file_write.
        "skill_manage",
        // Alias labels for the canonical entries above. ToolAliases already maps
        // them; keeping them here makes the set correct even if the alias table
        // is ever trimmed.
        "save_memory",
        "checkpoint",
        "rollback",
        "undo",
    )

    /** True for any write-capable tool name, canonical or alias. */
    fun isWriteTool(rawName: String): Boolean {
        val raw = rawName.trim()
        if (raw in WRITE_TOOL_NAMES) return true
        return ToolAliases.canonical(raw) in WRITE_TOOL_NAMES
    }
}
