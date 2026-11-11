package com.openminis.app.ui.settings
import com.openminis.app.ui.theme.m3Icon

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import kotlinx.coroutines.launch
import androidx.compose.material.icons.outlined.Dns
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.data.repository.MCPRepository
import com.openminis.app.mcp.server.McpHttpServer
import com.openminis.app.mcp.server.McpServerConfigStore
import com.openminis.app.mcp.server.McpServerManager
import com.openminis.app.ui.components.DialogTextField
import com.openminis.app.ui.components.MinisTextButton
/** MCP integrations: server rows, add/edit sheet, built-in server + approval inbox. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MCPIntegrationsScreen(
    mcpRepository: MCPRepository,
    onBack: () -> Unit,
    onOpenTools: (MCPRepository.MCPServerConfig) -> Unit = {},
    // [T-mcp-env-var-picker-android] App env vars for the STDIO env "insert app var" picker; null hides the affordance.
    envVarRepository: com.openminis.app.data.repository.EnvVarRepository? = null,
) {
    val servers by mcpRepository.servers.collectAsStateWithLifecycle()

    // [T-android-mcp-list-reload-on-appear] Re-read servers.json on entry so CLI-added servers show without restart.
    LaunchedEffect(Unit) {
        mcpRepository.reloadFromDisk()
    }

    var showAddSheet by remember { mutableStateOf(false) }
    // [T-mcp-review-fixes-android] FIX 1: row tap opens EDIT mode; null = add flow.
    var editServer by remember { mutableStateOf<MCPRepository.MCPServerConfig?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }

    // ─── 内置 MCP Server（实验）─────────────────────────────────────────────
    // Config store is created in init(); null-safe reads fall back to defaults pre-init.
    val mcpStore = remember { McpServerManager.configStore() }
    val mcpConfig = mcpStore?.config?.collectAsStateWithLifecycle()?.value
        ?: McpServerConfigStore.Config()
    val mcpState by McpServerManager.state.collectAsStateWithLifecycle()
    var showPortDialog by remember { mutableStateOf(false) }
    var showTokenDialog by remember { mutableStateOf(false) }
    var showShellPolicyDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    // [T-mcp-disk-io] delete/setPort/regenerateToken run off the main thread.
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var builtinServerBusy by remember { mutableStateOf(false) }

    SettingsScaffold(
        title = stringResource(R.string.mcp_title),
        onBack = null,
        actions = {
            IconButton(onClick = { showAddSheet = true }) {
                Icon(m3Icon(Icons.Default.Add), contentDescription = stringResource(R.string.mcp_add))
            }
        },
    ) {
        MCPMergedServerList(
            servers = servers,
            mcpRepository = mcpRepository,
            onEditServer = { editServer = it },
            onOpenTools = onOpenTools,
        )
        // ─── 内置 MCP Server（实验）──────────────────────────────────────────
        val builtInPort = mcpConfig.port
        SettingsSection(
            header = stringResource(R.string.mcp_builtin_section),
            footer = null,
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.mcp_builtin_enable),
                subtitle = when (mcpState.status) {
                    McpServerManager.Status.RUNNING -> {
                        val actual = mcpState.actualPort ?: builtInPort
                        if (actual != builtInPort) {
                            stringResource(
                                R.string.mcp_status_running_shifted,
                                actual, builtInPort, mcpState.handledRequests,
                            )
                        } else {
                            stringResource(
                                R.string.mcp_status_running,
                                actual, mcpState.handledRequests,
                            )
                        }
                    }
                    McpServerManager.Status.ERROR -> stringResource(
                        R.string.mcp_status_error,
                        mcpState.error ?: stringResource(R.string.mcp_error_unknown),
                    )
                    McpServerManager.Status.STOPPED -> stringResource(
                        R.string.mcp_status_stopped, builtInPort,
                    )
                },
                icon = Icons.Outlined.Dns,
                checked = mcpConfig.enabled,
                onCheckedChange = { checked ->
                    if (checked) {
                        mcpStore?.setEnabled(true)
                        if (!McpServerManager.start()) {
                            mcpStore?.setEnabled(false)
                            android.widget.Toast.makeText(
                                context,
                                context.getString(
                                    R.string.mcp_start_failed_toast,
                                    McpServerManager.state.value.error
                                        ?: context.getString(R.string.mcp_error_unknown),
                                ),
                                android.widget.Toast.LENGTH_SHORT,
                            ).show()
                        }
                    } else {
                        mcpStore?.setEnabled(false)
                        McpServerManager.stop()
                    }
                },
            )
            SettingsRow(
                title = stringResource(R.string.mcp_builtin_port),
                subtitle = stringResource(R.string.mcp_builtin_port_sub, builtInPort),
                icon = Icons.Outlined.Terminal,
                onClick = { showPortDialog = true },
            )
            SettingsRow(
                title = stringResource(R.string.mcp_builtin_token),
                subtitle = mcpConfig.token?.let { stringResource(R.string.mcp_builtin_token_configured, it.take(4) + "****" + it.takeLast(4)) }
                    ?: stringResource(R.string.mcp_builtin_token_missing),
                icon = Icons.Outlined.Key,
                onClick = { showTokenDialog = true },
            )
            SettingsRow(
                title = stringResource(R.string.mcp_builtin_shell_policy),
                subtitle = if (mcpConfig.shellPolicy == McpServerConfigStore.SHELL_POLICY_STRICT)
                    stringResource(R.string.mcp_builtin_shell_strict)
                else
                    stringResource(R.string.mcp_builtin_shell_auto),
                icon = Icons.Outlined.Info,
                onClick = { showShellPolicyDialog = true },
            )
            SettingsRow(
                title = stringResource(R.string.mcp_builtin_pc_connect),
                subtitle = stringResource(R.string.mcp_builtin_pc_connect_sub, builtInPort),
                icon = Icons.Outlined.Language,
                showChevron = false,
                showDivider = false,
            )
        }
        // [T-mcp-approval-ui] Decision inbox for waiting clients; silent when idle.
        val approvalRuntime = remember { McpServerManager.ensureApprovalRuntime() }
        McpApprovalSection(approvalRuntime) { McpServerManager.approvalSurface() }
        Spacer(Modifier.height(24.dp))
    }

    if (showAddSheet) {
        MCPAddSheet(
            mcpRepository = mcpRepository,
            envVarRepository = envVarRepository,
            editServer = null,
            onDismiss = { showAddSheet = false },
            onRequestDelete = {},
        )
    }

    editServer?.let { server ->
        MCPAddSheet(
            mcpRepository = mcpRepository,
            envVarRepository = envVarRepository,
            editServer = server,
            onDismiss = { editServer = null },
            onRequestDelete = {
                editServer = null
                deleteId = server.id
            },
        )
    }

    if (deleteId != null) {
        val id = deleteId!!
        AlertDialog(
            onDismissRequest = { deleteId = null },
            title = { Text(stringResource(R.string.mcp_delete_title, id)) },
            text = { Text(stringResource(R.string.mcp_delete_message)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    deleteId = null
                    scope.launch {
                        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            mcpRepository.delete(id)
                        }
                    }
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                MinisTextButton(onClick = { deleteId = null }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    // ─── 内置 Server：端口修改对话框 ─────────────────────────────────────────
    if (showPortDialog) {
        var portText by remember(showPortDialog) { mutableStateOf(mcpConfig.port.toString()) }
        AlertDialog(
            onDismissRequest = { showPortDialog = false },
            title = { Text(stringResource(R.string.mcp_builtin_port_edit)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.mcp_builtin_port_edit_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    DialogTextField(
                        value = portText,
                        onValueChange = { portText = it.filter { c -> c.isDigit() }.take(5) },
                        singleLine = true,
                    )
                }
            },
            confirmButton = {
                MinisTextButton(onClick = {
                    val p = portText.toIntOrNull()
                    if (p != null && p in McpHttpServer.MIN_PORT..McpHttpServer.MAX_PORT && !builtinServerBusy) {
                        builtinServerBusy = true
                        showPortDialog = false
                        scope.launch {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                mcpStore?.setPort(p)
                            }
                            builtinServerBusy = false
                            if (mcpState.status == McpServerManager.Status.RUNNING) {
                                McpServerManager.restart()
                            }
                        }
                    }
                }) { Text(stringResource(R.string.common_save)) }
            },
            dismissButton = {
                MinisTextButton(onClick = { showPortDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    // ─── 内置 Server：Token 对话框 ──────────────────────────────────────
    if (showTokenDialog) {
        val currentToken = mcpConfig.token
        AlertDialog(
            onDismissRequest = { showTokenDialog = false },
            title = { Text(stringResource(R.string.mcp_builtin_token_dialog_title)) },
            text = {
                Column {
                    Text(
                        currentToken?.let { it.take(4) + "****" + it.takeLast(4) }
                            ?: stringResource(R.string.mcp_builtin_token_not_generated),
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    )
                    Text(
                        stringResource(R.string.mcp_builtin_token_dialog_body),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                Row {
                    MinisTextButton(onClick = {
                        if (builtinServerBusy) return@MinisTextButton
                        builtinServerBusy = true
                        scope.launch {
                            // [T-mcp-disk-io] Token generation writes the config store off-main, guarded by builtinServerBusy.
                            val newToken = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                mcpStore?.regenerateToken()
                            }
                            builtinServerBusy = false
                            if (newToken != null) {
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(newToken))
                                android.widget.Toast.makeText(context, context.getString(R.string.mcp_builtin_token_regenerated_toast), android.widget.Toast.LENGTH_SHORT).show()
                            }
                        }
                    }) { Text(stringResource(R.string.mcp_builtin_token_regenerate)) }
                    currentToken?.let {
                        MinisTextButton(onClick = {
                            clipboard.setText(androidx.compose.ui.text.AnnotatedString(it))
                            android.widget.Toast.makeText(context, context.getString(R.string.mcp_export_copied), android.widget.Toast.LENGTH_SHORT).show()
                        }) { Text(stringResource(R.string.common_copy)) }
                    }
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { showTokenDialog = false }) { Text(stringResource(R.string.cancel)) }
            },
        )
    }

    // ─── 内置 Server：Shell 策略对话框 ────────────────────────────────
    if (showShellPolicyDialog) {
        AlertDialog(
            onDismissRequest = { showShellPolicyDialog = false },
            title = { Text(stringResource(R.string.mcp_builtin_shell_dialog_title)) },
            text = {
                Column {
                    Text(
                        stringResource(R.string.mcp_builtin_shell_dialog_sub),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = mcpConfig.shellPolicy == McpServerConfigStore.SHELL_POLICY_AUTO,
                            onClick = {
                                mcpStore?.setShellPolicy(McpServerConfigStore.SHELL_POLICY_AUTO)
                            },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                        ) { Text("auto") }
                        SegmentedButton(
                            selected = mcpConfig.shellPolicy == McpServerConfigStore.SHELL_POLICY_STRICT,
                            onClick = {
                                mcpStore?.setShellPolicy(McpServerConfigStore.SHELL_POLICY_STRICT)
                            },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                        ) { Text("strict") }
                    }
                }
            },
            confirmButton = {
                MinisTextButton(onClick = { showShellPolicyDialog = false }) { Text(stringResource(R.string.common_done)) }
            },
        )
    }
}

// ─── Add Sheet (Form / Import tabs) ─────────────────────────────────────────

/** Add/Edit bottom sheet: add flow (Form/Import tabs) when [editServer] is null, else edit flow with Delete. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MCPAddSheet(
    mcpRepository: MCPRepository,
    envVarRepository: com.openminis.app.data.repository.EnvVarRepository?,
    editServer: MCPRepository.MCPServerConfig?,
    onDismiss: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    // [T-android-mcp-sheet-ime-occlusion] GH#44: open full-height so the keyboard doesn't cover lower fields.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var selectedTab by remember { mutableIntStateOf(0) }
    val isEdit = editServer != null

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        // [T-android-mcp-sheet-ime-occlusion] GH#44: verticalScroll + imePadding keep fields reachable while the keyboard is up.
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = 32.dp)
                .imePadding()
                .navigationBarsPadding(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(if (isEdit) R.string.mcp_edit else R.string.mcp_add),
                style = MaterialTheme.typography.titleMedium,
            )

            if (!isEdit) {
                TabRow(selectedTabIndex = selectedTab) {
                    Tab(selected = selectedTab == 0, onClick = { selectedTab = 0 }) {
                        Text(stringResource(R.string.mcp_tab_form), modifier = Modifier.padding(12.dp))
                    }
                    Tab(selected = selectedTab == 1, onClick = { selectedTab = 1 }) {
                        Text(stringResource(R.string.mcp_tab_import), modifier = Modifier.padding(12.dp))
                    }
                }
            }

            if (isEdit || selectedTab == 0) {
                MCPFormTab(
                    mcpRepository = mcpRepository,
                    envVarRepository = envVarRepository,
                    editServer = editServer,
                    onDone = onDismiss,
                    onRequestDelete = onRequestDelete,
                )
            } else {
                MCPImportTab(mcpRepository = mcpRepository, onDone = onDismiss)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MCPFormTab(
    mcpRepository: MCPRepository,
    envVarRepository: com.openminis.app.data.repository.EnvVarRepository?,
    editServer: MCPRepository.MCPServerConfig?,
    onDone: () -> Unit,
    onRequestDelete: () -> Unit,
) {
    val context = LocalContext.current
    val isEdit = editServer != null
    // [T-mcp-disk-io] Save writes servers.json on IO; isSaving guards double-taps.
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    var isSaving by remember { mutableStateOf(false) }
    // [T-mcp-server-export-android] Clipboard for "Copy JSON".
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current
    // [T-mcp-env-var-picker-android] App env var keys for the STDIO env picker.
    val appEnvEntries by (envVarRepository?.entries
        ?.collectAsStateWithLifecycle() ?: remember { mutableStateOf(emptyList<com.openminis.app.data.repository.EnvVarRepository.EnvVarEntry>()) })
    val appEnvKeys = remember(appEnvEntries) { appEnvEntries.map { it.key } }
    var showEnvVarPicker by remember { mutableStateOf(false) }
    // [T-mcp-http-headers-env-picker-android] Same $$VAR picker on HTTP/SSE headers + URL fields.
    var showHeaderVarPicker by remember { mutableStateOf(false) }
    var showUrlVarPicker by remember { mutableStateOf(false) }
    // [T-mcp-review-fixes-android] FIX 4: transport 0=HTTP, 1=SSE, 2=STDIO; HTTP/SSE store as url-only, STDIO uses command/args/env.
    var transport by remember { mutableIntStateOf(if (editServer?.isStdio == true) 2 else 0) }
    var name by remember { mutableStateOf(editServer?.id ?: "") }
    var note by remember { mutableStateOf(editServer?.note ?: "") }
    var url by remember { mutableStateOf(editServer?.url ?: "") }
    var headers by remember { mutableStateOf(mapToLines(editServer?.headers, ": ")) }
    var command by remember { mutableStateOf(editServer?.command ?: "") }
    var args by remember { mutableStateOf(editServer?.args?.joinToString(" ") ?: "") }
    var env by remember { mutableStateOf(mapToLines(editServer?.env, "=")) }
    // [T-mcp-startup-timeout] Optional STDIO startup timeout (seconds), text; blank = default 60s.
    var startupTimeout by remember { mutableStateOf(editServer?.startupTimeoutSeconds?.toString() ?: "") }
    var errorText by remember { mutableStateOf<String?>(null) }

    // [T-android-mcp-oauth] Static-OAuth fields (expanded when an oauth block exists); secret lives in MCPOAuthStore, not JSON.
    val oauthScope = androidx.compose.runtime.rememberCoroutineScope()
    var oauthExpanded by remember { mutableStateOf(editServer?.oauth != null) }
    var oauthClientId by remember { mutableStateOf(editServer?.oauth?.clientId ?: "") }
    var oauthClientSecret by remember {
        mutableStateOf(
            editServer?.id?.let {
                com.openminis.app.mcp.oauth.MCPOAuthStore.clientSecret(context, it)
            } ?: "",
        )
    }
    var oauthAuthEndpoint by remember { mutableStateOf(editServer?.oauth?.authorizationEndpoint ?: "") }
    var oauthTokenEndpoint by remember { mutableStateOf(editServer?.oauth?.tokenEndpoint ?: "") }
    var oauthScopes by remember { mutableStateOf(editServer?.oauth?.scopes ?: "") }
    var oauthAuthorized by remember {
        mutableStateOf(
            editServer?.id?.let {
                com.openminis.app.mcp.oauth.MCPOAuthStore.isAuthorized(context, it)
            } ?: false,
        )
    }
    var oauthBusy by remember { mutableStateOf(false) }

    fun currentOAuthConfig(): com.openminis.app.mcp.oauth.MCPOAuthConfig? {
        val cfg = com.openminis.app.mcp.oauth.MCPOAuthConfig(
            clientId = oauthClientId.trim(),
            authorizationEndpoint = oauthAuthEndpoint.trim(),
            tokenEndpoint = oauthTokenEndpoint.trim(),
            scopes = oauthScopes.trim().ifBlank { null },
        )
        return cfg.takeIf { it.isConfigured }
    }
    // [T-mcp-env-var-delete-confirm-android] Confirm before clearing a non-empty env field.
    var showClearEnvConfirm by remember { mutableStateOf(false) }

    // True for the url-based transports (HTTP / SSE).
    val isUrlTransport = transport != 2

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = transport == 0,
                onClick = { transport = 0; errorText = null },
                shape = SegmentedButtonDefaults.itemShape(0, 3),
            ) { Text(stringResource(R.string.mcp_transport_http)) }
            SegmentedButton(
                selected = transport == 1,
                onClick = { transport = 1; errorText = null },
                shape = SegmentedButtonDefaults.itemShape(1, 3),
            ) { Text(stringResource(R.string.mcp_transport_sse)) }
            SegmentedButton(
                selected = transport == 2,
                onClick = { transport = 2; errorText = null },
                shape = SegmentedButtonDefaults.itemShape(2, 3),
            ) { Text(stringResource(R.string.mcp_transport_stdio)) }
        }

        FieldLabel(stringResource(R.string.mcp_form_name))
        DialogTextField(
            value = name,
            onValueChange = { name = it; errorText = null },
            placeholder = stringResource(R.string.mcp_form_name_placeholder),
            singleLine = true,
            // Editing keys by name/id; renaming would orphan the row, so lock it.
            enabled = !isEdit,
        )

        if (isUrlTransport) {
            // [T-mcp-http-headers-env-picker-android] $$VAR picker on URL + Custom Headers; selecting appends `$$KEY`.
            Row(verticalAlignment = Alignment.CenterVertically) {
                FieldLabel(stringResource(R.string.mcp_form_url))
                if (envVarRepository != null) {
                    Spacer(Modifier.weight(1f))
                    MinisTextButton(
                        onClick = { showUrlVarPicker = true },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 8.dp, vertical = 0.dp,
                        ),
                    ) {
                        Text(stringResource(R.string.mcp_form_env_insert_app_var))
                    }
                }
            }
            DialogTextField(
                value = url,
                onValueChange = { url = it; errorText = null },
                placeholder = stringResource(R.string.mcp_form_url_placeholder),
                singleLine = true,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                FieldLabel(stringResource(R.string.mcp_form_headers))
                if (envVarRepository != null) {
                    Spacer(Modifier.weight(1f))
                    MinisTextButton(
                        onClick = { showHeaderVarPicker = true },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 8.dp, vertical = 0.dp,
                        ),
                    ) {
                        Text(stringResource(R.string.mcp_form_env_insert_app_var))
                    }
                }
            }
            DialogTextField(
                value = headers,
                onValueChange = { headers = it },
                placeholder = stringResource(R.string.mcp_form_headers_placeholder),
                singleLine = false,
                modifier = Modifier.height(72.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )

            // [T-android-mcp-oauth] Static OAuth section (collapsible); PKCE authorize + attach bearer token at connect.
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { oauthExpanded = !oauthExpanded },
            ) {
                FieldLabel(stringResource(R.string.mcp_form_oauth_section))
                Spacer(Modifier.weight(1f))
                if (oauthAuthorized) {
                    Text(
                        stringResource(R.string.mcp_form_oauth_authorized),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.width(8.dp))
                }
                Icon(
                    imageVector = if (oauthExpanded) m3Icon(Icons.Default.KeyboardArrowUp)
                    else m3Icon(Icons.Default.KeyboardArrowDown),
                    contentDescription = null,
                )
            }
            if (oauthExpanded) {
                FieldLabel(stringResource(R.string.mcp_form_oauth_client_id))
                DialogTextField(
                    value = oauthClientId,
                    onValueChange = { oauthClientId = it },
                    placeholder = stringResource(R.string.mcp_form_oauth_client_id_placeholder),
                    singleLine = true,
                )
                FieldLabel(stringResource(R.string.mcp_form_oauth_client_secret))
                DialogTextField(
                    value = oauthClientSecret,
                    onValueChange = { oauthClientSecret = it },
                    placeholder = stringResource(R.string.mcp_form_oauth_client_secret_placeholder), singleLine = true,
                    visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(), // [GH#285] secret masked like provider keys
                )
                FieldLabel(stringResource(R.string.mcp_form_oauth_auth_endpoint))
                DialogTextField(
                    value = oauthAuthEndpoint,
                    onValueChange = { oauthAuthEndpoint = it },
                    placeholder = stringResource(R.string.mcp_form_oauth_auth_endpoint_placeholder),
                    singleLine = true,
                )
                FieldLabel(stringResource(R.string.mcp_form_oauth_token_endpoint))
                DialogTextField(
                    value = oauthTokenEndpoint,
                    onValueChange = { oauthTokenEndpoint = it },
                    placeholder = stringResource(R.string.mcp_form_oauth_token_endpoint_placeholder),
                    singleLine = true,
                )
                FieldLabel(stringResource(R.string.mcp_form_oauth_scopes))
                DialogTextField(
                    value = oauthScopes,
                    onValueChange = { oauthScopes = it },
                    placeholder = stringResource(R.string.mcp_form_oauth_scopes_placeholder),
                    singleLine = true,
                )
                Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    if (oauthAuthorized) {
                        MinisTextButton(
                            enabled = !oauthBusy,
                            onClick = {
                                val sid = name.trim()
                                if (sid.isNotEmpty()) {
                                    com.openminis.app.mcp.oauth.MCPOAuthStore.signOut(context, sid)
                                    oauthAuthorized = false
                                }
                            },
                        ) {
                            Text(
                                stringResource(R.string.mcp_form_oauth_sign_out),
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                    Spacer(Modifier.weight(1f))
                    MinisTextButton(
                        enabled = !oauthBusy,
                        onClick = {
                            val sid = name.trim()
                            val cfg = currentOAuthConfig()
                            when {
                                sid.isEmpty() ->
                                    errorText = context.getString(R.string.mcp_form_error_no_name)
                                url.isBlank() ->
                                    errorText = context.getString(R.string.mcp_form_error_no_transport)
                                cfg == null ->
                                    errorText = context.getString(R.string.mcp_form_oauth_incomplete)
                                else -> {
                                    errorText = null
                                    // Persist the secret for the token exchange; harmless if cancelled.
                                    com.openminis.app.mcp.oauth.MCPOAuthStore.setClientSecret(
                                        context, sid, oauthClientSecret.trim().ifBlank { null },
                                    )
                                    oauthBusy = true
                                    oauthScope.launch {
                                        val result = com.openminis.app.mcp.oauth.MCPOAuthController(context)
                                            .authorize(sid, url.trim(), cfg)
                                        oauthBusy = false
                                        when (result) {
                                            is com.openminis.app.mcp.oauth.MCPOAuthController.Result.Success -> {
                                                oauthAuthorized = true
                                                android.widget.Toast.makeText(
                                                    context,
                                                    context.getString(R.string.mcp_form_oauth_authorized),
                                                    android.widget.Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                            is com.openminis.app.mcp.oauth.MCPOAuthController.Result.Cancelled -> {}
                                            is com.openminis.app.mcp.oauth.MCPOAuthController.Result.Failed -> {
                                                errorText = result.message
                                            }
                                        }
                                    }
                                }
                            }
                        },
                    ) {
                        Text(
                            stringResource(
                                if (oauthAuthorized) R.string.mcp_form_oauth_reauthorize
                                else R.string.mcp_form_oauth_authorize,
                            ),
                        )
                    }
                }
            }
        } else {
            FieldLabel(stringResource(R.string.mcp_form_command))
            DialogTextField(
                value = command,
                onValueChange = { command = it; errorText = null },
                placeholder = stringResource(R.string.mcp_form_command_placeholder),
                singleLine = true,
            )
            FieldLabel(stringResource(R.string.mcp_form_args))
            DialogTextField(
                value = args,
                onValueChange = { args = it },
                placeholder = stringResource(R.string.mcp_form_args_placeholder),
                singleLine = true,
            )
            // [T-mcp-env-var-picker-android] "insert app var" picker appends a `KEY=$$KEY` line (resolved at runtime in PRoot).
            Row(verticalAlignment = Alignment.CenterVertically) {
                FieldLabel(stringResource(R.string.mcp_form_env))
                Spacer(Modifier.weight(1f))
                if (envVarRepository != null) {
                    MinisTextButton(
                        onClick = { showEnvVarPicker = true },
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(
                            horizontal = 8.dp, vertical = 0.dp,
                        ),
                    ) {
                        Text(stringResource(R.string.mcp_form_env_insert_app_var))
                    }
                }
                // [T-mcp-env-var-delete-confirm-android] Clear affordance: confirm when non-empty, else clear immediately.
                MinisTextButton(
                    onClick = {
                        if (env.isBlank()) env = "" else showClearEnvConfirm = true
                    },
                    enabled = env.isNotBlank(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 8.dp, vertical = 0.dp,
                    ),
                ) {
                    Text(
                        stringResource(R.string.mcp_form_env_clear),
                        color = if (env.isNotBlank()) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            DialogTextField(
                value = env,
                onValueChange = { env = it },
                placeholder = stringResource(R.string.mcp_form_env_placeholder),
                singleLine = false,
                modifier = Modifier.height(72.dp),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            FieldLabel(stringResource(R.string.mcp_form_startup_timeout))
            DialogTextField(
                value = startupTimeout,
                onValueChange = { startupTimeout = it.filter { c -> c.isDigit() } },
                placeholder = stringResource(R.string.mcp_form_startup_timeout_placeholder),
                singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                ),
            )
        }

        FieldLabel(stringResource(R.string.mcp_form_note))
        DialogTextField(
            value = note,
            onValueChange = { note = it },
            placeholder = stringResource(R.string.mcp_form_note_placeholder),
            singleLine = false,
            modifier = Modifier.height(64.dp),
        )

        if (errorText != null) {
            Text(errorText!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }

        // [T-mcp-server-export-android] Build config from current form fields (null + errorText when invalid).
        fun buildServerFromForm(): MCPRepository.MCPServerConfig? {
            if (name.isBlank()) {
                errorText = context.getString(R.string.mcp_form_error_no_name); return null
            }
            return if (isUrlTransport) {
                if (url.isBlank()) {
                    errorText = context.getString(R.string.mcp_form_error_no_transport); return null
                }
                MCPRepository.MCPServerConfig(
                    id = name.trim(),
                    note = note.trim().ifBlank { null },
                    enabled = editServer?.enabled ?: true,
                    url = url.trim(),
                    headers = parseKeyValueLines(headers, ':'),
                    // [T-android-mcp-oauth] Persist the non-secret OAuth config.
                    oauth = currentOAuthConfig(),
                    createdAt = editServer?.createdAt ?: System.currentTimeMillis(),
                )
            } else {
                if (command.isBlank()) {
                    errorText = context.getString(R.string.mcp_form_error_no_transport); return null
                }
                MCPRepository.MCPServerConfig(
                    id = name.trim(),
                    note = note.trim().ifBlank { null },
                    enabled = editServer?.enabled ?: true,
                    command = command.trim(),
                    args = args.trim().split(Regex("\\s+")).filter { it.isNotEmpty() },
                    env = parseKeyValueLines(env, '='),
                    startupTimeoutSeconds = startupTimeout.trim().toIntOrNull(),
                    createdAt = editServer?.createdAt ?: System.currentTimeMillis(),
                )
            }
        }

        // [T-mcp-server-export-android] Copy/Share the current config as mcpServers JSON.
        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            MinisTextButton(onClick = {
                val server = buildServerFromForm() ?: return@MinisTextButton
                clipboard.setText(androidx.compose.ui.text.AnnotatedString(mcpRepository.exportServerJSON(server)))
                android.widget.Toast.makeText(
                    context, context.getString(R.string.mcp_export_copied), android.widget.Toast.LENGTH_SHORT,
                ).show()
            }) { Text(stringResource(R.string.mcp_export_copy)) }
            MinisTextButton(onClick = {
                val server = buildServerFromForm() ?: return@MinisTextButton
                shareMcpServerJson(context, server.id, mcpRepository.exportServerJSON(server))
            }) { Text(stringResource(R.string.mcp_export_share)) }
        }

        Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            // FIX 1: Delete lives inside the edit sheet (plain row tap no longer deletes).
            if (isEdit) {
                MinisTextButton(onClick = onRequestDelete) {
                    Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error)
                }
            }
            Spacer(Modifier.weight(1f))
            MinisTextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) }
            MinisTextButton(onClick = {
                if (isSaving) return@MinisTextButton
                val server = buildServerFromForm() ?: return@MinisTextButton
                isSaving = true
                scope.launch {
                    // [T-mcp-disk-io] update/add + OAuth secret write run off-main; isSaving guards double-taps.
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                        // [T-android-mcp-oauth] Store the client secret in the encrypted store (keyed by id); never in servers.json.
                        if (isUrlTransport) {
                            com.openminis.app.mcp.oauth.MCPOAuthStore.setClientSecret(
                                context, server.id,
                                if (server.oauth != null) oauthClientSecret.trim().ifBlank { null } else null,
                            )
                        }
                        if (isEdit) mcpRepository.update(server) else mcpRepository.add(server)
                    }
                    isSaving = false
                    onDone()
                }
            },
            enabled = !isSaving,
        ) { Text(stringResource(R.string.mcp_form_save)) }
        }
    }

    // [T-mcp-env-var-picker-android] App env var picker; appends a `KEY=$$KEY` line to the env editor.
    if (showEnvVarPicker) {
        MCPEnvVarPickerSheet(
            keys = appEnvKeys,
            onPick = { key ->
                // [T-mcp-env-picker-insert-android] Append (not replace) a new KEY=$$KEY line so multiple vars accumulate.
                val line = "$key=$\$$key"
                env = if (env.isBlank()) line else env.trimEnd('\n') + "\n" + line
                showEnvVarPicker = false
            },
            onDismiss = { showEnvVarPicker = false },
        )
    }

    // [T-mcp-http-headers-env-picker-android] Headers/URL pickers append `$$KEY` (with a leading space if needed).
    if (showHeaderVarPicker) {
        MCPEnvVarPickerSheet(
            keys = appEnvKeys,
            onPick = { key ->
                val ref = "$\$$key"
                headers = if (headers.isEmpty() || headers.last().isWhitespace()) {
                    headers + ref
                } else {
                    "$headers $ref"
                }
                showHeaderVarPicker = false
            },
            onDismiss = { showHeaderVarPicker = false },
        )
    }
    if (showUrlVarPicker) {
        MCPEnvVarPickerSheet(
            keys = appEnvKeys,
            onPick = { key ->
                val ref = "$\$$key"
                url = if (url.isEmpty() || url.last().isWhitespace()) url + ref else "$url$ref"
                errorText = null
                showUrlVarPicker = false
            },
            onDismiss = { showUrlVarPicker = false },
        )
    }

    // [T-mcp-env-var-delete-confirm-android] Confirm before wiping a non-empty env field.
    if (showClearEnvConfirm) {
        AlertDialog(
            onDismissRequest = { showClearEnvConfirm = false },
            title = { Text(stringResource(R.string.mcp_form_env_clear_confirm_title)) },
            text = { Text(stringResource(R.string.mcp_form_env_clear_confirm_message)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    env = ""
                    showClearEnvConfirm = false
                }) { Text(stringResource(R.string.delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                MinisTextButton(onClick = { showClearEnvConfirm = false }) {
                    Text(stringResource(R.string.cancel))
                }
            },
        )
    }
}

/** [T-mcp-env-var-picker-android] Bottom sheet of App env var keys; picking one inserts a `$$KEY` via [onPick]. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MCPEnvVarPickerSheet(
    keys: List<String>,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                stringResource(R.string.mcp_env_picker_title),
                style = MaterialTheme.typography.titleMedium,
            )
            if (keys.isEmpty()) {
                Text(
                    stringResource(R.string.mcp_env_picker_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            } else {
                for (key in keys) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onPick(key) }
                            .padding(vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            "$\$$key",
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MCPImportTab(
    mcpRepository: MCPRepository,
    onDone: () -> Unit,
) {
    val context = LocalContext.current
    var text by remember { mutableStateOf("") }
    var errorText by remember { mutableStateOf<String?>(null) }
    // [T-mcp-import-debounce] Debounced to one parse per 300 ms of typing silence.
    var previewCount by remember { mutableStateOf(0) }
    LaunchedEffect(text) {
        kotlinx.coroutines.delay(300)
        val t = text
        previewCount = if (t.isBlank()) 0
        else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            mcpRepository.previewImport(t)
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(
            stringResource(R.string.mcp_import_instruction),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        DialogTextField(
            value = text,
            onValueChange = { text = it; errorText = null },
            placeholder = stringResource(R.string.mcp_import_placeholder),
            singleLine = false,
            modifier = Modifier.height(200.dp),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        if (text.isNotBlank()) {
            Text(
                stringResource(R.string.mcp_import_found, previewCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (errorText != null) {
            Text(errorText!!, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            MinisTextButton(onClick = onDone) { Text(stringResource(R.string.cancel)) }
            MinisTextButton(
                onClick = {
                    val imported = mcpRepository.importJSON(text)
                    if (imported.isEmpty()) errorText = context.getString(R.string.mcp_import_none)
                    else onDone()
                },
                enabled = previewCount > 0,
            ) { Text(stringResource(R.string.mcp_import_submit)) }
        }
    }
}

@Composable
private fun FieldLabel(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

// [T-mcp-server-export-android] Share [json] (a single-server mcpServers config) via ACTION_SEND using the shared FileProvider helper.
private fun shareMcpServerJson(context: android.content.Context, serverId: String, json: String) {
    val safeName = serverId.ifBlank { "mcp-server" }
        .replace(Regex("[^A-Za-z0-9._-]"), "_")
    val dir = java.io.File(context.cacheDir, "shared").apply { mkdirs() }
    val file = java.io.File(dir, "$safeName-mcp.json")
    runCatching { file.writeText(json) }.onFailure { return }
    // [T-share-fileprovider-unify] Shared FileProvider helper (clip + double grant hardening).
    shareViaFileProvider(
        context, file, "application/json",
        context.getString(R.string.mcp_share_chooser_title),
    )
}

private fun mapToLines(map: Map<String, String>?, joiner: String): String {
    if (map.isNullOrEmpty()) return ""
    return map.entries.joinToString("\n") { "${it.key}$joiner${it.value}" }
}

private fun parseKeyValueLines(text: String, sep: Char): Map<String, String> {
    val out = LinkedHashMap<String, String>()
    for (line in text.lines()) {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) continue
        val idx = trimmed.indexOf(sep)
        if (idx <= 0) continue
        val key = trimmed.substring(0, idx).trim()
        val value = trimmed.substring(idx + 1).trim()
        if (key.isNotEmpty()) out[key] = value
    }
    return out
}
