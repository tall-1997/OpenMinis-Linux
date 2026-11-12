package com.openminis.app.tools

import com.openminis.app.data.model.AgentToolDefinition
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
 *  4. 整轮 finish_reason=length/max_tokens → 拒绝执行（流尾参数不可信）；
 *  5. 参数已解析但缺必填字段 / 空 `{}`（[requiredFieldViolation]）→ 拒绝执行
 *     （#61 的子代理/群聊缺口：它们曾把 provider 顶替的 `{}` 当合法调用）。
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

    /**
     * [F3 GH#61] 必填字段 / 空参数预检 —— 从主循环下沉到共享层。
     *
     * 中文：这段逻辑原先只存在于 `ChatViewModel.preflightValidateToolCall`
     * （主循环独有），于是同一个 provider 用 `{}` 顶替解析失败参数流时，
     * 主循环会拒绝，子代理和群聊/主管却把空对象当合法调用执行：写工具报
     * `path required`、无必填字段的工具直接静默空操作，模型还得多跑一轮。
     * 现在 [finalize] 在修复之后、返回 Ready 之前调用本函数，三个执行入口
     * （主循环 / SubAgentRunner / 群聊-主管 speakModel）自动一致。
     */
    private val NON_BLOCKING_REQUIRED_FIELDS = setOf("tool_title")

    /**
     * [F3 GH#61] (tool → field) 中 "" 是合法值、不能当"缺失"的字段。
     *
     * 与 [NON_BLOCKING_REQUIRED_FIELDS] 不同：这些字段仍必须 PRESENT，只是
     * 允许内容为空。典型是 `file_edit.new_string == ""` 的删除语义。
     * [T-preflight-empty-string-allowed] 与 iOS
     * `AIChatViewModel.preflightEmptyStringAllowedFields` 对齐。
     */
    private val EMPTY_STRING_ALLOWED_FIELDS: Map<String, Set<String>> = mapOf(
        "file_edit" to setOf("new_string"),
    )

    /** True when "" is a legal value for this exact (tool, field) pair. */
    internal fun emptyStringAllowed(tool: String, field: String): Boolean =
        EMPTY_STRING_ALLOWED_FIELDS[tool]?.contains(field) == true

    /**
     * [F3 GH#61] Returns null when [args] satisfies the advertised tool schema,
     * or a human-readable reason when the call must not execute.
     *
     * 匹配用 canonical 名：模型可能发 `shell_exec` 这类历史别名，而
     * advertisedTools 里只有 `shell_execute`；只按原名字面匹配会让别名绕过
     * 整个预检（旧主循环的隐藏缺口）。报文仍用模型发出的 [toolName]，便于
     * 模型把错误对上自己那次调用。
     */
    fun requiredFieldViolation(
        toolName: String,
        args: JSONObject,
        advertisedTools: List<AgentToolDefinition>,
    ): String? {
        // Unknown tool names stay silent: dispatch returns "Unknown tool: …".
        val canonical = ToolAliases.canonical(toolName)
        val toolDef = advertisedTools.firstOrNull { it.name == canonical }
            ?: advertisedTools.firstOrNull { it.name == toolName }
            ?: return null
        val enforced = toolDef.required.filter { it !in NON_BLOCKING_REQUIRED_FIELDS }
        // Empty args on a tool that requires anything → block. Gate on `enforced`
        // so a tool whose only required field is non-blocking is not rejected.
        if (args.length() == 0 && enforced.isNotEmpty()) {
            return "Tool '$toolName' was called with empty arguments {} but requires: " +
                "${enforced.joinToString(", ")}."
        }
        val missing = mutableListOf<String>()
        for (field in enforced) {
            // Absent — or present as explicit JSON null. org.json reports
            // has()==true for {"x":null}, so both spellings are genuinely missing.
            if (!args.has(field) || args.isNull(field)) {
                missing.add(field)
                continue
            }
            val raw = args.opt(field)
            // Only the truly-empty literal "" is rejected — NOT whitespace.
            // file_edit with new_string "\n" / old_string "  " are valid edits.
            if (raw is String && raw.isEmpty() &&
                !emptyStringAllowed(canonical, field)
            ) {
                missing.add(field)
            }
        }
        if (missing.isNotEmpty()) {
            return "Tool '$toolName' is missing required parameter(s): ${missing.joinToString(", ")}."
        }
        return null
    }

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
        // [F4 GH#61] Required, no `= null` default: a call site that forgot the
        // turn's finish reason would silently lose the length/max_tokens refusal
        // — the exact "three entries drift" failure this shared layer exists to
        // prevent. All three call sites pass it explicitly.
        turnFinishReason: String?,
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

        // [F3 GH#61] 必填字段 / 空参数预检。放在修复与截断拒绝之后：修复出的参数
        // 才是真正要执行的参数，而整轮截断的拒绝信息优先于"缺字段"。
        // 中文：这一段以前只在主循环的 `preflightValidateToolCall` 里，子代理/
        // 群聊对"解析成功的空 {}"仍会执行；下沉到这里后三个入口自然一致。
        requiredFieldViolation(toolName, args, advertisedTools)?.let { violation ->
            val rawBytes = args.toString().toByteArray(Charsets.UTF_8).size
            AppLogger.warning(
                "ToolPreflight",
                "BLOCKED missing required fields tool=$toolName reason=\"$violation\" " +
                    "argsKeys=[${args.keys().asSequence().toList().sorted().joinToString(",")}]",
            )
            return Outcome.Rejected(
                modelMessage = "Error: Tool call rejected before execution. $violation " +
                    "The arguments your client sent were empty or missing required fields — " +
                    "re-issue the call with all required parameters filled in. Do not retry " +
                    "with the same empty arguments.",
                uiMessage = "Blocked invalid tool call",
                repairs = repairs,
                truncationRepairTag = truncationRepairTag,
                rawBytes = rawBytes,
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
