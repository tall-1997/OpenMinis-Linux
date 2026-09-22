package com.openminis.app.upgrade

import com.openminis.app.sandbox.RootfsUpgradePolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpgradeRootfsCompatTest {
    @Test
    fun missingDistroMarkerOnARealGuestIsKept() {
        assertTrue(RootfsUpgradePolicy.shouldAdoptMissingDistroMarker(archMatches = true, guestTreePresent = true))
    }

    @Test
    fun partialExtractWithoutAGuestTreeIsNotAdopted() {
        assertFalse(RootfsUpgradePolicy.shouldAdoptMissingDistroMarker(archMatches = true, guestTreePresent = false))
        assertFalse(RootfsUpgradePolicy.shouldAdoptMissingDistroMarker(archMatches = false, guestTreePresent = true))
    }

    @Test
    fun nodeSeedMarkerIsNotWrittenBeforeATransientFailure() {
        assertFalse(RootfsUpgradePolicy.shouldStampNodeSeed(100, "E: Could not get lock", binaryPresent = false))
        assertFalse(RootfsUpgradePolicy.shouldStampNodeSeed(-1, "", binaryPresent = false))
    }

    @Test
    fun nodeSeedMarkerIsWrittenAfterSuccessOrAMissingPackage() {
        assertTrue(RootfsUpgradePolicy.shouldStampNodeSeed(0, "", binaryPresent = true))
        assertTrue(
            RootfsUpgradePolicy.shouldStampNodeSeed(
                100,
                "E: Unable to locate package nodejs",
                binaryPresent = false,
            ),
        )
    }
}
