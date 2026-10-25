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
            append("minis_acquire_apt_lock 120 || exit 1; ")
            append("echo 'apt: 正在更新软件源…'; ")
            append("if ! DEBIAN_FRONTEND=noninteractive apt-get update; then ")
            append("minis_release_apt_lock || true; ")
            append("[ -x /usr/local/bin/minis-mirror ] && /usr/local/bin/minis-mirror auto || true; ")
            append("minis_acquire_apt_lock 120 || exit 1; ")
            append("DEBIAN_FRONTEND=noninteractive apt-get update || true; ")
            append("fi; ")
            append("echo 'apt: 正在安装软件包…'; ")
            append("DEBIAN_FRONTEND=noninteractive apt-get install -y --no-upgrade --no-install-recommends ")
            append(joined)
        }
    }

    /**
     * [T-apt-stale-lock-boot-sweep] One-shot boot-time sweep for dpkg/apt
     * lock files left behind by a session killed mid-install (PRoot
     * --kill-on-exit tears down the processes but not the guest filesystem).
     * The in-script `minis_acquire_apt_lock` only recovers locks for its own
     * run — an `apt-get` the agent ran BEFORE any minis- script could wedge
     * on such a residue and report a busy package manager forever.
     *
     * MUST run under the host [SandboxResourceGate.aptMutex]: no concurrent
     * apt can exist then, so a lock file with NO live apt/dpkg process is
     * stale by definition. The /proc scan is the liveness check (procps's
     * pgrep is not guaranteed in the base rootfs).
     */
    fun staleLockSweep(): String = buildString {
        append("busy=0; ")
        append("for c in /proc/[0-9]*/comm; do read -r n < \"\$c\" 2>/dev/null || continue; ")
        append("case \"\$n\" in apt-get|apt|dpkg) busy=1; break;; esac; done; ")
        append("if [ \"\$busy\" = 0 ]; then ")
        append("rm -f /var/lib/dpkg/lock /var/lib/dpkg/lock-frontend ")
        append("/var/lib/apt/lists/lock /var/cache/apt/archives/lock && ")
        append("echo 'apt: cleared stale dpkg locks'; ")
        append("else echo 'apt: package manager processes alive, keeping locks'; fi; ")
        // [T-mcp-daemon-boot-sweep] /tmp survives an app restart (guest tmpfs
        // is not cleared), so a dead daemon's markers persist. Drop them ONLY
        // when the recorded pid is gone: a LIVE pid is kept — the cli reuses
        // that daemon (its idle-selfkill bounds the lifetime), while dropping
        // its port file would just fork a duplicate.
        append("if [ -f /tmp/minis-mcp-daemon.pid ]; then ")
        append("p=$(cat /tmp/minis-mcp-daemon.pid 2>/dev/null); ")
        append("if [ -z \"\$p\" ] || ! kill -0 \"\$p\" 2>/dev/null; then ")
        append("rm -f /tmp/minis-mcp-daemon.pid /tmp/minis-mcp-daemon.port; ")
        append("echo 'mcp: cleared stale daemon markers'; fi; fi; ")
    }
}
