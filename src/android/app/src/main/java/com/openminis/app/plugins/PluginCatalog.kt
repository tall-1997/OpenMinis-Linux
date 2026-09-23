package com.openminis.app.plugins

import com.openminis.app.data.repository.MCPRepository

/**
 * One-tap MCP server presets. Shown on Settings → MCP, not the plugin market.
 *
 * HTTP entries adapted from XINCODE-Public PluginStore MCP catalog
 * (GPL-3.0-or-later, https://github.com/kusesad-1122/XINCODE-Public).
 * GitHub-token connectors are intentionally not ported.
 */
object PluginCatalog {

    data class McpPreset(
        val id: String,
        val name: String,
        val description: String,
        val url: String? = null,
        val command: String? = null,
        val args: List<String> = emptyList(),
    ) {
        fun toServer(): MCPRepository.MCPServerConfig = MCPRepository.MCPServerConfig(
            id = id,
            note = name,
            enabled = true,
            url = url,
            command = command,
            args = args,
        )

        fun matches(server: MCPRepository.MCPServerConfig): Boolean =
            server.id == id || (!url.isNullOrBlank() && server.url == url)
    }

    val MCP_PRESETS: List<McpPreset> = listOf(
        McpPreset(
            id = "microsoft_learn_mcp",
            name = "Microsoft Learn",
            description = "Microsoft documentation search over MCP.",
            url = "https://learn.microsoft.com/api/mcp",
        ),
        McpPreset(
            id = "context7_mcp",
            name = "Context7",
            description = "Up-to-date library docs for coding agents.",
            url = "https://mcp.context7.com/mcp",
        ),
        McpPreset(
            id = "deepwiki_mcp",
            name = "DeepWiki",
            description = "Ask questions about public GitHub repositories.",
            url = "https://mcp.deepwiki.com/mcp",
        ),
        McpPreset(
            id = "git",
            name = "Git",
            description = "Official Python mcp-server-git. Tools: status, diff, log, commit, blame. Needs pip install mcp-server-git once.",
            command = "python3",
            args = listOf("-m", "mcp_server_git"),
        ),
    )
}
