#!/system/bin/sh
# One snapshot; no stress workload, logcat, command arguments, or environment dump.
# Android shell: sh collect-memory.sh com.openminis.linux > memory-idle.txt
# PRoot fallback: bash collect-memory.sh --proc-only > memory-guest.txt
set -u
PACKAGE=com.openminis.linux
PROC_ONLY=0
case "${1:-}" in
  --proc-only) PROC_ONLY=1 ;;
  --help|-h)
    printf '%s\n' 'Usage: sh collect-memory.sh [package|--proc-only]' \
      'Run in adb shell or an authorized host shell for app/WebView visibility.' \
      'Capture idle, during normal workload, and after workload; no polling loop is installed.'
    exit 0 ;;
  '') ;;
  *) PACKAGE=$1 ;;
esac
case "$PACKAGE" in *[!a-zA-Z0-9_.]*|'') printf '%s\n' 'Invalid package name' >&2; exit 2 ;; esac
section() { printf '\n=== %s ===\n' "$1"; }
read_file() {
  if [ -r "$1" ]; then cat "$1"; else printf 'unavailable: %s\n' "$1"; fi
}
section 'Capture metadata'
date -u '+%Y-%m-%dT%H:%M:%SZ'
printf 'package=%s proc_only=%s\n' "$PACKAGE" "$PROC_ONLY"
printf '%s\n' 'This snapshot belongs to the device on which this script runs.'
if [ "$PROC_ONLY" = 0 ] && command -v getprop >/dev/null 2>&1; then
  for key in ro.product.model ro.build.version.release ro.build.version.sdk ro.boot.bootreason sys.boot.reason; do
    printf '%s=' "$key"
    getprop "$key"
  done
fi
section 'Global memory (kB)'
if [ -r /proc/meminfo ]; then
  grep -E '^(MemTotal|MemFree|MemAvailable|Buffers|Cached|SwapCached|Active|Inactive|AnonPages|Shmem|SReclaimable|SUnreclaim|Slab|SwapTotal|SwapFree|Dirty|Writeback):' /proc/meminfo
fi
section 'Memory PSI (avg percentages, total microseconds)'
read_file /proc/pressure/memory
section 'VM counters (cumulative; compare delta between captures)'
if [ -r /proc/vmstat ]; then
  grep -E '^(pswpin|pswpout|pgmajfault|allocstall[^ ]*|pgscan[^ ]*|pgsteal[^ ]*|oom_kill) ' /proc/vmstat
fi
section 'Process RSS (kB); shared pages can be counted repeatedly'
printf '%s\n' 'NAME only: process command-line arguments are intentionally excluded.'
if ! ps -A -o PID,PPID,UID,RSS,NAME 2>/dev/null; then
  ps -e -o pid,ppid,uid,rss,comm 2>/dev/null || true
fi
section 'Selected process rollups (permission-dependent)'
# No smaps walk across all processes. Read at most 12 relevant rollups.
(ps -A -o PID,NAME 2>/dev/null || ps -e -o pid,comm 2>/dev/null) | {
  count=0
  while read -r pid name; do
    case "$pid" in ''|*[!0-9]*) continue ;; esac
    case "$name" in
      "$PACKAGE"|"$PACKAGE":*|java|proot|libproot.so|dex2oat|dex2oat64|*sandboxed_process*|*webview*) ;;
      *) continue ;;
    esac
    printf '\npid=%s name=%s\n' "$pid" "$name"
    if [ -r "/proc/$pid/status" ]; then
      grep -E '^(Name|PPid|Uid|VmRSS|VmHWM|RssAnon|RssFile|VmSwap|Threads):' "/proc/$pid/status" 2>/dev/null || true
    fi
    if [ -r "/proc/$pid/smaps_rollup" ]; then
      grep -E '^(Rss|Pss|Pss_Anon|Pss_File|Private_Clean|Private_Dirty|Swap|SwapPss):' "/proc/$pid/smaps_rollup" 2>/dev/null || true
    else
      printf '%s\n' 'smaps_rollup unavailable (permission or process exited)'
    fi
    count=$((count + 1))
    [ "$count" -ge 12 ] && break
  done
}
if [ "$PROC_ONLY" = 0 ] && command -v dumpsys >/dev/null 2>&1; then
  section 'App version'
  dumpsys -t 8 package "$PACKAGE" 2>/dev/null | grep -E 'versionCode=|versionName=' || true
  section 'App PSS and heap details (kB)'
  dumpsys -t 8 meminfo "$PACKAGE" 2>&1 || true
  section 'Thermal status (sensor labels must accompany temperatures)'
  dumpsys -t 8 thermalservice 2>&1 || true
else
  section 'Visibility limitation'
  printf '%s\n' 'Guest-only snapshot: Android hidepid can conceal other UIDs and WebView renderers.' \
    'Missing processes or PSS data do not imply zero usage; use adb/authorized host shell.'
fi
