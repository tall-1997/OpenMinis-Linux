package com.openminis.app.harness.operation

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Adapted from taixu HarnessRuntimePolicyTest (GPL-3.0-or-later).
 * https://github.com/wkbin/taixu
 */
class OperationSnapshotTest {

    @Test
    fun `operation snapshot is a complete serializable program counter`() {
        val snapshot = OperationSnapshot(
            phase = OperationPhase.TOOL_INTENT.id,
            round = 2,
            effectKind = "tool",
            effectId = "call-1",
            effectPayloadJson = "{\"path\":\"a\"}",
            replayPolicy = ReplayPolicy.NEVER.id,
            attempt = 1,
            maxAttempts = 1,
        )
        val encoded = Json.encodeToString(OperationSnapshot.serializer(), snapshot)
        val decoded = Json.decodeFromString(OperationSnapshot.serializer(), encoded)
        assertEquals(snapshot, decoded)
        assertTrue(encoded.contains("tool_intent"))
    }
}
