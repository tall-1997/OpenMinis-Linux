#!/system/bin/sh
# ============================================================
# LemonCore 控制脚本 v1.1 · 20260927 · 小柠檬本体
# 配置驱动：/data/adb/lemon_core/conf/lemoncore.conf
#   PORT=<本内核端口>  UPSTREAM_PORT=<Java 内核端口>  UPSTREAM=<0|1>
# 用法: lemoncore.sh start|stop|status|restart|test|conf [端口] [上游端口] [上游开关]
# ============================================================
C=/data/adb/lemon_core
BIN=$C/lemoncore
LOG=$C/lemoncore.log
CONF=$C/conf/lemoncore.conf
export PATH=/data/adb/magisk:/system/bin:$PATH

load_conf(){
    PORT=8791; UPSTREAM_PORT=8788; UPSTREAM=1
    [ -f "$CONF" ] && . "$CONF"
    PORT=${PORT:-8791}; UPSTREAM_PORT=${UPSTREAM_PORT:-8788}; UPSTREAM=${UPSTREAM:-1}
}
save_conf(){
    mkdir -p "$C/conf"
    printf 'PORT=%s\nUPSTREAM_PORT=%s\nUPSTREAM=%s\n' "$1" "$2" "$3" > "$CONF"
}

pid_by_port(){ ss -ltnp 2>/dev/null | grep ":$1 " | sed -n 's/.*pid=\([0-9]*\).*/\1/p' | head -1; }

do_start(){
    load_conf
    if [ -n "$(pid_by_port $PORT)" ]; then
        echo "[skip] 端口 $PORT 已被 pid=$(pid_by_port $PORT) 占用"; return 0
    fi
    [ -x "$BIN" ] || { echo "[FAIL] 找不到可执行文件 $BIN"; return 1; }
    nohup "$BIN" "$PORT" "$UPSTREAM_PORT" "$UPSTREAM" >> "$LOG" 2>&1 &
    sleep 1
    if [ -n "$(pid_by_port $PORT)" ]; then
        echo "[OK] lemoncore 已启动 port=$PORT upstream=$UPSTREAM_PORT:$UPSTREAM pid=$(pid_by_port $PORT)"
    else
        echo "[FAIL] 启动失败，日志尾部:"; tail -5 "$LOG"; return 1
    fi
}

do_stop(){
    load_conf
    p=$(pid_by_port $PORT)
    if [ -z "$p" ]; then echo "[skip] 端口 $PORT 无进程"; return 0; fi
    kill "$p" 2>/dev/null; sleep 1; kill -9 "$p" 2>/dev/null
    echo "[OK] 已停止 pid=$p"
}

do_status(){
    load_conf
    p=$(pid_by_port $PORT)
    echo "配置: PORT=$PORT UPSTREAM_PORT=$UPSTREAM_PORT UPSTREAM=$UPSTREAM"
    echo "pid=${p:-无}"
    [ -n "$p" ] && curl -s -m 5 "http://127.0.0.1:$PORT/healthz"
}

do_test(){
    load_conf
    B="http://127.0.0.1:$PORT/mcp"
    echo "== LemonCore 冒烟测试 (port=$PORT) =="
    pass=0; fail=0
    t(){ echo "$2" > /data/local/tmp/lc_act.txt; if grep -qF -- "$3" /data/local/tmp/lc_act.txt; then echo "[PASS] $1"; pass=$((pass+1)); else echo "[FAIL] $1"; head -c 150 /data/local/tmp/lc_act.txt; echo; fail=$((fail+1)); fi; }
    t "healthz" "$(curl -s -m 10 http://127.0.0.1:$PORT/healthz)" '"status":"ok"'
    t "initialize" "$(curl -s -m 20 -X POST $B -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}')" '"protocolVersion":"2025-06-18"'
    t "tools/list" "$(curl -s -m 60 -X POST $B -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","id":2,"method":"tools/list"}')" '"local":'
    t "本地工具" "$(curl -s -m 60 -X POST $B -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"khook_status","arguments":{}}}')" 'khook'
    t "上游桥" "$(curl -s -m 60 -X POST $B -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"meta_version","arguments":{}}}')" 'lemon-mcp-root'
    t "ping" "$(curl -s -m 20 -X POST $B -H 'Content-Type: application/json' -d '{"jsonrpc":"2.0","id":5,"method":"ping"}')" '"result":{}'
    echo "== 结果: PASS=$pass FAIL=$fail =="
    [ "$fail" -eq 0 ] && echo "LemonCore: 全绿 OK" || echo "LemonCore: 有失败项"
}

case "$1" in
    start)   do_start ;;
    stop)    do_stop ;;
    status)  do_status ;;
    restart) do_stop; sleep 1; do_start ;;
    test)    do_test ;;
    conf)
        load_conf
        if [ -n "$2" ]; then save_conf "$2" "${3:-$UPSTREAM_PORT}" "${4:-$UPSTREAM}"; echo "已写入 $CONF"; fi
        . "$CONF" 2>/dev/null
        echo "PORT=$PORT UPSTREAM_PORT=$UPSTREAM_PORT UPSTREAM=$UPSTREAM" ;;
    *) echo "用法: lemoncore.sh start|stop|status|restart|test|conf [端口] [上游端口] [上游开关]" ;;
esac