package com.openminis.app.agent

import com.openminis.app.harness.HarnessMessage
import com.openminis.app.harness.HarnessTool
import com.openminis.app.harness.ToolCall
import com.openminis.app.harness.ToolResult
import com.openminis.app.harness.subagent.SubagentClaimAdjudication
import com.openminis.app.harness.subagent.adjudicateSubagentClaim
import com.openminis.app.harness.subagent.extractSubagentReceipts
import com.openminis.app.harness.subagent.parseSubagentClaim
import com.openminis.app.harness.subagent.renderSubagentClaimAdjudication
import com.openminis.app.harness.subagent.stripSubagentClaimBlock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * [T-subagent-claim-seam] P0-2 的解锁接缝：host 裁定不依赖 SubagentOrchestrator /
 * ToolExecutor（上游闭包 41x 的来源），只依赖一份**轨迹清单**。
 *
 * 子代理 lane 的执行器（无论将来是现在的 runOneSubAgentInner 还是批次三的
 * 编排器）只需在收尾时交出 [LaneToolReceipt] 列表——工具名、参数 JSON、是否
 * 成功、调用 id——本桥负责把它投影成 harness 消息视图并跑完
 * 解析 → 凭据提取 → 裁定 → 渲染 全链路。执行器因此永远不需要认识裁定器，
 * 裁定器也永远不需要认识执行器：2.3 的接线只剩「在 recordLaneOutcome 前把
 * 轨迹递进来」一步。
 *
 * fail-open 语义原样保留：结论文没有合法 claim 块时返回 null，lane 报告与
 * 今天完全一致；有 claim 块才启用裁定。
 */
object SubagentClaimBridge {

    private val json = Json { ignoreUnknownKeys = true }

    /** lane 里一次工具调用的最小凭据记录（执行器收尾时交出）。 */
    data class LaneToolReceipt(
        val callId: String,
        val toolName: String,
        val argsJson: String,
        val success: Boolean,
    )

    /** 裁定结果 + 剔除协议块后的干净报告 + 供父汇总引用的裁定段。 */
    data class LaneAdjudication(
        val cleanReport: String,
        val adjudicationSection: String,
        val adjudicatedStatus: String,
        val claimedStatus: String,
        val downgraded: Boolean,
        val adjudication: SubagentClaimAdjudication,
    )

    /** 宿主工具名 → harness 工具枚举；只影响凭据提取的归类。 */
    internal fun harnessToolFor(name: String): HarnessTool = when (name) {
        "shell_execute", "shell_exec", "env_exec" -> HarnessTool.BASE
        "file_write" -> HarnessTool.WRITE
        "file_edit", "multi_edit" -> HarnessTool.EDIT
        "download", "web_fetch" -> HarnessTool.DOWNLOAD
        else -> HarnessTool.BASE
    }

    /** 轨迹清单 → harness 消息视图（调用/结果成对，结果 id 加后缀避撞）。 */
    fun transcriptOf(receipts: List<LaneToolReceipt>): List<HarnessMessage> = receipts.flatMap { receipt ->
        val args = runCatching { json.parseToJsonElement(receipt.argsJson) as? JsonObject }
            .getOrNull() ?: JsonObject(emptyMap())
        listOf(
            ToolCall(
                id = receipt.callId,
                createdAt = 0L,
                tool = harnessToolFor(receipt.toolName),
                args = args,
                rawToolName = receipt.toolName,
            ),
            ToolResult(
                id = receipt.callId + ":result",
                createdAt = 0L,
                toolCallId = receipt.callId,
                success = receipt.success,
                output = "",
            ),
        )
    }

    /**
     * lane 工具调用的收口包装：执行原 lambda 并把（调用 id / 工具名 / 参数 / 成败）
     * 记进轨迹清单。被拦下的调用（kind/role/type 黑名单）也记——host 凭据要反映
     * lane 真实尝试过什么，「被拒的写」正是 files 条目不该被背书的证据。
     */
    suspend fun laneToolCall(
        sink: MutableList<LaneToolReceipt>,
        name: String,
        argsJson: String,
        block: suspend () -> com.openminis.app.tools.ToolExecutionResult,
    ): com.openminis.app.tools.ToolExecutionResult {
        val result = block()
        sink += LaneToolReceipt(
            callId = "lane-${sink.size + 1}",
            toolName = name,
            argsJson = argsJson,
            success = result.success,
        )
        return result
    }

    /**
     * 把裁定结果折进 lane 报告：无合法 claim 块原样返回（fail-open）；有则换成
     * 剔除协议块的干净报告 + 父汇总裁定段。父模型经 spawn_agent 返回值或
     * check_agent collect 读到的就是这份，原始 claim JSON 不进父上下文。
     */
    fun adjudicatedReport(reportText: String, receipts: List<LaneToolReceipt>): String {
        val adjudication = adjudicateLaneReport(reportText, receipts) ?: return reportText
        return adjudication.cleanReport + "\n\n" + adjudication.adjudicationSection
    }

    /** [adjudicatedReport] 的 ToolExecutionResult 形态；outcome 为 null 原样返回。 */
    fun adjudicateLaneOutcome(
        outcome: com.openminis.app.tools.ToolExecutionResult?,
        receipts: List<LaneToolReceipt>,
    ): com.openminis.app.tools.ToolExecutionResult? =
        outcome?.copy(output = adjudicatedReport(outcome.output, receipts))

    /**
     * 一站式裁定。无合法 claim 块返回 null（调用方保持旧行为）。
     * 返回的 [LaneAdjudication.cleanReport] 已剔除协议块——原始 JSON 不进父上下文。
     */
    fun adjudicateLaneReport(reportText: String, receipts: List<LaneToolReceipt>): LaneAdjudication? {
        val claim = parseSubagentClaim(reportText) ?: return null
        val adjudication = adjudicateSubagentClaim(claim, extractSubagentReceipts(transcriptOf(receipts)))
        return LaneAdjudication(
            cleanReport = stripSubagentClaimBlock(reportText, claim),
            adjudicationSection = renderSubagentClaimAdjudication(adjudication),
            adjudicatedStatus = adjudication.adjudicatedStatus,
            claimedStatus = adjudication.claimedStatus,
            downgraded = adjudication.downgraded,
            adjudication = adjudication,
        )
    }
}
