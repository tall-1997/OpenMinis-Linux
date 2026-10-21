package com.openminis.app.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 上下文长度与最大输出的取值顺序：端点 /v1/models 返回的参数 >
 * models.dev 目录 > 兜底（上下文 256k，输出 128k）。每个字段独立取值。
 */
class ModelParamPriorityTest {

    @Test
    fun `endpoint values beat the catalog`() {
        val (ctx, out) = ModelParamPriority.resolve(
            endpointContext = 1_000_000, endpointOutput = 64_000,
            devContext = 128_000, devOutput = 8_192,
        )
        assertEquals(1_000_000, ctx)
        assertEquals(64_000, out)
    }

    @Test
    fun `catalog fills what the endpoint left out`() {
        val (ctx, out) = ModelParamPriority.resolve(
            endpointContext = null, endpointOutput = 32_000,
            devContext = 200_000, devOutput = 8_192,
        )
        assertEquals(200_000, ctx)
        assertEquals(32_000, out)
    }

    @Test
    fun `fallback applies per field when both sources are silent`() {
        val (ctx, out) = ModelParamPriority.resolve(
            endpointContext = null, endpointOutput = null,
            devContext = null, devOutput = null,
        )
        // 兜底：上下文 256k，输出 128k。常量是 internal，测试直接断言数值。
        assertEquals(256_000, ctx)
        assertEquals(128_000, out)
    }

    @Test
    fun `zero or negative values count as missing`() {
        val (ctx, out) = ModelParamPriority.resolve(
            endpointContext = 0, endpointOutput = -1,
            devContext = 0, devOutput = null,
        )
        assertEquals(256_000, ctx)
        assertEquals(128_000, out)
    }
}
