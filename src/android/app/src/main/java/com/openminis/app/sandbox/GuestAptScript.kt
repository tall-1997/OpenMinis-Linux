package com.openminis.app.sandbox

/**
 * Guest `apt-get install` script used by the host-side dpkg-world heal path.
 *
 * TLS verification stays on. A failed `apt-get update` releases the apt lock
 * and asks `minis-mirror auto` to switch sources, then retries. It must not
 * pass `Acquire::https::Verify-Peer=false` — that accepts any certificate.
 */
internal object GuestAptScript {
    fun install(pkgs: List<String>): String {
        val joined = pkgs.joinToString(" ")
        return buildString {
            append("export TMPDIR=/tmp TMP=/tmp TEMP=/tmp DEBIAN_FRONTEND=noninteractive ")
            append("SSL_CERT_FILE=/etc/ssl/certs/ca-certificates.crt ")
            append("CURL_CA_BUNDLE=/etc/ssl/certs/ca-certificates.crt; ")
            append("mkdir -p /tmp /var/tmp /var/lock; ")
            append("[ -f /usr/local/lib/minis/apt-lock.sh ] && . /usr/local/lib/minis/apt-lock.sh; ")
            append("minis_acquire_apt_lock 120 || true; ")
            append("if ! DEBIAN_FRONTEND=noninteractive apt-get update -qq; then ")
            append("minis_release_apt_lock || true; ")
            append("[ -x /usr/local/bin/minis-mirror ] && /usr/local/bin/minis-mirror auto || true; ")
            append("minis_acquire_apt_lock 120 || true; ")
            append("DEBIAN_FRONTEND=noninteractive apt-get update -qq || true; ")
            append("fi; ")
            append("DEBIAN_FRONTEND=noninteractive apt-get install -y -qq --no-upgrade --no-install-recommends ")
            append(joined)
        }
    }
}
