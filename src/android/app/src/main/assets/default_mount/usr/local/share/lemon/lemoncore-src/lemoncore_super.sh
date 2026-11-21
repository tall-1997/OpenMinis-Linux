#!/system/bin/sh

# ---------- [261112 防空空转·#249 新变体] ----------
# 裸 tr 读 /proc/$PID/cmdline：目标 exec 过渡态时 cmdline 返回 0 字节空读，
# tr 把空读当「稍后重读」→ 满核空转（实锤 261111 19:38：103% CPU 连烧 10h43m，
# SoC 99.6°C，守护主循环被拖死一整夜）。一律经 pcmdline() 读，timeout 3s 兜底。
RTO=""
[ -x /system/bin/timeout ] && RTO="/system/bin/timeout"
[ -z "$RTO" ] && [ -x /data/adb/magisk/busybox ] && RTO="/data/adb/magisk/busybox timeout"
pcmdline() {
  [ -n "$1" ] || return 0
  [ -d "/proc/$1" ] || return 0
  if [ -n "$RTO" ]; then
    "$RTO" 3 tr '\0' ' ' <"/proc/$1/cmdline" 2>/dev/null
  else
    tr '\0' ' ' <"/proc/$1/cmdline" 2>/dev/null
  fi
}
# ============================================================
#  LemonCore 守护 v2.4 · 20261003 · 小柠檬本体
#  职责：8788 原生内核（lemoncore）的「晚启动 + 有界守护 + 永不休眠」
# ------------------------------------------------------------
#  v2.5 修正（20261003：新增秒级夺回 RECLAIM —— 8788 被外人占住时 5s 内夺回并重启自家 core）：
#   详见下方 reclaim_port()；侧写系统进程黑名单保护，绝不误杀 init/system_server/netd 等。
#
#  v2.4 修正（20261003：8788 今晨 08:44 后失联 4.5 小时无人恢复）：
#   BUG-V 熔断即退出：FAIL>=5 或 LAUNCHED>=8 时 exit 0 → 守护彻底死透，
#         同一次开机内再没人拉起 8788（本次长时间失联的直接放大器）
#         → 修法：改为「慢速守候」soft-circuit，永不退出，冷却后继续守护
#   BUG-W 端口被外人占住即永久 yield：只退让、不夺回；对方不放手则 8788 永远回不来
#         → 修法：RECLAIM 夺回开关（默认 0 安全；=1 时夺回并记录占位者）
#   BUG-X holds_ours 依赖 owner_pid，一旦扫描失败就误判 non-lemon
#         → 修法：owner_pid 失败时以「服务身份探活」兜底（initialize → lemon-core）
# ------------------------------------------------------------
#  v2.1 修正（对照 8 号库 #229 检测结果）：
#   BUG-A holds_ours 只看 .core.pid（PID 文件过期/竞态）
#         → 误报 "port served by non-lemon"（实际是自家 lemoncore）
#         → 修法：listening 时反查 /proc/net/tcp 的 inode → 找到属主 PID → 验身
#   BUG-B 端口属主多实例：历史遗留让 8788 有两个 lemoncore（10859 + 13691）
#         → 修法：daemon 启动时 dedup（保留最早一个，其余验身后 kill）
#   BUG-C PID 文件竞态：start_core 写 .core.pid 时无锁，两个 super 会互相覆盖
#         → 修法：PID 文件只作提示，真实身份永远以端口属主反查为准
# ------------------------------------------------------------
#  子命令：daemon | once | status | stop | pause | resume | reset | uninstall
# ============================================================

export PATH=/data/adb/magisk:/system/bin:/system/xbin:/apex/com.android.art/bin:$PATH

C=/data/adb/lemon_core
BIN=$C/lemoncore
CONF=$C/conf/lemoncore.conf
LOG=$C/super.log
LOCK=$C/.super.lock
PIDF=$C/.core.pid
PAUSE=$C/.paused
CIRCUIT=$C/.circuit_open

LOGMAX=262144
SETTLE=90
INTERVAL=10
GRACE=6
MAXFAIL=5
WINMAX=8
OOMADJ=-600
SLOWPROBE=600
RECLAIM=1
RECLAIM_INTERVAL=5

PORT=8788; UP=8790; UPSTREAM=1
[ -f "$CONF" ] && . "$CONF"
PORT=${PORT:-8788}
UP=${UPSTREAM_PORT:-8790}
UPSTREAM=${UPSTREAM:-1}
[ -n "$LEMON_SETTLE" ] && SETTLE=$LEMON_SETTLE
[ -n "$LEMON_SLOWPROBE" ] && SLOWPROBE=$LEMON_SLOWPROBE
[ -n "$LEMON_RECLAIM" ] && RECLAIM=$LEMON_RECLAIM
[ -n "$LEMON_RECLAIM_INT" ] && RECLAIM_INTERVAL=$LEMON_RECLAIM_INT

log() {
  if [ -f "$LOG" ]; then
    SZ=$(wc -c <"$LOG" 2>/dev/null)
    case "$SZ" in ''|*[!0-9]*) SZ=0;; esac
    [ "$SZ" -gt "$LOGMAX" ] && mv -f "$LOG" "$LOG.1" 2>/dev/null
  fi
  echo "[$(date '+%F %T')] $*" >>"$LOG"
}

alive() { [ -n "$1" ] && [ -d "/proc/$1" ] && kill -0 "$1" 2>/dev/null; }

# 验身：白名单 comm + cmdline 含 lemon
ours() {
  P=$1
  [ -n "$P" ] || return 1
  [ -d "/proc/$P" ] || return 1
  CN=$(cat "/proc/$P/comm" 2>/dev/null)
  case "$CN" in
    lemoncore|lemoncore_super|lemon-kernel|lemon-svc|lemoncore_sys) ;;
    *) return 1;;
  esac
  CL=$(pcmdline "$P")
  case "$CL" in *lemon*) return 0;; esac
  return 1
}

hexport() {
  case "$1" in
    8788) echo 2254;;
    8789) echo 2255;;
    8790) echo 2256;;
    8791) echo 2257;;
    *) printf '%04X' "$1" 2>/dev/null || echo 0000;;
  esac
}

# 判监听
listening() {
  H=$(hexport "$PORT")
  [ -n "$H" ] || return 1
  grep -Eq ":$H [0-9A-F:]+ 0A" /proc/net/tcp 2>/dev/null && return 0
  grep -Eq ":$H [0-9A-F:]+ 0A" /proc/net/tcp6 2>/dev/null && return 0
  return 1
}

# ★ BUG-A 修法：反查监听端口的属主 PID（inode → /proc/*/fd）
owner_pid() {
  H=$(hexport "$PORT")
  [ -n "$H" ] || return 1
  # 快路径：busybox netstat 直接给 PID（仅 1 次 fork）
  P=$(netstat -tlnp 2>/dev/null | awk -v h=":$PORT" '$4 ~ h && $6=="LISTEN" {n=split($7,a,"/"); print a[1]; exit}')
  if [ -n "$P" ]; then echo "$P"; return 0; fi
  # 回退：inode → 一次性全量 ls（避免逐进程逐 fd fork 造成空转）
  INO=$(awk -v h=":$H" '$2 ~ h && $4=="0A" {print $10}' /proc/net/tcp /proc/net/tcp6 2>/dev/null | head -1)
  [ -n "$INO" ] || return 1
  P=$(ls -l /proc/[0-9]*/fd 2>/dev/null | grep -F "socket:[$INO]" | head -1 | awk '{print $9}' | cut -d/ -f3)
  [ -n "$P" ] && { echo "$P"; return 0; }
  return 1
}

# ★ v2.4 新增：服务身份探活（owner_pid 扫描失败时的兜底判据）
probe_service() {
  PC=""
  for c in /system/bin/curl /system/xbin/curl; do
    [ -x "$c" ] && { PC="$c"; break; }
  done
  [ -n "$PC" ] || return 2
  R=$("$PC" -s -m 4 -X POST "http://127.0.0.1:$PORT" -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}' 2>/dev/null)
  case "$R" in *lemon-core*) return 0;; esac
  return 1
}

# ★ v2.5 新增：秒级夺回 —— 杀掉占用目标端口的「非自家」进程
#   保护：① 绝不动系统关键进程（init/system_server/netd/...）
#         ② 杀前二次验身（防竞态误杀自家）
#         ③ 有界（kill → 1s → kill -9 → 1s → 复查）
reclaim_port() {
  OP=$(owner_pid)
  [ -n "$OP" ] || { log "RECLAIM: owner unknown -> skip"; return 1; }
  OC=$(cat "/proc/$OP/comm" 2>/dev/null)
  case "$OC" in
    init|system_server|zygote|zygote64|netd|surfaceflinger|servicemanager|vold|logd|lmkd|keystore2|statsd|android.hardware.*)
      log "RECLAIM: refuse (critical comm=$OC pid=$OP)"; return 1;;
  esac
  [ "$OP" = "1" ] && { log "RECLAIM: refuse pid 1"; return 1; }
  ours "$OP" && { log "RECLAIM: pid=$OP is ours -> skip"; return 1; }
  OPD="$(pcmdline "$OP")"
  log "RECLAIM: take back $PORT <- kill pid=$OP comm=$OC [$OPD]"
  kill "$OP" 2>/dev/null
  sleep 1
  [ -d "/proc/$OP" ] && kill -9 "$OP" 2>/dev/null
  sleep 1
  if [ -d "/proc/$OP" ]; then log "RECLAIM: pid=$OP survived -> give up this round"; return 1; fi
  log "RECLAIM: pid=$OP removed"
  return 0
}

# ★ BUG-A 修法：listening 时以端口属主为准（不再只信 PID 文件）
holds_ours() {
  if listening; then
    P=$(owner_pid)
    if [ -n "$P" ]; then ours "$P"; return $?; fi
    # ★ v2.4/BUG-X：owner_pid 扫描失败 → 服务身份探活兜底
    probe_service && return 0
    return 1
  fi
  P=$(cat "$PIDF" 2>/dev/null)
  ours "$P"
}

# ★ BUG-B 修法：全部自家实例列表（用于去重）
list_ours() {
  # ★ BUG-N 修法：lemoncore 是 fork-per-connection 模型，处理连接的子进程
  #   ppid = 监听者 → 旧实现把它也当“重复实例”，dedup 就会去杀它
  #   → 反把正在办的连接杀断（客户端表现为 not connected）。
  #   修法：先收集，再过滤掉“父进程也是 lemoncore”的 fork 子进程。
  RAW=""
  for d in /proc/[0-9]*; do
    [ -r "$d/comm" ] || continue
    read -r CN <"$d/comm" 2>/dev/null
    [ "$CN" = "lemoncore" ] || continue
    case "$(pcmdline "${d#/proc/}")" in
      *lemoncore*) RAW="$RAW ${d#/proc/}";;
    esac
  done
  for p in $RAW; do
    PP=$(awk '{print $4}' "/proc/$p/stat" 2>/dev/null)
    SKIP=0
    for q in $RAW; do
      if [ "$q" = "$PP" ]; then SKIP=1; break; fi
    done
    [ "$SKIP" = "1" ] && continue
    echo "$p"
  done
}

count_ours() { list_ours | wc -l | tr -d ' '; }

dedup() {
  N=$(count_ours)
  [ "$N" -le 1 ] && return 0
  # 优先保留「真正占用监听端口」的那个实例；检索不到才退化为保留最小 pid
  KEEP=""
  if listening; then KEEP=$(owner_pid); fi
  [ -z "$KEEP" ] && KEEP=$(list_ours | sort -n | head -1)
  log "dedup: instances=$N, keep pid=$KEEP"
  for p in $(list_ours); do
    [ "$p" = "$KEEP" ] && continue
    log "dedup: kill extra pid=$p"
    kill "$p" 2>/dev/null
  done
  sleep 2
  for p in $(list_ours); do
    [ "$p" = "$KEEP" ] && continue
    kill -9 "$p" 2>/dev/null
  done
  # 把幸存者的 pid 写回 PID 文件（只作提示）
  [ -n "$KEEP" ] && echo "$KEEP" >"$PIDF"
}

acquire() {
  ME0=$(basename "$0" 2>/dev/null)
  mkdir -p "$C" 2>/dev/null
  if mkdir "$LOCK" 2>/dev/null; then
    echo $$ >"$LOCK/pid"
    # ★ BUG-T 修法：写入后复核（mkdir 与写 pid 之间有 TOCTOU 窗口，并发者会看到空 pid 抢锁）
    LP2=$(cat "$LOCK/pid" 2>/dev/null)
    if [ "$LP2" != "$$" ]; then
      log "lock stolen by pid=$LP2 → yield"
      return 1
    fi
    return 0
  fi
  # ★ BUG-T2 修法：锁目录存在但 pid 还没写入（并发 mkdir→写pid 窗口）——短等重读，别抢
  LP=$(cat "$LOCK/pid" 2>/dev/null)
  j=0
  while [ -z "$LP" ] && [ $j -lt 3 ]; do sleep 1; LP=$(cat "$LOCK/pid" 2>/dev/null); j=$((j+1)); done
  if alive "$LP"; then
    if ours "$LP"; then return 1; fi
    # ★ BUG-I 修法：守护本体是 `sh .../lemoncore_super.sh daemon`，comm="sh"，
    #   白名单认不出 → 会把自己的同类当陌生人抢锁 → 可能出现双 super
    case "$(pcmdline "$LP")" in
      *lemoncore_super.sh*|*"$ME0"*) return 1;;
    esac
    log "lock pid=$LP alive but not ours → reclaim"
  else
    log "stale lock (pid=$LP) → reclaim"
  fi
  rm -rf "$LOCK"
  mkdir "$LOCK" 2>/dev/null || return 1
  echo $$ >"$LOCK/pid"
  # ★ BUG-T 修法：同上，写入后复核
  LP2=$(cat "$LOCK/pid" 2>/dev/null)
  if [ "$LP2" != "$$" ]; then
    log "lock stolen by pid=$LP2 → yield"
    return 1
  fi
  return 0
}
release() {
  # ★ BUG-R 修法：只清自己持有的锁（避免误删他人锁）
  LP=$(cat "$LOCK/pid" 2>/dev/null)
  [ "$LP" = "$$" ] && rm -rf "$LOCK"
  return 0
}

wait_ready() {
  I=0
  while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ] && [ "$I" -lt 90 ]; do
    sleep 2; I=$((I+1))
  done
  I=0
  while [ ! -w "$C" ] && [ "$I" -lt 30 ]; do sleep 2; I=$((I+1)); done
  log "boot_completed → settle ${SETTLE}s before touching $PORT"
  I=0
  while [ "$I" -lt "$SETTLE" ]; do
    [ -f "$PAUSE" ] && { log "paused during settle → exit"; exit 0; }
    sleep 5; I=$((I+5))
  done
}

start_core() {
  if [ ! -f "$BIN" ]; then log "ERR: $BIN missing → skip launch"; return 1; fi
  # ★ BUG-B 修法：启动前去重
  dedup
  cd "$C" 2>/dev/null
  setsid env -u LD_LIBRARY_PATH -u JAVA_HOME "$BIN" "$PORT" "$UP" "$UPSTREAM" >>"$LOG" 2>&1 &
  P=$!
  echo "$P" >"$PIDF"
  log "launch core: pid=$P port=$PORT upstream=$UP on=$UPSTREAM"
  if [ "$OOMADJ" != "0" ]; then
    I=0
    while [ "$I" -lt 5 ]; do
      [ -d "/proc/$P" ] && echo "$OOMADJ" >"/proc/$P/oom_score_adj" 2>/dev/null
      sleep 1; I=$((I+1))
    done
  fi
  sleep "$GRACE"
  if listening; then log "core listening ✓ (port=$PORT)"; else log "core not listening yet (will re-check)"; fi
  return 0
}

stop_ours() {
  P=$(cat "$PIDF" 2>/dev/null)
  if ours "$P"; then
    log "stop own core pid=$P"
    kill "$P" 2>/dev/null
    I=0
    while [ "$I" -lt 5 ] && [ -d "/proc/$P" ]; do sleep 1; I=$((I+1)); done
    if [ -d "/proc/$P" ]; then
      kill -9 "$P" 2>/dev/null
      log "force-kill own core pid=$P"
    fi
  fi
  rm -f "$PIDF"
}

daemon() {
  if ! acquire; then log "another supervisor is running → exit"; exit 0; fi
  # ★ BUG-Q 修法：信号必须“清锁后退出”！
  #   原 `trap 'release' EXIT INT TERM HUP` 收到 SIGTERM 后只清锁、不退出
  #   → kill / timeout / 系统关机流程都停不掉守护（实测 timeout 被卡死）。
  on_stop() { release; exit 0; }
  trap on_stop INT TERM HUP
  trap release EXIT
  # ★ BUG-L 修法：.paused 不跨重启残留（内容=boot_id；写 keep 才永久）
  if [ -f "$PAUSE" ]; then
    CUR=$(cat /proc/sys/kernel/random/boot_id 2>/dev/null || echo unknown)
    OLD=$(cat "$PAUSE" 2>/dev/null)
    if [ "$OLD" != "keep" ] && [ "$OLD" != "$CUR" ]; then
      log "clear stale pause (previous boot) → resume"
      rm -f "$PAUSE"
    else
      log "paused → exit"; exit 0
    fi
  fi
  # ★ BUG-F 同类防护：熔断状态不跨重启残留
  if [ -f "$CIRCUIT" ]; then
    log "clear stale circuit (previous run): $(cat "$CIRCUIT" 2>/dev/null)"
    rm -f "$CIRCUIT"
  fi
  log "=== super v2.5 start (pid=$$ port=$PORT settle=${SETTLE}s interval=${INTERVAL}s) ==="
  wait_ready
  # ★ BUG-B 修法：启动时去重
  dedup
  FAIL=0; LAUNCHED=0
  while :; do
    if [ -f "$PAUSE" ]; then log "paused → exit"; exit 0; fi
    # ★ v2.4/BUG-V：熔断不再是退出理由（清标记后继续守护，绝不放弃）
    if [ -f "$CIRCUIT" ]; then log "stale circuit flag → cleared, keep supervising"; rm -f "$CIRCUIT"; fi
    # ★ BUG-R 修法：锁属主自检 —— 锁丢失/被他人持有时：能拿回就拿，拿不回就让位退出
    # （杜绝“手工重启误杀陈旧PID + 删锁”后出现多守护并存）
    LP=$(cat "$LOCK/pid" 2>/dev/null)
    if [ "$LP" != "$$" ]; then
      # ★ BUG-U 修法：空 pid / 死者持锁 = 陈旧锁，应当接管！
      if [ -n "$LP" ] && alive "$LP"; then
        log "lock held by $LP → yield, exit"
        exit 0
      fi
      rm -rf "$LOCK"
      if mkdir "$LOCK" 2>/dev/null; then
        echo $$ >"$LOCK/pid"
        log "lock re-acquired (prev=${LP:-empty})"
      else
        log "lock grab failed → yield, exit"
        exit 0
      fi
    fi
    # ★ BUG-B 修法：运行中也去重
    dedup
    if listening; then
      SLEEPN="$INTERVAL"
      if holds_ours; then
        :
      else
        # ★ v2.5：端口被非自家占住 → 秒级夺回，夺回后立刻拉起自家 core
        SLEEPN="$RECLAIM_INTERVAL"
        OP=$(owner_pid)
        if [ "$RECLAIM" = "1" ]; then
          log "port $PORT held by NON-ours pid=$OP -> RECLAIM (fast, every ${RECLAIM_INTERVAL}s)"
          if reclaim_port; then
            sleep 1
            if ! listening; then
              log "RECLAIM: $PORT freed -> relaunch our core now"
              LAUNCHED=$((LAUNCHED+1))
              start_core
            fi
          fi
        else
          log "port $PORT held by NON-ours pid=$OP -> yield (RECLAIM=0)"
        fi
      fi
      FAIL=0
      sleep "$SLEEPN"
      continue
    fi
    if holds_ours; then
      sleep "$GRACE"
      if ! listening; then
        log "own core alive but not listening → stop it, then relaunch"
        stop_ours
      fi
    fi
    if listening; then FAIL=0; sleep "$INTERVAL"; continue; fi
    LAUNCHED=$((LAUNCHED+1))
    start_core
    FAIL=$((FAIL+1))
    case "$FAIL" in
      1) B=5;;
      2) B=15;;
      3) B=60;;
      4) B=180;;
      *) B=300;;
    esac
    log "attempt #$LAUNCHED pending → backoff ${B}s (fail=$FAIL/$MAXFAIL)"
    sleep "$B"
    # ★ v2.4/BUG-V：soft-circuit —— 绝不退出！冷却后清零计数继续守护
    if [ "$FAIL" -ge "$MAXFAIL" ]; then
      log "SOFT-CIRCUIT: $FAIL failures → cool down ${SLOWPROBE}s then keep supervising"
      FAIL=0; LAUNCHED=0
      sleep "$SLOWPROBE"
      continue
    fi
    if [ "$LAUNCHED" -ge "$WINMAX" ]; then
      log "SOFT-CIRCUIT: $LAUNCHED launches this boot → cool down ${SLOWPROBE}s then keep supervising"
      LAUNCHED=0; FAIL=0
      sleep "$SLOWPROBE"
      continue
    fi
  done
}

once() {
  log "once: single launch requested"
  if listening; then log "once: already listening on $PORT → nothing to do"; return 0; fi
  if holds_ours; then
    sleep "$GRACE"
    if listening; then return 0; fi
    stop_ours
  fi
  start_core
}

status() {
  echo "── LemonCore super v2.5 ──────────────────"
  echo "bin        : $BIN $([ -f "$BIN" ] && echo '(ok)' || echo '(MISSING)')"
  echo "port       : $PORT  (upstream $UP on=$UPSTREAM)"
  if listening; then echo "listening  : YES"; else echo "listening  : NO"; fi
  if listening; then
    if holds_ours; then echo "identity   : ours (lemon-core)"; else echo "identity   : NON-ours / unknown"; fi
  fi
  echo "instances  : $(count_ours) (=1 正常)"
  for p in $(list_ours); do echo "  pid=$p ppid=$(awk '{print $4}' /proc/$p/stat 2>/dev/null)"; done
  echo "core pid   : $(cat "$PIDF" 2>/dev/null) $(holds_ours && echo '(ours)' || echo '(none/unknown)')"
  echo "super lock : $([ -f "$LOCK/pid" ] && cat "$LOCK/pid" || echo none)"
  echo "paused     : $([ -f "$PAUSE" ] && echo YES || echo no)"
  echo "circuit    : $([ -f "$CIRCUIT" ] && cat "$CIRCUIT" || echo closed)"
  echo "log size   : $(wc -c <"$LOG" 2>/dev/null || echo 0) bytes"
  echo "──────────────────────────────────────────"
}

case "$1" in
  daemon) daemon;;
  once) once;;
  status|st) status;;
  stop) stop_ours; log "manual stop"; status;;
  dedup) dedup; status;;
  pause) cat /proc/sys/kernel/random/boot_id >"$PAUSE" 2>/dev/null || echo keep >"$PAUSE"; log "paused by user (this boot; keep=永久)"; echo "paused";;
  pausekeep) echo keep >"$PAUSE"; log "paused permanently by user"; echo "paused-forever";;
  resume) rm -f "$PAUSE" "$CIRCUIT"; log "resumed by user"; echo "resumed";;
  reset) rm -f "$CIRCUIT" "$PIDF"; rm -rf "$LOCK"; log "reset"; echo "reset";;
  uninstall|-u)
    stop_ours
    rm -f "$PAUSE" "$CIRCUIT" "$PIDF" "$LOCK/pid"
    rmdir "$LOCK" 2>/dev/null
    echo "已停手并清标记。彻底移除："
    echo "  rm -f /data/adb/service.d/lemon_lemoncore_boot.sh"
    echo "  rm -f /data/adb/lemon_core/lemoncore_super.sh"
    ;;
  *)
    echo "用法: sh lemoncore_super.sh {daemon|once|status|stop|dedup|pause|resume|reset|uninstall}"
    ;;
esac
