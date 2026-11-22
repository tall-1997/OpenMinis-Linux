#!/system/bin/sh
# ============================================================
#  小柠檬 fslock 管理器 · v1.1 · 20260928 · 本体
#  作用：给「只读资产」上 VFS 内核级 immutable —— 连 root 都删不掉/改不掉
#  依赖：/data/adb/lemonbpf/lemonbpf  (子命令为 on/off/status)
#  清单：/data/adb/lemon_core/fslock_targets.txt
#  v1.1 修正：子命令映射 lock→on / unlock→off（v1.0 直接透传 lock，非法子命令导致全失败）
#  用法：
#    sh lemon_fslock.sh lock|unlock|status|verify [路径]
# ============================================================
LB=/data/adb/lemonbpf/lemonbpf
LIST=/data/adb/lemon_core/fslock_targets.txt
CMD="$1"; TARGET="$2"
[ -x "$LB" ] || { echo "lemonbpf 不可用"; exit 1; }

# ★ v1.1：子命令映射
one(){
  case "$1" in
    lock)   $LB fslock on     "$2" ;;
    unlock) $LB fslock off    "$2" ;;
    status) $LB fslock status "$2" ;;
    *)      $LB fslock "$1"   "$2" ;;
  esac
}

case "$CMD" in
  lock|unlock)
    if [ -n "$TARGET" ]; then one "$CMD" "$TARGET"; exit $?; fi
    [ -f "$LIST" ] || { echo "清单不存在: $LIST"; exit 1; }
    N=0; OK=0
    while read -r p; do
      [ -z "$p" ] && continue
      N=$((N+1))
      if one "$CMD" "$p" >/dev/null 2>&1; then OK=$((OK+1)); else echo "  [WARN] $CMD 失败: $p"; fi
    done < "$LIST"
    echo "$CMD 完成：$OK/$N"
    ;;
  status)
    if [ -n "$TARGET" ]; then one status "$TARGET"; exit $?; fi
    L=0; N=0
    while read -r p; do
      [ -z "$p" ] && continue
      N=$((N+1))
      S=$($LB fslock status "$p" 2>/dev/null | grep '不可变')
      case "$S" in *已锁定*) L=$((L+1)); echo "[锁定] $p";; *) echo "[未锁] $p";; esac
    done < "$LIST"
    echo "---- 已锁定 $L/$N ----"
    ;;
  verify)
    P=0; F=0
    while read -r p; do
      [ -z "$p" ] && continue
      if touch "$p" 2>/dev/null; then F=$((F+1)); echo "[FAIL] 仍可写: $p"; else P=$((P+1)); fi
    done < "$LIST"
    echo "---- 写保护生效 $P / 未生效 $F ----"
    ;;
  *) echo "用法: sh lemon_fslock.sh lock|unlock|status|verify [路径]"; exit 1;;
esac
