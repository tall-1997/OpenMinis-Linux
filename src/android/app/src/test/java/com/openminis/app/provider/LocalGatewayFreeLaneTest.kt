package com.openminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Loopback gateway lanes (OFM on 18900, Agent2API on 18901): everything
 * they serve is free by construction, and nothing a squatter on the port
 * says can change that. The semantics live once in [LoopbackGatewayLane];
 * these tests run the same contract against every concrete lane so a new
 * gateway cannot silently drift.
 *
 * The contract that matters:
 * - [LoopbackGatewayLane.owns] is host + port + empty-key — a user who
 *   points a real credential at the port owns their own instance, not ours;
 * - [LoopbackGatewayLane.classify] never returns PAID or DEAD. The gateway
 *   is a local proxy over upstream free channels: an error body is a channel
 *   complaint, not a verdict about the model, and a 401 from whatever process
 *   squatted the port after the gateway died is garbage evidence. Only a
 *   clean 200 is evidence, and it is positive.
 */
class LocalGatewayFreeLaneTest {

    private val lanes = listOf(LocalGatewayFreeLane, Agent2ApiFreeLane)

    @Test
    fun ownsMatchesLocalhostGatewayPortWithEmptyKey() {
        for (lane in lanes) {
            assertTrue(lane.owns(lane.defaultBaseURL, ""))
            assertTrue("${lane.id}: null URL falls back to default", lane.owns(null, null))
            assertTrue("${lane.id}: blank key", lane.owns(lane.defaultBaseURL, "  "))
        }
    }

    @Test
    fun ownsRejectsRealCredentialsAndOtherHosts() {
        for (lane in lanes) {
            // A user's own key at the same port is their instance, not our lane.
            assertFalse(lane.owns(lane.defaultBaseURL, "sk-real-key"))
            // Same host, wrong port: not this gateway.
            assertFalse(lane.owns("http://127.0.0.1:8080/v1", ""))
            // The gateway port with a remote base URL is not local.
            assertFalse(lane.owns("http://example.com:${lane.port}/v1", ""))
            // The other loopback lane's port is not ours.
            val other = lanes.first { it !== lane }
            assertFalse(lane.owns(other.defaultBaseURL, ""))
        }
    }

    @Test
    fun everythingTheGatewayServesIsDeclaredFree() {
        for (lane in lanes) {
            assertTrue(lane.declaredFree("deepseek-v3.2", null))
            assertTrue(lane.declaredFree("anything-at-all", false))
        }
    }

    @Test
    fun probesCarryNoHeaders() {
        for (lane in lanes) assertTrue(lane.probeHeaders().isEmpty())
    }

    @Test
    fun aClean200IsFreeAndCancelsDeathEvidence() {
        for (lane in lanes) {
            assertEquals(
                FreeLaneVerdict.FREE,
                lane.classify("deepseek-v3.2", 200, "{\"data\":[]}"),
            )
        }
    }

    @Test
    fun errorBodiesNeverBecomePaidOrDead() {
        for (lane in lanes) {
            // A squatter's 401 must not read as "this model needs a credential".
            assertEquals(
                FreeLaneVerdict.UNKNOWN,
                lane.classify("deepseek-v3.2", 401, "{\"error\":\"unauthorized\"}"),
            )
            // An upstream channel complaint must not read as "model gone".
            assertEquals(FreeLaneVerdict.UNKNOWN, lane.classify("deepseek-v3.2", 502, "bad gateway"))
            assertEquals(
                FreeLaneVerdict.UNKNOWN,
                lane.classify("deepseek-v3.2", 410, "{\"error\":\"gone\"}"),
            )
        }
    }

    @Test
    fun laneIsRegisteredInTheCatalogue() {
        assertEquals("ofm", LocalGatewayFreeLane.id)
        assertEquals("a2a", Agent2ApiFreeLane.id)
        assertTrue(FreeLanes.LANE_IDS.contains("a2a"))
        assertEquals(Agent2ApiFreeLane, FreeLanes.byId("a2a"))
        // The directory resolves each seeded endpoint to its own lane.
        for (lane in lanes) {
            assertEquals(lane, FreeLanes.forEndpoint(lane.defaultBaseURL, ""))
        }
    }

    @Test
    fun laneDoesNotDiscoverIdsByProbing() {
        // The gateway's listing IS the membership: a probe has nothing to add,
        // and letting learned.free add an id would keep a model visible after
        // the gateway stopped serving it.
        for (lane in lanes) assertFalse(lane.discoversUndeclaredIds)
    }

    @Test
    fun endpointResolutionIgnoresOtherLanesForTheGatewayPort() {
        // Zen and Kilo must not claim the local endpoints even by accident.
        assertNull(FreeLanes.forEndpoint("http://127.0.0.1:18900/v1", "public"))
        assertNull(FreeLanes.forEndpoint("http://127.0.0.1:18901/v1", "public"))
    }
}
