package com.openminis.app.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic coverage for [DohDns]. No network: every case here drives the
 * parsing / TTL / URL-selection functions directly, and the cache cases use
 * the injected `nowProvider` clock rather than sleeping.
 *
 * The point of these tests is not "the JSON parser works" — it is that a
 * malformed or empty DoH body can never be mistaken for a resolution, because
 * that mistake turns a network hiccup into "your provider is gone".
 */
class DohDnsTest {

    // ---- parseDnsJson ----

    @Test
    fun `parseDnsJson reads A and AAAA data in server order`() {
        val body = """
            {"Status":0,"Answer":[
              {"name":"api.example.com","type":1,"TTL":120,"data":"1.2.3.4"},
              {"name":"api.example.com","type":28,"TTL":300,"data":"2606:4700::1"},
              {"name":"api.example.com","type":1,"TTL":120,"data":"5.6.7.8"}
            ]}
        """.trimIndent()
        val parsed = DohDns.parseDnsJson(body)
        assertEquals(listOf("1.2.3.4", "2606:4700::1", "5.6.7.8"), parsed.ips)
    }

    @Test
    fun `parseDnsJson ignores non A AAAA record types`() {
        val body = """
            {"Status":0,"Answer":[
              {"name":"x.example.com","type":5,"TTL":60,"data":"ns1.example.com"},
              {"name":"x.example.com","type":16,"TTL":60,"data":"txt-records"},
              {"name":"x.example.com","type":1,"TTL":60,"data":"9.9.9.9"}
            ]}
        """.trimIndent()
        assertEquals(listOf("9.9.9.9"), DohDns.parseDnsJson(body).ips)
    }

    @Test
    fun `parseDnsJson returns empty for missing Answer array`() {
        assertTrue(DohDns.parseDnsJson("""{"Status":3,"Comment":"NXDOMAIN"}""").ips.isEmpty())
    }

    @Test
    fun `parseDnsJson returns empty for blank body`() {
        assertTrue(DohDns.parseDnsJson("").ips.isEmpty())
        assertTrue(DohDns.parseDnsJson("   \n ").ips.isEmpty())
    }

    @Test
    fun `parseDnsJson returns empty for malformed json instead of throwing`() {
        assertTrue(DohDns.parseDnsJson("{not json at all").ips.isEmpty())
        assertTrue(DohDns.parseDnsJson("<html>gateway error</html>").ips.isEmpty())
    }

    @Test
    fun `parseDnsJson treats empty Answer array as no result`() {
        assertTrue(DohDns.parseDnsJson("""{"Status":0,"Answer":[]}""").ips.isEmpty())
    }

    @Test
    fun `parseDnsJson skips answer entries with blank data`() {
        val body = """
            {"Answer":[
              {"type":1,"TTL":60,"data":"  "},
              {"type":1,"TTL":60,"data":"4.4.4.4"}
            ]}
        """.trimIndent()
        assertEquals(listOf("4.4.4.4"), DohDns.parseDnsJson(body).ips)
    }

    // ---- TTL clamping ----

    @Test
    fun `clampTtl caps large ttl at the cache ceiling`() {
        assertEquals(300L, DohDns.clampTtl(86_400L))
    }

    @Test
    fun `clampTtl floors non positive ttl at one second`() {
        assertEquals(1L, DohDns.clampTtl(0L))
        assertEquals(1L, DohDns.clampTtl(-5L))
    }

    @Test
    fun `clampTtl passes through in range ttl`() {
        assertEquals(120L, DohDns.clampTtl(120L))
    }

    @Test
    fun `parseDnsJson takes the minimum ttl across answers`() {
        val body = """
            {"Answer":[
              {"type":1,"TTL":300,"data":"1.1.1.1"},
              {"type":1,"TTL":30,"data":"2.2.2.2"}
            ]}
        """.trimIndent()
        assertEquals(30L, DohDns.parseDnsJson(body).ttlSec)
    }

    @Test
    fun `parseDnsJson caps an oversized declared ttl`() {
        val body = """{"Answer":[{"type":1,"TTL":99999,"data":"1.1.1.1"}]}"""
        assertEquals(300L, DohDns.parseDnsJson(body).ttlSec)
    }

    @Test
    fun `parseDnsJson defaults ttl to the cap when the record omits it`() {
        val body = """{"Answer":[{"type":1,"data":"1.1.1.1"}]}"""
        assertEquals(300L, DohDns.parseDnsJson(body).ttlSec)
    }

    // ---- bootstrap URL selection ----

    @Test
    fun `pickBootstrapUrl replaces host with the literal ip and keeps path`() {
        val source = DohDns.SOURCES.first { it.name == "alidns" }
        val url = DohDns.pickBootstrapUrl(source, "223.5.5.5")
        assertTrue(url, url.startsWith("https://223.5.5.5/resolve"))
    }

    @Test
    fun `pickBootstrapUrl works for dns-query path sources`() {
        val source = DohDns.SOURCES.first { it.name == "cloudflare" }
        val url = DohDns.pickBootstrapUrl(source, "104.16.248.249")
        assertTrue(url, url.startsWith("https://104.16.248.249/dns-query"))
    }

    @Test
    fun `every source declares at least one bootstrap ip`() {
        DohDns.SOURCES.forEach { source ->
            assertTrue("${source.name} has no bootstrap ip", source.bootstrapIps.isNotEmpty())
        }
    }

    @Test
    fun `sources are ordered alidns then dnspod then cloudflare`() {
        assertEquals(listOf("alidns", "dnspod", "cloudflare"), DohDns.SOURCES.map { it.name })
    }

    @Test
    fun `bootstrap ips are well formed ipv4 literals`() {
        DohDns.SOURCES.forEach { source ->
            source.bootstrapIps.forEach { ip ->
                val parts = ip.split('.')
                assertEquals("${source.name}: $ip not ipv4", 4, parts.size)
                parts.forEach { part ->
                    val v = part.toIntOrNull()
                    assertTrue("${source.name}: $ip has bad octet", v != null && v in 0..255)
                }
            }
        }
    }

    // ---- cache / negative cache (injected clock, no sleeping, no network) ----

    @Test
    fun `cached entry is returned before it expires`() {
        withClock { clock ->
            DohDns.putForTest("cached.example.com", listOf("1.1.1.1"), 120L)
            clock.value = 1_000L + 119_000L
            assertEquals(listOf("1.1.1.1"), DohDns.cachedForTest("cached.example.com"))
        }
    }

    @Test
    fun `cached entry is dropped once its ttl elapses`() {
        withClock { clock ->
            DohDns.putForTest("expiring.example.com", listOf("1.1.1.1"), 10L)
            clock.value = 1_000L + 10_000L
            assertEquals(null, DohDns.cachedForTest("expiring.example.com"))
        }
    }

    @Test
    fun `negative cache entry is held then released after its ttl`() {
        withClock { clock ->
            DohDns.putForTest("dead.example.com", emptyList(), 30L)
            clock.value = 1_000L + 29_000L
            assertEquals(emptyList<String>(), DohDns.cachedForTest("dead.example.com"))
            clock.value = 1_000L + 30_000L
            assertEquals(null, DohDns.cachedForTest("dead.example.com"))
        }
    }

    @Test
    fun `unknown host has no cache entry`() {
        withClock { _ ->
            assertEquals(null, DohDns.cachedForTest("never-seen.example.com"))
        }
    }

    @Test
    fun `re-putting a host refreshes rather than duplicating`() {
        withClock { _ ->
            DohDns.putForTest("refresh.example.com", listOf("1.1.1.1"), 300L)
            DohDns.putForTest("refresh.example.com", listOf("2.2.2.2"), 300L)
            assertEquals(listOf("2.2.2.2"), DohDns.cachedForTest("refresh.example.com"))
        }
    }

    /** Runs [block] against a fresh cache + fake clock, restoring both after. */
    private fun withClock(block: (Holder) -> Unit) {
        val originalNow = DohDns.nowProvider
        val holder = Holder()
        DohDns.clearCacheForTest()
        DohDns.nowProvider = { holder.value }
        try {
            block(holder)
        } finally {
            DohDns.nowProvider = originalNow
            DohDns.clearCacheForTest()
        }
    }

    private class Holder {
        // Starts at 1s so the `1_000L + …` clock jumps below line up with the
        // expiry computed at put() time.
        @Volatile var value: Long = 1_000L
    }
}