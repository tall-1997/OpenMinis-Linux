package com.openminis.app.mcp.server

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * [T-mcp-approval] The discovery file is the only contract between the app and
 * a spawned stdio bridge, so the shape and the merge rules are pinned here:
 * the app refreshes the address, the user owns the token.
 */
class McpEndpointFileTest {

    private lateinit var filesDir: File

    @Before
    fun setUp() {
        filesDir = Files.createTempDirectory("minis-endpoint").toFile()
    }

    @After
    fun tearDown() {
        McpEndpointFile.fileFor(filesDir).delete()
        filesDir.deleteRecursively()
    }

    @Test
    fun `advertising creates the file with the live address`() {
        assertTrue(McpEndpointFile.advertise(filesDir, 8765, nowMs = 42L))
        val target = McpEndpointFile.fileFor(filesDir)
        assertTrue(target.isFile)
        assertEquals(
            "minis-global/shared/mcp/endpoint.json",
            target.path.removePrefix(filesDir.path + "/"),
        )
        val json = JSONObject(target.readText())
        assertEquals("http://127.0.0.1:8765/mcp", json.getString("url"))
        assertEquals(8765, json.getInt("port"))
        assertEquals(42L, json.getLong("updatedAtMs"))
        // No token invented by the app: auth is the user's call.
        assertFalse(json.has("token"))
    }

    @Test
    fun `a hand-written token survives later updates`() {
        val target = McpEndpointFile.fileFor(filesDir)
        target.parentFile!!.mkdirs()
        target.writeText(JSONObject().put("url", "http://127.0.0.1:8765/mcp").put("token", "secret-1").toString())

        assertTrue(McpEndpointFile.advertise(filesDir, 8766, nowMs = 7L))
        val json = JSONObject(target.readText())
        assertEquals("secret-1", json.getString("token"))
        assertEquals("http://127.0.0.1:8766/mcp", json.getString("url"))
        assertEquals(8766, json.getInt("port"))
    }

    @Test
    fun `a corrupt file is replaced instead of blocking the server`() {
        val target = McpEndpointFile.fileFor(filesDir)
        target.parentFile!!.mkdirs()
        target.writeText("{not json")

        assertTrue(McpEndpointFile.advertise(filesDir, 8765, nowMs = 1L))
        val json = JSONObject(target.readText())
        assertEquals(8765, json.getInt("port"))
        assertFalse(json.has("token"))
    }

    @Test
    fun `withdraw removes the file and is idempotent`() {
        assertTrue(McpEndpointFile.advertise(filesDir, 8765))
        val target = McpEndpointFile.fileFor(filesDir)
        assertTrue(target.exists())
        assertTrue(McpEndpointFile.withdraw(filesDir))
        assertFalse(target.exists())
        // Second call: nothing to remove, still a success.
        assertTrue(McpEndpointFile.withdraw(filesDir))
    }

    @Test
    fun `no temp file is left behind after a write`() {
        McpEndpointFile.advertise(filesDir, 8765, nowMs = 3L)
        val siblings = McpEndpointFile.fileFor(filesDir).parentFile!!.list()!!.sorted()
        assertEquals(listOf("endpoint.json"), siblings)
    }

    @Test
    fun `a directory squatting the file name reports failure without throwing`() {
        // The exact path is a directory: the temp write succeeds, the rename
        // cannot replace a directory, so advertise must report false and leave
        // no temp file behind rather than throwing into the server start path.
        val target = McpEndpointFile.fileFor(filesDir)
        assertTrue(target.mkdirs())
        assertFalse(McpEndpointFile.advertise(filesDir, 8765))
        assertEquals(listOf("endpoint.json"), target.parentFile!!.list()!!.toList())
    }
    @Test
    fun `url builder keeps loopback and the mcp path`() {
        assertEquals("http://127.0.0.1:8765/mcp", McpEndpointFile.urlFor(8765))
        assertEquals("shared/mcp", McpEndpointFile.RELATIVE_DIR)
        assertEquals("endpoint.json", McpEndpointFile.NAME)
        assertNull(JSONObject(McpEndpointFile.payload(1, null, 0L)).opt("token"))
    }
}
