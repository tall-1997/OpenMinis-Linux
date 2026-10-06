package com.openminis.app.ssh

import android.content.Context
import android.content.SharedPreferences
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.json.JSONArray
import org.json.JSONObject

/**
 * [T-ssh-backend] SSH host configuration store.
 *
 * Split storage, mirroring EnvVarRepository: NON-secret metadata (id, name,
 * host, port, username, auth type) lives in a plain JSON file so it can be
 * inspected/debugged; secret material (password, private key, passphrase)
 * lives behind [SecretBox], which production backs with keystore-encrypted
 * SharedPreferences and tests back with an in-memory map. The metadata file
 * must never contain secrets — [SshConfigStoreTest] asserts exactly that.
 */

/** Abstraction over the secret backend so the store is JVM-testable. */
interface SecretBox {
    fun get(key: String): String?
    fun put(key: String, value: String)
    fun remove(key: String)
}

class PrefsSecretBox(private val prefs: SharedPreferences) : SecretBox {
    override fun get(key: String): String? = prefs.getString(key, null)
    override fun put(key: String, value: String) {
        prefs.edit().putString(key, value).apply()
    }
    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }
}

class InMemorySecretBox : SecretBox {
    val map = HashMap<String, String>()
    override fun get(key: String): String? = map[key]
    override fun put(key: String, value: String) { map[key] = value }
    override fun remove(key: String) { map.remove(key) }
}

/** What [SshEngine] needs to authenticate one host. Assembled in memory only. */
data class SshCredentials(
    val password: String? = null,
    val privateKeyPem: String? = null,
    val passphrase: String? = null,
)

class SshConfigStore(
    private val metadataFile: File,
    private val secrets: SecretBox,
) {

    private val lock = Any()

    @Volatile
    private var cached: List<SshHostConfig> = load()

    fun list(): List<SshHostConfig> = cached

    /** Lookup by exact id, then case-insensitive name, then exact hostname. */
    fun find(idOrName: String): SshHostConfig? {
        val key = idOrName.trim()
        if (key.isEmpty()) return null
        return cached.find { it.id == key }
            ?: cached.find { it.name.equals(key, ignoreCase = true) }
            ?: cached.find { it.host == key }
    }

    fun hasHosts(): Boolean = cached.isNotEmpty()

    /**
     * Insert or replace (same id) a host. Returns the stored config.
     * Secrets go to [secrets] under "<id>.password" / "<id>.key" /
     * "<id>.passphrase"; metadata stays secret-free.
     */
    fun put(config: SshHostConfig, credentials: SshCredentials?): SshHostConfig {
        val err = validate(config)
        if (err != null) throw SshFailure.Config(err)
        synchronized(lock) {
            val next = cached.filterNot { it.id == config.id } + config
            cached = next
            persist(next)
        }
        if (credentials != null) {
            credentials.password?.let { secrets.put("${config.id}.password", it) }
            credentials.privateKeyPem?.let { secrets.put("${config.id}.key", it) }
            credentials.passphrase?.let { secrets.put("${config.id}.passphrase", it) }
        }
        return config
    }

    fun remove(id: String): Boolean {
        val existed = synchronized(lock) {
            val next = cached.filterNot { it.id == id }
            val had = next.size != cached.size
            cached = next
            if (had) persist(next)
            had
        }
        if (existed) {
            secrets.remove("$id.password")
            secrets.remove("$id.key")
            secrets.remove("$id.passphrase")
        }
        return existed
    }

    /** Update only the pinned host-key fingerprint (TOFU record). Blank clears the pin. */
    fun pinFingerprint(id: String, fingerprint: String) {
        synchronized(lock) {
            val next = cached.map { if (it.id == id) it.copy(hostKeyFingerprint = fingerprint.ifBlank { null }) else it }
            cached = next
            persist(next)
        }
    }

    fun credentialsFor(id: String): SshCredentials? {
        val password = secrets.get("$id.password")
        val key = secrets.get("$id.key")
        val passphrase = secrets.get("$id.passphrase")
        if (password == null && key == null) return null
        return SshCredentials(password = password, privateKeyPem = key, passphrase = passphrase)
    }

    private fun persist(hosts: List<SshHostConfig>) {
        val arr = JSONArray()
        for (h in hosts) arr.put(encode(h))
        runCatching {
            metadataFile.parentFile?.mkdirs()
            val tmp = File(metadataFile.parentFile, metadataFile.name + ".tmp")
            tmp.writeText(arr.toString())
            Files.move(tmp.toPath(), metadataFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun load(): List<SshHostConfig> {
        if (!metadataFile.isFile) return emptyList()
        return runCatching {
            val arr = JSONArray(metadataFile.readText())
            (0 until arr.length()).mapNotNull { i -> decode(arr.optJSONObject(i)) }
        }.getOrDefault(emptyList())
    }

    companion object {
        private val NAME_REGEX = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$")

        /** Pure validation — returns an error message or null. */
        fun validate(c: SshHostConfig): String? = when {
            c.id.isBlank() -> "host id must not be blank"
            !NAME_REGEX.matches(c.name) ->
                "invalid host name '${c.name}': 1-64 chars, start alphanumeric, only letters/digits/._-"
            c.host.isBlank() -> "hostname must not be blank"
            c.port !in 1..65535 -> "port ${c.port} outside 1-65535"
            c.username.isBlank() -> "username must not be blank"
            else -> null
        }

        /** Pure id generation: name slug + 4 hex chars, stable per call site. */
        fun newId(name: String, taken: Set<String>): String {
            val slug = name.lowercase()
                .replace(Regex("[^a-z0-9]+"), "-")
                .trim('-')
                .take(24)
                .ifBlank { "host" }
            var candidate = slug
            var n = 0
            while (candidate in taken) {
                n++
                candidate = "$slug-$n"
            }
            return candidate
        }

        fun encode(c: SshHostConfig): JSONObject = JSONObject().apply {
            put("id", c.id)
            put("name", c.name)
            put("host", c.host)
            put("port", c.port)
            put("username", c.username)
            put("authType", c.authType.wireName)
            c.hostKeyFingerprint?.let { put("fingerprint", it) }
            put("createdAt", c.createdAt)
        }

        fun decode(obj: JSONObject?): SshHostConfig? {
            if (obj == null) return null
            val id = obj.optString("id", "")
            val name = obj.optString("name", "")
            val host = obj.optString("host", "")
            val username = obj.optString("username", "")
            val authType = SshAuthType.parse(obj.optString("authType", ""))
            if (id.isBlank() || name.isBlank() || host.isBlank() || username.isBlank() || authType == null) return null
            return SshHostConfig(
                id = id,
                name = name,
                host = host,
                port = obj.optInt("port", 22),
                username = username,
                authType = authType,
                hostKeyFingerprint = obj.optString("fingerprint", "").ifBlank { null },
                createdAt = obj.optLong("createdAt", 0L),
            )
        }

        /** Production entry point: filesDir/ssh/hosts.json + keystore prefs. */
        fun forContext(context: Context): SshConfigStore {
            val app = context.applicationContext
            val meta = File(app.filesDir, "ssh/hosts.json")
            val prefs = com.openminis.app.util.EncryptedPrefsFactory.safeCreate(app, "ssh_host_secrets")
            return SshConfigStore(meta, PrefsSecretBox(prefs))
        }
    }
}
