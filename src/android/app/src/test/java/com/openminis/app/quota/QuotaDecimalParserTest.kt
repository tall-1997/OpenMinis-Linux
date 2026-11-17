package com.openminis.app.quota

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-quota-magnitude-cap] The parser accepts exponent form, and the canonical
 * string renders plain ([BigDecimal.quotaString] calls toPlainString): an
 * upstream "1e999999999" would expand into a ~1 GB amount string at persist
 * time, and "1e-999999999" into an equally long fraction. Absurd magnitudes
 * must parse as null - the same "field absent" convention as an unparseable
 * string - never as a number, and never blow up the caller.
 */
class QuotaDecimalParserTest {

    @Test
    fun `huge positive exponents are not amounts`() {
        assertNull(QuotaDecimalParser.parseString("1e999999999"))
        assertNull(QuotaDecimalParser.parseString("1e31"))
    }

    @Test
    fun `huge negative exponents are not amounts`() {
        assertNull(QuotaDecimalParser.parseString("1e-999999999"))
        assertNull(QuotaDecimalParser.parseString("0." + "0".repeat(31) + "1"))
    }

    @Test
    fun `absurd significant digit counts are not amounts`() {
        assertNull(QuotaDecimalParser.parseString("1".repeat(41)))
        assertNull(QuotaDecimalParser.parseString("12345678901234567890123456789012345678901234567890"))
    }

    @Test
    fun `magnitude bounds are inclusive`() {
        // Exactly at the caps: 10^30 and 10^-30 are the largest magnitudes kept.
        assertEquals(0, QuotaDecimalParser.parseString("1e30")!!.compareTo(("1" + "0".repeat(30)).toBigDecimal()))
        assertEquals(
            0,
            QuotaDecimalParser.parseString("0.000000000000000000000000000001")!!
                .compareTo("0.000000000000000000000000000001".toBigDecimal()),
        )
        // One past either bound is rejected.
        assertNull(QuotaDecimalParser.parseString("1e-31"))
    }

    @Test
    fun `realistic amounts still parse`() {
        assertEquals(0, QuotaDecimalParser.parseString("250000")!!.compareTo(250_000.toBigDecimal()))
        assertEquals(0, QuotaDecimalParser.parseString("0.001")!!.compareTo("0.001".toBigDecimal()))
        assertEquals(0, QuotaDecimalParser.parseString("1e9")!!.compareTo(1_000_000_000.toBigDecimal()))
        assertEquals(0, QuotaDecimalParser.parseString("-1")!!.compareTo((-1).toBigDecimal()))
    }

    @Test
    fun `json values go through the same cap`() {
        assertNull(QuotaDecimalParser.parse(JsonPrimitive("1e999999999")))
        assertEquals(0, QuotaDecimalParser.parse(JsonPrimitive("250000"))!!.compareTo(250_000.toBigDecimal()))
    }

    @Test
    fun `unparseable strings stay null`() {
        assertNull(QuotaDecimalParser.parseString("12abc"))
        assertNull(QuotaDecimalParser.parseString("abc"))
        assertNull(QuotaDecimalParser.parseString(""))
        assertNull(QuotaDecimalParser.parseString(null))
    }
}
