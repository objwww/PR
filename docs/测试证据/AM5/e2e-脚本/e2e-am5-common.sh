#!/bin/sh
# ============================================================================
# e2e-am5-common.sh —— AM5 E2E 公共函数库（M5-22 总控资产；落码方案附录 §一）
#
# 职责（附录原文）：脱敏、轮询、只读 SQL、digest、资源账公共函数。
# 安全纪律：密钥/Bearer/数据库口令/env dump 永不入证据包——所有落盘前经 am5_redact。
# 本库只被 source，不独立执行。
# ============================================================================

AM5_COMMON_VERSION="1"

am5_log() { echo "[AM5] $1"; }
am5_fail() { echo "[AM5] FAIL: $1"; exit 1; }

# 唯一批次 id（UTC；runs/<id>/ 目录锚，清理只认它——附录 §二.8）
am5_suite_run_id() {
    date -u +"%Y%m%dT%H%M%SZ"
}

# 脱敏管道：Bearer/token/password/secret/[redis|db] 口令替换为 ***（先脱敏再落盘）
am5_redact() {
    sed -E \
        -e 's/(Bearer[ ]+)[A-Za-z0-9._~+/-]+/\1***REDACTED***/g' \
        -e 's/((password|passwd|secret|token|api[_-]?key)[" ]*[:=][" ]*)[^" ,}]+/\1***REDACTED***/Ig' \
        -e 's#(postgres(ql)?://[^:/@]+:)[^@]+@#\1***REDACTED***@#g'
}

# 只读 SQL（只读事务包裹；证据面 SQL 一律经此——禁止 DML 借证据通道落库）
am5_psql_ro() {
    # 用法：am5_psql_ro <db_url_env_name> <sql>
    _am5_url_var="$1"; _am5_sql="$2"
    eval "_am5_url=\${${_am5_url_var}}"
    [ -n "$_am5_url" ] || am5_fail "环境变量 ${_am5_url_var} 未注入（连接信息走既有安全配置）"
    psql "$_am5_url" -v ON_ERROR_STOP=1 -q \
        -c "BEGIN TRANSACTION ISOLATION LEVEL SERIALIZABLE READ ONLY; ${_am5_sql}; ROLLBACK;"
}

# 轮询至条件满足或超时（秒）；条件为返回 0 的 shell 命令片段
am5_poll_until() {
    _am5_desc="$1"; _am5_timeout="$2"; _am5_probe="$3"
    _am5_elapsed=0
    while [ "$_am5_elapsed" -lt "$_am5_timeout" ]; do
        if eval "$_am5_probe" >/dev/null 2>&1; then
            return 0
        fi
        sleep 5
        _am5_elapsed=$((_am5_elapsed + 5))
    done
    am5_fail "轮询超时(${_am5_timeout}s): ${_am5_desc}"
}

# scenario-results.json 行（唯一允许状态面：PASS/FAIL/BLOCKED_EXTERNAL——附录 §二.4）
am5_scenario_result() {
    # 用法：am5_scenario_result <runs_dir> <scenario_id> <name> <fidelity> <status>
    _am5_runs="$1"; _am5_sid="$2"; _am5_name="$3"; _am5_fid="$4"; _am5_status="$5"
    case "$_am5_status" in
        PASS|FAIL|BLOCKED_EXTERNAL) ;;
        *) am5_fail "非法场景状态: $_am5_status（SKIP/NOT_RUN 不入表=总体失败）" ;;
    esac
    printf '{"scenario":"%s","name":"%s","fidelity":"%s","status":"%s","recorded_at":"%s"}\n' \
        "$_am5_sid" "$_am5_name" "$_am5_fid" "$_am5_status" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
        >> "$_am5_runs/scenario-results.json"
}

# 资源账三连（执行前/峰值/执行后各记一次——附录 §六.6）
am5_resource_snapshot() {
    _am5_runs="$1"; _am5_tag="$2"
    {
        printf '# %s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$_am5_tag"
        df -h / | am5_redact
        free -m 2>/dev/null || true
        docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' 2>/dev/null || true
    } >> "$_am5_runs/resource.log" 2>&1 || true
}
