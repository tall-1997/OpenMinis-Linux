package com.openminis.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.logging.AppLogger
import com.openminis.app.provider.ProviderHeaderStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ProviderDetail"

/**
 * [GH#347] Per-provider opt-in for the `x-opencode-session` header on a CUSTOM
 * OpenAI-compatible endpoint.
 *
 * ## Why an opt-in (privacy root fix)
 *
 * The header carries a stable per-conversation identifier. Sending it to every
 * third-party relay the user configures is a tracking vector the app would be
 * introducing without being asked, so custom endpoints start OFF and only get
 * the header after the user flips this switch for that provider. The official
 * OpenCode/Zen lane is unaffected: [com.openminis.app.provider.OpenCodeSessionHeader.headersFor]
 * gates it by HOST and still sends automatically, because the service requires
 * the header to route.
 *
 * Extraction note: this lives next to [ProviderHeaderSection] rather than inside
 * `ProviderDetailScreen` (architecture-policy line ceiling), and the initial
 * read + save run on IO because the first [ProviderHeaderStore] access opens
 * Keystore-backed EncryptedSharedPreferences.
 */
@Composable
internal fun OpenCodeSessionSection(instanceId: String, customBaseURL: String?) {
    // No custom base = the official endpoint path; nothing to opt into.
    if (customBaseURL.isNullOrBlank()) return

    var enabled by remember(instanceId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(instanceId) {
        enabled = withContext(Dispatchers.IO) {
            ProviderHeaderStore.openCodeSessionEnabled(instanceId)
        }
    }

    SettingsSection(
        header = stringResource(R.string.provider_detail_opencode_session_header),
        footer = stringResource(R.string.provider_detail_opencode_session_footer),
    ) {
        SettingsSwitchRow(
            title = stringResource(R.string.provider_detail_opencode_session_toggle),
            checked = enabled,
            onCheckedChange = { on ->
                scope.launch {
                    val saved = ProviderHeaderStore.setOpenCodeSessionEnabled(instanceId, on)
                    if (saved) {
                        enabled = on
                        AppLogger.info(TAG, "Set openCodeSessionEnabled=$on for $instanceId")
                    }
                }
            },
            showDivider = false,
        )
    }
}
