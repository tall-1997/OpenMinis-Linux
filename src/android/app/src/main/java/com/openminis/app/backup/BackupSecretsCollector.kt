package com.openminis.app.backup

import android.content.Context
import com.openminis.app.logging.AppLogger
import java.io.File

/**
 * [T-backup-restore-oauth] Collects the credential values of a backup into
 * `staging/secrets.json` (NOT `data/`), base64 per value; the exporter's
 * encryption pass seals it with `secretsKey`. Extracted from
 * BackupExporter (its size was at the architecture ceiling); mirrors iOS
 * `BackupSecretsCollector.collect`.
 *
 * Only the selected categories contribute: PROVIDERS -> provider keys and
 * provider OAuth sign-in bundles, ENVIRONMENT_VARIABLES -> env-var values,
 * plus proxy passwords. Writes nothing when nothing yields a secret, so a
 * share copy carries no secrets member. Every key and value is read from the
 * device's own stores - no literals.
 */
internal object BackupSecretsCollector {
    private const val TAG = "Backup"

    fun collect(context: Context, staging: File, categories: Set<BackupCategory>) {
        val app = context.applicationContext as? com.openminis.app.MinisApp

        val providerSecrets =
            if (BackupCategory.PROVIDERS in categories) {
                val repo = app?.providerRepositoryOrNull
                repo?.config?.value?.instances.orEmpty()
                    .mapNotNull { repo?.collectBackupProviderSecret(it) }
            } else emptyList()

        val envSecrets =
            if (BackupCategory.ENVIRONMENT_VARIABLES in categories) {
                val repo = app?.let { if (it.subsystemsReady()) it.envVarRepository else null }
                repo?.entries?.value.orEmpty().mapNotNull { entry ->
                    val value = repo?.getValue(entry.key)
                    if (value.isNullOrEmpty()) null
                    else BackupSecrets.EnvVarSecret(
                        name = entry.key,
                        value = android.util.Base64.encodeToString(
                            value.toByteArray(), android.util.Base64.NO_WRAP
                        ),
                    )
                }
            } else emptyList()

        // [T-backup-restore-oauth] Provider sign-in bundles: every stored
        // token JSON in oauth_prefs, keyed by instance (getAll enumerates the
        // decrypted entries). The manual bearer override rides along.
        val oauthSecrets =
            if (BackupCategory.PROVIDERS in categories) {
                runCatching {
                    com.openminis.app.util.EncryptedPrefsFactory
                        .safeCreate(context, "oauth_prefs")
                        .all
                        .mapNotNull { (key, value) ->
                            val id = key.removePrefix("oauth_tokens_")
                            val tokens = value as? String
                            if (tokens.isNullOrEmpty() || id == key) return@mapNotNull null
                            BackupSecrets.OauthSecret(
                                instanceId = id,
                                tokensJson = tokens,
                                manualBearer = (com.openminis.app.util.EncryptedPrefsFactory
                                    .safeCreate(context, "oauth_prefs")
                                    .getString("oauth_manual_bearer_token_$id", null)),
                            )
                        }
                }.getOrDefault(emptyList())
            } else emptyList()

        // [T-backup-restore-network] Proxy passwords are credentials: they ride
        // in secrets.json (secrets subkey), one entry per configured proxy.
        val proxyPasswords =
            if (BackupCategory.PROVIDERS in categories) {
                com.openminis.app.network.NetworkSettingsStore.load(context).proxies.mapNotNull { proxy ->
                    val pass = com.openminis.app.network.NetworkSecrets.password(context, proxy.id)
                    if (pass.isEmpty()) null
                    else BackupSecrets.NamedSecret(
                        name = proxy.id,
                        value = android.util.Base64.encodeToString(
                            pass.toByteArray(), android.util.Base64.NO_WRAP,
                        ),
                    )
                }
            } else emptyList()

        if (providerSecrets.isEmpty() && envSecrets.isEmpty() &&
            oauthSecrets.isEmpty() && proxyPasswords.isEmpty()
        ) return

        val secrets = BackupSecrets(
            providers = providerSecrets,
            envVars = envSecrets,
            oauth = oauthSecrets,
            proxyPasswords = proxyPasswords,
        )
        File(staging, "secrets.json").writeText(
            BackupFormat.json.encodeToString(BackupSecrets.serializer(), secrets)
        )
        AppLogger.warning(
            TAG,
            "[Backup] package contains UNENCRYPTED credentials (stage 3a): " +
                "providers=${providerSecrets.size} envVars=${envSecrets.size}"
        )
    }
}
