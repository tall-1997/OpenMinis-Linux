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
#  LemonCore 开机入口 v2.1 (service.d) · 20260928 · 小柠檬本体
# ------------------------------------------------------------
#  铁律：开机期只做一件事 —— 把守护丢到后台，然后立刻退出
#        绝不 while / 绝不 until curl 死等 / 绝不在开机期常驻
#  BUG-P 修法：预检不能只做 kill -0（PID 复用会让无关进程冒充守护
#               → 入口误判“已在跑” → 守护永不启动）。
#              身份必须看 cmdline。
# ============================================================
C=/data/adb/lemon_core
S=$C/lemoncore_super.sh

[ -f "$C/.paused" ] && exit 0
[ -f "$S" ] || exit 0

if [ -f "$C/.super.lock/pid" ]; then
  P=$(cat "$C/.super.lock/pid" 2>/dev/null)
  if [ -n "$P" ] && kill -0 "$P" 2>/dev/null; then
    case "$(pcmdline "$P")" in
      *lemoncore_super.sh*) exit 0;;
    esac
  fi
fi

if command -v setsid >/dev/null 2>&1; then
  setsid sh "$S" daemon >/dev/null 2>&1 &
else
  nohup sh "$S" daemon >/dev/null 2>&1 &
fi
exit 0
