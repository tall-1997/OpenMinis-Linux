// [port-upstream-1.16] Ported from OpenMinis/OpenMinis@main (ui/settings NetworkSettingsScreen), split from the 1023-line upstream file to satisfy the 800-line ratchet.
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
 * [FEAT-261021-04, iOS c77be4fb6] The pages of Settings > Network, each its
 * own row in the Settings list and its own deep link: Network Routing
 * (default / per-feature / per-provider routes, diagnostics, Restore Direct
 * Connections), Tailscale and Proxies. One screen draws all three, so the
 * dialogs, polling and sign-in flow stay in one place.
 */
enum class NetworkPage { ROUTING, TAILSCALE, PROXIES }

/** A provider instance as the Network Routing page lists it. */
data class NetworkProviderRow(val id: String, val label: String)

/**
 * [T-net-android-phase2] Settings > Network: built-in Tailscale, upstream
 * proxies, the default route and per-scope routes, diagnostics and the
 * "Restore Direct Connections" switch. Mirrors iOS `NetworkSettingsView`;
 * the runtime lives in [NetworkHub].
 */
@Composable
fun NetworkSettingsScreen(
    onBack: () -> Unit,
    page: NetworkPage = NetworkPage.ROUTING,
    /** [FEAT-261021-04, iOS 17e6141be] Configured providers, listed with their routes on the Routing page. */
    providers: List<NetworkProviderRow> = emptyList(),
) {
    val settings by NetworkHub.settings.collectAsStateWithLifecycle()
    val ts by NetworkHub.tailscale.collectAsStateWithLifecycle()
    val applying by NetworkHub.applying.collectAsStateWithLifecycle()
    val lastError by NetworkHub.lastError.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var routePicker by remember { mutableStateOf<RoutePickerTarget?>(null) }
    var editingProxy by remember { mutableStateOf<ProxyServer?>(null) }
    var showAuthKey by remember { mutableStateOf(false) }
    var showHostname by remember { mutableStateOf(false) }
    var showExitNode by remember { mutableStateOf(false) }
    var showDevices by remember { mutableStateOf(false) }
    var confirmClear by remember { mutableStateOf(false) }
    var addingProviderRoutes by remember { mutableStateOf(false) }

    // [FIX-261025-10] Poll only while the screen is started. Tied to the
    // composable alone, a Network page left open when the user went to
    // another app kept asking tsnet for its status every 2 s for as long as
    // the process lived (the agent's foreground service can keep it alive
    // for hours). The browser sign-in has its own bounded poll
    // (TailscaleLoginFlow), so stopping here does not miss a login.
    val pollLifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(pollLifecycle) {
        var polling = false
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_START -> if (!polling) { polling = true; NetworkHub.startPolling() }
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> if (polling) { polling = false; NetworkHub.stopPolling() }
                else -> {}
            }
        }
        // addObserver replays ON_START when the screen is already started.
        pollLifecycle.lifecycle.addObserver(observer)
        onDispose {
            pollLifecycle.lifecycle.removeObserver(observer)
            if (polling) NetworkHub.stopPolling()
        }
    }

    // [FEAT-261021-05] What keeps failing, and the editor the banner asked for.
    val failing by com.openminis.app.network.NetworkAlertCenter.failing.collectAsStateWithLifecycle()
    val pendingProxy by NetworkHub.pendingProxyEditor.collectAsStateWithLifecycle()
    LaunchedEffect(page, pendingProxy) {
        val id = pendingProxy ?: return@LaunchedEffect
        if (page != NetworkPage.PROXIES) return@LaunchedEffect
        NetworkHub.pendingProxyEditor.value = null
        settings.proxies.firstOrNull { it.id == id }?.let { editingProxy = it }
    }

    // [T-net-ts-login-browser] One-tap browser sign-in: the flow hands over the
    // URL once tsnet has it, and is told when the browser covers / uncovers us.
    val login by NetworkHub.browserLogin.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        NetworkHub.browserLogin.opens.collect { url ->
            if (!openSignInPage(context, url)) NetworkHub.browserLogin.browserUnavailable()
        }
    }
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            when (event) {
                androidx.lifecycle.Lifecycle.Event.ON_STOP -> NetworkHub.browserLogin.appHidden()
                androidx.lifecycle.Lifecycle.Event.ON_START -> NetworkHub.browserLogin.appShown()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val title = stringResource(
        when (page) {
            NetworkPage.ROUTING -> R.string.network_routing_title
            NetworkPage.TAILSCALE -> R.string.network_tailscale
            NetworkPage.PROXIES -> R.string.network_proxies
        },
    )
    SettingsScaffold(title = title, onBack = onBack) {
        // While routing is off, every page shows the switch back on in place.
        if (settings.forceDirect) {
            SettingsSection(footer = stringResource(R.string.network_paused_message)) {
                SettingsRow(
                    title = stringResource(R.string.network_paused_title),
                    icon = Icons.Outlined.Restore,
                    iconColor = Color(0xFFFF9500),
                    trailing = {
                        MinisTextButton(onClick = { NetworkHub.resume() }) {
                            Text(stringResource(R.string.network_turn_back_on))
                        }
                    },
                    showDivider = false,
                )
            }
        }

        // Default route
        // [FEAT-261021-16] What follows the default route besides "Follow Default" scopes.
        if (page == NetworkPage.ROUTING) SettingsSection(
            footer = stringResource(R.string.network_default_route_footer) + "\n\n" + stringResource(R.string.network_default_route_covers),
        ) {
            SettingsValueRow(
                title = stringResource(R.string.network_default_route),
                value = routeLabel(NetworkRoute.parse(settings.defaultRoute), settings),
                onClick = { routePicker = RoutePickerTarget.Default },
                showDivider = false,
            )
        }

        // Tailscale
        if (page == NetworkPage.TAILSCALE) SettingsSection(
            header = stringResource(R.string.network_tailscale),
            footer = stringResource(R.string.network_tailscale_footer),
        ) {
            SettingsSwitchRow(
                title = stringResource(R.string.network_tailscale),
                checked = settings.tailscaleEnabled,
                onCheckedChange = { on -> NetworkHub.update { it.copy(tailscaleEnabled = on) } },
                showDivider = settings.tailscaleEnabled,
            )
            if (settings.tailscaleEnabled) {
                StatusRow(ts, applying, settings.forceDirect)
                if (ts.needsLogin) {
                    // Always tappable: the flow waits for the URL itself (a
                    // handler that appeared only once a later poll brought the
                    // URL is why the first tap used to do nothing).
                    val opening = login == com.openminis.app.network.TailscaleLoginFlow.State.OpeningPage
                    SettingsRow(
                        title = stringResource(R.string.network_login_browser),
                        subtitle = when (val l = login) {
                            com.openminis.app.network.TailscaleLoginFlow.State.OpeningPage -> stringResource(R.string.network_login_opening)
                            com.openminis.app.network.TailscaleLoginFlow.State.InBrowser -> stringResource(R.string.network_login_in_browser)
                            is com.openminis.app.network.TailscaleLoginFlow.State.Failed -> stringResource(
                                if (l.reason == com.openminis.app.network.TailscaleLoginFlow.Reason.NO_BROWSER) R.string.network_login_no_browser
                                else R.string.network_login_no_page,
                            )
                            else -> null
                        },
                        icon = Icons.Outlined.OpenInBrowser,
                        onClick = { NetworkHub.browserLogin.begin() },
                        showChevron = !opening,
                        trailing = if (opening) ({ CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp) }) else null,
                    )
                    // Fallback only: when no app can open the page, the link to copy.
                    val url = NetworkHub.browserLogin.lastUrl
                    val noBrowser = (login as? com.openminis.app.network.TailscaleLoginFlow.State.Failed)?.reason ==
                        com.openminis.app.network.TailscaleLoginFlow.Reason.NO_BROWSER
                    if (noBrowser && url != null) {
                        val clipboard = LocalClipboardManager.current
                        SettingsRow(
                            title = stringResource(R.string.network_copy_login_link),
                            icon = Icons.Outlined.ContentCopy,
                            onClick = { clipboard.setText(AnnotatedString(url)) },
                        )
                    }
                    SettingsRow(
                        title = stringResource(R.string.network_login_auth_key),
                        icon = Icons.Outlined.Key,
                        onClick = { showAuthKey = true },
                    )
                }
                SettingsValueRow(
                    title = stringResource(R.string.network_device_name),
                    value = settings.hostname.ifEmpty { stringResource(R.string.network_device_name_automatic) },
                    onClick = { showHostname = true },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.network_use_subnet_routes),
                    checked = settings.acceptRoutes,
                    onCheckedChange = { on -> NetworkHub.update { it.copy(acceptRoutes = on) } },
                )
                if (ts.isLoggedIn) {
                    val exit = ts.peers.firstOrNull { p -> p.ips.contains(settings.exitNodeIP) }
                    SettingsValueRow(
                        title = stringResource(R.string.network_exit_node),
                        value = exit?.displayName ?: stringResource(R.string.network_exit_node_none),
                        onClick = { showExitNode = true },
                    )
                    SettingsValueRow(
                        title = stringResource(R.string.network_devices),
                        value = "${ts.peers.count { it.online }}/${ts.peers.size}",
                        onClick = { showDevices = true },
                    )
                    SettingsRow(title = stringResource(R.string.network_sign_out), onClick = { NetworkHub.logout() })
                }
                SettingsRow(
                    title = stringResource(R.string.network_sign_out_clear_ellipsis),
                    titleColor = MaterialTheme.colorScheme.error,
                    onClick = { confirmClear = true },
                    showChevron = false,
                    showDivider = false,
                )
            }
        }

        // [FEAT-261021-23, iOS 45510cce2] Exit node privacy for the shell, shown
        // only while an exit node is selected (both off by default).
        if (page == NetworkPage.TAILSCALE && settings.tailscaleEnabled && ts.isLoggedIn && settings.exitNodeIP != null) {
            SettingsSection(footer = stringResource(R.string.network_exit_node_privacy_footer)) {
                SettingsSwitchRow(
                    title = stringResource(R.string.network_exit_node_dns),
                    checked = settings.exitNodeDNS,
                    onCheckedChange = { on -> NetworkHub.update { it.copy(exitNodeDNS = on) } },
                )
                SettingsSwitchRow(
                    title = stringResource(R.string.network_exit_node_fail_closed),
                    checked = settings.exitNodeFailClosed,
                    onCheckedChange = { on -> NetworkHub.update { it.copy(exitNodeFailClosed = on) } },
                    showDivider = false,
                )
            }
        }

        // [FEAT-261021-05] Opened from the "keeps failing" banner: why, and
        // that the switch above is the way out.
        val tsFailure = failing[com.openminis.app.network.NetworkAlertEvent.Subject.Tailscale]
        if (page == NetworkPage.TAILSCALE && settings.tailscaleEnabled && tsFailure != null) {
            SettingsSection(footer = stringResource(R.string.network_tailscale_failing_footer)) {
                NetworkFailureNote(tsFailure, Modifier.padding(16.dp))
            }
        }

        // Proxies
        if (page == NetworkPage.PROXIES) SettingsSection(
            header = stringResource(R.string.network_proxies),
            footer = stringResource(R.string.network_proxies_footer),
        ) {
            for (p in settings.proxies) {
                val keepsFailing = failing.containsKey(com.openminis.app.network.NetworkAlertEvent.Subject.Proxy(p.id))
                SettingsRow(
                    title = p.displayName,
                    subtitle = "${ProxyServer.kindLabel(p.kind)} · ${p.host}:${p.port}",
                    onClick = { editingProxy = p },
                    trailing = if (keepsFailing) ({
                        Text(
                            stringResource(R.string.network_alert_keeps_failing),
                            color = Color(0xFFFF9500),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }) else null,
                )
            }
            SettingsRow(
                title = stringResource(R.string.network_add_proxy),
                icon = Icons.Outlined.Add,
                onClick = { editingProxy = ProxyServer() },
                showChevron = false,
                showDivider = false,
            )
        }

        // Used By
        if (page == NetworkPage.ROUTING) SettingsSection(
            header = stringResource(R.string.network_used_by),
            // [FEAT-261021-18] The shell's limits (G4b) are said, not left to be discovered.
            footer = stringResource(R.string.network_used_by_footer) + "\n" + stringResource(R.string.network_shell_limits) +
                (if (!WebViewNetworkProxy.isSupported()) "\n" + stringResource(R.string.network_browser_no_proxy) else ""),
        ) {
            NetworkScope.entries.forEachIndexed { i, scope ->
                SettingsValueRow(
                    title = scopeTitle(scope),
                    value = routeLabel(settings.route(scope), settings),
                    onClick = { routePicker = RoutePickerTarget.Scope(scope) },
                    showDivider = i < NetworkScope.entries.size - 1,
                )
            }
        }

        // [FEAT-261021-04, iOS 17e6141be] Provider routes, the same setting
        // minis-config writes as providers.<id>.networkRoute.
        // [PAR-261021-01, iOS 1b4e8bc8a] Only providers with a route of their
        // own are listed; a row per provider buried the rest of the page.
        // "Add Providers" picks several at once and gives them one route.
        // Choosing "Follow Default" in a row removes the override, so the
        // provider leaves the list. Overrides of deleted providers stay in
        // settings but are not shown.
        val routed = providers.filter { settings.providerRoutes.containsKey(it.id) }
        val unrouted = providers.filter { !settings.providerRoutes.containsKey(it.id) }
        if (page == NetworkPage.ROUTING && providers.isNotEmpty()) SettingsSection(
            header = stringResource(R.string.settings_section_llm_providers),
            footer = stringResource(R.string.network_providers_unlisted_footer),
        ) {
            routed.forEachIndexed { i, p ->
                SettingsValueRow(
                    title = p.label,
                    value = routeLabel(settings.providerRoute(p.id), settings),
                    onClick = { routePicker = RoutePickerTarget.Provider(p.id, p.label) },
                    showDivider = i < routed.size - 1 || unrouted.isNotEmpty(),
                )
            }
            if (unrouted.isNotEmpty()) SettingsRow(
                title = stringResource(R.string.network_add_providers),
                icon = Icons.Outlined.Add,
                onClick = { addingProviderRoutes = true },
                showChevron = false,
                showDivider = false,
            )
        }

        lastError?.let { err ->
            SettingsSection {
                Text(
                    err,
                    color = Color(0xFFFF9500),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(16.dp),
                )
            }
        }

        if (page == NetworkPage.ROUTING) DiagnosticsSection()

        if (page == NetworkPage.ROUTING) SettingsSection(footer = stringResource(R.string.network_restore_direct_footer) + "\n\n" + stringResource(R.string.network_entry_note)) {
            SettingsRow(
                title = stringResource(R.string.network_restore_direct),
                icon = Icons.Outlined.Restore,
                iconColor = MaterialTheme.colorScheme.error,
                titleColor = MaterialTheme.colorScheme.error,
                onClick = if (settings.forceDirect || settings.isUnconfigured) null else ({ NetworkHub.restoreDirect() }),
                showChevron = false,
                showDivider = false,
            )
        }
    }

    routePicker?.let { target ->
        val current = when (target) {
            RoutePickerTarget.Default -> NetworkRoute.parse(settings.defaultRoute)
            is RoutePickerTarget.Scope -> settings.route(target.scope)
            is RoutePickerTarget.Provider -> settings.providerRoute(target.id)
        }
        RoutePickerDialog(
            title = when (target) {
                RoutePickerTarget.Default -> stringResource(R.string.network_default_route)
                is RoutePickerTarget.Scope -> scopeTitle(target.scope)
                is RoutePickerTarget.Provider -> target.label
            },
            includeFollowDefault = target !is RoutePickerTarget.Default,
            current = current,
            proxies = settings.proxies,
            onPick = { r ->
                when (target) {
                    RoutePickerTarget.Default -> NetworkHub.update { it.copy(defaultRoute = r.raw) }
                    is RoutePickerTarget.Scope -> NetworkHub.update { it.copy(scopes = it.scopes + (target.scope.tag to r.raw)) }
                    is RoutePickerTarget.Provider -> NetworkHub.setProviderRoute(r, target.id)
                }
                routePicker = null
            },
            onDismiss = { routePicker = null },
        )
    }

    if (addingProviderRoutes) {
        AddProviderRoutesSheet(
            candidates = providers.filter { !settings.providerRoutes.containsKey(it.id) },
            proxies = settings.proxies,
            onAdd = { ids, r ->
                NetworkHub.update { s -> s.copy(providerRoutes = s.providerRoutes + ids.associateWith { r.raw }) }
                addingProviderRoutes = false
            },
            onDismiss = { addingProviderRoutes = false },
        )
    }

    editingProxy?.let { p ->
        ProxyEditorDialog(
            proxy = p,
            isNew = settings.proxies.none { it.id == p.id },
            onDismiss = { editingProxy = null },
        )
    }

    if (showAuthKey) AuthKeyDialog(onDismiss = { showAuthKey = false })

    if (showHostname) {
        var draft by remember { mutableStateOf(settings.hostname) }
        AlertDialog(
            onDismissRequest = { showHostname = false },
            title = { Text(stringResource(R.string.network_device_name)) },
            text = {
                DialogTextField(value = draft, onValueChange = { draft = it }, placeholder = "minis-android")
            },
            confirmButton = {
                MinisTextButton(onClick = {
                    NetworkHub.update { it.copy(hostname = draft.trim()) }
                    showHostname = false
                }) { Text(stringResource(R.string.network_save)) }
            },
            dismissButton = {
                MinisTextButton(onClick = { showHostname = false }) { Text(stringResource(R.string.network_cancel)) }
            },
        )
    }

    if (showExitNode) {
        val options = ts.peers.filter { it.exitNodeOption }
        AlertDialog(
            onDismissRequest = { showExitNode = false },
            title = { Text(stringResource(R.string.network_exit_node)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SettingsChoiceRow(
                        title = stringResource(R.string.network_exit_node_none),
                        selected = settings.exitNodeIP == null,
                        onSelect = { NetworkHub.update { it.copy(exitNodeIP = null) }; showExitNode = false },
                    )
                    for (p in options) {
                        val ip = p.ips.firstOrNull { !it.contains(':') } ?: continue
                        SettingsChoiceRow(
                            title = if (p.online) p.displayName else stringResource(R.string.network_peer_offline, p.displayName),
                            selected = settings.exitNodeIP == ip,
                            onSelect = { NetworkHub.update { it.copy(exitNodeIP = ip) }; showExitNode = false },
                        )
                    }
                }
            },
            confirmButton = {
                MinisTextButton(onClick = { showExitNode = false }) { Text(stringResource(R.string.network_cancel)) }
            },
        )
    }

    if (showDevices) {
        AlertDialog(
            onDismissRequest = { showDevices = false },
            title = { Text(stringResource(R.string.network_devices)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    for (p in ts.peers) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(8.dp).background(
                                    if (p.online) Color(0xFF34C759) else MaterialTheme.colorScheme.outline,
                                    CircleShape,
                                ),
                            )
                            Spacer(Modifier.size(10.dp))
                            Column {
                                Text(p.displayName, style = MaterialTheme.typography.bodyMedium)
                                SelectionContainer {
                                    Text(
                                        listOfNotNull(p.ipv4, p.os.ifEmpty { null }).joinToString(" · "),
                                        style = MaterialTheme.typography.bodySmall,
                                        fontFamily = FontFamily.Monospace,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            if (p.exitNode) {
                                Spacer(Modifier.weight(1f))
                                Text(stringResource(R.string.network_exit_node), style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            },
            confirmButton = {
                MinisTextButton(onClick = { showDevices = false }) { Text("OK") }
            },
        )
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(R.string.network_sign_out_clear_title)) },
            text = { Text(stringResource(R.string.network_sign_out_clear_message)) },
            confirmButton = {
                MinisTextButton(onClick = { NetworkHub.clearTailscale(); confirmClear = false }) {
                    Text(stringResource(R.string.network_sign_out_clear), color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { confirmClear = false }) { Text(stringResource(R.string.network_cancel)) }
            },
        )
    }
}

/**
 * [FEAT-261021-09, iOS ProviderNetworkRouteSection] The Network row on a
 * provider's detail page: Follow Default / Direct / Tailscale / a proxy, the
 * same NetworkHub.providerRoute setting the Routing page lists.
 */
@Composable
internal fun ProviderNetworkRouteSection(instanceId: String) {
    val settings by NetworkHub.settings.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf(false) }
    val title = stringResource(R.string.network_provider_route)
    SettingsSection(footer = stringResource(R.string.network_provider_route_footer)) {
        SettingsValueRow(
            title = title,
            value = routeLabel(settings.providerRoute(instanceId), settings),
            onClick = { picking = true },
            showDivider = false,
        )
    }
    if (picking) {
        RoutePickerDialog(
            title = title,
            includeFollowDefault = true,
            current = settings.providerRoute(instanceId),
            proxies = settings.proxies,
            onPick = { r ->
                NetworkHub.setProviderRoute(r, instanceId)
                picking = false
            },
            onDismiss = { picking = false },
        )
    }
}

// [port-upstream-1.16] The port of this file dropped its tail: the call sites
// above reference RoutePickerTarget / RoutePickerDialog / routeLabel /
// scopeTitle, but their definitions (the end of the upstream file) never
// arrived. Restored verbatim from OpenMinis/OpenMinis 1a71df0f so the module
// compiles; everything they need (NetworkRoute, NetworkScope, ProxyServer,
// SettingsChoiceRow, the network_* strings) already exists in this tree.
private sealed class RoutePickerTarget {
    object Default : RoutePickerTarget()
    data class Scope(val scope: NetworkScope) : RoutePickerTarget()
    data class Provider(val id: String, val label: String) : RoutePickerTarget()
}

@Composable
private fun scopeTitle(scope: NetworkScope): String = stringResource(
    when (scope) {
        NetworkScope.SHELL -> R.string.network_scope_shell
        NetworkScope.BROWSER -> R.string.network_scope_browser
        NetworkScope.MCP -> R.string.network_scope_mcp
        NetworkScope.BACKUP -> R.string.network_scope_backup
    },
)

@Composable
internal fun routeLabel(route: NetworkRoute, settings: NetworkSettings): String = when (route) {
    NetworkRoute.FollowDefault -> stringResource(R.string.network_route_follow_default)
    NetworkRoute.Direct -> stringResource(R.string.network_route_direct)
    NetworkRoute.Tailscale -> stringResource(R.string.network_route_tailscale)
    is NetworkRoute.Proxy -> settings.proxies.firstOrNull { it.id == route.id }?.displayName
        ?: stringResource(R.string.network_route_direct)
}

@Composable
internal fun RoutePickerDialog(
    title: String,
    includeFollowDefault: Boolean,
    current: NetworkRoute,
    proxies: List<ProxyServer>,
    onPick: (NetworkRoute) -> Unit,
    onDismiss: () -> Unit,
) {
    val options = buildList {
        if (includeFollowDefault) add(NetworkRoute.FollowDefault)
        add(NetworkRoute.Direct)
        add(NetworkRoute.Tailscale)
        proxies.forEach { add(NetworkRoute.Proxy(it.id)) }
    }
    val s = NetworkSettings(proxies = proxies)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                options.forEachIndexed { i, r ->
                    SettingsChoiceRow(
                        title = routeLabel(r, s),
                        selected = r == current,
                        onSelect = { onPick(r) },
                        showDivider = i < options.size - 1,
                    )
                }
            }
        },
        confirmButton = {
            MinisTextButton(onClick = onDismiss) { Text(stringResource(R.string.network_cancel)) }
        },
    )
}

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
