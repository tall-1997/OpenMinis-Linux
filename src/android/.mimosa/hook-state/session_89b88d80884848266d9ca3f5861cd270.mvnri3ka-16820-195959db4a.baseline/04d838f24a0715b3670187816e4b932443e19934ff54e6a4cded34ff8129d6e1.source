package com.openminis.app.data.model

/**
 * 上下文长度与最大输出长度的取值顺序，每个字段独立：
 * 端点 /v1/models 实际返回的值 > models.dev 目录值 > 兜底常量。
 *
 * 之前目录值无条件覆盖端点值（ModelsDevApi.applyDevData），自定义端点明明
 * 返回了窗口大小也被目录盖掉。0 与负数视为「没返回」。
 */
object ModelParamPriority {

    fun resolve(
        endpointContext: Int?,
        endpointOutput: Int?,
        devContext: Int?,
        devOutput: Int?,
    ): Pair<Int, Int> = Pair(
        pick(endpointContext, devContext, UNKNOWN_CONTEXT_WINDOW),
        pick(endpointOutput, devOutput, UNKNOWN_MAX_OUTPUT),
    )

    private fun pick(endpoint: Int?, dev: Int?, fallback: Int): Int =
        endpoint?.takeIf { it > 0 } ?: dev?.takeIf { it > 0 } ?: fallback
}
