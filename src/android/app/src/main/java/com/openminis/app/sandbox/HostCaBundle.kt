package com.openminis.app.sandbox

import java.io.File
import java.security.MessageDigest
import java.util.Base64

/**
 * Host CA material that must survive `update-ca-certificates`.
 *
 * Writing only `/etc/ssl/certs/ca-certificates.crt` is wiped the next time
 * Debian regenerates that bundle. Unique host certs are therefore stored
 * under `/var/lib/minis/host-ca`, copied into the local and distro CA
 * directories, named between markers in `ca-certificates.conf`, and
 * re-merged by `/etc/ca-certificates/update.d/minis-host-ca`.
 */
object HostCaBundle {
    const val MARKER_BEGIN = "# MINIS-HOST-CA-BEGIN"
    const val MARKER_END = "# MINIS-HOST-CA-END"
    const val STORE_REL = "var/lib/minis/host-ca"
    const val LOCAL_REL = "usr/local/share/ca-certificates/minis-host"
    const val SHARE_REL = "usr/share/ca-certificates/minis-host"
    const val HOOK_REL = "etc/ca-certificates/update.d/minis-host-ca"

    data class PemCert(val fingerprint: String, val pem: String)

    fun pemOf(der: ByteArray): String {
        val b64 = Base64.getMimeEncoder(64, byteArrayOf('\n'.code.toByte())).encodeToString(der)
        return "-----BEGIN CERTIFICATE-----\n$b64\n-----END CERTIFICATE-----\n"
    }

    fun parsePems(text: String): List<PemCert> {
        val out = mutableListOf<PemCert>()
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val body = StringBuilder()
        var inCert = false
        for (line in lines) {
            if (line.startsWith("-----BEGIN CERTIFICATE-----")) {
                inCert = true
                body.clear()
                body.append(line).append('\n')
                continue
            }
            if (inCert && line.startsWith("-----END CERTIFICATE-----")) {
                body.append(line).append('\n')
                val pem = body.toString()
                val inner = pem.lineSequence()
                    .drop(1)
                    .filter { !it.startsWith("-----END") }
                    .joinToString("")
                if (inner.isNotBlank()) out += PemCert(fingerprintOf(inner), pem)
                inCert = false
                body.clear()
                continue
            }
            if (inCert) body.append(line).append('\n')
        }
        return out
    }

    fun dedupe(certs: Iterable<PemCert>): List<PemCert> {
        val seen = linkedMapOf<String, PemCert>()
        for (cert in certs) seen.putIfAbsent(cert.fingerprint, cert)
        return seen.values.toList()
    }

    fun renderBundle(certs: Iterable<PemCert>): String =
        dedupe(certs).joinToString("") { it.pem }

    fun fileName(fingerprint: String): String = "minis-${fingerprint.take(16)}.crt"

    fun mergeConf(existing: String, names: List<String>): String {
        val block = buildString {
            append(MARKER_BEGIN).append('\n')
            append("# host-unique certs; also in /usr/local/share/ca-certificates/minis-host\n")
            for (name in names) append(name).append('\n')
            append(MARKER_END).append('\n')
        }
        val start = existing.indexOf(MARKER_BEGIN)
        val stop = existing.indexOf(MARKER_END)
        if (start >= 0 && stop > start) {
            val head = existing.substring(0, start).trimEnd()
            val tail = existing.substring(stop + MARKER_END.length).trimStart('\n', '\r')
            return listOf(head, block.trimEnd(), tail.trimEnd())
                .filter { it.isNotEmpty() }
                .joinToString("\n") + "\n"
        }
        val base = existing.trimEnd()
        return if (base.isEmpty()) block else base + "\n" + block
    }

    fun fingerprintsUnder(dir: File): Set<String> {
        if (!dir.isDirectory) return emptySet()
        val out = linkedSetOf<String>()
        dir.walkTopDown().maxDepth(6).forEach { file ->
            if (!file.isFile) return@forEach
            val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
            if (!text.contains("BEGIN CERTIFICATE")) return@forEach
            parsePems(text).forEach { out += it.fingerprint }
        }
        return out
    }

    fun fingerprintOf(base64Body: String): String {
        val compact = base64Body.replace(Regex("\\s+"), "")
        val digest = MessageDigest.getInstance("SHA-256").digest(compact.toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun hookScript(): String = """
        #!/bin/sh
        # Re-merge host CAs after update-ca-certificates and drop duplicate PEMs.
        set -u
        STORE=/var/lib/minis/host-ca
        LOCAL=/usr/local/share/ca-certificates/minis-host
        BUNDLE=/etc/ssl/certs/ca-certificates.crt
        mkdir -p "${'$'}STORE" "${'$'}LOCAL" /etc/ssl/certs /usr/lib/ssl /etc/ssl
        if [ -d "${'$'}STORE" ]; then
          for f in "${'$'}STORE"/*.crt; do
            [ -f "${'$'}f" ] || continue
            cp -f "${'$'}f" "${'$'}LOCAL/${'$'}(basename "${'$'}f")"
            chmod 644 "${'$'}LOCAL/${'$'}(basename "${'$'}f")" || true
          done
        fi
        tmp="${'$'}{BUNDLE}.minis-tmp"
        {
          [ -f "${'$'}BUNDLE" ] && cat "${'$'}BUNDLE"
          for f in "${'$'}STORE"/*.crt; do
            [ -f "${'$'}f" ] || continue
            printf '\n'
            cat "${'$'}f"
          done
        } | awk '
          function flush() {
            if (body == "") return
            key = body
            gsub(/[[:space:]]/, "", key)
            if (!(key in seen)) {
              seen[key] = 1
              printf "%s", body
              if (substr(body, length(body), 1) != "\n") printf "\n"
            }
            body = ""
          }
          /^-----BEGIN CERTIFICATE-----/ { if (inb) flush(); inb = 1; body = ${'$'}0 "\n"; next }
          inb && /^-----END CERTIFICATE-----/ { body = body ${'$'}0 "\n"; flush(); inb = 0; next }
          inb { body = body ${'$'}0 "\n"; next }
          { if (${'$'}0 != "") print }
        ' > "${'$'}tmp"
        if [ -s "${'$'}tmp" ]; then
          mv "${'$'}tmp" "${'$'}BUNDLE"
          chmod 644 "${'$'}BUNDLE" || true
          cp -f "${'$'}BUNDLE" /usr/lib/ssl/cert.pem 2>/dev/null || true
          cp -f "${'$'}BUNDLE" /etc/ssl/cert.pem 2>/dev/null || true
          chmod 644 /usr/lib/ssl/cert.pem /etc/ssl/cert.pem 2>/dev/null || true
        else
          rm -f "${'$'}tmp"
        fi
        exit 0
    """.trimIndent() + "\n"
}
