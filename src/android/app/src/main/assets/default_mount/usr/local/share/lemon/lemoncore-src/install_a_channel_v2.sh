#!/system/bin/sh
# ============================================================
#  A 通道 + 三套守护 v2 刷入器 · 20260927 · 小柠檬本体
#  顺序：工具体检 → 备份旧链 → 装三套守护 → 立即拉起 → 校验 → 报告
#  可重复执行（幂等）
# ============================================================
C=/data/adb/lemon_core
S=/data/adb/lemon_svc
K=/data/adb/lemon_kernel
SD=/data/adb/service.d
BK=$C/backup_pre_super_v2_20260927
mkdir -p "$BK"

echo "=== 0) 工具体检（判活依赖项，缺一即隐患）==========="
for t in grep awk wc tr cat date mkdir sleep kill mv rm cp chmod getprop service tail head expr; do
  if command -v "$t" >/dev/null 2>&1; then echo "  ok      $t"; else echo "  MISSING $t"; fi
done
for t in ss pidof curl pgrep netstat; do
  if command -v "$t" >/dev/null 2>&1; then echo "  ok      $t"; else echo "  MISSING $t  ← 老守护依赖它，必空转"; fi
done

echo "=== 1) 备份旧链 ==============================="
for f in lemon_lemoncore_boot.sh lemon_svc_boot.sh lemon_kernel_guard.sh; do
  if [ -f "$SD/$f" ]; then cp -f "$SD/$f" "$BK/$f.pre_v2"; echo "  bak → $f.pre_v2"; fi
done
md5sum "$BK"/*.pre_v2 2>/dev/null

echo "=== 2) 安装 8788 LemonCore 守护 v2 ============"
cp -f "$C/lemon_lemoncore_boot.v2.sh" "$SD/lemon_lemoncore_boot.sh"
chmod 700 "$SD/lemon_lemoncore_boot.sh" "$C/lemoncore_super.sh"
echo "  ok: service.d/lemon_lemoncore_boot.sh (v2)"

echo "=== 3) 安装 A 通道守护 v2 ===================="
cp -f "$S/lemon_svc_boot_v2.sh" "$SD/lemon_svc_boot.sh"
chmod 700 "$SD/lemon_svc_boot.sh" "$S/lemon_svc_guard.sh"
echo "  ok: service.d/lemon_svc_boot.sh (v2)"

echo "=== 4) 安装 8790 卫星守护 v2 ================"
cp -f "$K/lemon_kernel_guard_v2.sh" "$SD/lemon_kernel_guard.sh"
chmod 700 "$SD/lemon_kernel_guard.sh" "$K/lemon_kernel_guard_v2.sh"
echo "  ok: service.d/lemon_kernel_guard.sh (v2)"

echo "=== 5) 旧守护残留清理（防两份抢同一端口）===="
for f in $SD/lemon_kernel_service.sh; do
  [ -f "$f" ] && mv -f "$f" "$BK/$(basename $f).disabled" && echo "  moved → $(basename $f).disabled"
done

echo "=== 6) 立即拉起 A 通道（不等重启）=========="
sh "$S/lemon_svc_guard.sh" once
sleep 6
sh "$S/lemon_svc_guard.sh" status

echo "=== 7) 后台常驻三守护 ======================="
for g in "$C/lemoncore_super.sh daemon" "$S/lemon_svc_guard.sh daemon" "$K/lemon_kernel_guard_v2.sh daemon"; do
  set -- $g
  SCRIPT=$1
  if [ -f "$SCRIPT" ]; then
    setsid sh "$SCRIPT" daemon >/dev/null 2>&1 &
    echo "  started: $SCRIPT daemon"
  fi
done

echo "=== 8) 校验 ================================"
echo "-- 监听端口 (2254=8788 2256=8790) --"
grep -E ':(2254|2256) [0-9A-F:]+ 0A' /proc/net/tcp /proc/net/tcp6 2>/dev/null | head -4
echo "-- binder lemon --"
service check lemon 2>&1 | head -3
echo "-- 进程 --"
for d in /proc/[0-9]*; do
  n=$(cat "$d/comm" 2>/dev/null)
  case "$n" in lemon*|lemon-*) echo "  $n pid=${d#/proc/}";; esac
done
echo "-- svc_guard.log 尾 --"
tail -n 8 "$S/svc_guard.log" 2>/dev/null
echo "-- super.log 尾 --"
tail -n 6 "$C/super.log" 2>/dev/null
echo "=== done ==================================="
