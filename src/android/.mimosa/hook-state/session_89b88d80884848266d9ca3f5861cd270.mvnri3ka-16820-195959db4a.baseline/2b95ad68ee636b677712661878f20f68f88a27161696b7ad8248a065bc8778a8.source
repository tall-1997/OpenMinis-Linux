package com.openminis.app.tools

import com.openminis.app.data.model.AgentContentPart
import com.openminis.app.data.model.AgentToolDefinition
import com.openminis.app.data.model.LLMMessage
import com.openminis.app.data.model.LLMStreamChunk
import com.openminis.app.data.model.ThinkingLevel
import com.openminis.app.provider.LLMProvider
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject

/**
 * Drives [DiscussionGraph]. Roles speak only on their edge. Critics run in
 * parallel and do not see each other. An objection selects at most one
 * clarification and one architect revise; objectors then revote once.
 */
object PlanDiscussionOrchestrator {
    private const val MAX_TOOL_TURNS = 3

    data class Member(
        val displayName: String,
        val stance: String,
        val provider: LLMProvider,
        val maxTokens: Int,
        val temperature: Double? = null,
        val role: String = "",
        val thinkingLevel: ThinkingLevel = ThinkingLevel.ULTRA,
    )

    data class Result(
        val markdown: String,
        val transcript: String,
        val contract: String,
    )

    suspend fun run(
        userText: String,
        conversationExcerpt: String,
        main: Member,
        members: List<Member>,
        tools: List<AgentToolDefinition>,
        executeTool: suspend (String, String) -> ToolExecutionResult,
        onProgress: suspend (String) -> Unit = {},
    ): Result {
        val seats = DiscussionGraph.staff(userText, members.size)
        val statements = mutableListOf<DiscussionGraph.Statement>()
        val toolGate = Mutex()
        val guarded: suspend (String, String) -> ToolExecutionResult = { name, json ->
            val denied = DiscussionGraph.denyExecution(name, json)
            if (denied != null) {
                ToolExecutionResult(denied, false)
            } else {
                toolGate.withLock { executeTool(name, json) }
            }
        }

        suspend fun push(status: String) {
            onProgress(
                liveMarkdown(
                    status,
                    userText,
                    DiscussionGraph.renderBoard(userText, conversationExcerpt, statements),
                ),
            )
        }

        suspend fun say(
            seat: DiscussionGraph.Seat,
            phase: DiscussionGraph.Phase,
            instruction: String,
            withTools: Boolean,
        ): DiscussionGraph.Statement {
            val member = memberFor(seat, main, members)
            push("${seat.role}：${DiscussionGraph.phaseHeading(phase)}")
            val raw = speak(
                member = member,
                tools = if (withTools) DiscussionGraph.allowedTools(seat.role, tools) else emptyList(),
                executeTool = guarded,
                system = DiscussionGraph.roleSystem(seat.role),
                user = instruction + "\n\n" + DiscussionGraph.renderBoard(userText, conversationExcerpt, statements),
            )
            val statement = DiscussionGraph.parseStatement(seat.role, phase, raw)
            statements += statement
            return statement
        }

        say(
            seat = DiscussionGraph.seat(seats, "产品经理"),
            phase = DiscussionGraph.Phase.BRIEF,
            instruction = "写简报，不要设计实现。用这些标题：【目标】【非目标】【验收】【必须】【可砍】【未知】。",
            withTools = false,
        )
        say(
            seat = DiscussionGraph.seat(seats, "架构师"),
            phase = DiscussionGraph.Phase.DESIGN,
            instruction = "根据简报给一个可执行方案。用这些标题：【方案】【放弃】【边界】【失败恢复】。可以只读查看仓库。不要开始实现。",
            withTools = true,
        )

        val critics = DiscussionGraph.critics(seats)
        push(critics.joinToString("、") { it.role } + "并行审查")
        val critiqueBoard = DiscussionGraph.renderBoard(userText, conversationExcerpt, statements)
        val critiques = speakAll(critics) { seat ->
            speak(
                member = memberFor(seat, main, members),
                tools = DiscussionGraph.allowedTools(seat.role, tools),
                executeTool = guarded,
                system = DiscussionGraph.roleSystem(seat.role),
                user = "独立审查方案。不要附和。必须以 VERDICT / OBJECTION / ASK 三行结束。\n\n" +
                    critiqueBoard,
            )
        }
        critics.zip(critiques).forEach { (seat, raw) ->
            statements += DiscussionGraph.parseStatement(seat.role, DiscussionGraph.Phase.CRITIQUE, raw)
        }

        val objections = DiscussionGraph.openObjections(statements)
        if (objections.isNotEmpty()) {
            val clarify = DiscussionGraph.clarificationRole(seats, objections)
            if (clarify != null) {
                say(
                    seat = DiscussionGraph.seat(seats, clarify),
                    phase = DiscussionGraph.Phase.CLARIFY,
                    instruction = "只回答点名给你的异议，不要重写方案。\n\n" + formatObjections(objections),
                    withTools = false,
                )
            }
            say(
                seat = DiscussionGraph.seat(seats, "架构师"),
                phase = DiscussionGraph.Phase.REVISE,
                instruction = "只修订下面这些未关闭异议。每条写采纳或不采纳以及理由。不要开始实现。\n\n" +
                    formatObjections(objections),
                withTools = true,
            )
            val objectors = DiscussionGraph.objectorSeats(seats, objections)
            push(objectors.joinToString("、") { it.role } + "复审")
            val boardForRevote = DiscussionGraph.renderBoard(userText, conversationExcerpt, statements)
            val revotes = speakAll(objectors) { seat ->
                speak(
                    member = memberFor(seat, main, members),
                    tools = DiscussionGraph.allowedTools(seat.role, tools),
                    executeTool = guarded,
                    system = DiscussionGraph.roleSystem(seat.role),
                    user = "复审修订。只判断你自己的异议是否关闭。必须以 VERDICT / OBJECTION / ASK 三行结束。\n\n" +
                        boardForRevote,
                )
            }
            objectors.zip(revotes).forEach { (seat, raw) ->
                statements += DiscussionGraph.parseStatement(seat.role, DiscussionGraph.Phase.REVOTE, raw)
            }
        }

        push("秘书助理写执行契约")
        val open = DiscussionGraph.openObjections(statements)
        val brief = statements.firstOrNull { it.phase == DiscussionGraph.Phase.BRIEF }?.body.orEmpty()
        val design = statements.lastOrNull {
            it.phase == DiscussionGraph.Phase.REVISE || it.phase == DiscussionGraph.Phase.DESIGN
        }?.body.orEmpty()
        val fallback = DiscussionGraph.fallbackContract(brief, design, open)
        val minutes = speak(
            member = memberFor(DiscussionGraph.seat(seats, "秘书助理"), main, members),
            tools = emptyList(),
            executeTool = guarded,
            system = DiscussionGraph.roleSystem("秘书助理"),
            user = "把讨论收成一份执行契约。必须包含【已定】【未采纳】【任务】【风险】【停止条件】。" +
                "未关闭的异议写入【未采纳】，不要假装已经同意。不要开始实现。\n\n" +
                DiscussionGraph.renderBoard(userText, conversationExcerpt, statements) +
                "\n\n未关闭异议:\n" + formatObjections(open),
        )
        val contract = DiscussionGraph.extractContract(minutes, fallback)
        statements += DiscussionGraph.parseStatement("秘书助理", DiscussionGraph.Phase.MINUTES, minutes)
        val board = DiscussionGraph.renderBoard(userText, conversationExcerpt, statements)
        return Result(
            markdown = DiscussionGraph.discussionMarkdown(board, contract),
            transcript = board,
            contract = contract,
        )
    }

    fun discussionMarkdown(board: String): String =
        DiscussionGraph.discussionMarkdown(board, board.substringAfter("【已定】", board).trim())

    fun liveMarkdown(status: String, userText: String, board: String): String = buildString {
        appendLine("## 计划讨论（进行中）")
        appendLine()
        appendLine("**状态：** $status")
        appendLine()
        appendLine("**任务：** ${userText.trim().take(500)}")
        appendLine()
        if (board.isNotBlank()) appendLine(board.takeLast(12_000))
    }

    private fun memberFor(seat: DiscussionGraph.Seat, main: Member, members: List<Member>): Member {
        val base = if (seat.providerIndex < 0 || members.isEmpty()) main
        else members[seat.providerIndex % members.size]
        val model = base.displayName.substringBefore(" · ").trim().ifBlank { base.displayName }
        return base.copy(
            displayName = "${seat.role} · $model",
            stance = seat.role,
            role = seat.role,
        )
    }

    private suspend fun speakAll(
        seats: List<DiscussionGraph.Seat>,
        block: suspend (DiscussionGraph.Seat) -> String,
    ): List<String> = supervisorScope {
        seats.map { seat -> async { block(seat) } }.awaitAll()
    }

    private fun formatObjections(items: List<DiscussionGraph.Statement>): String {
        if (items.isEmpty()) return "无"
        return items.joinToString("\n") { "- ${it.role}：${it.objection.ifBlank { it.body.take(400) }}" }
    }

    private suspend fun speak(
        member: Member,
        tools: List<AgentToolDefinition>,
        executeTool: suspend (String, String) -> ToolExecutionResult,
        system: String,
        user: String,
    ): String {
        val history = mutableListOf(
            LLMMessage(role = LLMMessage.Role.USER, content = user),
        )
        val report = StringBuilder()
        try {
            repeat(MAX_TOOL_TURNS) {
                val textSb = StringBuilder()
                val toolCalls = mutableListOf<Triple<String, String, JSONObject>>()
                member.provider.streamMessage(
                    messages = history,
                    systemPrompt = system,
                    maxTokens = member.maxTokens.coerceIn(256, 8192),
                    temperature = member.temperature,
                    tools = tools,
                    thinkingLevel = member.thinkingLevel,
                ).collect { chunk ->
                    when (chunk) {
                        is LLMStreamChunk.Text -> textSb.append(chunk.text)
                        is LLMStreamChunk.ToolCallComplete ->
                            toolCalls.add(Triple(chunk.id, chunk.name, chunk.args))
                        else -> Unit
                    }
                }
                val text = textSb.toString().trim()
                if (text.isNotEmpty()) {
                    if (report.isNotEmpty()) report.append("\n\n")
                    report.append(text)
                }
                if (toolCalls.isEmpty() || tools.isEmpty()) {
                    return report.toString().ifBlank { "(无发言)" }
                }
                val assistantParts = mutableListOf<AgentContentPart>()
                if (text.isNotEmpty()) assistantParts.add(AgentContentPart.Text(text))
                for ((id, name, args) in toolCalls) {
                    assistantParts.add(AgentContentPart.ToolUse(id, name, input = args))
                }
                history.add(
                    LLMMessage(
                        role = LLMMessage.Role.ASSISTANT,
                        content = text,
                        contentParts = assistantParts,
                    ),
                )
                val resultParts = mutableListOf<AgentContentPart>()
                for ((id, name, args) in toolCalls) {
                    val result = try {
                        executeTool(name, args.toString())
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        ToolExecutionResult("Error: ${e.message ?: e.javaClass.simpleName}", false)
                    }
                    resultParts.add(
                        AgentContentPart.ToolResult(
                            id = id,
                            name = name,
                            content = result.output,
                            isError = !result.success,
                        ),
                    )
                }
                history.add(
                    LLMMessage(
                        role = LLMMessage.Role.USER,
                        content = "",
                        contentParts = resultParts,
                    ),
                )
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (report.isNotEmpty()) report.append("\n\n")
            report.append("(").append(member.displayName).append(" failed: ")
                .append(e.message ?: e.javaClass.simpleName).append(")")
        }
        return report.toString().trim().ifBlank { "(无发言)" }
    }
}
