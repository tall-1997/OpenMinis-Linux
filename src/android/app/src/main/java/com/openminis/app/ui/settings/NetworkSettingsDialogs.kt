// [port-upstream-1.16] Ported from OpenMinis/OpenMinis@main (ui/settings NetworkSettingsScreen second half): proxy editor, auth-key dialog, add-providers sheet, diagnostics section and sign-in launcher.
// Split from the upstream single file to satisfy the 800-line ratchet; the helpers the screen uses are internal instead of private.
// Adaptation [port-upstream-1.16]: collectAsState() -> collectAsStateWithLifecycle() (project forbiddenPatterns ratchet bans lifecycle-unaware collection).
package com.openminis.app.ui.settings

import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Key
import androidx.compose.material.icons.outlined.OpenInBrowser
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material.icons.outlined.Troubleshoot
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.auth.OAuthTabLauncher
import com.openminis.app.network.NetworkDiagnostics
import com.openminis.app.network.NetworkAlertCenter
import com.openminis.app.network.NetworkAlertEvent
import com.openminis.app.network.NetworkHub
import com.openminis.app.network.NetworkRoute
import com.openminis.app.network.NetworkScope
import com.openminis.app.network.NetworkSettings
import com.openminis.app.network.ProxyServer
import com.openminis.app.network.TailscaleStatus
import com.openminis.app.network.WebViewNetworkProxy
import com.openminis.app.ui.components.DialogTextField
import com.openminis.app.ui.components.MinisTextButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [PAR-261021-01, iOS AddProviderRoutesView] Gives several providers the same
 * route in one go: one route (no Follow Default - that is what not being
 * listed means) and a multi-select list of the providers not yet listed.
 * Add writes them in a single [NetworkHub.update], so the runtime is applied
 * once.
 *
 * A half-screen sheet like the model and provider pickers: slim drag handle,
 * Cancel / centred title / Add in one bar, grouped settings cards below.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AddProviderRoutesSheet(
    candidates: List<NetworkProviderRow>,
    proxies: List<ProxyServer>,
    onAdd: (Set<String>, NetworkRoute) -> Unit,
    onDismiss: () -> Unit,
) {
    var route by remember { mutableStateOf<NetworkRoute>(NetworkRoute.Tailscale) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    val routes = buildList {
        add(NetworkRoute.Direct)
        add(NetworkRoute.Tailscale)
        proxies.forEach { add(NetworkRoute.Proxy(it.id)) }
    }
    val s = NetworkSettings(proxies = proxies)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = MaterialTheme.colorScheme.background,
        // The slim handle the model picker uses (6dp / 4dp, not Material's ~22dp).
        dragHandle = {
            Box(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp), contentAlignment = Alignment.TopCenter) {
                Box(
                    Modifier.width(32.dp).height(4.dp).background(
                        MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                        RoundedCornerShape(2.dp),
                    ),
                )
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.9f).navigationBarsPadding()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    MinisTextButton(
                        onClick = onDismiss,
                        colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.onSurfaceVariant),
                    ) { Text(stringResource(R.string.network_cancel)) }
                }
                Text(
                    stringResource(R.string.network_add_providers),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterEnd) {
                    MinisTextButton(onClick = { onAdd(selected, route) }, enabled = selected.isNotEmpty()) {
                        Text(
                            if (selected.isEmpty()) stringResource(R.string.network_add)
                            else stringResource(R.string.network_add) + " (${selected.size})",
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                SettingsSection(header = stringResource(R.string.network_route)) {
                    routes.forEachIndexed { i, r ->
                        SettingsChoiceRow(
                            title = routeLabel(r, s),
                            selected = r == route,
                            onSelect = { route = r },
                            showDivider = i < routes.size - 1,
                        )
                    }
                }
                SettingsSection(header = stringResource(R.string.settings_section_llm_providers)) {
                    candidates.forEachIndexed { i, p ->
                        val on = p.id in selected
                        SettingsRow(
                            title = p.label,
                            onClick = { selected = if (on) selected - p.id else selected + p.id },
                            showChevron = false,
                            showDivider = i < candidates.size - 1,
                            trailing = {
                                Checkbox(
                                    checked = on,
                                    onCheckedChange = { selected = if (it) selected + p.id else selected - p.id },
                                )
                            },
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun StatusRow(ts: TailscaleStatus, applying: Boolean, paused: Boolean) {
    val (label, color) = when {
        paused -> stringResource(R.string.network_status_off) to MaterialTheme.colorScheme.onSurfaceVariant
        applying && !ts.running -> stringResource(R.string.network_status_starting) to MaterialTheme.colorScheme.onSurfaceVariant
        ts.isLoggedIn -> stringResource(R.string.network_status_connected) to Color(0xFF34C759)
        ts.needsLogin -> stringResource(R.string.network_status_not_logged_in) to Color(0xFFFF9500)
        ts.running -> stringResource(R.string.network_status_connecting) to MaterialTheme.colorScheme.onSurfaceVariant
        else -> stringResource(R.string.network_status_off) to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val ip = ts.ips.firstOrNull { !it.contains(':') } ?: ts.ips.firstOrNull()
    SettingsValueRow(
        title = stringResource(R.string.network_status),
        value = label,
        valueColor = color,
        subtitle = if (ts.isLoggedIn) listOfNotNull(
            ts.tailnet.ifEmpty { null },
            ip?.let { it + (if (ts.dnsName.isEmpty()) "" else " · " + ts.dnsName) },
        ).joinToString("\n") else null,
    )
}

@Composable
internal fun DiagnosticsSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    var running by remember { mutableStateOf(false) }
    var report by remember { mutableStateOf<NetworkDiagnostics.Report?>(null) }
    var copied by remember { mutableStateOf(false) }
    SettingsSection(footer = stringResource(R.string.network_diagnostics_footer)) {
        SettingsRow(
            title = stringResource(if (running) R.string.network_diagnostics_running else R.string.network_diagnostics),
            icon = Icons.Outlined.Troubleshoot,
            onClick = if (running) null else ({
                running = true
                copied = false
                scope.launch {
                    report = withContext(Dispatchers.IO) { NetworkDiagnostics.run(context) }
                    running = false
                }
            }),
            trailing = if (running) ({ CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp) }) else null,
            showChevron = false,
            showDivider = report != null,
        )
        report?.let { r ->
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                for (c in r.checks) {
                    val color = when (c.result) {
                        NetworkDiagnostics.Result.PASS -> Color(0xFF34C759)
                        NetworkDiagnostics.Result.FAIL -> MaterialTheme.colorScheme.error
                        NetworkDiagnostics.Result.WARN -> Color(0xFFFF9500)
                        NetworkDiagnostics.Result.SKIP -> MaterialTheme.colorScheme.onSurfaceVariant
                    }
                    Row(Modifier.padding(vertical = 3.dp)) {
                        Text(c.result.name, color = color, style = MaterialTheme.typography.labelSmall,
                            fontFamily = FontFamily.Monospace, modifier = Modifier.heightIn(min = 16.dp).padding(end = 8.dp))
                        Column {
                            Text(c.name, style = MaterialTheme.typography.bodySmall)
                            Text(c.detail, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            SettingsRow(
                title = stringResource(if (copied) R.string.network_copied else R.string.network_diagnostics_copy),
                icon = Icons.Outlined.ContentCopy,
                onClick = { clipboard.setText(AnnotatedString(r.toText())); copied = true },
                showChevron = false,
                showDivider = false,
            )
        }
    }
}

@Composable
internal fun ProxyEditorDialog(proxy: ProxyServer, isNew: Boolean, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(proxy.name) }
    var kind by remember { mutableStateOf(proxy.kind) }
    var host by remember { mutableStateOf(proxy.host) }
    var portText by remember { mutableStateOf(proxy.port.toString()) }
    var username by remember { mutableStateOf(proxy.username) }
    var password by remember { mutableStateOf(if (isNew) "" else NetworkHub.proxyPassword(proxy.id)) }
    var bypassText by remember { mutableStateOf(proxy.bypass.joinToString(", ")) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    val okFormat = stringResource(R.string.network_proxy_test_ok)
    val valid = host.isNotBlank() && (portText.toIntOrNull() ?: 0) in 1..65535

    fun edited() = proxy.copy(
        name = name.trim(),
        kind = kind,
        host = host.trim(),
        port = portText.toIntOrNull() ?: proxy.port,
        username = username,
        bypass = bypassText.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() },
    )

    LaunchedEffect(kind) {
        // Suggest the usual port when the user has not typed one.
        if (portText in listOf("", "1080", "8080", "443")) portText = ProxyServer.defaultPort(kind).toString()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (isNew) R.string.network_add_proxy else R.string.network_edit_proxy)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogTextField(value = name, onValueChange = { name = it }, placeholder = stringResource(R.string.network_proxy_name))
                Text(stringResource(R.string.network_proxy_type), style = MaterialTheme.typography.labelMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (k in ProxyServer.KINDS) {
                        MinisTextButton(onClick = { kind = k }) {
                            Text(
                                ProxyServer.kindLabel(k),
                                color = if (k == kind) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                DialogTextField(value = host, onValueChange = { host = it }, placeholder = stringResource(R.string.network_proxy_server),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
                DialogTextField(value = portText, onValueChange = { portText = it.filter(Char::isDigit).take(5) },
                    placeholder = stringResource(R.string.network_proxy_port),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number))
                Text(stringResource(R.string.network_proxy_authentication), style = MaterialTheme.typography.labelMedium)
                DialogTextField(value = username, onValueChange = { username = it }, placeholder = stringResource(R.string.network_proxy_username))
                DialogTextField(value = password, onValueChange = { password = it }, placeholder = stringResource(R.string.network_proxy_password),
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
                Text(stringResource(R.string.network_proxy_bypass), style = MaterialTheme.typography.labelMedium)
                DialogTextField(value = bypassText, onValueChange = { bypassText = it }, placeholder = "example.com, *.lan, 10.0.0.0/8",
                    singleLine = false, maxLines = 4)
                Text(stringResource(R.string.network_proxy_bypass_footer), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MinisTextButton(enabled = valid && !testing, onClick = {
                        testing = true
                        testResult = null
                        val p = edited()
                        val pw = password
                        scope.launch {
                            testResult = withContext(Dispatchers.IO) {
                                runCatching { NetworkHub.testProxy(p, pw) }.fold(
                                    { true to okFormat.format(it.toInt()) },
                                    { false to NetworkDiagnostics.classifyText(it.message.orEmpty()) },
                                )
                            }
                            testing = false
                        }
                    }) { Text(stringResource(R.string.network_proxy_test)) }
                    if (testing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
                testResult?.let { (ok, msg) ->
                    Text(msg, style = MaterialTheme.typography.bodySmall,
                        color = if (ok) Color(0xFF34C759) else MaterialTheme.colorScheme.error)
                }
                // [FEAT-261021-05] Opened from the "keeps failing" banner: say
                // why, and let the user stop using it without deleting it.
                val failure = NetworkAlertCenter.failing.collectAsStateWithLifecycle().value[NetworkAlertEvent.Subject.Proxy(proxy.id)]
                if (!isNew && failure != null) {
                    val inUse = NetworkHub.proxyInUse(proxy.id, NetworkHub.settings.collectAsStateWithLifecycle().value)
                    NetworkFailureNote(failure)
                    if (inUse) {
                        MinisTextButton(onClick = { NetworkHub.stopUsingProxy(proxy.id); onDismiss() }) {
                            Text(stringResource(R.string.network_stop_using_proxy), color = MaterialTheme.colorScheme.error)
                        }
                    }
                    Text(
                        stringResource(if (inUse) R.string.network_stop_using_proxy_footer else R.string.network_proxy_not_in_use),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (!isNew) {
                    MinisTextButton(onClick = { NetworkHub.deleteProxy(proxy.id); onDismiss() }) {
                        Text(stringResource(R.string.network_proxy_delete), color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            MinisTextButton(enabled = valid, onClick = { NetworkHub.saveProxy(edited(), password); onDismiss() }) {
                Text(stringResource(R.string.network_save))
            }
        },
        dismissButton = {
            MinisTextButton(onClick = onDismiss) { Text(stringResource(R.string.network_cancel)) }
        },
    )
}

@Composable
internal fun AuthKeyDialog(onDismiss: () -> Unit) {
    var key by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.network_auth_key)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                DialogTextField(value = key, onValueChange = { key = it }, placeholder = "tskey-auth-…",
                    visualTransformation = PasswordVisualTransformation())
                Text(stringResource(R.string.network_auth_key_footer), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = {
            MinisTextButton(enabled = key.isNotBlank(), onClick = { NetworkHub.login(key); onDismiss() }) {
                Text(stringResource(R.string.network_log_in))
            }
        },
        dismissButton = {
            MinisTextButton(onClick = onDismiss) { Text(stringResource(R.string.network_cancel)) }
        },
    )
}

/**
 * [T-net-ts-login-browser] Opens the Tailscale sign-in page in a Custom Tab -
 * the default browser's own cookies, so an existing sign-in (Google, Apple,
 * GitHub, Microsoft, passkeys) is reused - or, with no Custom Tabs provider, in
 * the browser itself (ACTION_VIEW). False when no app can open it. The URL is
 * not logged.
 */
internal fun openSignInPage(context: android.content.Context, url: String): Boolean {
    val uri = Uri.parse(url)
    val provider = androidx.browser.customtabs.CustomTabsClient.getPackageName(context, null)
    if (provider != null) {
        try {
            OAuthTabLauncher.launch(context, uri)
            com.openminis.app.logging.AppLogger.info("Network", "[Net][TS] login: sign-in page opened in a Custom Tab ($provider)")
            return true
        } catch (_: android.content.ActivityNotFoundException) {
        }
    }
    return try {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, uri))
        com.openminis.app.logging.AppLogger.info("Network", "[Net][TS] login: no Custom Tabs provider, sign-in page opened with ACTION_VIEW")
        true
    } catch (_: android.content.ActivityNotFoundException) {
        false
    }
}
