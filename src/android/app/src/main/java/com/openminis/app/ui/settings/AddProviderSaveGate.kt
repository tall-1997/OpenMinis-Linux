package com.openminis.app.ui.settings

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.openminis.app.data.model.ProviderInstance
import com.openminis.app.data.repository.ProviderRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * [OpenMinis#306] Save-gate for the three add-provider flows.
 *
 * `addInstance` refuses (returns false) while the provider config is still the
 * empty cold-start placeholder, because persisting against it would make
 * `ProviderConfigDao.replaceDiff` delete every stored instance. The old UI
 * ignored that and went on to save the credential, fire a refresh and navigate
 * away — attaching a key to an instance that was never persisted.
 *
 * Extracted from [AddProviderScreen] (pinned at its architecture line ceiling)
 * so each call site stays one expression. Returns null on success, otherwise
 * the message to render under the button.
 */
internal fun ProviderRepository.addInstanceFromUi(
    instance: ProviderInstance,
    credential: () -> Unit,
    scope: CoroutineScope,
    onAdded: () -> Unit,
): String? {
    if (!addInstance(instance)) return ADD_PROVIDER_CONFIG_LOADING
    credential()
    // [OpenMinis#306] refreshModels mutates config once the config is loaded;
    // never start it from the Main-immediate rememberCoroutineScope dispatcher.
    scope.launch(Dispatchers.IO) { refreshModels(instance) }
    onAdded()
    return null
}

/** [OpenMinis#306] User-facing refusal text shared by the three save buttons. */
internal const val ADD_PROVIDER_CONFIG_LOADING =
    "Provider settings are still loading. Please try again in a moment."

/** [OpenMinis#306] Inline failure text rendered under a save button. */
@Composable
internal fun SaveFailureMessage(message: String?) {
    if (message == null) return
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 6.dp),
    )
}

/** [OpenMinis#306] Moved out of [AddProviderScreen] for its line ceiling. */
internal fun maskOAuthToken(token: String): String {
    if (token.length <= 4) return "*".repeat(token.length)
    val edge = minOf(10, token.length / 3)
    return token.take(edge) + "*".repeat(10) + token.takeLast(edge)
}
