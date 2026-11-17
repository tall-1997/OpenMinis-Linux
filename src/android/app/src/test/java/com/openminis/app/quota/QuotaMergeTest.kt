package com.openminis.app.quota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-quota-ingest-null-amounts] An incoming pool whose amounts carry no
 * number at all must not erase the amounts already held: a relay that briefly
 * answers without readable numbers would otherwise overwrite a good balance
 * with blanks (the same class of bug as the New API unknown-wallet parse).
 */
class QuotaMergeTest {

    private fun subject() = QuotaSubjectID(QuotaVendor.customRelay, "acct")

    private fun pool(
        amounts: QuotaAmounts?,
        unlimited: Boolean = false,
        observedAt: Long,
        label: String? = null,
    ) = QuotaPool(
        id = QuotaPoolKey.of(subject(), "relay"),
        subjectId = subject(),
        kind = QuotaPool.Kind.allowance,
        unit = QuotaUnit.Custom("quota"),
        vendorLabel = label,
        amounts = amounts,
        unlimited = unlimited,
        provenance = QuotaProvenance(
            QuotaSource.relayAccountLedger, QuotaConfidence.thirdParty, "relay.newAPI", "/api/usage/token", "relay.example",
        ),
        observedAt = observedAt,
    )

    private fun snapshot(pool: QuotaPool) = UpstreamQuotaSnapshot(
        subjectId = subject(),
        provenance = pool.provenance,
        capturedAt = pool.observedAt,
        pools = listOf(pool),
        bindings = emptyList(),
    )

    @Test
    fun `all-null incoming amounts keep the existing ones`() {
        val existing = pool(
            QuotaAmounts(total = 1_000.toBigDecimal(), used = 200.toBigDecimal(), remaining = 800.toBigDecimal()),
            observedAt = 1_000L,
            label = "old",
        )
        val incoming = pool(QuotaAmounts(), observedAt = 2_000L, label = "new")
        val pools = mutableMapOf(existing.id to existing)
        QuotaMerge.ingest(snapshot(incoming), authoritative = true, pools = pools, bindings = mutableListOf())

        val merged = pools.getValue(existing.id)
        assertEquals(0, merged.amounts?.total!!.compareTo(1_000.toBigDecimal()))
        assertEquals(0, merged.amounts?.used!!.compareTo(200.toBigDecimal()))
        assertEquals(0, merged.amounts?.remaining!!.compareTo(800.toBigDecimal()))
        // The rest of the merge still applies: label provenance and freshness.
        assertEquals("new", merged.vendorLabel)
        assertEquals(2_000L, merged.observedAt)
    }

    @Test
    fun `real incoming amounts still replace the existing ones`() {
        val existing = pool(QuotaAmounts(total = 1_000.toBigDecimal()), observedAt = 1_000L)
        val incoming = pool(
            QuotaAmounts(total = 500.toBigDecimal(), remaining = 500.toBigDecimal()),
            observedAt = 2_000L,
        )
        val pools = mutableMapOf(existing.id to existing)
        QuotaMerge.ingest(snapshot(incoming), authoritative = true, pools = pools, bindings = mutableListOf())

        val merged = pools.getValue(existing.id)
        assertEquals(0, merged.amounts?.total!!.compareTo(500.toBigDecimal()))
        assertEquals(0, merged.amounts?.remaining!!.compareTo(500.toBigDecimal()))
    }

    @Test
    fun `unlimited incoming without amounts still replaces amounts as before`() {
        // The guard is deliberately narrow: an UNLIMITED observation carries a
        // real verdict and replaces the amounts wholesale, as before the fix.
        val existing = pool(QuotaAmounts(total = 1_000.toBigDecimal()), observedAt = 1_000L)
        val incoming = pool(QuotaAmounts(), unlimited = true, observedAt = 2_000L)
        val pools = mutableMapOf(existing.id to existing)
        QuotaMerge.ingest(snapshot(incoming), authoritative = true, pools = pools, bindings = mutableListOf())

        val merged = pools.getValue(existing.id)
        assertTrue(merged.unlimited)
        assertNotNull(merged.amounts)
        assertNull(merged.amounts?.total)
        assertNull(merged.amounts?.used)
        assertNull(merged.amounts?.remaining)
    }
}
