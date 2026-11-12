package com.openminis.app.ui.settings


import com.openminis.app.ui.theme.m3Icon
import com.openminis.app.ui.theme.IosGreen
import com.openminis.app.ui.theme.IosRed
import androidx.compose.foundation.ExperimentalFoundationApi
import com.openminis.app.ui.chat.ModelBrandIcon
import com.openminis.app.ui.chat.ModelBrandResolver
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.openminis.app.data.model.ProviderType
import com.openminis.app.data.repository.ModelRefreshResult
import com.openminis.app.data.repository.ProviderRefreshMarks
import com.openminis.app.data.repository.ProviderRepository
import com.openminis.app.provider.BuiltinFreeLanes
import com.openminis.app.data.repository.restoreBuiltInFreeLaneAndRefresh
import com.openminis.app.logging.AppLogger
import com.openminis.app.ui.components.MinisAlertDialog
import com.openminis.app.ui.util.bringIntoViewOnFocus
import com.openminis.app.R
import kotlinx.coroutines.launch
import com.openminis.app.ui.components.MinisButton
import com.openminis.app.ui.components.MinisOutlinedButton
import com.openminis.app.ui.components.MinisSmallButton
import com.openminis.app.ui.components.MinisSmallOutlinedButton
import com.openminis.app.ui.components.MinisSmallTextButton
import com.openminis.app.ui.components.SectionTextField

private const val TAG = "ProviderDetail"


@Composable
internal fun OAuthCredentialBlock(
    instance: com.openminis.app.data.model.ProviderInstance,
    storedKey: String,
    providerRepository: ProviderRepository,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    // [T-android-openai-oauth-signout-signin-stuck] Drive the Sign In / Sign Out
    // toggle off LOCAL Compose state instead of the `storedKey` parameter (read
    // once per composition, never updates): the stale gate showed "Sign Out"
    // after logout and left Sign In dead. `displayedKey` mirrors the live
    // credential so the masked token / connected dot refresh together. Mirrors
    // iOS ProviderInstanceDetailView's `oauthRefreshTrigger.toggle()`.
    var displayedKey by remember(instance.id) { mutableStateOf(storedKey) }
    // [T-oauth-keep-credentials] Rejected refresh → red dot + hint instead of
    // the green one; cleared automatically when a fresh bundle lands.
    var needsReauth by remember(instance.id) {
        mutableStateOf(com.openminis.app.auth.OAuthManager.needsReauth(context, instance.id))
    }
    var isAuthenticating by remember(instance.id) { mutableStateOf(false) }
    // [T-kimi-oauth] Device-code dialog state for Kimi re-auth (see
    // AddProviderScreen.OAuthConfigSection for the rationale).
    var kimiDeviceAuth by remember(instance.id) {
        mutableStateOf<com.openminis.app.auth.KimiDeviceFlow.DeviceAuthorization?>(null)
    }
    var kimiLoginJob by remember(instance.id) { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    kimiDeviceAuth?.let { auth ->
        KimiDeviceLoginDialog(
            userCode = auth.userCode,
            verificationUrl = auth.openUrl,
            onCancel = {
                kimiLoginJob?.cancel()
                kimiDeviceAuth = null
            },
        )
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "OAuth",
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
        Spacer(modifier = Modifier.width(8.dp))
        if (displayedKey.isNotEmpty()) {
            Text(
                maskedKey(displayedKey),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            Box(
                modifier = Modifier
                    .padding(horizontal = 8.dp)
                    .size(8.dp)
                    .background(
                        if (needsReauth) IosRed else IosGreen,
                        CircleShape,
                    ),
            )
            if (needsReauth) {
                Text(
                    stringResource(R.string.provider_oauth_login_expired),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        } else {
            Spacer(modifier = Modifier.weight(1f))
            Text(
                stringResource(R.string.provider_oauth_not_connected),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
    Spacer(modifier = Modifier.height(8.dp))
    if (displayedKey.isNotEmpty()) {
        MinisSmallButton(
            onClick = {
                // [T-android-openai-oauth-signout-signin-stuck] A real sign-out
                // must clear BOTH credential stores: (1) the persisted OAuth
                // token blob (access + refresh_token + expire_at, plus any manual
                // bearer / account_id) via OAuthManager.logout(), and (2) the
                // apiKey mirror that ProviderFactory.loadApiKey() reads. Before
                // this fix only deleteApiKey() ran, so the refresh_token survived
                // and validAccessToken() silently re-minted an access token —
                // the user stayed effectively logged in. Then flip local state so
                // the UI swaps to the Sign In button immediately.
                com.openminis.app.auth.OAuthManager.forInstance(context, instance)?.logout()
                providerRepository.deleteApiKey(instance.id)
                displayedKey = ""
                AppLogger.info(TAG, "OAuth signed out for ${instance.id} (tokens + apiKey cleared)")
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.errorContainer,
                contentColor = MaterialTheme.colorScheme.error,
            ),
        ) {
            Text(stringResource(R.string.provider_detail_sign_out))
        }
    } else {
        MinisSmallButton(
            onClick = {
                if (isAuthenticating) return@MinisSmallButton
                isAuthenticating = true
                kimiLoginJob = scope.launch {
                    try {
                        val token = when (instance.providerType) {
                            ProviderType.kimiCode ->
                                com.openminis.app.auth.KimiOAuthManager.login(
                                    context, instance.id, providerRepository,
                                    onDeviceCode = { auth -> kimiDeviceAuth = auth },
                                ).also { kimiDeviceAuth = null }
                            ProviderType.anthropic ->
                                com.openminis.app.auth.ClaudeOAuthManager.login(
                                    context, instance.id, providerRepository,
                                )
                            ProviderType.openAI ->
                                com.openminis.app.auth.OpenAIOAuthManager.login(
                                    context, instance.id, providerRepository,
                                )
                            ProviderType.openRouter ->
                                com.openminis.app.auth.OpenRouterOAuthManager.login(
                                    context, instance.id, providerRepository,
                                )
                            ProviderType.xAI ->
                                com.openminis.app.auth.XAIOAuthManager.login(
                                    context, instance.id, providerRepository,
                                )
                            else -> null
                        }
                        // login() persists the token via providerRepository.saveApiKey;
                        // reflect it locally so the UI flips to the connected state
                        // without needing the parent to recompose.
                        if (token != null) {
                            displayedKey = providerRepository.loadApiKey(instance.id) ?: token
                            // [T-oauth-keep-credentials] Fresh bundle landed —
                            // clear the re-login flag so the dot turns green.
                            needsReauth = false
                            AppLogger.info(TAG, "OAuth signed in for ${instance.id}")
                        }
                    } catch (e: Exception) {
                        AppLogger.warning(TAG, "OAuth sign-in failed for ${instance.id}: ${e.message}")
                    } finally {
                        isAuthenticating = false
                        kimiDeviceAuth = null
                    }
                }
            },
        ) {
            Text(stringResource(R.string.provider_detail_sign_in))
        }
    }
}

