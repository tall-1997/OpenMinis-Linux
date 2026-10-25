package com.openminis.app.sandbox

import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * PEM / fingerprint helpers for the guest CA bundle.
 *
 * Fingerprint is SHA-256 of the DER (`X509Certificate.encoded`). Used to
 * (1) drop duplicate entries from AndroidCAStore and (2) skip Mozilla CAs
 * already shipped in `/usr/share/ca-certificates` when registering host-only
 * extras under `/usr/local/share/ca-certificates/minis-android`.
 */
object CaBundle {

    private val pemRegex = Regex(
        "-----BEGIN CERTIFICATE-----\\s+([A-Za-z0-9+/=\r\n]+)\\s+-----END CERTIFICATE-----",
    )

    fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) sb.append("%02x".format(b))
        return sb.toString()
    }

    fun fingerprintDer(der: ByteArray): String = sha256Hex(der)

    fun derFromPem(pem: String): ByteArray? {
        val b64 = pem
            .replace("-----BEGIN CERTIFICATE-----", "")
            .replace("-----END CERTIFICATE-----", "")
            .replace(Regex("\\s"), "")
        if (b64.isEmpty()) return null
        return try {
            Base64.getDecoder().decode(b64)
        } catch (_: Exception) {
            null
        }
    }

    fun fingerprintPem(pem: String): String? = derFromPem(pem)?.let { fingerprintDer(it) }

    fun parsePemBlocks(text: String): List<String> =
        pemRegex.findAll(text).map { it.value.replace("\r\n", "\n") }.toList()

    fun loadPemsFromTree(dir: File, excludeDirNames: Set<String> = emptySet()): List<String> {
        if (!dir.isDirectory) return emptyList()
        val out = ArrayList<String>()
        dir.walkTopDown()
            .onEnter { sub -> sub == dir || sub.name !in excludeDirNames }
            .forEach { f ->
                if (!f.isFile) return@forEach
                val name = f.name.lowercase()
                if (!name.endsWith(".crt") && !name.endsWith(".pem")) return@forEach
                try {
                    out.addAll(parsePemBlocks(f.readText()))
                } catch (_: Exception) {
                }
            }
        return out
    }

    /** First occurrence of each fingerprint wins. */
    fun uniquePems(pems: List<String>): List<String> {
        val seen = LinkedHashSet<String>()
        val out = ArrayList<String>(pems.size)
        for (pem in pems) {
            val fp = fingerprintPem(pem) ?: continue
            if (seen.add(fp)) out.add(pem)
        }
        return out
    }

    const val HOST_CA_CONF_BEGIN = "# minis-host-ca begin"
    const val HOST_CA_CONF_END = "# minis-host-ca end"

    /**
     * Replace the minis host-CA block in `/etc/ca-certificates.conf`.
     *
     * `update-ca-certificates` rebuilds the bundle from this file plus
     * `/usr/local/share/ca-certificates`. Paths are relative to
     * `/usr/share/ca-certificates` (for example `minis-android/abcd.crt`).
     * Stale `minis-android/` lines outside the block are dropped so a
     * previous inject cannot keep a cert the current host store no longer has.
     */
    fun rewriteCaCertificatesConf(existing: String, enabledRelativePaths: List<String>): String {
        val kept = ArrayList<String>()
        var skipping = false
        for (raw in existing.lineSequence()) {
            val line = raw.trim().trimEnd('\r')
            when {
                line == HOST_CA_CONF_BEGIN -> skipping = true
                line == HOST_CA_CONF_END -> skipping = false
                skipping -> Unit
                else -> {
                    val body = line.removePrefix("!")
                    if (body.startsWith("minis-android/")) continue
                    if (raw.isNotEmpty()) kept += raw.trimEnd('\r')
                }
            }
        }
        while (kept.lastOrNull()?.isBlank() == true) kept.removeAt(kept.lastIndex)
        kept += HOST_CA_CONF_BEGIN
        enabledRelativePaths.map { it.trim() }.filter { it.isNotEmpty() }.distinct().sorted()
            .forEach { kept += it }
        kept += HOST_CA_CONF_END
        return kept.joinToString("\n") + "\n"
    }
}
