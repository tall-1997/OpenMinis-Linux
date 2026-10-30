package com.openminis.app.ui.settings

import android.content.Context
import android.content.Intent
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.outlined.CloudUpload
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.VisibilityOff
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.openminis.app.R
import com.openminis.app.a2a.A2aGateway
import com.openminis.app.ui.components.MinisCenterTopBar
import com.openminis.app.ui.components.MinisTextButton
import com.openminis.app.ui.theme.m3Icon
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * The a2a gateway's first-class management page: status card, install /
 * start-stop control, and an in-app panel view.
 *
 * [T-android-a2a-native] The engine sits behind the pinned A2aGateway
 * contract (parallel workstream); this screen only reads its status
 * StateFlow and calls its methods. All user-visible state is derived in
 * [a2aViewState] (pure, JVM-tested) so the status ladder — installing →
 * not-installed → stopped → running, error overlaying all — is decided in
 * one place.
 *
 * Manage / Panel switch in place (LogManagementScreen's segmented-tab
 * idiom, no sub-route). The panel tab launches the gateway's local web
 * panel as a dedicated :a2a-process activity — nothing here ever leaves
 * the app, and the panel's WebView storage is isolated from this process.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun A2aScreen(
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val status by A2aGateway.status.collectAsStateWithLifecycle()
    var tab by remember { mutableStateOf(A2aTab.MANAGE) }
    var installing by remember { mutableStateOf(false) }
    var installFailed by remember { mutableStateOf(false) }
    // [T-android-a2a-incognito] Read once here and carried in the launch
    // intent — the :a2a process must not re-read SharedPreferences.
    var incognitoLogin by remember { mutableStateOf(A2aGateway.isIncognitoLogin(context)) }
    val scope = rememberCoroutineScope()

    val viewState = a2aViewState(status, installing)

    // Cold-app re-entry: the singleton StateFlow starts at defaults, so
    // re-derive installed/running from disk + the port probe once per
    // screen visit — otherwise a deployed gateway reads as "not installed"
    // until the user touches a control.
    LaunchedEffect(Unit) {
        kotlinx.coroutines.withContext(Dispatchers.IO) {
            A2aGateway.refresh(context)
            // [T-android-a2a-panel-cold-start] Warm the engine while the user
            // is still on the manage tab, so the panel opens into a serving
            // gateway instead of racing its boot on the first tap.
            if (A2aGateway.isEnabled(context) && !A2aGateway.status.value.running) {
                A2aGateway.maybeAutoStart(context)
            }
        }
    }

    // The panel lives in its own process-activity (started from the panel
    // tab), so this screen has no embedded WebView to walk back through —
    // back simply leaves the screen.

    Scaffold(
        topBar = {
            MinisCenterTopBar(
                title = {
                    Text(
                        stringResource(R.string.builtin_plugin_a2a_name),
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            m3Icon(Icons.AutoMirrored.Filled.ArrowBack),
                            contentDescription = stringResource(R.string.common_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                ),
            )
        },
        containerColor = MaterialTheme.colorScheme.background,
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            SingleChoiceSegmentedButtonRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
            ) {
                SegmentedButton(
                    selected = tab == A2aTab.MANAGE,
                    onClick = { tab = A2aTab.MANAGE },
                    shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                ) { Text(stringResource(R.string.mcp_manage)) }
                SegmentedButton(
                    selected = tab == A2aTab.PANEL,
                    onClick = { tab = A2aTab.PANEL },
                    shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                ) { Text(stringResource(R.string.builtin_plugin_open_panel)) }
            }

            when (tab) {
                A2aTab.MANAGE -> A2aManageBody(
                    viewState = viewState,
                    installing = installing,
                    installFailed = installFailed,
                    incognitoLogin = incognitoLogin,
                    onIncognitoChange = { enabled ->
                        incognitoLogin = enabled
                        A2aGateway.setIncognitoLogin(context, enabled)
                    },
                    onToggleRunning = { turnOn ->
                        if (turnOn) {
                            // Flipping the switch on a not-yet-deployed
                            // gateway installs first, transparently.
                            scope.launch(Dispatchers.IO) {
                                if (!A2aGateway.isInstalled(context)) {
                                    A2aGateway.ensureInstalled(context)
                                }
                                A2aGateway.start(context)
                            }
                        } else {
                            scope.launch(Dispatchers.IO) { A2aGateway.stop(context) }
                        }
                    },
                    onInstall = {
                        installing = true
                        installFailed = false
                        scope.launch(Dispatchers.IO) {
                            val ok = runCatching {
                                A2aGateway.ensureInstalled(context)
                                A2aGateway.isInstalled(context)
                            }.getOrDefault(false)
                            installFailed = !ok
                            installing = false
                        }
                    },
                )
                A2aTab.PANEL -> A2aPanelBody(
                    viewState = viewState,
                    incognitoLogin = incognitoLogin,
                )
            }
        }
    }
}

/** Manage tab: status card (state / port / address / error) + control. */
@Composable
private fun A2aManageBody(
    viewState: A2aViewState,
    installing: Boolean,
    installFailed: Boolean,
    incognitoLogin: Boolean,
    onIncognitoChange: (Boolean) -> Unit,
    onToggleRunning: (Boolean) -> Unit,
    onInstall: () -> Unit,
) {
    var showLog by remember { mutableStateOf(false) }
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
    ) {
        SettingsSection(header = stringResource(R.string.shizuku_status_header)) {
            // State badge row: icon chip + ladder label.
            SettingsRow(
                title = stringResource(a2aStatusLabelRes(viewState.state)),
                icon = a2aStatusIcon(viewState.state),
                iconColor = a2aStatusColor(viewState.state),
                showChevron = false,
            )
            // Port + copyable panel address, whenever the engine reports
            // a port (the contract owns the number, currently 18901).
            if (viewState.port != null) {
                val clipboard = LocalClipboardManager.current
                val address = remember(viewState.port) { a2aDisplayAddress(viewState.port) }
                SettingsValueRow(
                    title = stringResource(R.string.mcp_builtin_port),
                    value = viewState.port.toString(),
                )
                SettingsRow(
                    title = stringResource(R.string.a2a_panel_address),
                    subtitle = address,
                    showChevron = false,
                    showDivider = viewState.lastError != null || !viewState.installed,
                    trailing = {
                        IconButton(onClick = { clipboard.setText(AnnotatedString(address)) }) {
                            Icon(
                                m3Icon(Icons.Filled.ContentCopy),
                                contentDescription = stringResource(R.string.common_copy),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                    },
                )
            }
            // lastError: red row of its own (contract: engine-reported).
            if (viewState.lastError != null) {
                SettingsRow(
                    title = viewState.lastError,
                    titleColor = MaterialTheme.colorScheme.error,
                    icon = Icons.Outlined.ErrorOutline,
                    iconColor = Color(0xFFFF3B30),
                    showChevron = false,
                    showDivider = !viewState.installed,
                )
            }
            // Install / initialise entry while the gateway is not on disk.
            if (!viewState.installed) {
                SettingsRow(
                    title = stringResource(
                        when {
                            installing -> R.string.dev_suite_installing
                            installFailed -> R.string.builtin_plugin_retry
                            else -> R.string.builtin_plugin_install
                        },
                    ),
                    onClick = if (installing) null else onInstall,
                    showChevron = false,
                    showDivider = false,
                    trailing = {
                        if (installing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        }
                    },
                )
            }
        }

        // Start/stop switch — only meaningful once the gateway is installed.
        if (viewState.installed) {
            SettingsSection(footer = stringResource(R.string.a2a_desktop_bridge_footer)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.a2a_start_gateway),
                    checked = viewState.running,
                    onCheckedChange = onToggleRunning,
                    icon = Icons.Outlined.CloudUpload,
                    iconColor = Color(0xFF34C759),
                    showDivider = true,
                )
                // [T-android-a2a-login-capture] Each login window starts from a
                // cleared jar: adding a second account of the same provider
                // never reuses the first one's session.
                SettingsSwitchRow(
                    title = stringResource(R.string.a2a_incognito_login_title),
                    subtitle = stringResource(R.string.a2a_incognito_login_subtitle),
                    checked = incognitoLogin,
                    onCheckedChange = onIncognitoChange,
                    icon = Icons.Outlined.VisibilityOff,
                    iconColor = Color(0xFF8E8E93),
                    showDivider = true,
                )
                // The engine's own log — the only place a failed login state
                // machine explains itself.
                SettingsRow(
                    title = stringResource(R.string.a2a_view_gateway_log),
                    onClick = { showLog = true },
                    icon = Icons.Outlined.Description,
                    showDivider = false,
                )
            }
        }
    }
    if (showLog) {
        A2aGatewayLogDialog(onDismiss = { showLog = false })
    }
}

/** Panel tab: one open action; the panel activity starts the gateway itself. */
@Composable
private fun A2aPanelBody(
    viewState: A2aViewState,
    incognitoLogin: Boolean,
) {
    val context = LocalContext.current
    // [T-android-a2a-panel-cold-start] "Open panel" must mean open panel:
    // the button shows whenever the gateway is deployed, and the panel
    // activity starts/waits for the engine on its own. Previously the button
    // only appeared after the status snapshot said "running", so a cold start
    // made the first taps land on guidance or a connection error.
    val installing = viewState.state == A2aUiState.INSTALLING
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(horizontal = 32.dp),
        ) {
            Icon(
                imageVector = m3Icon(Icons.Outlined.Lan),
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(36.dp),
            )
            Spacer(Modifier.height(12.dp))
            Text(
                text = stringResource(
                    when {
                        installing -> R.string.dev_suite_installing
                        !viewState.installed -> R.string.a2a_panel_not_installed
                        viewState.running -> R.string.a2a_panel_isolated_hint
                        else -> R.string.a2a_panel_needs_start
                    },
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            // The activity deploys/starts the gateway itself, so the first
            // tap always lands in the panel — no visit-the-manage-tab detour.
            if (!installing) {
                Spacer(Modifier.height(16.dp))
                MinisTextButton(onClick = {
                    context.startActivity(
                        Intent(context, A2aPanelActivity::class.java)
                            .putExtra(A2aPanelActivity.EXTRA_PORT, viewState.port ?: A2aGateway.PORT)
                            .putExtra(A2aPanelActivity.EXTRA_INCOGNITO_LOGIN, incognitoLogin),
                    )
                }) {
                    Text(
                        stringResource(
                            if (viewState.running) R.string.builtin_plugin_open_panel
                            else R.string.a2a_open_panel_and_start,
                        ),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

// ==================== Pure view-model (JVM-testable) ====================

/** Which of the two page-internal views the segmented control shows. */
internal enum class A2aTab { MANAGE, PANEL }

/** The five screen states the status card can render. */
internal enum class A2aUiState { INSTALLING, NOT_INSTALLED, STOPPED, RUNNING, ERROR }

/**
 * Everything the manage card renders, derived from the gateway contract's
 * [com.openminis.app.a2a.A2aStatus] plus this screen's own
 * install-in-flight flag. Pure: no android.* types, unit-tested in
 * A2aViewStateTest.
 *
 * Ladder precedence (highest first): INSTALLING → ERROR → NOT_INSTALLED →
 * RUNNING → STOPPED. The installing flag outranks everything so the badge
 * never flickers back to "not installed" mid-deploy; a reported error
 * overlays the service state (the engine surfaces what went wrong).
 */
internal data class A2aViewState(
    val state: A2aUiState,
    val installed: Boolean,
    val running: Boolean,
    val port: Int?,
    val panelUrl: String?,
    val lastError: String?,
)

/** Adapt the engine contract to the view state. */
internal fun a2aViewState(status: com.openminis.app.a2a.A2aStatus, installing: Boolean): A2aViewState {
    val state = when {
        installing -> A2aUiState.INSTALLING
        status.lastError != null -> A2aUiState.ERROR
        !status.installed -> A2aUiState.NOT_INSTALLED
        status.running -> A2aUiState.RUNNING
        else -> A2aUiState.STOPPED
    }
    return A2aViewState(
        state = state,
        installed = status.installed,
        running = status.running,
        port = status.port.takeIf { it > 0 },
        // Contract first; while running, the loopback root is a safe
        // fallback so the panel tab never dead-ends on a null URL.
        panelUrl = status.panelUrl
            ?: status.port.takeIf { status.running && it > 0 }?.let { a2aEndpointUrl(it) },
        lastError = status.lastError,
    )
}

/**
 * Loopback base URL for the panel at [port]. The engine binds 127.0.0.1
 * by contract (never "localhost" — an IPv6-preferring resolver can answer
 * ::1 where nothing listens); the origin check in [A2aPanelNav] pins panel
 * navigations to exactly this origin.
 */
internal fun a2aEndpointUrl(port: Int): String = "http://127.0.0.1:$port/"

/** Copyable "host:port" form of the panel address. */
internal fun a2aDisplayAddress(port: Int): String = "127.0.0.1:$port"

/**
 * Badge label for the status row, keyed off the state ladder. Reuses the
 * generic family strings (not-installed / stopped / error / installing)
 * so only genuinely a2a-specific copy is new.
 */
internal fun a2aStatusLabelRes(state: A2aUiState): Int = when (state) {
    A2aUiState.INSTALLING -> R.string.dev_suite_installing
    A2aUiState.NOT_INSTALLED -> R.string.dev_suite_not_installed
    A2aUiState.STOPPED -> R.string.backup_history_stopped
    A2aUiState.RUNNING -> R.string.a2a_status_running
    A2aUiState.ERROR -> R.string.filebrowser_error_title
}

/** Status-row icon, purely derived so the ladder stays testable. */
private fun a2aStatusIcon(state: A2aUiState) = when (state) {
    A2aUiState.INSTALLING -> Icons.Outlined.CloudUpload
    A2aUiState.NOT_INSTALLED -> Icons.Outlined.CloudUpload
    A2aUiState.STOPPED -> Icons.Outlined.Stop
    A2aUiState.RUNNING -> Icons.Filled.CheckCircle
    A2aUiState.ERROR -> Icons.Outlined.ErrorOutline
}

/** Status-row chip color (iOS-family palette used across Settings). */
private fun a2aStatusColor(state: A2aUiState) = when (state) {
    A2aUiState.RUNNING -> Color(0xFF34C759)
    A2aUiState.ERROR -> Color(0xFFFF3B30)
    else -> Color(0xFF8E8E93)
}
