#!/bin/sh
# ============================================================================
# e2e-am6-common.sh —— AM6 E2E 公共函数库（M6-01 案④ 随件交付；
#                       沿 AM5 附录 §一 house style：e2e-am5-common.sh 同构）
#
# 职责：脱敏、轮询、只读 SQL、HTTP 面（webhook / config-bundle / canary-status）、
#       场景账与资源账公共函数。
# 安全纪律：密钥/Bearer/数据库口令/env dump 永不入证据包——所有落盘前经 am6_redact；
#       bearer 只经环境变量注入（AM6_WEBHOOK_BEARER / AM6_RELEASE_BEARER），
#       curl 经 600 权限临时配置文件携带（不进命令行参数/进程列表/日志）。
# 本库只被 source，不独立执行。
#
# 执行环境（195 部署段，操作员 shell 预注入；脚本内零密钥字面量）：
#   AM6_CONTROL_URL    control-app 基址（默认 http://127.0.0.1:8080）
#   AM6_WEBHOOK_BEARER app.alert.webhook.bearer（/webhooks/alertmanager 注入面）
#   AM6_RELEASE_BEARER app.release.api.bearer（config-bundle / canary-status 面）
#   AM6_PG_URL         只读 SQL 连接串（容器内视角 127.0.0.1:5432；口令只存
#                      195 侧 600 权限 env，am6_redact 兜底）
#   AM6_PSQL_CMD       可覆盖 psql 命令面——195 宿主无 psql 客户端，部署段经
#                      AM6_PSQL_CMD="docker exec deploy-postgres-1 psql" 容器内执行
#   AM6_RUNS_DIR       证据根目录（默认 ./runs）
# ============================================================================

AM6_COMMON_VERSION="1"

am6_log() { echo "[AM6] $1"; }
# FAIL 走 stderr：本库函数可能在命令替换子壳内失败（子壳 exit 即让 set -e 主壳中止，
# 消息经 stderr 仍可见，不被 $( ) 吞掉）
am6_fail() { echo "[AM6] FAIL: $1" >&2; exit 1; }

# 唯一批次 id（UTC；runs/<id>/ 目录锚，清理只认它）
am6_suite_run_id() { date -u +"%Y%m%dT%H%M%SZ"; }

# 脱敏管道：Bearer/token/password/secret/[redis|db] 口令替换为 ***（先脱敏再落盘）
am6_redact() {
    sed -E \
        -e 's/(Bearer[ ]+)[A-Za-z0-9._~+/-]+/\1***REDACTED***/g' \
        -e 's/((password|passwd|secret|token|api[_-]?key)[" ]*[:=][" ]*)[^" ,}]+/\1***REDACTED***/Ig' \
        -e 's#(postgres(ql)?://[^:/@]+:)[^@]+@#\1***REDACTED***@#g'
}

# 只读 SQL（只读事务包裹；证据面 SQL 一律经此——禁止 DML 借证据通道落库）
# 用法：am6_psql_ro <db_url_env_name> <sql> [额外 psql 旗标，如 "-At -F |"]
am6_psql_ro() {
    _am6_url_var="$1"; _am6_sql="$2"; _am6_flags="${3-}"
    eval "_am6_url=\${${_am6_url_var}}"
    [ -n "$_am6_url" ] || am6_fail "环境变量 ${_am6_url_var} 未注入（连接信息走既有安全配置）"
    ${AM6_PSQL_CMD:-psql} "$_am6_url" -v ON_ERROR_STOP=1 -q ${_am6_flags} \
        -c "BEGIN TRANSACTION ISOLATION LEVEL SERIALIZABLE READ ONLY; ${_am6_sql}; ROLLBACK;"
}

# 只读 SQL 轮询至标量 >= n；用法：am6_db_poll_ge <描述> <超时秒> <url_var> <count_sql> <n>
#   <count_sql> 须返回单个数值（count(*)/标量）；轮询步进 3s
am6_db_poll_ge() {
    _am6_desc="$1"; _am6_to="$2"; _am6_uv="$3"; _am6_sql="$4"; _am6_n="$5"
    _am6_t=0
    while [ "$_am6_t" -lt "$_am6_to" ]; do
        _am6_c="$(am6_psql_ro "$_am6_uv" "$_am6_sql" '-At')"
        if [ "${_am6_c:-0}" -ge "$_am6_n" ] 2>/dev/null; then
            return 0
        fi
        sleep 3
        _am6_t=$((_am6_t + 3))
    done
    am6_fail "DB 轮询超时(${_am6_to}s): ${_am6_desc}（last=$_am6_c）"
}

# 轮询至条件满足或超时（秒）；条件为返回 0 的 shell 命令片段（SQL 内含引号时勿用本函数，
# 用 am6_db_poll_ge）
am6_poll_until() {
    _am6_desc="$1"; _am6_to="$2"; _am6_probe="$3"
    _am6_t=0
    while [ "$_am6_t" -lt "$_am6_to" ]; do
        if eval "$_am6_probe" >/dev/null 2>&1; then
            return 0
        fi
        sleep 3
        _am6_t=$((_am6_t + 3))
    done
    am6_fail "轮询超时(${_am6_to}s): ${_am6_desc}"
}

# ---------------------------------------------------------------------------
# HTTP 面（bearer 经 600 临时 curl 配置文件携带；响应体落文件，状态码上 stdout）
# 用法：code=$(am6_http <METHOD> <path|url> <bearer_var_name|''> <body_file|''> <out_body_file>)
# ---------------------------------------------------------------------------
am6_http() {
    _am6_m="$1"; _am6_u="$2"; _am6_bvar="$3"; _am6_body="$4"; _am6_out="$5"
    case "$_am6_u" in
        http*) : ;;
        *) _am6_u="${AM6_CONTROL_URL:-http://127.0.0.1:8080}$_am6_u" ;;
    esac
    set -- -sS -o "$_am6_out" -w '%{http_code}' -X "$_am6_m" \
        -H "Content-Type: application/json"
    _am6_cfg=""
    if [ -n "$_am6_bvar" ]; then
        eval "_am6_bearer=\${${_am6_bvar}-}"
        [ -n "$_am6_bearer" ] || am6_fail "bearer 变量 ${_am6_bvar} 未注入"
        _am6_cfg="$(mktemp)" || am6_fail "mktemp 失败"
        chmod 600 "$_am6_cfg"
        printf 'header = "Authorization: Bearer %s"\n' "$_am6_bearer" > "$_am6_cfg"
        set -- "$@" --config "$_am6_cfg"
    fi
    [ -n "$_am6_body" ] && set -- "$@" --data-binary "@$_am6_body"
    set -- "$@" "$_am6_u"
    curl "$@"
    _am6_rc=$?
    [ -z "$_am6_cfg" ] || rm -f "$_am6_cfg"
    return "$_am6_rc"
}

# 发布 bundle（body={content:<文件内容>}；幂等锚=canonical digest，同内容重发 replayed=true）
# 用法：am6_publish_bundle <content_json_file> <tag>；成功 echo 64hex digest
am6_publish_bundle() {
    _am6_content="$1"; _am6_tag="$2"
    _am6_dir="$(dirname "$_am6_content")"
    printf '{"content":%s}' "$(cat "$_am6_content")" > "${_am6_dir}/publish-${_am6_tag}.body"
    _am6_code="$(am6_http POST /api/config-bundles AM6_RELEASE_BEARER \
        "${_am6_dir}/publish-${_am6_tag}.body" "${_am6_dir}/publish-${_am6_tag}.resp")"
    [ "$_am6_code" = "200" ] \
        || am6_fail "publish(${_am6_tag}) HTTP $_am6_code: $(cat "${_am6_dir}/publish-${_am6_tag}.resp")"
    _am6_digest="$(sed -E 's/.*"bundleDigest":"([0-9a-f]{64})".*/\1/' "${_am6_dir}/publish-${_am6_tag}.resp")"
    echo "$_am6_digest" | grep -qE '^[0-9a-f]{64}$' \
        || am6_fail "publish(${_am6_tag}) 响应无合法 digest: $(cat "${_am6_dir}/publish-${_am6_tag}.resp")"
    echo "$_am6_digest"
}

# 原子激活（CAS；200 moved/replayed，409=指针被并发移走即败者面零状态改写）
am6_activate() {
    _am6_digest="$1"; _am6_dir="$2"
    printf '{}' > "${_am6_dir}/activate.body"
    _am6_code="$(am6_http POST "/api/config-bundles/${_am6_digest}/activate" AM6_RELEASE_BEARER \
        "${_am6_dir}/activate.body" "${_am6_dir}/activate.resp")"
    [ "$_am6_code" = "200" ] \
        || am6_fail "activate(${_am6_digest}) HTTP $_am6_code: $(cat "${_am6_dir}/activate.resp")"
}

# 回滚（pointer 指回 toDigest；历史行零改写 INV-AM5-5）
am6_rollback() {
    _am6_to="$1"; _am6_dir="$2"
    printf '{"toDigest":"%s"}' "$_am6_to" > "${_am6_dir}/rollback.body"
    _am6_code="$(am6_http POST /api/config-bundles/rollback AM6_RELEASE_BEARER \
        "${_am6_dir}/rollback.body" "${_am6_dir}/rollback.resp")"
    [ "$_am6_code" = "200" ] \
        || am6_fail "rollback(${_am6_to}) HTTP $_am6_code: $(cat "${_am6_dir}/rollback.resp")"
}

# 当前激活 digest（从未激活 404 → echo 空串）
am6_active_digest() {
    _am6_dir="$1"
    _am6_code="$(am6_http GET /api/config-bundles/active AM6_RELEASE_BEARER "" "${_am6_dir}/active.resp")"
    if [ "$_am6_code" = "200" ]; then
        sed -E 's/.*"bundleDigest":"([0-9a-f]{64})".*/\1/' "${_am6_dir}/active.resp"
    elif [ "$_am6_code" = "404" ]; then
        echo ""
    else
        am6_fail "active HTTP $_am6_code: $(cat "${_am6_dir}/active.resp")"
    fi
}

# 合成告警注入（Alertmanager webhook v4 形；唯一键由 <service> 承担；
# 同键异 startsAt → 异 payloadHash → 复燃新 episode）
# 用法：am6_inject_alert <alertname> <service> <firing|resolved> <runs_dir>；echo HTTP 状态码
am6_inject_alert() {
    _am6_an="$1"; _am6_svc="$2"; _am6_st="$3"; _am6_dir="$4"
    # 毫秒时间戳：投影乱序防御以 startsAt 与 resolvedAt 比先后（复燃判据），
    # 秒级截断会让同秒内的 refire 被误判"迟到 firing"不复活（E2E 实证）
    _am6_now="$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)"
    if [ "$_am6_st" = "resolved" ]; then
        _am6_ends="$_am6_now"
    else
        _am6_ends="0001-01-01T00:00:00Z"
    fi
    cat > "${_am6_dir}/alert-${_am6_svc}-${_am6_st}.body" <<EOF
{"version":"4","groupKey":"am6e2e:${_am6_svc}","status":"${_am6_st}","receiver":"e2e-am6","groupLabels":{"alertname":"${_am6_an}","service":"${_am6_svc}"},"commonLabels":{"alertname":"${_am6_an}","service":"${_am6_svc}"},"commonAnnotations":{},"alerts":[{"status":"${_am6_st}","fingerprint":"am6e2e-${_am6_svc}-${_am6_now}","labels":{"alertname":"${_am6_an}","service":"${_am6_svc}","severity":"warning"},"annotations":{"summary":"E2E-AM6 synthetic ${_am6_svc}"},"startsAt":"${_am6_now}","endsAt":"${_am6_ends}"}]}
EOF
    am6_http POST /webhooks/alertmanager AM6_WEBHOOK_BEARER \
        "${_am6_dir}/alert-${_am6_svc}-${_am6_st}.body" "${_am6_dir}/alert-${_am6_svc}-${_am6_st}.resp"
}

# scenario-results.json 行（唯一允许状态面：PASS/FAIL/BLOCKED_EXTERNAL）
am6_scenario_result() {
    # 用法：am6_scenario_result <runs_dir> <scenario_id> <name> <fidelity> <status>
    _am6_runs="$1"; _am6_sid="$2"; _am6_name="$3"; _am6_fid="$4"; _am6_status="$5"
    case "$_am6_status" in
        PASS|FAIL|BLOCKED_EXTERNAL) ;;
        *) am6_fail "非法场景状态: $_am6_status（SKIP/NOT_RUN 不入表=总体失败）" ;;
    esac
    printf '{"scenario":"%s","name":"%s","fidelity":"%s","status":"%s","recorded_at":"%s"}\n' \
        "$_am6_sid" "$_am6_name" "$_am6_fid" "$_am6_status" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
        >> "$_am6_runs/scenario-results.json"
}

# 资源账三连（执行前/峰值/执行后各记一次）
am6_resource_snapshot() {
    _am6_runs="$1"; _am6_tag="$2"
    {
        printf '# %s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$_am6_tag"
        df -h / | am6_redact
        free -m 2>/dev/null || true
        docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' 2>/dev/null || true
    } >> "$_am6_runs/resource.log" 2>&1 || true
}
