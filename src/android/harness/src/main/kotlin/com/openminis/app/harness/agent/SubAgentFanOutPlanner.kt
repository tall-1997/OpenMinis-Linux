package com.openminis.app.harness.agent

/**
 * [T-android-seam-extraction] 循环片段：子代理并行派发的**规划**半边。
 *
 * 主循环的派发契约：一轮工具调用里，**首个** spawn 工具触发整批扇出——
 * 本轮所有 spawn 调用并行派发（Semaphore 限并发），且一轮只扇出一次
 * （`didFanOutSubAgents` 语义）。写者计数（canWrite 的 spawn 数）供
 * WriteLease 预算与「worker 并行须声明 write_paths」判定。
 *
 * 纯函数：判定与选择在这里，执行（executeRunSubAgent、Semaphore、
 * viewModelScope、块投影）永久留宿主——接缝二边界。宿主把批次解析
 * （parseSubAgentBatch）与 kind 判定（SubAgentKind.canWrite）以函数
 * 注入，harness 不攥 app/tools 的类型。
 */
object SubAgentFanOutPlanner {

    /** 一条待派发的 spawn 调用（id/name/argsJson + 波内序号）。 */
    data class PeerCall(
        val toolCallId: String,
        val toolName: String,
        val argsJson: String,
        val waveIndex: Int,
        val waveSize: Int,
    )

    /**
     * @param fanOut true → 本轮含 spawn 调用，宿主应在**首个** spawn 工具
     *   到达派发点时并行派发 [peers] 全体；false → 无 spawn，顺序执行。
     * @param peers 本轮全部 spawn 调用（保持 toolCalls 顺序），波内序号
     *   与波规模已填好。
     * @param writerCount 各批次中可写（worker/general）spawn 的总数——
     *   WriteLease 预算与 requiresWritePaths 判定的输入。
     */
    data class Plan(
        val fanOut: Boolean,
        val peers: List<PeerCall>,
        val writerCount: Int,
    )

    /**
     * 规划一轮的子代理扇出。
     *
     * @param toolCalls 本轮全部已完成工具调用（id, name, argsJson）。
     * @param isSpawnTool spawn 工具名判定（SubAgentKind::isSpawnTool）。
     * @param batchWriterCount argsJson → 该批次内可写 spawn 数（宿主解析
     *   批次后按 kind 统计）。
     */
    fun plan(
        toolCalls: List<Triple<String, String, String>>,
        isSpawnTool: (String) -> Boolean,
        batchWriterCount: (String) -> Int,
    ): Plan {
        val peersRaw = toolCalls.filter { isSpawnTool(it.second) }
        if (peersRaw.isEmpty()) return Plan(fanOut = false, peers = emptyList(), writerCount = 0)
        val size = peersRaw.size
        val peers = peersRaw.mapIndexed { index, (id, name, argsJson) ->
            PeerCall(
                toolCallId = id,
                toolName = name,
                argsJson = argsJson,
                waveIndex = index,
                waveSize = size,
            )
        }
        val writerCount = peersRaw.sumOf { (_, _, argsJson) -> batchWriterCount(argsJson) }
        return Plan(fanOut = true, peers = peers, writerCount = writerCount)
    }
}
