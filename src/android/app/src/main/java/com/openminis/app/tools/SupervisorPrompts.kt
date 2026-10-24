package com.openminis.app.tools

import com.openminis.app.data.model.LLMMessage

/**
 * Seat prompts for the Supervisor-Worker chat
 * (spec: docs/superpowers/specs/2026-10-24-supervisor-worker-loop-design.md §3.4–3.5).
 *
 * All copy lives here so the protocol wording (DONE line, envelope markers,
 * rubber-stamp rule) has a single source; the runner only assembles calls.
 * Chinese prompt text matches the GroupChat precedent — this file is outside
 * the CJK literal gate, which only watches `ui/`.
 */
object SupervisorPrompts {

    /** System prompt for the supervisor seat. */
    fun supervisorSystem(displayName: String): String {
        return """
            你是监督席（supervisor seat），运行在 $displayName 上。你的对席是一个执行器（worker 席）：
            它拿着工具干活，你负责拆解、指派、验收。你不亲手改文件——你读证据、写指令。

            每条给 worker 的指令必须遵守信封格式：
            ## Task
            - 具体要做的事（一条一个动作）
            - write_paths: <允许写入的路径，逗号分隔>（仅当本轮要写文件时）

            ## Expected result
            - 验收标准（下一条 review 按它判，写可核验的事实，不写"看起来好了"）

            收到 worker 报告后，逐条对照上一条指令的验收标准复核。可以读文件、跑只读命令取证。
            不通过就发下一条修正指令（同样走信封格式）；通过就结束会话。

            结束会话的唯一方式是单独一行：
            DONE: <一句话总结，必须引用验收标准的原词作为证据>

            不引用验收标准的 DONE 会被打回一次；第二次直接判会话失败。
            用户随时可能插话（inbox 送达）：把插话内容吸收进下一条指令，不要直接回应用户。
            worker 回合预算有限，指令要一次说清楚，不要挤牙膏。
        """.trimIndent()
    }

    /** First supervisor turn: objective, chat context, inbox, optional recovery. */
    fun firstTurn(
        objective: String,
        contextDigest: String,
        inbox: List<String>,
        recovery: String?,
    ): String {
        val recoverySection = recovery?.let { "\n\n$it" } ?: ""
        val inboxSection = if (inbox.isEmpty()) "" else "\n\n用户插话（按顺序）：\n" + inbox.joinToString("\n") { "- $it" }
        return buildString {
            append("目标：")
            append(objective.trim())
            if (contextDigest.isNotBlank()) {
                append("\n\n会话上下文（最近对话摘要）：\n")
                append(contextDigest.trim())
            }
            append(recoverySection)
            append(inboxSection)
            append(
                "\n\n请发出第一条 worker 指令（信封格式：## Task 与 ## Expected result）。",
            )
        }
    }

    /** Review turn after a worker report. */
    fun reviewTurn(
        objective: String,
        lastReport: String,
        acceptance: List<String>,
        inbox: List<String>,
        workerTurns: Int,
        maxWorkerTurns: Int,
        bounceNote: String?,
    ): String {
        val acceptanceBlock = acceptance.joinToString("\n") { "- $it" }
        val inboxSection = if (inbox.isEmpty()) "" else "\n\n用户插话（按顺序）：\n" + inbox.joinToString("\n") { "- $it" }
        val bounceSection = bounceNote?.let { "\n\n$it" } ?: ""
        return buildString {
            append("目标：")
            append(objective.trim())
            append("\n\nworker 上一轮报告：\n")
            append(lastReport.trim())
            append("\n\n上一条指令的验收标准：\n")
            append(acceptanceBlock)
            append(inboxSection)
            append(bounceSection)
            append(
                "\n\nworker 回合预算：已用 $workerTurns / $maxWorkerTurns。" +
                    "逐条复核报告：全部通过就单独一行 DONE: <总结>（引用验收标准原词）；" +
                    "否则发下一条修正指令（信封格式）。",
            )
        }
    }

    /** Bounce note when the supervisor's instruction broke the envelope contract. */
    fun bounceEnvelope(reason: String): String {
        return "你上一条指令被信封校验拒绝：$reason\n请重发完整指令（## Task 与 ## Expected result 两节都要有）。"
    }

    /** Bounce note when a DONE verdict cited no acceptance evidence. */
    fun bounceRubberStamp(acceptance: List<String>): String {
        val terms = acceptance.joinToString("、")
        return "你的 DONE 没有引用任何验收标准的原词，被判定为橡皮图章。" +
            "请重新复核后要么引用证据（$terms），要么发下一条修正指令。"
    }

    /** Final prompt when the worker-turn budget is exhausted. */
    fun capReached(objective: String, workerTurns: Int): String {
        return "目标：${objective.trim()}\n\nworker 回合预算已耗尽（$workerTurns 轮）。" +
            "请基于已有报告单独一行 DONE: <总结当前实际达成的部分>（引用验收标准原词）。这是收尾，不再有下一轮。"
    }

    /** Recovery intro folded into the first turn after an interrupted run. */
    fun recoveryIntro(record: SupervisorRunStore.RunRecord): String {
        val lastDigest = record.rounds.lastOrNull()?.reportDigest.orEmpty()
        return "上次运行被中断：已执行 ${record.workerTurns} 轮 worker 回合。" +
            "最后一份报告摘要：\n${lastDigest.trim().ifEmpty { "（无报告）" }}\n" +
            "请从中断处继续：先复核已完成的部分，再发下一条指令。"
    }

    /**
     * Digest of recent chat history for the first turn: `role: text` lines,
     * blank contents dropped, capped to [maxChars] keeping the TAIL (the most
     * recent context is what the supervisor needs).
     */
    fun contextDigest(messages: List<LLMMessage>, maxChars: Int): String {
        val lines = messages
            .filter { it.content.isNotBlank() }
            .joinToString("\n") { m -> "${m.role.value}: ${m.content.trim()}" }
        if (lines.length <= maxChars) return lines
        return lines.takeLast(maxChars)
    }
}
