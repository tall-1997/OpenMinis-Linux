package com.openminis.app.ui.settings

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BatteryAlert
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.RestartAlt
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.accessibility.AccessibilityRecoveryManager
import com.openminis.app.accessibility.MinisAccessibilityService
import com.openminis.app.power.PowerOptimizationManager
import com.openminis.app.ui.components.MinisTextButton
import kotlinx.coroutines.launch

/**
 * [T-settings-single-permissions-entry] OS-level grant sections that live on
 * the permissions page (OffloadPermissionScreen) instead of a separate
 * "System Permissions" screen. The old screen duplicated the a11y status row
 * and the restricted-settings section that OffloadPermissionScreen already
 * had, so only its unique sections moved here: the force-stop repair, the OEM
 * keep-alive guidance, and the voice-correction consent/wipe control.
 *
 * `a11yDegraded` / `a11yRevoked` come from the host's one-second poll so the
 * whole page shares a single source of truth for accessibility health;
 * `shizukuReady` comes from the Shizuku snapshot the host already collects.
 */
@Composable
fun SystemGrantSections(
    a11yDegraded: Boolean,
    a11yRevoked: Boolean,
    shizukuReady: Boolean,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var repairing by remember { mutableStateOf(false) }
    var repairFailed by remember { mutableStateOf(false) }

    // [T-android-a11y-force-stop-recovery] Repair affordance, shown ONLY when
    // the grant was previously held and is now missing from Settings.Secure —
    // the force-stop signature. Hidden in the healthy case and on a fresh
    // install (see hasEverBeenGranted) so the page stays clean.
    if (a11yRevoked) {
        SettingsSection(
            header = stringResource(R.string.a11y_repair_section_header),
            footer = stringResource(
                if (shizukuReady) R.string.a11y_repair_footer_shizuku
                else R.string.a11y_repair_footer_manual
            ),
        ) {
            SettingsRow(
                icon = Icons.Outlined.Build,
                iconColor = Color(0xFFFF3B30),
                title = stringResource(
                    if (shizukuReady) R.string.a11y_repair_row_shizuku
                    else R.string.a11y_repair_row_manual
                ),
                subtitle = when {
                    repairing -> stringResource(R.string.a11y_repair_in_progress)
                    repairFailed -> stringResource(R.string.a11y_repair_failed)
                    else -> stringResource(R.string.a11y_repair_row_sub)
                },
                onClick = {
                    if (!shizukuReady) {
                        openAccessibilitySettings(context)
                        return@SettingsRow
                    }
                    if (repairing) return@SettingsRow
                    repairing = true
                    repairFailed = false
                    scope.launch {
                        val ok = AccessibilityRecoveryManager
                            .repairWithShizuku(context)
                        repairing = false
                        repairFailed = !ok
                        // On success the host poll clears a11yRevoked within
                        // a second and this whole section disappears on its own.
                    }
                },
                showDivider = false,
            )
        }
    }

    // [T-android-a11y-miui-service-failure] OEM keep-alive guidance. Only
    // shown when the service is degraded ("此服务出现故障" — enabled in
    // Settings but the process was killed by the ROM) AND the vendor is one
    // known to enforce autostart on top of stock Android. Routes the two
    // canonical remediations through the existing PowerOptimizationManager
    // (which already catches a missing Activity and falls back). Stays hidden
    // on Pixel / stock Android (Vendor.OTHER).
    val activity = context as? Activity
    if (a11yDegraded && activity != null && PowerOptimizationManager.needsOemAutostartGuidance()) {
        val vendor = PowerOptimizationManager.Vendor.current().displayName
        SettingsSection(
            header = stringResource(R.string.system_permissions_a11y_oem_header),
            footer = stringResource(R.string.system_permissions_a11y_oem_footer, vendor),
        ) {
            SettingsRow(
                icon = Icons.Outlined.RestartAlt,
                iconColor = Color(0xFFFF9500),
                title = stringResource(R.string.system_permissions_a11y_oem_autostart),
                subtitle = stringResource(R.string.system_permissions_a11y_oem_autostart_sub),
                onClick = {
                    if (!PowerOptimizationManager.openOemAutostartSettings(activity)) {
                        PowerOptimizationManager.openAppDetailsSettings(activity)
                    }
                },
            )
            SettingsRow(
                icon = Icons.Outlined.BatteryAlert,
                iconColor = Color(0xFFFF9500),
                title = stringResource(R.string.system_permissions_a11y_oem_battery),
                subtitle = stringResource(R.string.system_permissions_a11y_oem_battery_sub),
                onClick = {
                    if (!PowerOptimizationManager.requestBatteryOptimizationExemption(activity)) {
                        PowerOptimizationManager.openAppDetailsSettings(activity)
                    }
                },
                showDivider = false,
            )
        }
    }

    // [T-android-voice-correction] Phase 4: opt-in consent for storing
    // correction learning data, plus the wipe action. Placed here because it
    // is a privacy control, matching iOS's Permissions page.
    var correctionEnabled by remember {
        mutableStateOf(
            com.openminis.app.speech.correction.VoiceCorrectionConsent.isEnabled(context),
        )
    }
    var showClearCorrectionConfirm by remember { mutableStateOf(false) }

    SettingsSection(
        header = stringResource(R.string.voice_correction_section),
        footer = stringResource(R.string.voice_correction_footer),
    ) {
        SettingsSwitchRow(
            icon = Icons.Outlined.RecordVoiceOver,
            title = stringResource(R.string.voice_correction_toggle),
            checked = correctionEnabled,
            onCheckedChange = { on ->
                correctionEnabled = on
                com.openminis.app.speech.correction.VoiceCorrectionConsent
                    .setEnabled(context, on)
                // Turning the toggle off counts as an answer, so the one-time
                // prompt must not reappear afterwards.
                com.openminis.app.speech.correction.VoiceCorrectionConsent
                    .setPrompted(context, true)
            },
        )
        SettingsRow(
            icon = Icons.Outlined.DeleteSweep,
            iconColor = MaterialTheme.colorScheme.error,
            title = stringResource(R.string.voice_correction_clear),
            titleColor = MaterialTheme.colorScheme.error,
            onClick = { showClearCorrectionConfirm = true },
            showDivider = false,
        )
    }

    if (showClearCorrectionConfirm) {
        AlertDialog(
            onDismissRequest = { showClearCorrectionConfirm = false },
            title = { Text(stringResource(R.string.voice_correction_clear_title)) },
            confirmButton = {
                MinisTextButton(onClick = {
                    showClearCorrectionConfirm = false
                    com.openminis.app.speech.correction.VoiceCorrection
                        .clearAllData(context)
                    Toast.makeText(
                        context,
                        context.getString(R.string.voice_correction_cleared),
                        Toast.LENGTH_SHORT,
                    ).show()
                }) {
                    Text(
                        stringResource(R.string.voice_correction_clear),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                MinisTextButton(onClick = { showClearCorrectionConfirm = false }) {
                    Text(stringResource(R.string.voice_correction_consent_not_now))
                }
            },
        )
    }
}

internal fun isA11yServiceEnabled(context: Context): Boolean {
    val expected = "${context.packageName}/${MinisAccessibilityService::class.java.name}"
    val enabled = Settings.Secure.getString(
        context.contentResolver,
        Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
    ) ?: return false
    return enabled.split(':').any { it.equals(expected, ignoreCase = true) }
}

internal fun openAccessibilitySettings(context: Context) {
    try {
        context.startActivity(
            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
        )
    } catch (_: Throwable) {
        try {
            context.startActivity(
                Intent(Settings.ACTION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            )
        } catch (_: Throwable) {}
    }
}
