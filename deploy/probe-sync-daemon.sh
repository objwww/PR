#!/usr/bin/env bash
# ============================================================================
# probe-sync-daemon.sh —— probe-sync 常驻守护（TB-21/TB-22）
#
# 背景：probe-sync 曾是测试脚本生命周期内的子进程——脚本退出守护即停、
# trap 还摘除全部探针映射，导致脚本外任何栈运行窗口（尤其栈重启）stub 回到
# "重建对象不可见"的纯无状态态，drift-repair 风暴复发（TB-21）；且守护内部
# curl/psql 全无超时，风暴负载尖峰上一轮卡死即案内失能（TB-22）。
#
# 本脚本把守护提升为栈级常驻进程：
#   * 生命周期独立于任何测试脚本（nohup 常驻；栈重启不死；宿主机重启后由
#     任一测试脚本的 ensure 自愈拉起）；
#   * 全外部调用有界（库内 curl --max-time 10/15、m2_psql timeout 20），
#     单轮最坏 ~50s，下轮自愈；
#   * 每轮写 heartbeat（epoch 秒），ensure/status 据此判活；
#   * 每轮检测 stub 重启（已知 mapid 404）→ 按状态文件全量重发布。
#
# 用法：probe-sync-daemon.sh start|stop|status|ensure|run
#   start   启动常驻守护（幂等：已健康则不动）
#   stop    停止守护（仅栈级维护时用，测试脚本永不调用）
#   status  退出码 0=健康（pid 活且心跳 <15s），1=不健康；stdout 一句话
#   ensure  status 不健康则 start（测试脚本 m2_probe_sync_start 的唯一入口）
#   run     前台主循环（内部用，start 以 nohup 拉起）
#
# 状态目录：${M2_PROBE_SYNC_DIR:-<deploy>/probe-sync-state}/（库 m2_ps_init 同源）
# ============================================================================
set -u
cd "$(dirname "$0")"

# 栈配置（STUB_ADMIN_PORT / POSTGRES_DB / GITHUB_API_BASE 等）
[ -f ./.env ] && { set -a; . ./.env; set +a; }

M2_EVIDENCE="$(pwd)"   # 库内个别函数的形式参数；probe-sync 状态不依赖它
. ./m2-lib.sh
m2_ps_init
PSD="$M2_PS_DIR"
PIDFILE="$PSD/daemon.pid"
LOG="$PSD/daemon.log"

ps_alive() { # <pid>
    [ -n "$1" ] && kill -0 "$1" 2>/dev/null
}

ps_status() { # 0=健康
    local pid="" age=9999 now hb
    [ -f "$PIDFILE" ] && pid=$(cat "$PIDFILE" 2>/dev/null)
    if [ -f "$PSD/heartbeat" ]; then
        now=$(date +%s)
        hb=$(cat "$PSD/heartbeat" 2>/dev/null)
        case "$hb" in ''|*[!0-9]*) hb=0 ;; esac
        age=$((now - hb))
    fi
    if ps_alive "$pid" && [ "$age" -lt 15 ]; then
        echo "[probe-sync-daemon] 健康（pid=$pid，心跳 ${age}s 前）"
        return 0
    fi
    echo "[probe-sync-daemon] 不健康（pid=${pid:-无} 存活=$(ps_alive "$pid" && echo 是 || echo 否)，心跳 ${age}s 前）"
    return 1
}

ps_start() {
    local old=""
    [ -f "$PIDFILE" ] && old=$(cat "$PIDFILE" 2>/dev/null)
    if ps_alive "$old"; then kill "$old" 2>/dev/null; sleep 1; fi
    # 启动时清扫本机制历史运行时映射（静态映射无此 metadata），再按状态文件全量重发布
    local ids id
    ids=$(curl -s --max-time 10 "$(m2_stub_admin)/__admin/mappings" \
        | jq -r '.mappings[] | select(.metadata.m2ProbeSync == true) | .id' 2>/dev/null)
    for id in $ids; do m2_map_del "$id"; done
    m2_probe_sync_republish_all
    # 状态文件卫生：rv_<pr>.json 按 PR 号随轮次累积，7 天前的直接退役
    find "$PSD" -maxdepth 1 -name 'rv_*.json' -mtime +7 -delete 2>/dev/null
    # seen.txt 游标连续性保留（防守护重启后复活已摘除对象）；超 5000 行截尾
    if [ -f "$PSD/seen.txt" ] && [ "$(wc -l < "$PSD/seen.txt")" -gt 5000 ]; then
        tail -2000 "$PSD/seen.txt" > "$PSD/.seen.tmp" && mv "$PSD/.seen.tmp" "$PSD/seen.txt"
    fi
    [ -f "$PSD/seen.txt" ] || : > "$PSD/seen.txt"
    nohup bash "$0" run >> "$LOG" 2>&1 &
    echo $! > "$PIDFILE"
    echo "[probe-sync-daemon] 已启动（pid=$(cat "$PIDFILE")，状态目录 $PSD）"
}

ps_run() {
    echo "[$(date '+%F %T')] probe-sync 常驻守护主循环启动（pid=$$）" 
    while true; do
        m2_ps_daemon_tick
        sleep 1
    done
}

case "${1:-}" in
    start)  ps_start ;;
    stop)
        if [ -f "$PIDFILE" ] && ps_alive "$(cat "$PIDFILE")"; then
            kill "$(cat "$PIDFILE")" 2>/dev/null
            echo "[probe-sync-daemon] 已停止"
        else
            echo "[probe-sync-daemon] 本不在运行"
        fi
        rm -f "$PIDFILE"
        ;;
    status) ps_status ;;
    ensure)
        if ps_status > /dev/null 2>&1; then
            echo "  [probe-sync] 常驻守护健康（心跳 <15s），复用"
        else
            echo "  [probe-sync] 常驻守护不在/失能，就地拉起"
            ps_start
        fi
        ;;
    run)    ps_run ;;
    *)      echo "用法: $0 start|stop|status|ensure|run" >&2; exit 2 ;;
esac
