package com.openminis.app.plugins.builtin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Contract tests for the builtin-plugin catalog: descriptor completeness, the
 * on-disk layout every builtin plugin shares, and the `<PREFIX>_*` env
 * contract each payload's install script consumes. Pure functions — no
 * Context, no disk.
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
            assertTrue("installScript is relative", !d.installScript.startsWith("/"))
            // 0 is legal and means "self-contained runtime, skip the node
            // gate" (the a2a Rust binary); Node runtimes pin their real floor.
            assertTrue("nodeMinMajor", d.nodeMinMajor >= 0)
            assertTrue("servicePort", d.servicePort in 1..65535)
            assertTrue("serviceHealthPath starts with /", d.serviceHealthPath.startsWith("/"))
            assertTrue("laneId", d.laneId.isNotBlank())
            assertTrue("envPrefix is shell-safe", d.envPrefix.matches(Regex("[A-Z0-9_]+")))
            assertTrue("launcher is a bare name", d.launcher.matches(Regex("[a-z0-9-]+")))
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
    fun `a2a descriptor matches the lane contract`() {
        val a2a = BuiltinPluginCatalog.byId("a2a")!!
        assertEquals("a2a lane id", "a2a", a2a.laneId)
        assertEquals(18901, a2a.servicePort)
        assertEquals("/health", a2a.serviceHealthPath)
        // Self-contained Rust binary — no Node anywhere.
        assertEquals(0, a2a.nodeMinMajor)
        assertEquals("a2a-gateway", a2a.launcher)
        assertEquals("/", a2a.panelPath)
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
    fun `install env carries each payload's own prefix contract`() {
        val ofm = BuiltinPluginCatalog.byId("ofm")!!
        val env = BuiltinPluginCatalog.installEnv(ofm)
        assertEquals(BuiltinPluginCatalog.payloadDir("ofm"), env["OFM_PLUGIN_PAYLOAD"])
        assertEquals(BuiltinPluginCatalog.toolDir("ofm"), env["OFM_TOOL_DIR"])
        assertEquals(BuiltinPluginCatalog.dataDir("ofm"), env["OFM_TOOL_DATA"])

        val a2a = BuiltinPluginCatalog.byId("a2a")!!
        val a2aEnv = BuiltinPluginCatalog.installEnv(a2a)
        assertEquals(BuiltinPluginCatalog.payloadDir("a2a"), a2aEnv["A2A_PLUGIN_PAYLOAD"])
        assertEquals(BuiltinPluginCatalog.toolDir("a2a"), a2aEnv["A2A_TOOL_DIR"])
        assertEquals(BuiltinPluginCatalog.dataDir("a2a"), a2aEnv["A2A_TOOL_DATA"])
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
        assertEquals(
            "http://127.0.0.1:18901/health",
            BuiltinPluginCatalog.healthUrl(BuiltinPluginCatalog.byId("a2a")!!),
        )
    }

    @Test
    fun `panel url is null for panel-less plugins and derived otherwise`() {
        assertEquals(null, BuiltinPluginCatalog.panelUrl(BuiltinPluginCatalog.byId("ofm")!!))
        assertEquals(
            "http://127.0.0.1:18901/",
            BuiltinPluginCatalog.panelUrl(BuiltinPluginCatalog.byId("a2a")!!),
        )
    }
}
