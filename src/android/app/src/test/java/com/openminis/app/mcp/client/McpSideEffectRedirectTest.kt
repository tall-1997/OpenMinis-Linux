package com.openminis.app.mcp.client

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-mcp-side-effect-redirect] A `tools/call` POST is the one request that is
 * never replayed. A 3xx therefore proves the tool never ran, and must reach the
 * receipt ledger as "not sent" instead of the generic SERVER_ERROR that used to
 * mark it `serverAnswered` (i.e. delivered).
 */
class McpSideEffectRedirectTest {

    @Test
    fun `every redirect on a side-effecting call is a proven not sent`() {
        for (code in listOf(301, 302, 303, 307, 308)) {
            assertEquals("HTTP $code", SideEffectRedirect.NOT_SENT, sideEffectRedirect(code))
        }
    }

    @Test
    fun `ordinary responses keep their own handling`() {
        for (code in listOf(200, 202, 204, 401, 403, 500, 503)) {
            assertEquals("HTTP $code", SideEffectRedirect.NONE, sideEffectRedirect(code))
        }
    }
}
