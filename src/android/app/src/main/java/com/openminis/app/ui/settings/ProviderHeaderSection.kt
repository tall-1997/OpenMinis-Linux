package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.openminis.app.provider.ProviderHeaderStore
import com.openminis.app.ui.components.MinisSmallTextButton
import com.openminis.app.ui.components.SectionTextField
import com.openminis.app.ui.util.bringIntoViewOnFocus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val TAG = "ProviderDetail"

/**
 * [GH#221] Per-provider custom request headers (one `Name: value` per line),
 * stored OUTSIDE ProviderInstance so the Room schema stays untouched.
 *
 * Extracted from ProviderDetailScreen.kt both to keep that screen inside its
 * architecture line ceiling and to fix the main-thread write: the store's
 * `save()` opens the Keystore-backed EncryptedSharedPreferences and calls
 * `commit()`, which used to run directly in the button's onClick. The initial
 * read moves to IO for the same reason. Saving still bumps the store revision,
 * so ProviderFactory's memo key changes and the next chat / `/models` request
 * picks up the new headers.
 */
@Composable
internal fun ProviderHeaderSection(instanceId: String) {
    var headersText by remember(instanceId) { mutableStateOf("") }
    var headersError by remember(instanceId) { mutableStateOf<String?>(null) }
    // The initial read now happens off Main; until it lands, an empty edit box
    // would otherwise let an early Save clear the user's stored headers.
    var loaded by remember(instanceId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(instanceId) {
        headersText = withContext(Dispatchers.IO) {
            ProviderHeaderStore.rawTextFor(instanceId)
        }
        loaded = true
    }

    SettingsSection(
        header = stringResource(R.string.provider_detail_custom_headers),
        footer = stringResource(R.string.provider_detail_custom_headers_footer),
    ) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
            SectionTextField(
                value = headersText,
                onValueChange = {
                    headersText = it
                    headersError = null
                },
                singleLine = false,
                maxLines = 8,
                placeholder = stringResource(R.string.provider_detail_custom_headers_placeholder),
                isError = headersError != null,
                fieldModifier = Modifier.bringIntoViewOnFocus(),
            )
            headersError?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            MinisSmallTextButton(
                onClick = {
                    scope.launch {
                        val error = ProviderHeaderStore.save(instanceId, headersText)
                        headersError = error
                        if (error == null) {
                            // The save lambda already runs off Main (suspending
                            // call), so re-reading the persisted form here is
                            // cheap and keeps the edit box normalized.
                            headersText = ProviderHeaderStore.rawTextFor(instanceId)
                            AppLogger.info(
                                TAG,
                                "Saved custom headers for $instanceId: " +
                                    "${ProviderHeaderStore.headersFor(instanceId).size} header(s)",
                            )
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = loaded,
            ) {
                Text(stringResource(R.string.provider_detail_save_headers))
            }
        }
    }
}
