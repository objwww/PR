#!/bin/sh
# ============================================================================
# e2e-am5-preflight.sh —— AM5 E2E 预检（M5-22 总控资产；落码方案附录 §二.2/§六）
#
# 契约：195、2C4G Gatus、order-arena、Prometheus、Alertmanager、control-app、
# Holmes/Native、LiteLLM、PG 任一必需组件不满足 → 总体 FAIL_PRECONDITION（exit 3），
# 不得自动换 mock。同时记录两机时间/磁盘/内存/容器重启 OOM/当前迁移最大号（§六.1）。
#
# 用法（195 部署段）：. ./e2e-am5-common.sh && sh e2e-am5-preflight.sh <runs_dir>
# ============================================================================

set -e

OUT_PREFIX="[AM5-PREFLIGHT]"

log() { echo "$OUT_PREFIX $1"; }

RUNS_DIR="${1:?用法: e2e-am5-preflight.sh <runs_dir>}"
mkdir -p "$RUNS_DIR"

PREFLIGHT_OK=true

preflight_check() {
    # 用法：preflight_check <名称> <探针命令>
    _pf_name="$1"; shift
    if "$@" >/dev/null 2>&1; then
        log "  OK   ${_pf_name}"
    else
        log "  MISS ${_pf_name}"
        PREFLIGHT_OK=false
    fi
}

log "preflight 开始（记录面 → $(basename "$RUNS_DIR")/preflight.log）"

# ---- 环境账（§六.1：两机时间/磁盘/内存/容器重启 OOM/迁移最大号） ----
{
    printf '# host %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)"
    date -u
    uname -a
    df -h /
    free -m 2>/dev/null || true
    docker ps --format '{{.Names}} {{.Status}}' 2>/dev/null || true
} | am5_redact >> "$RUNS_DIR/preflight.log" 2>&1 || true

# ---- 必需组件逐项（缺失不换 mock，直接 FAIL_PRECONDITION） ----
log "必需组件探针："
preflight_check "docker 可用"            docker info
preflight_check "195 control-app 健康"   curl -fsS --max-time 5 "http://${AM5_CONTROL_HOST:-127.0.0.1}:${AM5_CONTROL_PORT:-8080}/actuator/health"
preflight_check "PG 可达"                sh -c 'test -n "$AM5_PG_URL"'
preflight_check "迁移最大号读取"         am5_psql_ro AM5_PG_URL "select max(version) from flyway_schema_history"
# LiteLLM 标准探活路径 /health/liveliness（/health 是全量健康检查需 admin key；
# v2：路径可覆盖，适配真栈宿主发布端口面）
preflight_check "LiteLLM 可达"           curl -fsS --max-time 5 \
    "http://${AM5_LITELLM_HOST:-127.0.0.1}:${AM5_LITELLM_PORT:-4000}${AM5_LITELLM_HEALTH_PATH:-/health/liveliness}"
preflight_check "order-arena 可达"       curl -fsS --max-time 5 "http://${AM5_ARENA_HOST:-127.0.0.1}:${AM5_ARENA_PORT:-8081}/actuator/health"
preflight_check "Prometheus 可达"        curl -fsS --max-time 5 "http://${AM5_PROM_HOST:-127.0.0.1}:${AM5_PROM_PORT:-9090}/-/ready"
preflight_check "Alertmanager 可达"      curl -fsS --max-time 5 "http://${AM5_AM_HOST:-127.0.0.1}:${AM5_AM_PORT:-9093}/-/ready"
preflight_check "2C4G Gatus 可达"        curl -fsS --max-time 5 "http://${AM5_GATUS_HOST}:${AM5_GATUS_PORT:-8081}/health"
preflight_check "真栈预算上限已设"       sh -c 'test -n "$AM5_LLM_BUDGET_CAP"'

# ---- 结果 ----
if [ "$PREFLIGHT_OK" = "true" ]; then
    log "preflight PASS（全必需组件在位）"
    exit 0
fi
log "FAIL_PRECONDITION（缺失组件见上；不得自动换 mock）"
exit 3
