package com.openminis.app.plugins.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the builtin-plugin catalog: descriptor completeness, the
 * on-disk layout every builtin plugin shares, and the `<PREFIX>_*` env
 * contract each payload's install script consumes. Pure functions — no
 * Context, no disk.
 *
 * The catalog is guest-only now: the a2a gateway left it for
 * `com.openminis.app.a2a` (A2aGateway), so every descriptor runs inside the
 * PRoot sandbox and no descriptor serves a web panel. The native descriptor
 * fields stay dormant for a future native plugin (see the catalog's KDoc).
 */
class BuiltinPluginCatalogTest {

    @Test
    fun `every descriptor field is populated`() {
        for (d in BuiltinPluginCatalog.ALL) {
            assertTrue("id", d.id.isNotBlank())
            assertTrue("id is filesystem-safe", d.id.matches(Regex("[a-z0-9-]+")))
            assertNotNull("nameRes", d.nameRes)
            assertNotNull("summaryRes", d.summaryRes)
            assertTrue("version", d.version.isNotBlank())
            assertTrue("payloadAsset", d.payloadAsset.startsWith("builtin_plugins/"))
            assertTrue("payloadAsset is a zip", d.payloadAsset.endsWith(".zip"))
            assertTrue("servicePort", d.servicePort in 1..65535)
            assertTrue("serviceHealthPath starts with /", d.serviceHealthPath.startsWith("/"))
            assertTrue("laneId", d.laneId.isNotBlank())
            assertTrue("installScript is relative", !d.installScript!!.startsWith("/"))
            // 0 is legal and means "self-contained runtime, skip the node
            // gate"; Node runtimes pin their real floor.
            assertTrue("nodeMinMajor", d.nodeMinMajor >= 0)
            assertTrue("envPrefix is shell-safe", d.envPrefix!!.matches(Regex("[A-Z0-9_]+")))
            assertTrue("launcher is a bare name", d.launcher!!.matches(Regex("[a-z0-9-]+")))
            // The catalog is guest-only after a2a left for com.openminis.app.a2a.
            assertFalse("no native descriptors in the catalog", d.isNative)
            assertEquals(null, d.nativeBinary)
            assertEquals(null, d.nativeEnvPrefix)
        }
    }

    @Test
    fun `ids are unique`() {
        val ids = BuiltinPluginCatalog.ALL.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
    }

    @Test
    fun `ofm descriptor matches the lane contract`() {
        val ofm = BuiltinPluginCatalog.byId("ofm")!!
        assertEquals("builtin-ofm-gateway lane id", "ofm", ofm.laneId)
        assertEquals(18900, ofm.servicePort)
        assertEquals("/v1/models", ofm.serviceHealthPath)
        assertEquals(22, ofm.nodeMinMajor)
    }

    @Test
    fun `ports are unique so two gateways never squat each other`() {
        val ports = BuiltinPluginCatalog.ALL.map { it.servicePort }
        assertEquals(ports.size, ports.toSet().size)
    }

    @Test
    fun `byId unknown returns null`() {
        assertEquals(null, BuiltinPluginCatalog.byId("no-such-plugin"))
    }

    @Test
    fun `byId a2a is gone — the gateway lives in the a2a package now`() {
        assertEquals(null, BuiltinPluginCatalog.byId("a2a"))
    }

    @Test
    fun `install root is shared builtin-plugins dir keyed by id`() {
        assertEquals(
            "/var/minis/shared/builtin-plugins/ofm",
            BuiltinPluginCatalog.installRoot("ofm"),
        )
    }

    @Test
    fun `layout subdirs sit under the install root`() {
        val root = BuiltinPluginCatalog.installRoot("ofm")
        assertEquals("$root/payload", BuiltinPluginCatalog.payloadDir("ofm"))
        assertEquals("$root/tool", BuiltinPluginCatalog.toolDir("ofm"))
        assertEquals("$root/data", BuiltinPluginCatalog.dataDir("ofm"))
        for (dir in listOf(
            BuiltinPluginCatalog.payloadDir("ofm"),
            BuiltinPluginCatalog.toolDir("ofm"),
            BuiltinPluginCatalog.dataDir("ofm"),
        )) {
            assertTrue("$dir under root", dir.startsWith(root))
        }
    }

    @Test
    fun `install env carries each guest payload's own prefix contract`() {
        val ofm = BuiltinPluginCatalog.byId("ofm")!!
        val env = BuiltinPluginCatalog.installEnv(ofm)
        assertEquals(BuiltinPluginCatalog.payloadDir("ofm"), env["OFM_PLUGIN_PAYLOAD"])
        assertEquals(BuiltinPluginCatalog.toolDir("ofm"), env["OFM_TOOL_DIR"])
        assertEquals(BuiltinPluginCatalog.dataDir("ofm"), env["OFM_TOOL_DATA"])
    }

    @Test
    fun `install env keeps PATH so node and npm resolve`() {
        val ofm = BuiltinPluginCatalog.byId("ofm")!!
        val env = BuiltinPluginCatalog.installEnv(ofm)
        assertNotNull(env["PATH"])
        assertTrue("PATH keeps /usr/local/bin", env["PATH"]!!.contains("/usr/local/bin"))
    }

    @Test
    fun `health url derives from port and path`() {
        val ofm = BuiltinPluginCatalog.byId("ofm")!!
        assertEquals(
            "http://127.0.0.1:18900/v1/models",
            BuiltinPluginCatalog.healthUrl(ofm),
        )
    }
}
