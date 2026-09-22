# Shared guest apt lock. Source, do not execute.
# Order: acquire global lock → then fuser-check → then delete stale dpkg
# locks → then dpkg --configure -a. Reversing any step races.
#
# The exclusion is POSIX mkdir. flock is taken as well when it exists, but
# a flock timeout must not fall through into a second protocol: the holder
# is still running apt. PRoot may also report flock success without excluding
# anyone, so mkdir is not optional.

MINIS_APT_LOCKFILE="${MINIS_APT_LOCKFILE:-/var/lock/sandbox-apt.lock}"
MINIS_APT_LOCKDIR="${MINIS_APT_LOCKDIR:-/var/lock/sandbox-apt.d}"

minis_clear_stale_dpkg_locks() {
    _lf=""
    for _lf in \
        /var/lib/dpkg/lock-frontend \
        /var/lib/dpkg/lock \
        /var/lib/apt/lists/lock \
        /var/cache/apt/archives/lock
    do
        [ -e "$_lf" ] || continue
        if command -v fuser >/dev/null 2>&1 && fuser "$_lf" >/dev/null 2>&1; then
            echo "minis-apt-lock: $_lf still held, skip" >&2
            continue
        fi
        rm -f "$_lf"
    done
    dpkg --configure -a || true
}

minis_acquire_apt_lock() {
    _timeout="${1:-180}"
    mkdir -p /var/lock /tmp /var/tmp 2>/dev/null || true
    _waited=0
    while ! mkdir "$MINIS_APT_LOCKDIR" 2>/dev/null; do
        sleep 1
        _waited=$((_waited + 1))
        if [ "$_waited" -ge "$_timeout" ]; then
            echo "minis-apt-lock: timeout waiting for $MINIS_APT_LOCKDIR" >&2
            return 1
        fi
        if [ -d "$MINIS_APT_LOCKDIR" ]; then
            _pid=$(cat "$MINIS_APT_LOCKDIR/pid" 2>/dev/null || true)
            if [ -n "$_pid" ] && kill -0 "$_pid" 2>/dev/null; then
                continue
            fi
            _mtime=$(stat -c %Y "$MINIS_APT_LOCKDIR/pid" 2>/dev/null || stat -c %Y "$MINIS_APT_LOCKDIR" 2>/dev/null || echo 0)
            _now=$(date +%s 2>/dev/null || echo 0)
            _age=$(( _now - _mtime ))
            # Dead pid: steal now. Missing pid: the holder may still be
            # writing it, so only steal a lock that has been empty for a bit.
            # Do not steal a live install just because the directory is old.
            if [ -n "$_pid" ] || [ "$_age" -gt 30 ]; then
                echo "minis-apt-lock: stale lock (pid=${_pid:-none} age=${_age}s), removing" >&2
                rm -rf "$MINIS_APT_LOCKDIR" 2>/dev/null || true
            fi
        fi
    done
    echo $$ > "$MINIS_APT_LOCKDIR/pid" 2>/dev/null || true
    if command -v flock >/dev/null 2>&1; then
        exec 9>"$MINIS_APT_LOCKFILE" 2>/dev/null || true
        flock -n 9 2>/dev/null || true
    fi
    minis_clear_stale_dpkg_locks
    return 0
}

minis_release_apt_lock() {
    flock -u 9 2>/dev/null || true
    exec 9>&- 2>/dev/null || true
    # Only drop a lock this process holds. An EXIT trap after a failed
    # re-acquire must not rm another apt's lock directory.
    _pid=$(cat "$MINIS_APT_LOCKDIR/pid" 2>/dev/null || true)
    if [ "$_pid" = "$$" ]; then
        rm -rf "$MINIS_APT_LOCKDIR" 2>/dev/null || true
    fi
    return 0
}
