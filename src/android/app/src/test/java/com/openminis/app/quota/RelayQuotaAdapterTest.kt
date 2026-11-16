package com.openminis.app.quota

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-relay-newapi-user-quota][T-relay-probe-transport] Field regression for
 * the free New API relay (2026-11-16): the balance query errored instead of
 * showing the wallet. Two defects, each pinned here:
 *
 * 1. **Probe chain aborts on a transport failure.** The first probe family
 *    (`/v1/usage`) can die at the connection level while the New API and
 *    billing endpoints answer 200; the old loop caught only
 *    [QuotaFetchError], so a raw IOException aborted the whole fetch before
 *    any working endpoint was tried. The chain must record and continue.
 * 2. **The account wallet was never read.** Newer New API deployments answer
 *    `/api/usage/token` with BOTH token-level totals AND the account wallet
 *    (`data.user_quota.total_available`, in the relay's quota units). Tokens
 *    are frequently created unlimited with zero totals, so the old parse
 *    reported "unlimited / 0" while the user's real balance lived one level
 *    down. Prefer the wallet when present.
 *
 * Fixture bodies are captured verbatim from the affected relay.
 */
class RelayQuotaAdapterTest {

    private val placeholderCredential = buildString { repeat(8) { append('x') } }

    // Captured 2026-11-16: token unlimited / zero totals; the account wallet
    // carries the real balance in the relay's quota units.
    private val newApiUserQuotaBody =
        (
            "{\"code\":true,\"data\":{\"expires_at\":0,\"model_limits\":{}," +
                "\"model_limits_enabled\":false,\"name\":\"free\",\"object\":\"token_usage\"," +
                "\"total_available\":0,\"total_granted\":0,\"total_used\":0,\"unlimited_quota\":true," +
                "\"user_quota\":{\"total_available\":250000,\"total_used\":0}},\"message\":\"ok\"}"
            ).toByteArray()

    private val newApiTokenLevelBody =
        (
            "{\"code\":true,\"data\":{\"name\":\"free\",\"object\":\"token_usage\"," +
                "\"total_available\":800000,\"total_granted\":1000000,\"total_used\":200000," +
                "\"unlimited_quota\":false},\"message\":\"ok\"}"
            ).toByteArray()

    private fun descriptor(base: String = "https://relay.example/v1") = QuotaInstanceDescriptor(
        instanceId = "inst-test",
        label = "test relay",
        providerKind = QuotaInstanceDescriptor.ProviderKind.openAI,
        isOAuth = false,
        isEnabled = true,
        customBaseURL = base,
    )

    private fun subject() = QuotaSubjectID(QuotaVendor.customRelay, "acct")

    private fun ctx(
        transport: QuotaHttpTransport,
        base: String = "https://relay.example/v1",
        hint: RelayKind? = null,
    ) = QuotaFetchContext(
        descriptor = descriptor(base),
        subjectId = subject(),
        credential = placeholderCredential,
        transport = transport,
        now = 1_000_000L,
        relayKindHint = hint,
    )

    /**
     * Scripted relay: per-family status (default all-404 so every probe
     * fails), an optional connection-level throw for /v1/usage, and an
     * optional throw for /api/usage/token (used by the hint-rethrow test —
     * with a hint only THAT endpoint is probed). The billing usage endpoint
     * always 200s with a zero total.
     */
    private fun fakeRelay(
        usageStatus: Int = 404,
        tokenStatus: Int = 404,
        billingStatus: Int = 404,
        tokenBody: ByteArray = newApiUserQuotaBody,
        failUsageTransport: Boolean = false,
        failTokenTransport: Boolean = false,
    ): QuotaHttpTransport = object : QuotaHttpTransport {
        override suspend fun get(url: String, bearer: String, headers: Map<String, String>): QuotaHttpResponse {
            if (failUsageTransport && url.endsWith("/v1/usage")) throw java.io.IOException("connection reset")
            if (failTokenTransport && url.endsWith("/api/usage/token")) throw java.io.IOException("token path reset")
            val status = when {
                url.endsWith("/v1/usage") -> usageStatus
                url.endsWith("/api/usage/token") -> tokenStatus
                url.endsWith("/v1/dashboard/billing/subscription") -> billingStatus
                url.endsWith("/v1/dashboard/billing/usage") -> 200
                else -> 404
            }
            val body = when {
                url.endsWith("/api/usage/token") -> tokenBody
                url.endsWith("/v1/dashboard/billing/subscription") ->
                    "{\"object\":\"billing_subscription\",\"hard_limit_usd\":100000000}".toByteArray()
                url.endsWith("/v1/dashboard/billing/usage") -> "{\"object\":\"list\",\"total_usage\":0}".toByteArray()
                else -> "{\"error\":\"not found\"}".toByteArray()
            }
            return QuotaHttpResponse(status, body, emptyMap())
        }
    }

    private fun snapshotPool(outcome: QuotaFetchOutcome) = outcome.snapshot.pools.single()

    // ------------------------------------------------ parseNewAPI shapes ----

    @Test
    fun `account wallet wins over unlimited token totals`() {
        val snap = RelayQuotaAdapter.parseNewAPI(newApiUserQuotaBody, subject(), "relay.example", 1_000_000L)
        val pool = snap.pools.single()
        // The token reads unlimited / zero; the wallet is what the user pays with.
        assertFalse("wallet presence must clear the token-level unlimited flag", pool.unlimited)
        assertEquals(0, pool.amounts?.remaining!!.compareTo(250000.toBigDecimal()))
        assertEquals(0, pool.amounts?.used!!.compareTo(0.toBigDecimal()))
        assertNull(pool.amounts?.total)
        assertTrue("label must mark the wallet source", pool.vendorLabel?.contains("account") == true)
    }

    @Test
    fun `token-level shape without user_quota keeps the legacy parse`() {
        val snap = RelayQuotaAdapter.parseNewAPI(newApiTokenLevelBody, subject(), "relay.example", 1_000_000L)
        val pool = snap.pools.single()
        assertFalse(pool.unlimited)
        assertEquals(0, pool.amounts?.total!!.compareTo(1000000.toBigDecimal()))
        assertEquals(0, pool.amounts?.used!!.compareTo(200000.toBigDecimal()))
        assertEquals(0, pool.amounts?.remaining!!.compareTo(800000.toBigDecimal()))
        assertFalse("token-level shape must not claim the wallet", pool.vendorLabel?.contains("account") == true)
    }

    @Test
    fun `unlimited token without user_quota stays unlimited`() {
        val body = (
            "{\"data\":{\"name\":\"free\",\"total_available\":0,\"total_granted\":0," +
                "\"total_used\":0,\"unlimited_quota\":true}}"
            ).toByteArray()
        val snap = RelayQuotaAdapter.parseNewAPI(body, subject(), "relay.example", 1_000_000L)
        val pool = snap.pools.single()
        assertTrue(pool.unlimited)
        assertNull(pool.amounts?.remaining)
    }

    @Test
    fun `data with only user_quota is still recognized as New API`() {
        val body = (
            "{\"code\":true,\"data\":{\"name\":\"free\",\"object\":\"token_usage\"," +
                "\"user_quota\":{\"total_available\":250000,\"total_used\":0}}}"
            ).toByteArray()
        val snap = RelayQuotaAdapter.parseNewAPI(body, subject(), "relay.example", 1_000_000L)
        assertEquals(0, snap.pools.single().amounts?.remaining!!.compareTo(250000.toBigDecimal()))
    }

    // --------------------------------------------- fetch probe chain -------

    @Test
    fun `transport failure on one family does not abort the probe chain`() = runTest {
        val outcome = RelayQuotaAdapter.fetch(ctx(fakeRelay(failUsageTransport = true, tokenStatus = 200)))
        assertEquals(RelayKind.newAPI, outcome.relayKind)
        assertEquals(0, snapshotPool(outcome).amounts?.remaining!!.compareTo(250000.toBigDecimal()))
        assertEquals(1, outcome.partialErrors.size)
        assertTrue(
            "the transport failure must be recorded as a partial error",
            outcome.partialErrors.single().detail.contains("transport:"),
        )
    }

    @Test
    fun `all families refusing reports the checked list`() = runTest {
        val result = runCatching { RelayQuotaAdapter.fetch(ctx(fakeRelay())) }
        val qfe = result.exceptionOrNull() as? QuotaFetchError
            ?: error(
                "fetch unexpectedly succeeded or threw a foreign type: " +
                    "outcome=${result.getOrNull()} exception=${result.exceptionOrNull()}",
            )
        assertEquals(
            "unexpected kind/detail: ${qfe.kind} ${qfe.detail}",
            QuotaError.Kind.notFound,
            qfe.kind,
        )
        assertTrue(
            "the error must list the probed endpoints: ${qfe.detail}",
            qfe.detail.contains("/v1/usage") && qfe.detail.contains("/api/usage/token") &&
                qfe.detail.contains("/v1/dashboard/billing/subscription"),
        )
    }

    @Test
    fun `hint family transport failure is rethrown honestly`() = runTest {
        try {
            RelayQuotaAdapter.fetch(
                ctx(fakeRelay(failTokenTransport = true), hint = RelayKind.newAPI),
            )
            org.junit.Assert.fail("expected the transport error to propagate on a hinted family")
        } catch (e: java.io.IOException) {
            assertEquals("token path reset", e.message)
        }
    }
}
