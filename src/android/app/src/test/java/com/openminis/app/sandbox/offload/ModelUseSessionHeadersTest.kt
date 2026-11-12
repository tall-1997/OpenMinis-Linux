package com.openminis.app.sandbox.offload

import com.openminis.app.data.model.ProviderCredential
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.model.ProviderType
import com.openminis.app.provider.OpenCodeSessionHeader
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [F2 GH#347] `model_use` 的会话头组合回归。
 *
 * 中文：这是本轮复现的 400 路径 —— 真实 key + 官方 `*.opencode.ai` base URL，
 * 经 `minis-model-use run` 时曾经只走 `customEndpointHeadersFor`（默认关闭），
 * 请求不带 `x-opencode-session`。现在 [modelUseSessionHeaders] 与聊天主循环
 * 共用 [OpenCodeSessionHeader.sessionHeaders]，官方 host 兜底必须命中；同时
 * 自定义 relay 仍然必须显式 opt-in，不能因为这次修复泄漏标识符。
 */
class ModelUseSessionHeadersTest {

    private val sid = "123e4567-e89b-12d3-a456-426614174000"

    private fun instance(baseUrl: String?) = ProviderInstance(
        id = "inst-model-use",
        label = "OpenCode",
        providerType = ProviderType.openAI,
        credentialType = ProviderCredential.apiKey,
        customBaseURL = baseUrl,
    )

    @Test
    fun `official key through model_use keeps the session header with the switch off`() {
        // Real API-key instance pointing at the official endpoint: the service
        // hard-requires the header, so the opt-in switch must not suppress it.
        val headers = modelUseSessionHeaders(
            instance("https://opencode.ai/zen/go/v1"),
            sid,
            enabled = false,
        )
        assertEquals(sid, headers[OpenCodeSessionHeader.HEADER])
    }

    @Test
    fun `custom endpoint with the switch off gets no identifier`() {
        val headers = modelUseSessionHeaders(
            instance("https://relay.example.com/v1"),
            sid,
            enabled = false,
        )
        assertTrue("third-party relay must stay opt-in, got $headers", headers.isEmpty())
    }

    @Test
    fun `custom endpoint opted in receives both header spellings`() {
        val headers = modelUseSessionHeaders(
            instance("https://relay.example.com/v1"),
            sid,
            enabled = true,
        )
        assertEquals(sid, headers[OpenCodeSessionHeader.HEADER])
        assertEquals(sid, headers["x-opencode-session-id"])
    }

    @Test
    fun `draft session ids never leave through model_use`() {
        assertTrue(
            modelUseSessionHeaders(
                instance("https://opencode.ai/zen/go/v1"),
                "__new__draft",
                enabled = true,
            ).isEmpty(),
        )
    }

    @Test
    fun `no custom base url has no host to trust`() {
        // Without a host the helper cannot prove the endpoint is OpenCode, so the
        // privacy-preserving answer is "send nothing".
        assertTrue(modelUseSessionHeaders(instance(null), sid, enabled = true).isEmpty())
    }
}
