package com.openminis.app.mcp.server

import com.openminis.app.sandbox.SessionWorkspace
import org.json.JSONObject
import java.io.File

/**
 * [T-mcp-approval] Zero-config discovery for local stdio bridges.
 *
 * The phone speaks MCP over loopback HTTP, but most local clients (Claude Code,
 * Codex CLI, custom agents) only know how to spawn a child and talk stdio. The
 * bridge script (`scripts/mcp/minis-mcp-stdio`) resolves its endpoint from
 * `/var/minis/shared/mcp/endpoint.json` inside the Linux guest, which the
 * PRoot bind maps to `<filesDir>/minis-global/shared/mcp/endpoint.json`.
 *
 * The host path is resolved through [SessionWorkspace] instead of being
 * spelled out here: the shared mount lives under `minis-global/`, and a
 * second hard-coded copy would silently drift away from the bind.
 *
 * Written on every successful bind and removed on stop, so the file never
 * advertises a port nobody listens on. A token the user dropped in by hand
 * survives the merge — the app refreshes the address, never the credential.
 */
internal object McpEndpointFile {

    const val GUEST_PATH = "/var/minis/shared/mcp/endpoint.json"
    const val NAME = "endpoint.json"

    /**
     * Directory under `filesDir` that mirrors the guest's `/var/minis/shared/mcp`.
     * Public and used by [fileFor] itself: a constant only a test reads would be
     * a second spelling of the layout, free to drift from the real one.
     */
    const val RELATIVE_DIR = "shared/mcp"
    private const val KEY_TOKEN = "token"

    /** Host file behind [GUEST_PATH]; the guest bind owns the real layout. */
    fun fileFor(filesDir: File): File =
        SessionWorkspace.resolveGuestPath(filesDir, "", GUEST_PATH)
            ?: File(File(File(filesDir, SessionWorkspace.GLOBAL_DIR), RELATIVE_DIR), NAME)

    fun urlFor(port: Int): String = "http://127.0.0.1:$port/mcp"

    /**
     * Advertise [port] for discovery. Returns false when the file could not be
     * written — a missing endpoint file only costs the client auto-discovery,
     * never a running server, so callers treat it as best-effort.
     */
    fun advertise(filesDir: File, port: Int, nowMs: Long = System.currentTimeMillis()): Boolean =
        runCatching {
            val target = fileFor(filesDir)
            val dir = target.parentFile ?: return false
            if (!dir.exists() && !dir.mkdirs()) return false
            val body = payload(
                port = port,
                keptToken = readToken(target),
                nowMs = nowMs,
            )
            // Write-then-rename: a client polling mid-write must never parse a
            // half file, and a crash leaves the previous address intact.
            val tmp = File(dir, "$NAME.tmp")
            tmp.writeText(body)
            restrictToOwner(tmp)
            if (!tmp.renameTo(target)) {
                tmp.delete()
                return false
            }
            restrictToOwner(target)
            true
        }.getOrDefault(false)

    /** Stop advertising: the guest file is gone the moment the server is down. */
    fun withdraw(filesDir: File): Boolean = runCatching {
        val target = fileFor(filesDir)
        File(target.parentFile, "$NAME.tmp").delete()
        !target.exists() || target.delete()
    }.getOrDefault(false)

    /**
     * The JSON body written to disk. Pure so tests can pin the shape: a client
     * reads `url`, everything else is diagnostics.
     */
    fun payload(port: Int, keptToken: String?, nowMs: Long): String {
        val json = JSONObject()
            .put("url", urlFor(port))
            .put("port", port)
            .put("updatedAtMs", nowMs)
        if (!keptToken.isNullOrBlank()) json.put(KEY_TOKEN, keptToken)
        return json.toString(2)
    }

    /** A previous token wins: the app has no business overwriting a credential. */
    private fun readToken(target: File): String? = runCatching {
        if (!target.exists()) return null
        JSONObject(target.readText()).optString(KEY_TOKEN).takeIf { it.isNotBlank() }
    }.getOrNull()

    /** Owner-only read/write; best effort, the guest mount may not honour it. */
    private fun restrictToOwner(file: File) {
        file.setReadable(false, false)
        file.setReadable(true, true)
        file.setWritable(false, false)
        file.setWritable(true, true)
    }
}
