package com.openminis.app.plugins.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the builtin-plugin store's pure encode/decode round trip
 * and the status-persistence rule. SharedPreferences itself is not exercised
 * (Robolectric-free zone, same as DevSuiteStore's tests).
 */
class BuiltinPluginStoreTest {

    @Test
    fun `encode decode round trip preserves every field`() {
        val raw = BuiltinPluginStore.encodeState(
            BuiltinPluginStore.Status.INSTALLED,
            version = "2.3.0",
            diagnosis = "",
            updatedAtMs = 1_721_000_000_000,
        )
        val decoded = BuiltinPluginStore.decodeState("ofm", raw)!!
        assertEquals(BuiltinPluginStore.Status.INSTALLED, decoded.status)
        assertEquals("2.3.0", decoded.version)
        assertEquals("", decoded.diagnosis)
        assertEquals(1_721_000_000_000, decoded.updatedAtMs)
    }

    @Test
    fun `decode with diagnosis survives the separator`() {
        val raw = BuiltinPluginStore.encodeState(
            BuiltinPluginStore.Status.FAILED,
            version = "",
            diagnosis = "node: exit 4 — nodesource install failed",
            updatedAtMs = 42,
        )
        val decoded = BuiltinPluginStore.decodeState("ofm", raw)!!
        assertEquals(BuiltinPluginStore.Status.FAILED, decoded.status)
        assertEquals("node: exit 4 — nodesource install failed", decoded.diagnosis)
    }

    @Test
    fun `decode absent or corrupt row is null or NOT_INSTALLED`() {
        assertNull(BuiltinPluginStore.decodeState("ofm", null))
        val corrupt = BuiltinPluginStore.decodeState("ofm", "garbage-without-separators")
        assertTrue(corrupt == null || corrupt.status == BuiltinPluginStore.Status.NOT_INSTALLED)
    }

    @Test
    fun `unknown status token decodes as NOT_INSTALLED`() {
        val raw = "WHATEVER\u00012.3.0\u00001"
        val decoded = BuiltinPluginStore.decodeState("ofm", raw)!!
        assertEquals(BuiltinPluginStore.Status.NOT_INSTALLED, decoded.status)
    }

    @Test
    fun `only status transitions persist`() {
        // Same status, new progress line → in-memory only.
        assertFalse(
            BuiltinPluginStore.shouldPersistState(
                BuiltinPluginStore.Status.INSTALLING,
                BuiltinPluginStore.Status.INSTALLING,
            ),
        )
        // Any real transition → disk.
        assertTrue(
            BuiltinPluginStore.shouldPersistState(
                BuiltinPluginStore.Status.INSTALLING,
                BuiltinPluginStore.Status.INSTALLED,
            ),
        )
        assertTrue(
            BuiltinPluginStore.shouldPersistState(
                BuiltinPluginStore.Status.INSTALLING,
                BuiltinPluginStore.Status.FAILED,
            ),
        )
        assertTrue(BuiltinPluginStore.shouldPersistState(null, BuiltinPluginStore.Status.INSTALLING))
        assertTrue(
            BuiltinPluginStore.shouldPersistState(
                BuiltinPluginStore.Status.FAILED,
                BuiltinPluginStore.Status.INSTALLING,
            ),
        )
    }
}
