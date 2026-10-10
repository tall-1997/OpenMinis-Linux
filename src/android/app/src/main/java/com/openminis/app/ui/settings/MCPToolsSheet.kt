package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.data.repository.MCPToolPolicy
import com.openminis.app.sandbox.ExecutionCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private data class McpToolRow(val name: String, val description: String)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MCPToolsSheet(
    server: MCPRepository.MCPServerConfig,
    mcpRepository: MCPRepository,
    onDismiss: () -> Unit,
    onManage: () -> Unit,
    onRequestDelete: () -> Unit = {},
) {
    val context = LocalContext.current
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var tools by remember { mutableStateOf<List<McpToolRow>>(emptyList()) }
    var disabled by remember { mutableStateOf(MCPToolPolicy.disabled(context, server.id)) }

    LaunchedEffect(server.id) {
        busy = true
        error = null
        // [T-p2-mcp-tools-inprocess] 工具列表改走 in-process 桥接（McpNativeBridge
        // 的 initialize + tools/list，HTTP 直连 / stdio 经 proot 派生），替换
        // `minis-mcp-cli tools` 的沙箱 shell 往返——那条路依赖 guest 里的 CLI 存在、
        // shell 存活与 25s 超时，任何一环断了工具列表就是空（「获取不到工具列表」）。
        val result = runCatching {
            withContext(Dispatchers.IO) {
                com.openminis.app.mcp.client.McpNativeBridge.listTools(
                    context, mcpRepository, "mcp-tools-ui", server.id,
                )
            }
        }
        result.fold(
            onSuccess = { text ->
                tools = parseMcpTools(text)
                disabled = MCPToolPolicy.disabled(context, server.id)
                if (tools.isEmpty()) error = text.ifBlank { "no tools reported" }
            },
            onFailure = { e ->
                error = e.message ?: e.javaClass.simpleName
                tools = emptyList()
            },
        )
        busy = false
    }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(bottom = 24.dp),
        ) {
            Text(
                stringResource(R.string.mcp_tools_title, server.id),
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            )
            Text(
                stringResource(R.string.mcp_tools_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp),
            )
            when {
                busy -> Text(
                    stringResource(R.string.mcp_tools_loading),
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                tools.isEmpty() -> Text(
                    error ?: stringResource(R.string.mcp_tools_empty),
                    modifier = Modifier.padding(20.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> tools.forEach { tool ->
                    val on = tool.name !in disabled
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 20.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(tool.name, style = MaterialTheme.typography.bodyLarge)
                            if (tool.description.isNotBlank()) {
                                Text(
                                    tool.description,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Switch(
                            checked = on,
                            onCheckedChange = { enabled ->
                                MCPToolPolicy.setEnabled(context, server.id, tool.name, enabled)
                                disabled = MCPToolPolicy.disabled(context, server.id)
                            },
                        )
                    }
                    HorizontalDivider()
                }
            }
            TextButton(
                onClick = onManage,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            ) { Text(stringResource(R.string.mcp_manage)) }
        }
    }
}

@Composable
fun MCPToolsScreen(
    mcpRepository: MCPRepository,
    serverId: String,
    onBack: () -> Unit,
    onManage: () -> Unit,
    onRequestDelete: () -> Unit = {},
) {
    val servers by mcpRepository.servers.collectAsState()
    val server = servers.find { it.id == serverId }
    val context = LocalContext.current
    var busy by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }
    var tools by remember { mutableStateOf<List<McpToolRow>>(emptyList()) }
    var disabled by remember(serverId) { mutableStateOf(MCPToolPolicy.disabled(context, serverId)) }
    // [T-p2-mcp-tools-delete] 删除入口就放在工具页：用户点服务器行进来的就是
    // 这里——旧路径要先点「管理」进编辑表单、再滚到底部找删除，找不到。
    var confirmDelete by remember { mutableStateOf(false) }

    LaunchedEffect(serverId, servers) {
        if (servers.isNotEmpty() && server == null) onBack()
    }

    LaunchedEffect(server?.id) {
        val current = server ?: return@LaunchedEffect
        busy = true
        error = null
        // [T-p2-mcp-tools-inprocess] in-process 桥接（同 Sheet）——不再依赖 guest
        // 里的 minis-mcp-cli / shell 存活 / 25s 超时。
        val result = runCatching {
            withContext(Dispatchers.IO) {
                com.openminis.app.mcp.client.McpNativeBridge.listTools(
                    context, mcpRepository, "mcp-tools-ui", current.id,
                )
            }
        }
        result.fold(
            onSuccess = { text ->
                tools = parseMcpTools(text)
                disabled = MCPToolPolicy.disabled(context, current.id)
                if (tools.isEmpty()) error = text.ifBlank { "no tools reported" }
            },
            onFailure = { e ->
                error = e.message ?: e.javaClass.simpleName
                tools = emptyList()
            },
        )
        busy = false
    }

    SettingsScaffold(
        title = stringResource(R.string.mcp_tools_title, server?.id ?: serverId),
        onBack = onBack,
    ) {
        Text(
            stringResource(R.string.mcp_tools_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        when {
            busy -> Text(
                stringResource(R.string.mcp_tools_loading),
                modifier = Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            tools.isEmpty() -> Text(
                error ?: stringResource(R.string.mcp_tools_empty),
                modifier = Modifier.padding(20.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            else -> tools.forEach { tool ->
                val on = tool.name !in disabled
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(tool.name, style = MaterialTheme.typography.bodyLarge)
                        if (tool.description.isNotBlank()) {
                            Text(
                                tool.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Switch(
                        checked = on,
                        onCheckedChange = { enabled ->
                            MCPToolPolicy.setEnabled(context, serverId, tool.name, enabled)
                            disabled = MCPToolPolicy.disabled(context, serverId)
                        },
                    )
                }
                HorizontalDivider()
            }
        }
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(
                onClick = { confirmDelete = true },
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            ) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            TextButton(
                onClick = onManage,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            ) { Text(stringResource(R.string.mcp_manage)) }
        }
    }
    // [T-p2-mcp-tools-delete] 删除确认：与 MCPIntegrationsScreen 的对话框同一文案。
    if (confirmDelete) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.mcp_delete_title, serverId)) },
            text = { Text(stringResource(R.string.mcp_delete_message)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    mcpRepository.delete(serverId)
                    onBack()
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }
}

private fun parseMcpTools(output: String): List<McpToolRow> {
    val start = output.indexOf('{')
    val end = output.lastIndexOf('}')
    if (start < 0 || end <= start) return emptyList()
    return try {
        val obj = JSONObject(output.substring(start, end + 1))
        val arr: JSONArray = obj.optJSONArray("tools") ?: return emptyList()
        buildList {
            for (i in 0 until arr.length()) {
                when (val item = arr.get(i)) {
                    is JSONObject -> {
                        val name = item.optString("name")
                        if (name.isNotBlank()) add(McpToolRow(name, item.optString("description")))
                    }
                    is String -> if (item.isNotBlank()) add(McpToolRow(item, ""))
                }
            }
        }
    } catch (_: Exception) {
        emptyList()
    }
}
