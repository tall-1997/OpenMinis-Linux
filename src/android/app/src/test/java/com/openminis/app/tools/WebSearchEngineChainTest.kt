package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [GH#298] The built-in engine chain must not request DuckDuckGo twice when the
 * user picked DuckDuckGo.
 *
 * `MinisApp.onCreate` registers [DuckDuckGoBackend] as the default backend, so
 * `execute()` already performs one DDG request before the engine chain. The
 * chain used to start with `preferred` unconditionally, so an empty/blocked
 * backend response triggered a SECOND identical DDG request. The planner now
 * skips DDG when — and only when — the default backend handled it.
 */
class WebSearchEngineChainTest {

    @Test
    fun `preferred DDG with the default backend handled makes the chain empty`() {
        val chain = WebSearchTool.buildEngineChain(
            preferred = WebSearchSettings.Engine.DDG,
            allowFallback = true,
            hasContext = true,
            configuredKeyed = listOf(WebSearchSettings.Engine.TAVILY),
            defaultBackendHandledPreferred = true,
        )

        assertTrue("DDG must not be requested a second time", chain.isEmpty())
    }

    @Test
    fun `preferred DDG still uses the built-in engine when the default backend is absent`() {
        val chain = WebSearchTool.buildEngineChain(
            preferred = WebSearchSettings.Engine.DDG,
            allowFallback = true,
            hasContext = true,
            configuredKeyed = emptyList(),
            defaultBackendHandledPreferred = false,
        )

        assertEquals(listOf(WebSearchSettings.Engine.DDG), chain)
    }

    @Test
    fun `preferred DDG never spends keyed quota on fallback`() {
        val chain = WebSearchTool.buildEngineChain(
            preferred = WebSearchSettings.Engine.DDG,
            allowFallback = true,
            hasContext = true,
            configuredKeyed = listOf(
                WebSearchSettings.Engine.TAVILY,
                WebSearchSettings.Engine.BOCHA,
            ),
            defaultBackendHandledPreferred = true,
        )

        assertTrue(chain.none { it.needsKey })
    }

    @Test
    fun `keyed preferred keeps configured engines then DDG exactly once`() {
        val chain = WebSearchTool.buildEngineChain(
            preferred = WebSearchSettings.Engine.TAVILY,
            allowFallback = true,
            hasContext = true,
            configuredKeyed = listOf(
                WebSearchSettings.Engine.TAVILY,
                WebSearchSettings.Engine.BOCHA,
            ),
            defaultBackendHandledPreferred = false,
        )

        assertEquals(
            listOf(
                WebSearchSettings.Engine.TAVILY,
                WebSearchSettings.Engine.BOCHA,
                WebSearchSettings.Engine.DDG,
            ),
            chain,
        )
        assertEquals(1, chain.count { it == WebSearchSettings.Engine.DDG })
    }

    @Test
    fun `no context or fallback disabled keeps only the preferred engine`() {
        assertEquals(
            listOf(WebSearchSettings.Engine.TAVILY),
            WebSearchTool.buildEngineChain(
                preferred = WebSearchSettings.Engine.TAVILY,
                allowFallback = false,
                hasContext = true,
                configuredKeyed = listOf(WebSearchSettings.Engine.TAVILY),
                defaultBackendHandledPreferred = false,
            ),
        )
        assertEquals(
            listOf(WebSearchSettings.Engine.TAVILY),
            WebSearchTool.buildEngineChain(
                preferred = WebSearchSettings.Engine.TAVILY,
                allowFallback = true,
                hasContext = false,
                configuredKeyed = listOf(WebSearchSettings.Engine.TAVILY),
                defaultBackendHandledPreferred = false,
            ),
        )
    }
}
