package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.sandbox.ExecutionCoordinator
import com.openminis.app.ui.components.MinisTextButton

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MCPToolsSheet(
    server: MCPRepository.MCPServerConfig,
    onDismiss: () -> Unit,
    onManage: () -> Unit,
) {
    var body by remember(server.id) { mutableStateOf("") }
    var busy by remember(server.id) { mutableStateOf(true) }
    LaunchedEffect(server.id) {
        val quoted = "'" + server.id.replace("'", "'\\''") + "'"
        val result = ExecutionCoordinator.execute(
            sessionId = "mcp-tools-ui",
            command = "minis-mcp-cli tools $quoted",
            timeout = 25_000L,
        )
        body = result.output.ifBlank { result.exitCode.toString() }
        busy = false
    }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(server.id, style = MaterialTheme.typography.titleMedium)
            Text(
                server.transportSummary,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            MinisTextButton(onClick = onManage) { Text(stringResource(R.string.mcp_manage)) }
            Text(
                if (busy) stringResource(R.string.mcp_tools_loading) else body,
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(bottom = 24.dp),
            )
        }
    }
}
