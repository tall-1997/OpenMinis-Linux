package com.openminis.app.provider

import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger

/**
 * Unit tests for the per-provider refresh generation guard
 * ([ModelRefreshGeneration]) — the Kotlin port of pi's `Models.refresh`
 * generation scheme (packages/ai/src/models.ts:506-641).
 *
 * Modeled concurrency: `refreshModels` on the same provider instance can be
 * entered concurrently from the manual refresh button, the toolbar Sync
 * sweep, the SWR picker refresh and the daily sweep. The guard must make the
 * slowest (superseded) refresh drop its result instead of overwriting the
 * fresher catalog the newer refresh already published.
 */
class ModelRefreshGenerationTest {

    @Test
    fun `newer refresh supersedes the older generation before it publishes`() {
        val guard = ModelRefreshGeneration()
        val written = mutableListOf<Int>()

        val stale = guard.begin("p")
        val fresh = guard.begin("p")

        assertFalse(guard.publishIfCurrent("p", stale) { written += 1 })
        assertTrue(guard.publishIfCurrent("p", fresh) { written += 2 })
        assertEquals("only the newest generation's publish lands", listOf(2), written)
    }

    @Test
    fun `late result of a superseded in-flight refresh is dropped`() = runTest {
        val guard = ModelRefreshGeneration()
        val publishedCatalogs = mutableListOf<String>()

        // First refresh starts and stalls on its network round trip.
        val staleGen = guard.begin("inst-1")
        val staleRefresh = launch {
            delay(500)
            guard.publishIfCurrent("inst-1", staleGen) {
                publishedCatalogs += "stale-catalog"
            }
        }

        // A newer refresh of the same instance starts while the first is in
        // flight, finishes first and publishes its catalog.
        val freshGen = guard.begin("inst-1")
        val freshPublished = guard.publishIfCurrent("inst-1", freshGen) {
            publishedCatalogs += "fresh-catalog"
        }
        assertTrue(freshPublished)

        // The stale network answer finally arrives and must be dropped.
        staleRefresh.join()
        assertEquals(listOf("fresh-catalog"), publishedCatalogs)
    }

    @Test
    fun `expired generation writes no cache and sends no notification`() {
        val guard = ModelRefreshGeneration()
        var cacheWrites = 0
        var uiNotifications = 0

        val staleGen = guard.begin("inst")
        guard.begin("inst") // a newer refresh started while the first ran

        val published = guard.publishIfCurrent("inst", staleGen) {
            cacheWrites++
            uiNotifications++
        }

        assertFalse(published)
        assertEquals("no cache write for an expired generation", 0, cacheWrites)
        assertEquals("no UI notification for an expired generation", 0, uiNotifications)
    }

    @Test
    fun `a current generation may publish repeatedly while it stays in charge`() {
        // pi's publication runs two gated steps per refresh (persist, then
        // update); the same captured generation must pass both.
        val guard = ModelRefreshGeneration()
        val gen = guard.begin("p")
        val steps = mutableListOf<Int>()

        assertTrue(guard.publishIfCurrent("p", gen) { steps += 1 })
        assertTrue(guard.publishIfCurrent("p", gen) { steps += 2 })
        assertEquals(listOf(1, 2), steps)
    }

    @Test
    fun `generations are strictly increasing and isCurrent tracks the latest`() {
        val guard = ModelRefreshGeneration()

        val first = guard.begin("p")
        assertTrue(guard.isCurrent("p", first))

        val second = guard.begin("p")
        assertTrue("generations must strictly increase", second > first)
        assertFalse(guard.isCurrent("p", first))
        assertTrue(guard.isCurrent("p", second))
    }

    @Test
    fun `generations of different providers are independent`() {
        val guard = ModelRefreshGeneration()

        val genA = guard.begin("provider-a")
        val genB = guard.begin("provider-b")
        assertTrue(guard.isCurrent("provider-a", genA))
        assertTrue(guard.isCurrent("provider-b", genB))

        val genA2 = guard.begin("provider-a")
        assertFalse(guard.isCurrent("provider-a", genA))
        assertTrue("provider-b must be untouched by provider-a's supersede", guard.isCurrent("provider-b", genB))
        assertTrue(guard.isCurrent("provider-a", genA2))
    }

    @Test
    fun `no stale publish ever lands after a newer refresh began`() {
        val guard = ModelRefreshGeneration()
        // Global acquisition order (begin: after its state change; publish:
        // inside the guarded callback, i.e. atomic with the currentness
        // check) so sorted events replay the true chronology.
        val seq = AtomicInteger()
        val events = Collections.synchronizedList(ArrayList<Triple<Int, Int, String>>())

        val refreshers = (0 until 4).map {
            Thread {
                repeat(150) {
                    val gen = guard.begin("p")
                    events.add(Triple(seq.incrementAndGet(), gen, "begin"))
                    // Widen the window between begin and publish so the
                    // interleavings actually race.
                    Thread.sleep(0, 200)
                    guard.publishIfCurrent("p", gen) {
                        events.add(Triple(seq.incrementAndGet(), gen, "publish"))
                    }
                }
            }
        }
        refreshers.forEach { it.start() }
        refreshers.forEach { it.join(10_000) }
        refreshers.forEach { assertFalse("stress thread hung", it.isAlive) }

        val ordered = events.sortedBy { it.first }
        var maxBegun = 0
        for ((_, gen, kind) in ordered) {
            if (kind == "begin") {
                if (gen > maxBegun) maxBegun = gen
            } else {
                assertTrue(
                    "stale publish of generation $gen landed after newer begin $maxBegun",
                    gen >= maxBegun,
                )
            }
        }
        assertTrue("expected the stress run to exercise multiple generations", maxBegun > 2)
    }
}
