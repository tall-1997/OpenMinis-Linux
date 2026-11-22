#!/system/bin/sh
# ============================================================
# LemonCore 迁移 8788 主链路 · v1.0 · 20260927 · 小柠檬本体
# ------------------------------------------------------------
# 迁移后拓扑：
#   8788  LemonCore（原生 C，主链路，App 直连）
#   8790  Java 内核 lemon-kernel（卫星，供上游桥补 432 个 Java 内建工具）
# 用法: migrate_8788.sh migrate | rollback | verify | status
# ============================================================
MODE="$1"
C=/data/adb/lemon_core
K=${LEMON_K:-/data/adb/lemon_kernel}
SD=${LEMON_SD:-/data/adb/service.d}
BK=${LEMON_BK:-$C/backup_pre_migration_20260927}
LOG=$C/migrate.log
JP=8790
NP=8788
export PATH=/data/adb/magisk:/system/bin:$PATH

log(){ echo "[$(date '+%F %T')] $*" | tee -a "$LOG"; }
pidport(){ ss -ltnp 2>/dev/null | grep ":$1 " | sed -n 's/.*pid=\([0-9]*\).*/\1/p' | head -1; }
wait_port(){ i=0; while [ $i -lt "$2" ]; do [ -n "$(pidport $1)" ] && return 0; sleep 1; i=$((i+1)); done; return 1; }

# 单引号 ':8788' -> 双引号 ":$VAR "（保持行内其余内容不变）
fix_q8788(){ awk -v v="$2" 'index($0,"\x27:8788\x27")>0 {gsub(/\x27:8788\x27/,"\":" v " \""); print; next} {print}' "$1"; }

do_backup(){
    mkdir -p "$BK"
    for f in "$K/run.sh" "$K/lemon_rescue.sh" "$K/mcp_switch.sh" \
             "$SD/lemon_kernel_guard.sh" "$SD/lemon_kernel_service.sh" \
             "$SD/lemon_lemoncore_boot.sh" "$C/lemoncore.sh" "$C/conf/lemoncore.conf"; do
        [ -f "$f" ] && cp -a "$f" "$BK/$(basename $f)"
    done
    {
        echo "备份时间: $(date '+%F %T')"
        echo "Java 内核 pid: $(pidof lemon-kernel)"
        echo "LemonCore pid: $(pidof lemoncore)"
        echo "8788 占用者: $(pidport 8788)"
        echo "8791 占用者: $(pidport 8791)"
    } > "$BK/state.txt"
    log "备份完成 -> $BK ($(ls $BK | wc -l | tr -d ' ') 项)"
}

do_patch(){
    mkdir -p "$K/conf"; echo "$JP" > "$K/conf/java_port"
    log "--- 打补丁：Java 内核端口 8788 -> $JP ---"

    # ---- run.sh ----
    if ! grep -q 'java_port' "$K/run.sh"; then
        awk -v ins='JP=$(cat /data/adb/lemon_kernel/conf/java_port 2>/dev/null || echo 8790)' \
            'index($0,"unset JAVA_HOME")>0 && !d {print; print ins; d=1; next} {print}' \
            "$K/run.sh" > "$K/run.sh.n"
        mv "$K/run.sh.n" "$K/run.sh"
    fi
    fix_q8788 "$K/run.sh" '$JP' > "$K/run.sh.n" && mv "$K/run.sh.n" "$K/run.sh"
    awk 'index($0,"-Dlemon.ports=8788")>0 {gsub(/-Dlemon\.ports=8788/,"-Dlemon.ports=$JP"); print; next}
         index($0,"8788")>0 && !/^\s*#/ {gsub(/8788/,"$JP"); print; next} {print}' \
        "$K/run.sh" > "$K/run.sh.n" && mv "$K/run.sh.n" "$K/run.sh"

    # ---- guard.sh ----
    if ! grep -q 'java_port' "$SD/lemon_kernel_guard.sh"; then
        awk -v ins='JP=$(cat /data/adb/lemon_kernel/conf/java_port 2>/dev/null || echo 8790)' \
            'index($0,"export PATH")>0 && !d {print; print ins; d=1; next} {print}' \
            "$SD/lemon_kernel_guard.sh" > "$SD/lemon_kernel_guard.sh.n"
        mv "$SD/lemon_kernel_guard.sh.n" "$SD/lemon_kernel_guard.sh"
    fi
    awk -v r='    if ! ss -ltn 2>/dev/null | grep -q ":$JP "; then' \
        'index($0,"grep -qE")>0 {print r; next} {print}' \
        "$SD/lemon_kernel_guard.sh" > "$SD/lemon_kernel_guard.sh.n"
    mv "$SD/lemon_kernel_guard.sh.n" "$SD/lemon_kernel_guard.sh"

    # ---- lemon_rescue.sh ----
    awk -v r='PORT=$(cat /data/adb/lemon_kernel/conf/java_port 2>/dev/null || echo 8790)' \
        'index($0,"PORT=8788")>0 {print r; next} {print}' \
        "$K/lemon_rescue.sh" > "$K/lemon_rescue.sh.n" && mv "$K/lemon_rescue.sh.n" "$K/lemon_rescue.sh"
    fix_q8788 "$K/lemon_rescue.sh" '$PORT' > "$K/lemon_rescue.sh.n" && mv "$K/lemon_rescue.sh.n" "$K/lemon_rescue.sh"

    # ---- mcp_switch.sh：改为控制 LemonCore ----
    cat > "$K/mcp_switch.sh" <<'EOS'
#!/system/bin/sh
# LemonCore MCP 一键开关 v3（迁移后：8788 由 LemonCore 提供）
C=/data/adb/lemon_core
export PATH=/data/adb/magisk:/system/bin:$PATH
case "$1" in
    off)    touch $C/.paused; sh $C/lemoncore.sh stop ;;
    on)     rm -f $C/.paused; sh $C/lemoncore.sh start ;;
    status) sh $C/lemoncore.sh status ;;
    *) echo "用法: mcp_switch.sh on|off|status" ;;
esac
EOS
    chmod 755 "$K/mcp_switch.sh"

    # ---- 校验 ----
    ok=1
    grep -q 'java_port' "$K/run.sh"                    || { log "！run.sh 缺 JP"; ok=0; }
    grep -q 'Dlemon.ports=\$JP' "$K/run.sh"            || { log "！run.sh 启动行未改"; ok=0; }
    ! grep -q ":8788" "$K/run.sh"                      || { log "！run.sh 仍含 :8788"; ok=0; }
    grep -q 'java_port' "$SD/lemon_kernel_guard.sh"    || { log "！guard 缺 JP"; ok=0; }
    ! grep -q ":8788" "$SD/lemon_kernel_guard.sh"      || { log "！guard 仍含 :8788"; ok=0; }
    grep -q 'java_port' "$K/lemon_rescue.sh"           || { log "！rescue 缺 JP"; ok=0; }
    ! grep -q ":8788" "$K/lemon_rescue.sh"             || { log "！rescue 仍含 :8788"; ok=0; }
    if [ "$ok" = "1" ]; then log "补丁校验：全部通过"; return 0; else log "补丁校验：失败"; return 1; fi
}
# PART1_END# PART2
j0(){ curl -s -m "$2" -X POST "http://127.0.0.1:$1/mcp" -H 'Content-Type: application/json' -d "$3"; }

do_verify(){
    R=0
    h=$(curl -s -m 8 "http://127.0.0.1:$NP/healthz")
    echo "$h" | grep -q '"server":"lemon-core"' && log "  [OK] 8788 = LemonCore (healthz)" || { log "  [X] 8788 healthz 异常: $h"; R=1; }
    t=$(j0 $NP 30 '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-06-18"}}')
    echo "$t" | grep -q '"tools":895' && log "  [OK] initialize tools=895" || { log "  [X] initialize 异常"; R=1; }
    l=$(j0 $NP 60 '{"jsonrpc":"2.0","id":2,"method":"tools/list"}')
    echo "$l" | grep -q '"local":463'      && log "  [OK] tools/list local=463"   || { log "  [X] tools/list local 异常"; R=1; }
    echo "$l" | grep -q '"remote_added":432' && log "  [OK] tools/list 上游补 432" || { log "  [X] 上游未接入"; R=1; }
    echo "$(j0 $NP 60 '{"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"khook_status","arguments":{}}}')" | grep -q 'khook' \
        && log "  [OK] 本地工具 khook_status" || { log "  [X] 本地工具异常"; R=1; }
    echo "$(j0 $NP 60 '{"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"meta_version","arguments":{}}}')" | grep -q 'lemon-mcp-root' \
        && log "  [OK] 上游桥 meta_version" || { log "  [X] 上游桥异常"; R=1; }
    echo "$(j0 $JP 30 '{"jsonrpc":"2.0","id":5,"method":"tools/call","params":{"name":"meta_version","arguments":{}}}')" | grep -q 'lemon-mcp-root' \
        && log "  [OK] Java 卫星 $JP 直连正常" || { log "  [X] Java 卫星异常"; R=1; }
    return $R
}

do_migrate(){
    log "=========== 迁移开始 $(date '+%F %T') ==========="
    do_backup
    do_patch || { log "补丁失败，放弃（运行态未改动）"; return 1; }

    log "1) 停灰度实例 (8791)"
    sh $C/lemoncore.sh stop 2>&1 | tee -a $LOG

    log "2) 释放 8788（停 Java 内核）"
    for p in $(pidof lemon-kernel 2>/dev/null); do kill -9 $p 2>/dev/null; done
    sleep 2

    log "3) 切配置 -> PORT=$NP UPSTREAM_PORT=$JP"
    sh $C/lemoncore.sh conf $NP $JP 1 | tee -a $LOG

    log "4) 起 LemonCore 于 $NP（主链路立刻恢复）"
    sh $C/lemoncore.sh start 2>&1 | tee -a $LOG

    log "5) 起 Java 卫星于 $JP"
    nohup sh $K/run.sh >> $K/logs/kernel.log 2>&1 &
    if wait_port $JP 90; then log "  Java 卫星已监听 $JP"; else log "  ！Java 卫星超时未起"; fi

    log "6) 校验"
    if do_verify; then
        log "=========== 迁移成功 $(date '+%F %T') ==========="
        return 0
    else
        log "！校验未通过，执行自动回滚"
        do_rollback
        return 1
    fi
}

do_rollback(){
    log "=========== 回滚开始 $(date '+%F %T') ==========="
    log "1) 停 LemonCore"
    sh $C/lemoncore.sh stop 2>&1 | tee -a $LOG
    log "2) 恢复脚本"
    for f in "$BK"/*; do
        b=$(basename "$f")
        case "$b" in
            state.txt) ;;
            run.sh|lemon_rescue.sh|mcp_switch.sh) cp -a "$f" "$K/$b"; log "  恢复 $K/$b" ;;
            lemon_kernel_guard.sh|lemon_kernel_service.sh|lemon_lemoncore_boot.sh) cp -a "$f" "$SD/$b"; log "  恢复 $SD/$b" ;;
            lemoncore.sh) cp -a "$f" "$C/$b"; log "  恢复 $C/$b" ;;
            lemoncore.conf) cp -a "$f" "$C/conf/$b"; log "  恢复 $C/conf/$b" ;;
        esac
    done
    rm -f "$K/conf/java_port"
    chmod 755 "$SD"/*.sh "$K"/*.sh "$C"/lemoncore.sh 2>/dev/null
    log "3) 停 $JP 上的 Java"
    for p in $(ss -ltnp 2>/dev/null | grep ":$JP " | sed -n 's/.*pid=\([0-9]*\).*/\1/p'); do kill -9 $p 2>/dev/null; done
    sleep 2
    log "4) 以原配置重启 Java 于 8788"
    nohup sh $K/run.sh >> $K/logs/kernel.log 2>&1 &
    wait_port 8788 90 && log "  Java 已在 8788" || log "  ！Java 未起，请手动执行 lemon_rescue.sh force"
    log "5) 恢复 LemonCore 灰度"
    sh $C/lemoncore.sh start 2>&1 | tee -a $LOG
    if echo "$(j0 8788 30 '{"jsonrpc":"2.0","id":9,"method":"tools/call","params":{"name":"meta_version","arguments":{}}}')" | grep -q 'lemon-mcp-root'; then
        log "=========== 回滚成功，8788 已回到 Java 内核 ==========="
        return 0
    else
        log "！回滚后 8788 校验失败，需人工介入（可执行 $K/lemon_rescue.sh force）"
        return 1
    fi
}

do_status(){
    echo "--- 当前拓扑 ---"
    echo "8788: $(pidport 8788)  $(ss -ltnp 2>/dev/null | grep ':8788 ' | sed 's/.*users:/users:/')"
    echo "8790: $(pidport 8790)"
    echo "8791: $(pidport 8791)"
    echo "Java 内核 pid: $(pidof lemon-kernel)"
    echo "LemonCore pid: $(pidof lemoncore)"
    echo "java_port 配置: $(cat $K/conf/java_port 2>/dev/null || echo '无')"
    echo "lemoncore 配置: $(sh $C/lemoncore.sh conf 2>/dev/null)"
    echo "--- 8788 healthz ---"
    curl -s -m 8 http://127.0.0.1:8788/healthz; echo
}

case "$MODE" in
    migrate)  do_migrate ;;
    rollback) do_rollback ;;
    verify)   do_verify && echo "VERIFY OK" || echo "VERIFY FAIL" ;;
    status)   do_status ;;
    patchonly) do_patch && echo PATCH_OK || echo PATCH_FAIL ;;
    *) echo "用法: migrate_8788.sh migrate|rollback|verify|status" ;;
esac
