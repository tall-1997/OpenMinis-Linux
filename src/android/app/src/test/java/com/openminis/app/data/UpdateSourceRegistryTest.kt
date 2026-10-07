package com.openminis.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-update-source-choice] Unit tests for the source registry's pure logic:
 * fallback ordering and URL resolution. Probe/persist paths need Android
 * framework types (Context, ConnectivityManager) and are covered by device
 * verification instead.
 */
class UpdateSourceRegistryTest {

    private val assetUrl = "https://github.com/tall-1997/OpenMinis-Linux/releases/download/v0.1/app.apk"

    @Test
    fun `registry has stable ordered sources with unique ids`() {
        val ids = UpdateSourceRegistry.SOURCES.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        // The origin must be first: fresh installs default to GitHub direct
        // and the fallback chain ends at the canonical host.
        assertEquals("github-direct", UpdateSourceRegistry.SOURCES.first().id)
    }

    @Test
    fun `github-direct passes the asset url through unchanged`() {
        val src = UpdateSourceRegistry.SOURCES.first { it.id == "github-direct" }
        assertEquals(assetUrl, src.resolve(assetUrl))
    }

    @Test
    fun `gh-proxy prefixes the asset url`() {
        val src = UpdateSourceRegistry.SOURCES.first { it.id == "gh-proxy" }
        assertEquals("https://gh-proxy.com/$assetUrl", src.resolve(assetUrl))
    }

    @Test
    fun `fallbackOrder puts the preferred source first`() {
        val order = UpdateSourceRegistry.fallbackOrder("gh-proxy")
        assertEquals("gh-proxy", order.first().id)
        // Every source still appears exactly once — fallback is a chain, not
        // a filter.
        assertEquals(UpdateSourceRegistry.SOURCES.size, order.size)
        assertEquals(UpdateSourceRegistry.SOURCES.map { it.id }.toSet(), order.map { it.id }.toSet())
    }

    @Test
    fun `fallbackOrder with unknown or null preference keeps registry order`() {
        assertEquals(
            UpdateSourceRegistry.SOURCES.map { it.id },
            UpdateSourceRegistry.fallbackOrder(null).map { it.id },
        )
        // A stale persisted id (source removed from a future build) must not
        // break the chain — it just falls back to registry order.
        assertEquals(
            UpdateSourceRegistry.SOURCES.map { it.id },
            UpdateSourceRegistry.fallbackOrder("removed-in-future").map { it.id },
        )
    }

    @Test
    fun `every source resolves to an https url`() {
        UpdateSourceRegistry.SOURCES.forEach { src ->
            val resolved = src.resolve(assetUrl)
            assertTrue(resolved.startsWith("https://"))
            assertNotNull(src.host)
        }
    }
}
