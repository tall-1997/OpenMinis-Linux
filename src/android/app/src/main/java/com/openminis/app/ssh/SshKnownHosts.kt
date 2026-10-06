package com.openminis.app.ssh

import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * [T-ssh-backend] Trust-on-first-use host key store.
 *
 * The first successful connect pins SHA256:<base64> of the server's host
 * key blob (same format ssh-keygen prints, so users can compare against
 * `ssh-keygen -lf`). Every later connect must MATCH; a MISMATCH aborts the
 * handshake before credentials are offered (possible MITM) and surfaces as
 * [SshFailure.HostKeyMismatch]. Recovery is deliberate: remove_host +
 * add_host clears the pin (add_host calls [forget]) so re-pinning is a
 * conscious user/agent action, never automatic.
 *
 * [file] == null keeps everything in memory (unit tests); persistence is a
 * flat JSON object { "host:port": "SHA256:..." } written atomically.
 */
class SshKnownHosts(private val file: File?) {

    sealed class Decision {
        /** No pin yet — caller should [record] and proceed (TOFU). */
        object New : Decision()
        object Match : Decision()
        data class Mismatch(val stored: String) : Decision()
    }

    private val pins = ConcurrentHashMap<String, String>()

    init {
        load()
    }

    fun decide(hostPort: String, fingerprint: String): Decision {
        val cur = pins[hostPort] ?: return Decision.New
        return if (cur == fingerprint) Decision.Match else Decision.Mismatch(cur)
    }

    fun record(hostPort: String, fingerprint: String) {
        pins[hostPort] = fingerprint
        save()
    }

    fun forget(hostPort: String) {
        if (pins.remove(hostPort) != null) save()
    }

    fun fingerprintFor(hostPort: String): String? = pins[hostPort]

    fun all(): Map<String, String> = HashMap(pins)

    private fun load() {
        val f = file ?: return
        if (!f.isFile) return
        runCatching {
            val obj = JSONObject(f.readText())
            for (k in obj.keys()) {
                val v = obj.optString(k, "")
                if (v.isNotEmpty()) pins[k] = v
            }
        }
    }

    private fun save() {
        val f = file ?: return
        runCatching {
            val obj = JSONObject()
            for ((k, v) in pins) obj.put(k, v)
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(obj.toString())
            Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {
        fun hostPort(host: String, port: Int): String = "$host:$port"

        /** OpenSSH-style SHA256 fingerprint of a raw host key blob. */
        fun fingerprint(keyBlob: ByteArray): String {
            val digest = MessageDigest.getInstance("SHA-256").digest(keyBlob)
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest)
        }
    }
}
