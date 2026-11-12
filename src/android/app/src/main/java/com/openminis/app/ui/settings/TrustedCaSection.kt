package com.openminis.app.ui.settings

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.openminis.app.R
import com.openminis.app.network.AppTrustStore
import com.openminis.app.provider.ProviderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * [GH#258][GH#287] Settings → Network → Proxies → Trusted Certificates.
 *
 * Lets the user import a PEM for a provider that serves HTTPS from a private or
 * self-signed CA. The certificate goes into [AppTrustStore] (app-private files
 * dir) and is added to the system trust anchors for provider HTTP clients — it
 * never replaces them and does not relax hostname verification.
 *
 * Deliberately no per-certificate editor: importing the same PEM twice is
 * deduplicated by fingerprint, and "Remove All" is the recovery path. That is
 * enough to unblock a failed handshake without building a PKI manager.
 */
@Composable
fun TrustedCaSection() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var certCount by remember { mutableStateOf(AppTrustStore.list().size) }
    var status by remember { mutableStateOf<String?>(null) }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val pem = context.contentResolver.openInputStream(uri)?.use { stream ->
                        stream.readBytes().toString(Charsets.UTF_8)
                    } ?: return@runCatching null
                    AppTrustStore.init(context)
                    AppTrustStore.importPem(pem).also { result ->
                        // [GH#258][GH#287] provider 的 memo key 已带
                        // AppTrustStore.generation（新 key 不会命中旧 client），
                        // 这里再主动清一次，让旧 provider/OkHttpClient 立刻被丢弃，
                        // 而不是等 LRU 逐出或进程重启。导入前已发出的请求会自然失败，
                        // 重试即走新信任链。
                        if (result.imported > 0) ProviderFactory.invalidateAll()
                    }
                }.getOrNull()
            }
            certCount = AppTrustStore.list().size
            status = if (result == null) {
                context.getString(R.string.network_trusted_ca_failed)
            } else if (result.imported > 0) {
                context.getString(R.string.network_trusted_ca_imported, result.imported)
            } else if (result.duplicates > 0) {
                context.getString(R.string.network_trusted_ca_duplicate)
            } else {
                context.getString(R.string.network_trusted_ca_failed)
            }
        }
    }

    SettingsSection(
        header = stringResource(R.string.network_trusted_ca_title),
        footer = status ?: stringResource(R.string.network_trusted_ca_footer),
    ) {
        SettingsRow(
            title = stringResource(R.string.network_trusted_ca_title),
            subtitle = if (certCount == 0) {
                stringResource(R.string.network_trusted_ca_none)
            } else {
                stringResource(R.string.network_trusted_ca_count, certCount)
            },
            showChevron = false,
            showDivider = true,
        )
        SettingsRow(
            title = stringResource(R.string.network_trusted_ca_import),
            icon = Icons.Outlined.Add,
            onClick = {
                status = null
                importLauncher.launch(
                    arrayOf(
                        "application/x-pem-file",
                        "application/x-x509-ca-cert",
                        "application/pkix-cert",
                        "text/plain",
                        "*/*",
                    ),
                )
            },
            showChevron = false,
            showDivider = certCount > 0,
        )
        if (certCount > 0) {
            SettingsRow(
                title = stringResource(R.string.network_trusted_ca_remove_all),
                icon = Icons.Outlined.Delete,
                iconColor = MaterialTheme.colorScheme.error,
                titleColor = MaterialTheme.colorScheme.error,
                onClick = {
                    // [GH#258][GH#287] 删除 CA 同样要换掉旧 TLS 材料：清掉已缓存的
                    // provider，否则删掉的信任锚仍会随旧 client 继续生效。
                    val removed = AppTrustStore.clear()
                    if (removed > 0) ProviderFactory.invalidateAll()
                    certCount = 0
                    status = context.getString(R.string.network_trusted_ca_removed)
                },
                showChevron = false,
                showDivider = false,
            )
        }
    }
}
