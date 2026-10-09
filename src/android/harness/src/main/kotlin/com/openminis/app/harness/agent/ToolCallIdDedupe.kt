package com.openminis.app.harness.agent

import com.openminis.app.harness.HarnessLog

/**
 * [T-android-seam-extraction] 循环片段三：tool_call_id 去重状态机。
 *
 * [T-dedupe-toolcallid 03fbcbfd] 有些 OpenAI 兼容网关会在同一条流里发多个
 * **同 id 不同 name/args** 的并行 tool_calls；原样回传会撞接收端的唯一性校验
 * （HTTP 400 "duplicate tool_call_id"）。镜像 iOS 修法：首个出现保留原 id，
 * 第二个变 "<id>-2"，第三个 "<id>-3"。
 *
 * 三份状态缺一不可：Android 按 chunk.id 路由 ToolInputDelta（iOS 按 name），
 * 而 OpenAI 在 finish_reason 后把所有 complete **一起**发——complete 到达时
 * 不能清掉「当前在飞」映射。Start/complete 的出现顺序天然对齐（OpenAI 按
 * index 序发 start，complete 同序）。
 *
 * 纯逻辑，无 Android 依赖；日志走 HarnessLog。
 */
class ToolCallIdDedupe {

    private val startCounts = mutableMapOf<String, Int>()
    private val completeCounts = mutableMapOf<String, Int>()
    private val inFlightRenamedId = mutableMapOf<String, String>()

    /** ToolUseStart 到达：计数并按出现序改名（首个保留原 id）。 */
    fun startId(raw: String): String {
        val n = (startCounts[raw] ?: 0) + 1
        startCounts[raw] = n
        val renamed = if (n == 1) raw else "$raw-$n"
        if (n > 1) {
            HarnessLog.w("[ToolDedupe] duplicate tool_call id on stream start: '$raw' #$n -> renamed '$renamed'")
        }
        inFlightRenamedId[raw] = renamed
        return renamed
    }

    /** ToolInputDelta 到达：路由到当前在飞的重命名 id（无映射=未见过 start，原样）。 */
    fun inputId(raw: String): String = inFlightRenamedId[raw] ?: raw

    /** ToolCallComplete 到达：独立于 start 计数（网关重放 complete 时同样要去重）。 */
    fun completeId(raw: String): String {
        val n = (completeCounts[raw] ?: 0) + 1
        completeCounts[raw] = n
        return if (n == 1) raw else "$raw-$n"
    }
}
