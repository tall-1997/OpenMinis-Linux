package com.openminis.app.mcp.client

import com.openminis.app.mcp.client.McpJsonRpc.McpError
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-mcp-list-all] Paginated `list*` collection — port of pi client.ts
 * `listAll` (client.ts:357-376): follow `nextCursor` through every page, but
 * bound the walk and refuse servers that echo a cursor twice.
 *
 * Why the guards: a server whose `nextCursor` never changes (or repeats) would
 * otherwise loop forever silently duplicating results, and a cursor echo bug
 * is exactly the kind of failure that surfaces as a hang, not an error. pi
 * throws `MCP <method> returned duplicate cursor: <cursor>` and
 * `MCP <method> exceeded <MAX_LIST_PAGES> pages`; both messages are pinned
 * here verbatim so tests and operators see the same strings.
 *
 * Shared by the stdio and HTTP transports so their pagination cannot drift.
 */
object McpListPager {

    /** pi client.ts `MAX_LIST_PAGES`. */
    const val MAX_LIST_PAGES = 1_000

    /**
     * Collects every page of [method] into one array. [fetch] performs one
     * page request ([cursor] null for the first page) and returns the raw
     * result object; [key] names the items array inside it.
     *
     * pi semantics pinned: `null` / `""` nextCursor ends pagination (some
     * servers end with an explicit empty value); a non-string nextCursor is a
     * protocol error; a missing items array is a protocol error.
     */
    fun collect(method: String, key: String, fetch: (cursor: String?) -> JSONObject): JSONArray {
        val items = JSONArray()
        val cursors = HashSet<String>()
        var cursor: String? = null
        repeat(MAX_LIST_PAGES) {
            val result = fetch(cursor)
            val page = result.optJSONArray(key)
                ?: throw McpError(McpError.PROTOCOL_ERROR, "Invalid MCP $method result")
            for (i in 0 until page.length()) items.put(page.opt(i))
            val next = result.opt("nextCursor")
            val nextCursor: String? = when (next) {
                null, JSONObject.NULL, "" -> null
                is String -> next
                else -> throw McpError(McpError.PROTOCOL_ERROR, "Invalid MCP $method cursor")
            }
            if (nextCursor == null) return items
            if (!cursors.add(nextCursor)) {
                throw McpError(McpError.PROTOCOL_ERROR, "MCP $method returned duplicate cursor: $nextCursor")
            }
            cursor = nextCursor
        }
        throw McpError(McpError.PROTOCOL_ERROR, "MCP $method exceeded $MAX_LIST_PAGES pages")
    }
}
