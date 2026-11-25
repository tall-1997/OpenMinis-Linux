package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import com.openminis.app.offload.HostSuManager
import com.openminis.app.offload.ShizukuManager
import com.openminis.app.sandbox.offload.SuCommand
import com.openminis.app.ui.components.MinisTextButton
import kotlinx.coroutines.launch

/**
 * [T-host-su-fallback] Host Magisk/KernelSU su permission detail screen.
 *
 * The probe here is presence-only (binary found on a standard su path) -
 * the card on OffloadPermissionScreen never pops a Magisk grant dialog
 * just for being opened. The explicit test row is the only thing that
 * actually elevates, and its output is shown verbatim so a refusal reads
 * as the honest error it is.
 *
 * The Shizuku fallback row mirrors the runtime lane dispatch: when host
 * su is missing or refuses elevation, the same command is retried via
 * Shizuku.
 */
@Composable
fun SuPermissionScreen(onBack: () -> Unit, onOpenShizuku: () -> Unit) {
    val snap by HostSuManager.snapshot.collectAsState()
    val shizukuReady = ShizukuManager.isReady()
    val scope = rememberCoroutineScope()
    var testResult by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { HostSuManager.refresh() }

    SettingsScaffold(
        title = stringResource(R.string.host_su_title),
        onBack = onBack,
    ) {
        SettingsSection(
            header = stringResource(R.string.host_su_status_header),
            footer = stringResource(R.string.host_su_status_footer),
        ) {
            SettingsRow(
                title = stringResource(
                    if (snap.state == HostSuManager.State.FOUND) R.string.host_su_found
                    else R.string.host_su_not_found,
                ),
                subtitle = snap.path ?: stringResource(R.string.host_su_not_found_hint),
                trailing = {
                    Text(
                        text = stringResource(
                            if (snap.state == HostSuManager.State.FOUND) R.string.host_su_badge_found
                            else R.string.host_su_badge_not_found,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (snap.state == HostSuManager.State.FOUND) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                    )
                },
                showDivider = true,
            )
            SettingsRow(
                title = stringResource(R.string.host_su_recheck),
                onClick = { HostSuManager.refresh() },
                showDivider = false,
            )
        }

    SettingsSection(
        header = stringResource(R.string.host_su_test_header),
        footer = stringResource(R.string.host_su_test_footer),
        ) {
            SettingsRow(
                title = stringResource(if (testing) R.string.host_su_test_running else R.string.host_su_test_run),
                showChevron = false,
                showDivider = testResult != null,
            )
            if (testResult != null) {
                SettingsRow(
                    title = testResult.orEmpty(),
                    showDivider = false,
                )
            }
            MinisTextButton(onClick = {
                if (testing) return@MinisTextButton
                testing = true
                testResult = null
                scope.launch {
                    val (code, output) = SuCommand.runHost(listOf("su", "-c", "id"), 30_000L)
                    testResult = "exit $code".trim() + "\n" + output.trimEnd()
                    AppLogger.info("SuPermissionScreen", "su -c id -> $code")
                    testing = false
                }
            }) {
                Text(stringResource(R.string.host_su_test_run))
            }
        }

    SettingsSection(
        header = stringResource(R.string.host_su_fallback_header),
        footer = stringResource(R.string.host_su_fallback_footer),
        ) {
            SettingsRow(
                icon = Icons.Filled.Security,
                title = stringResource(R.string.host_su_fallback_title),
                subtitle = stringResource(
                    if (shizukuReady) R.string.host_su_fallback_ready
                    else R.string.host_su_fallback_not_ready,
                ),
                onClick = onOpenShizuku,
                showChevron = true,
                showDivider = false,
                trailing = {
                    Text(
                        text = stringResource(
                            if (shizukuReady) R.string.host_su_badge_found
                            else R.string.host_su_badge_not_found,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (shizukuReady) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                    )
                },
            )
        }

    Spacer(Modifier.height(16.dp))
    }
}

