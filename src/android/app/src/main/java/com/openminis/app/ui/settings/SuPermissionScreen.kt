package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.openminis.app.R
import com.openminis.app.offload.HostSuManager
import com.openminis.app.offload.ShizukuManager
import com.openminis.app.sandbox.offload.SuCommand
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Dedicated Host su card detail — Magisk/KernelSU binary status, optional
 * `su -c id` probe, and Shizuku fallback readiness.
 */
@Composable
fun SuPermissionScreen(
    onBack: () -> Unit,
    onOpenShizuku: () -> Unit = {},
) {
    val suSnap by HostSuManager.snapshot.collectAsStateWithLifecycle()
    val shizukuSnap by ShizukuManager.snapshot.collectAsStateWithLifecycle()
    var probing by remember { mutableStateOf(false) }
    var probeText by remember { mutableStateOf<String?>(null) }
    var probeOk by remember { mutableStateOf<Boolean?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        HostSuManager.refresh()
        ShizukuManager.refresh()
    }

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
                    if (suSnap.state == HostSuManager.State.FOUND) R.string.host_su_found
                    else R.string.host_su_not_found,
                ),
                subtitle = suSnap.path ?: stringResource(R.string.host_su_not_found_sub),
                trailing = {
                    Text(
                        text = stringResource(
                            if (suSnap.state == HostSuManager.State.FOUND) R.string.host_su_badge_found
                            else R.string.host_su_badge_missing,
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (suSnap.state == HostSuManager.State.FOUND) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                },
            )
            SettingsRow(
                title = stringResource(R.string.host_su_recheck_btn),
                subtitle = stringResource(R.string.host_su_recheck_sub),
                onClick = {
                    HostSuManager.refresh()
                    probeText = null
                    probeOk = null
                },
                showDivider = true,
            )
            SettingsRow(
                title = stringResource(
                    if (probing) R.string.host_su_testing else R.string.host_su_test_btn,
                ),
                subtitle = probeText ?: stringResource(R.string.host_su_test_sub),
                onClick = {
                    if (probing) return@SettingsRow
                    probing = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            val path = SuCommand.findSuBinary()
                                ?: return@withContext Triple(false, 1, "no su binary")
                            try {
                                val (code, out) = SuCommand.runHost(listOf(path, "-c", "id"), 8_000L)
                                val ok = code == 0 && out.contains("uid=0")
                                Triple(ok, code, out.trim().ifEmpty { "(empty, exit $code)" })
                            } catch (t: Throwable) {
                                Triple(false, 1, t.message ?: "failed")
                            }
                        }
                        probeOk = result.first
                        probeText = result.third.lineSequence().take(4).joinToString("\n")
                        probing = false
                        HostSuManager.refresh()
                    }
                },
                trailing = {
                    val ok = probeOk
                    if (ok != null) {
                        Text(
                            text = stringResource(
                                if (ok) R.string.host_su_test_ok else R.string.host_su_test_fail,
                            ),
                            style = MaterialTheme.typography.labelMedium,
                            color = if (ok) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.error,
                        )
                    }
                },
                showDivider = false,
            )
        }

        SettingsSection(
            header = stringResource(R.string.host_su_fallback_header),
            footer = stringResource(R.string.host_su_fallback_footer),
        ) {
            SettingsRow(
                title = stringResource(R.string.host_su_fallback_shizuku),
                subtitle = stringResource(
                    when (shizukuSnap.state) {
                        ShizukuManager.State.READY -> R.string.shizuku_state_ready
                        ShizukuManager.State.NEED_PERMISSION -> R.string.shizuku_state_need_permission
                        ShizukuManager.State.NOT_RUNNING -> R.string.shizuku_state_not_running
                        ShizukuManager.State.NOT_INSTALLED -> R.string.shizuku_state_not_installed
                    },
                ),
                onClick = onOpenShizuku,
                trailing = {
                    Text(
                        text = stringResource(
                            if (shizukuSnap.state == ShizukuManager.State.READY) {
                                R.string.host_su_badge_found
                            } else {
                                R.string.host_su_badge_missing
                            },
                        ),
                        style = MaterialTheme.typography.labelMedium,
                        color = if (shizukuSnap.state == ShizukuManager.State.READY) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.error
                        },
                    )
                },
                showDivider = false,
            )
        }

        Spacer(Modifier.height(16.dp))
    }
}
