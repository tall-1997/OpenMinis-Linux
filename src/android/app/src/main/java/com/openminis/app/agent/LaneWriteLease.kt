package com.openminis.app.agent

import com.openminis.app.harness.subagent.LeaseVerdict
import com.openminis.app.harness.subagent.WriteLease
import com.openminis.app.harness.subagent.WriteLeaseGate
import com.openminis.app.tools.ToolExecutionResult
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * [T-android-seam-extraction] 批次三接缝二：lane 写租约的宿主映射。
 *
 * 把宿主工具名 / 参数 JSON 翻译成 harness [WriteLeaseGate] 的语言，并在派发层
 * 给出**可解释的拒绝**（[deniedResult]）——lane 的工具执行 lambda 在真正执行前
 * 问一次，被拒的调用仍会进 2.3 的轨迹清单（「被拒的写」正是 files 条目不该被
 * 背书的证据），裁定段里看得见。
 *
 * 与 WritePathGuard 的分工：Guard 是工具落盘前的最后一道 ThreadLocal 前缀闸
 * （纵深防御，防 lambda 之外的写入路径）；租约是派发层的语义闸，负责给模型
 * 解释「为什么」与「替代动作」。两者共用同一份 write_paths 声明。
 */
object LaneWriteLease {

    private val json = Json { ignoreUnknownKeys = true }

    /** 租约拒绝时返回带文案的失败结果；放行返回 null（调用方继续执行）。 */
    fun deniedResult(writePaths: List<String>, toolName: String, argsJson: String): ToolExecutionResult? {
        val tool = SubagentClaimBridge.harnessToolFor(toolName)
        if (WriteLeaseGate.pathKey(tool) == null) return null
        val args = runCatching { json.parseToJsonElement(argsJson) as? JsonObject }.getOrNull()
            ?: JsonObject(emptyMap())
        val verdict = WriteLeaseGate.check(WriteLease(writePaths), tool, toolName, args)
        val reason = (verdict as? LeaseVerdict.Denied)?.reason ?: return null
        return ToolExecutionResult(reason, false, toolTitle = toolName)
    }
}
