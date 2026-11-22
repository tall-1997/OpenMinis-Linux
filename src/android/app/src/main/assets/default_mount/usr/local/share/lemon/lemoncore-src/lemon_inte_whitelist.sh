#!/system/bin/sh
# ============================================================
# lemon_inte_whitelist.sh v1.1 · 20261004 小柠檬
# 自研内核模块 → ACE 看门狗 inte.ko 白名单收编
# 原理: 伪装守护进程身份(comm=oplus_kohashpro + euid=1000)
#       经 /proc/inte_ko 写通道提交 {名字.ko, 文件SHA256}
#       逻辑经 QEMU 真内核 R1-R9 十五断言实证(#334)
# 防空转: 固定目录枚举(有界)、单条 timeout 10s、循环上限 16、
#         无嵌套循环、无无界追加、wl 文件固定 76B 覆盖写
# 用法: lemon_inte_whitelist.sh [ko路径...]
#       不带参数 = 收编 /data/adb/lemonguard6/*.ko(默认名单)
# ============================================================
HELPER=/data/adb/lemon_core/inte_wl
PY=/data/data/com.termux/files/usr/bin/python3
export LD_LIBRARY_PATH=/data/data/com.termux/files/usr/lib
WL=/data/local/tmp/inte_wl.bin
PROC=/proc/inte_ko
LOGTAG="[inte_whitelist]"
LIMIT=16

if [ ! -x "$HELPER" ]; then echo "$LOGTAG 缺 helper: $HELPER"; exit 1; fi
[ -e "$PROC" ] || { echo "$LOGTAG 本机无 /proc/inte_ko(无 ACE 看门狗),无事可做"; exit 0; }
[ -x "$PY" ] || { echo "$LOGTAG 缺 python3: $PY"; exit 1; }

if [ $# -gt 0 ]; then
  KOS="$@"
else
  KOS=$(ls /data/adb/lemonguard6/*.ko 2>/dev/null)
fi

cnt=0; ok=0
for ko in $KOS; do
  cnt=$((cnt+1)); [ $cnt -gt $LIMIT ] && break
  [ -f "$ko" ] || continue
  name=$(modinfo "$ko" 2>/dev/null | awk '/^name:/{print $2}')
  if [ -z "$name" ]; then echo "$LOGTAG 跳过(无 modinfo name): $ko"; continue; fi
  hex=$(sha256sum "$ko" 2>/dev/null | awk '{print $1}')
  if [ -z "$hex" ]; then echo "$LOGTAG 跳过(算不了哈希): $ko"; continue; fi
  timeout 10 "$PY" - "$name" "$hex" "$WL" <<'PYEOF' >/dev/null 2>&1 || { echo "$LOGTAG 构造失败: $name"; continue; }
import sys
name, hexs, out = sys.argv[1], sys.argv[2], sys.argv[3]
key = (name + ".ko").replace("-", "_")
if len(key) >= 40:
    sys.exit(2)
buf = bytearray(76)
buf[0:4] = (1).to_bytes(4, "little")
buf[4:4+len(key)] = key.encode()
buf[44:76] = bytes.fromhex(hexs)
open(out, "wb").write(bytes(buf))
sys.exit(0)
PYEOF
  timeout 10 "$HELPER" "$WL" "$PROC" >/dev/null 2>&1
  rc=$?
  if [ $rc -eq 0 ]; then
    ok=$((ok+1)); echo "$LOGTAG 收编成功: $name.ko"
  else
    echo "$LOGTAG 收编异常 rc=$rc: $name.ko"
  fi
done
echo "$LOGTAG 完成: 收编 $ok/$cnt"
[ $ok -gt 0 ] && exit 0
exit 1
