package com.openminis.app.provider

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The local gateway lane: everything it serves is free by construction, and
 * nothing a squatter on the port says can change that.
 *
 * The contract that matters:
 * - [LocalGatewayFreeLane.owns] is host + empty-key — a user who points a real
 *   credential at 127.0.0.1:18900 owns their own instance, not ours;
 * - [LocalGatewayFreeLane.classify] never returns PAID or DEAD. The gateway is
 *   a local proxy over upstream free channels: an error body is a channel
 *   complaint, not a verdict about the model, and a 401 from whatever process
 *   squatted the port after the gateway died is garbage evidence. Only a clean
 *   200 is evidence, and it is positive.
 */
class LocalGatewayFreeLaneTest {

    @Test
    fun ownsMatchesLocalhostGatewayPortWithEmptyKey() {
        assertTrue(LocalGatewayFreeLane.owns("http://127.0.0.1:18900/v1", ""))
        assertTrue(LocalGatewayFreeLane.owns(null, null))
        assertTrue(LocalGatewayFreeLane.owns("http://127.0.0.1:18900/v1/", "  "))
    }

    @Test
    fun ownsRejectsRealCredentialsAndOtherHosts() {
        // A user's own key at the same port is their instance, not our lane.
        assertFalse(LocalGatewayFreeLane.owns("http://127.0.0.1:18900/v1", "sk-real-key"))
        // Same host, wrong port: not the gateway.
        assertFalse(LocalGatewayFreeLane.owns("http://127.0.0.1:8080/v1", ""))
        // The gateway host with a remote base URL is not local.
        assertFalse(LocalGatewayFreeLane.owns("http://example.com:18900/v1", ""))
    }

    @Test
    fun everythingTheGatewayServesIsDeclaredFree() {
        assertTrue(LocalGatewayFreeLane.declaredFree("deepseek-v3.2", null))
        assertTrue(LocalGatewayFreeLane.declaredFree("anything-at-all", false))
    }

    @Test
    fun probesCarryNoHeaders() {
        assertTrue(LocalGatewayFreeLane.probeHeaders().isEmpty())
    }

    @Test
    fun aClean200IsFreeAndCancelsDeathEvidence() {
        val verdict = LocalGatewayFreeLane.classify("deepseek-v3.2", 200, "{\"data\":[]}")
        assertEquals(FreeLaneVerdict.FREE, verdict)
    }

    @Test
    fun errorBodiesNeverBecomePaidOrDead() {
        // A squatter's 401 must not read as "this model needs a credential".
        assertEquals(
            FreeLaneVerdict.UNKNOWN,
            LocalGatewayFreeLane.classify("deepseek-v3.2", 401, "{\"error\":\"unauthorized\"}"),
        )
        // An upstream channel complaint must not read as "model gone".
        assertEquals(
            FreeLaneVerdict.UNKNOWN,
            LocalGatewayFreeLane.classify("deepseek-v3.2", 502, "bad gateway"),
        )
        assertEquals(
            FreeLaneVerdict.UNKNOWN,
            LocalGatewayFreeLane.classify("deepseek-v3.2", 410, "{\"error\":\"gone\"}"),
        )
    }

    @Test
    fun laneIsRegisteredInTheCatalogue() {
        assertEquals("ofm", LocalGatewayFreeLane.id)
        assertTrue(FreeLanes.LANE_IDS.contains("ofm"))
        assertEquals(LocalGatewayFreeLane, FreeLanes.byId("ofm"))
        // The directory resolves the seeded endpoint to this lane.
        assertEquals(
            LocalGatewayFreeLane,
            FreeLanes.forEndpoint("http://127.0.0.1:18900/v1", ""),
        )
    }

    @Test
    fun laneDoesNotDiscoverIdsByProbing() {
        // The gateway's listing IS the membership: a probe has nothing to add,
        // and letting learned.free add an id would keep a model visible after
        // the gateway stopped serving it.
        assertFalse(LocalGatewayFreeLane.discoversUndeclaredIds)
    }

    @Test
    fun endpointResolutionIgnoresOtherLanesForTheGatewayPort() {
        // Zen and Kilo must not claim the local endpoint even by accident.
        assertNull(FreeLanes.forEndpoint("http://127.0.0.1:18900/v1", "public"))
    }
}
