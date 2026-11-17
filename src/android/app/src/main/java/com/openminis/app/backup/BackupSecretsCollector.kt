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

    /**
     * Stages the credential values into `staging/secrets.json`.
     *
     * @param recordSkip [T-backup-decode-honesty] called with a descriptive
     * path when a credential source could not be read, so the caller can put
     * the omission into the run's skipped-entries record instead of letting a
     * restore surface as "every provider logged out" with no trace.
     * @return the number of credential entries staged (provider secrets +
     * env vars + OAuth token bundles + proxy passwords), for callers that
     * want to surface how many credentials a package carries.
     */
    fun collect(
        context: Context,
        staging: File,
        categories: Set<BackupCategory>,
        recordSkip: ((String) -> Unit)? = null,
    ): Int {
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
                // [T-keystore-degraded-signal] safeCreate NEVER throws: on a
                // keystore/keyset failure it silently swaps in a non-persisting
                // in-memory store whose reads return empty — the exact
                // "every provider signed out" packaging the decode-honesty
                // contract exists to prevent, with no exception to catch. The
                // factory's degraded flag is the honest signal.
                val prefs = com.openminis.app.util.EncryptedPrefsFactory
                    .safeCreate(context, "oauth_prefs")
                val degraded = com.openminis.app.util.EncryptedPrefsFactory
                    .isDegraded("oauth_prefs")
                runCatching {
                    prefs.all
                        .mapNotNull { (key, value) ->
                            val id = key.removePrefix("oauth_tokens_")
                            val tokens = value as? String
                            if (tokens.isNullOrEmpty() || id == key) return@mapNotNull null
                            BackupSecrets.OauthSecret(
                                instanceId = id,
                                tokensJson = tokens,
                                manualBearer = prefs
                                    .getString("oauth_manual_bearer_token_$id", null),
                            )
                        }
                }.onFailure { e ->
                    // [T-backup-decode-honesty] An unreadable token store must
                    // not package as "every provider is signed out" without a
                    // trace: push the omission through the run's skip record so
                    // the history shows the package is missing sign-ins.
                    AppLogger.error(TAG, "[Backup] oauth token store unreadable: ${e.message}")
                    recordSkip?.invoke(
                        "secrets/oauth_prefs (oauth token store unreadable: ${e.javaClass.simpleName})",
                    )
                }.getOrDefault(emptyList()).also { secrets ->
                    if (degraded && secrets.isEmpty()) {
                        AppLogger.error(TAG, "[Backup] oauth token store DEGRADED (in-memory fallback): sign-ins omitted from the package")
                        recordSkip?.invoke(
                            "secrets/oauth_prefs (oauth token store degraded: keystore unavailable, sign-ins unreadable)",
                        )
                    }
                }
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
        ) return 0

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
        return providerSecrets.size + envSecrets.size +
            oauthSecrets.size + proxyPasswords.size
    }
}
