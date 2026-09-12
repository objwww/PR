#!/bin/sh
# ============================================================================
# e2e-r7-common.sh —— R7 真 LLM 多 Agent 线 195 真窗 E2E 公共函数库
# （R7 执行日志 §13 真窗断言包随件；沿 AM6 e2e-am6-common.sh house style 同构）
#
# 职责：脱敏、轮询、只读 SQL、HTTP 面（webhook / config-bundle / health）、
#       run 终态轮询、绑定快照（重启前后身份比对）、容器硬杀/重启
#       （RX13/RD08/RX02/RD09 专用，需 R7_ALLOW_DESTRUCTIVE=1 显式放行）、
#       场景账与资源账公共函数。
# 安全纪律：密钥/Bearer/数据库口令/env dump 永不入证据包——所有落盘前经 r7_redact；
#       bearer 只经环境变量注入（R7_WEBHOOK_BEARER / R7_RELEASE_BEARER），
#       curl 经 600 权限临时配置文件携带（不进命令行参数/进程列表/日志）。
# 本库只被 source，不独立执行。
#
# 执行环境（195 部署段，操作员 shell 预注入；脚本内零密钥字面量）：
#   R7_CONTROL_URL       control-app 基址（默认 http://127.0.0.1:8080）
#   R7_WEBHOOK_BEARER    app.alert.webhook.bearer（/webhooks/alertmanager 注入面）
#   R7_RELEASE_BEARER    app.release.api.bearer（config-bundle 面）
#   R7_PG_URL            只读 SQL 连接串（容器内视角；口令只存 195 侧 600 env）
#   R7_PSQL_CMD          可覆盖 psql 命令面——195 宿主无 psql 客户端，部署段经
#                        R7_PSQL_CMD="docker exec deploy-postgres-1 psql" 容器内执行
#   R7_RUNS_DIR          证据根目录（默认 ./runs）
#   R7_CONTROL_CONTAINER control-app 容器名（默认 deploy-control-app-1；杀/重启面）
#   R7_ALLOW_DESTRUCTIVE 破坏性动作总闸（docker kill/start；必须显式 =1，
#                        缺省时 r7_container_kill/start 直接 fail——防误跑）
# ============================================================================

R7_COMMON_VERSION="1"

r7_log() { echo "[R7] $1"; }
# FAIL 走 stderr：本库函数可能在命令替换子壳内失败（子壳 exit 即让 set -e 主壳中止，
# 消息经 stderr 仍可见，不被 $( ) 吞掉）
r7_fail() { echo "[R7] FAIL: $1" >&2; exit 1; }

# 唯一批次 id（UTC；runs/<id>/ 目录锚，清理只认它）
r7_suite_run_id() { date -u +"%Y%m%dT%H%M%SZ"; }

# 脱敏管道：Bearer/token/password/secret/[api|db] 口令替换为 ***（先脱敏再落盘）
r7_redact() {
    sed -E \
        -e 's/(Bearer[ ]+)[A-Za-z0-9._~+/-]+/\1***REDACTED***/g' \
        -e 's/((password|passwd|secret|token|api[_-]?key)[" ]*[:=][" ]*)[^" ,}]+/\1***REDACTED***/Ig' \
        -e 's#(postgres(ql)?://[^:/@]+:)[^@]+@#\1***REDACTED***@#g'
}

# 只读 SQL（只读事务包裹；证据面 SQL 一律经此——禁止 DML 借证据通道落库）
# 用法：r7_psql_ro <db_url_env_name> <sql> [额外 psql 旗标，如 "-At -F |"]
r7_psql_ro() {
    _r7_url_var="$1"; _r7_sql="$2"; _r7_flags="${3-}"
    eval "_r7_url=\${${_r7_url_var}}"
    [ -n "$_r7_url" ] || r7_fail "环境变量 ${_r7_url_var} 未注入（连接信息走既有安全配置）"
    ${R7_PSQL_CMD:-psql} "$_r7_url" -v ON_ERROR_STOP=1 -q ${_r7_flags} \
        -c "BEGIN TRANSACTION ISOLATION LEVEL SERIALIZABLE READ ONLY; ${_r7_sql}; ROLLBACK;"
}

# 只读 SQL 轮询至标量 >= n；用法：r7_db_poll_ge <描述> <超时秒> <url_var> <count_sql> <n>
#   <count_sql> 须返回单个数值（count(*)/标量）；轮询步进 3s
r7_db_poll_ge() {
    _r7_desc="$1"; _r7_to="$2"; _r7_uv="$3"; _r7_sql="$4"; _r7_n="$5"
    _r7_t=0
    while [ "$_r7_t" -lt "$_r7_to" ]; do
        _r7_c="$(r7_psql_ro "$_r7_uv" "$_r7_sql" '-At')"
        if [ "${_r7_c:-0}" -ge "$_r7_n" ] 2>/dev/null; then
            return 0
        fi
        sleep 3
        _r7_t=$((_r7_t + 3))
    done
    r7_fail "DB 轮询超时(${_r7_to}s): ${_r7_desc}（last=$_r7_c）"
}

# 轮询至条件满足或超时（秒）；条件为返回 0 的 shell 命令片段（SQL 内含引号时勿用本函数，
# 用 r7_db_poll_ge）
r7_poll_until() {
    _r7_desc="$1"; _r7_to="$2"; _r7_probe="$3"
    _r7_t=0
    while [ "$_r7_t" -lt "$_r7_to" ]; do
        if eval "$_r7_probe" >/dev/null 2>&1; then
            return 0
        fi
        sleep 3
        _r7_t=$((_r7_t + 3))
    done
    r7_fail "轮询超时(${_r7_to}s): ${_r7_desc}"
}

# ---------------------------------------------------------------------------
# HTTP 面（bearer 经 600 临时 curl 配置文件携带；响应体落文件，状态码上 stdout）
# 用法：code=$(r7_http <METHOD> <path|url> <bearer_var_name|''> <body_file|''> <out_body_file>)
# ---------------------------------------------------------------------------
r7_http() {
    _r7_m="$1"; _r7_u="$2"; _r7_bvar="$3"; _r7_body="$4"; _r7_out="$5"
    case "$_r7_u" in
        http*) : ;;
        *) _r7_u="${R7_CONTROL_URL:-http://127.0.0.1:8080}$_r7_u" ;;
    esac
    set -- -sS -o "$_r7_out" -w '%{http_code}' -X "$_r7_m" \
        -H "Content-Type: application/json"
    _r7_cfg=""
    if [ -n "$_r7_bvar" ]; then
        eval "_r7_bearer=\${${_r7_bvar}-}"
        [ -n "$_r7_bearer" ] || r7_fail "bearer 变量 ${_r7_bvar} 未注入"
        _r7_cfg="$(mktemp)" || r7_fail "mktemp 失败"
        chmod 600 "$_r7_cfg"
        printf 'header = "Authorization: Bearer %s"\n' "$_r7_bearer" > "$_r7_cfg"
        set -- "$@" --config "$_r7_cfg"
    fi
    [ -n "$_r7_body" ] && set -- "$@" --data-binary "@$_r7_body"
    set -- "$@" "$_r7_u"
    curl "$@"
    _r7_rc=$?
    [ -z "$_r7_cfg" ] || rm -f "$_r7_cfg"
    return "$_r7_rc"
}

# health 探针（200 才过；响应体脱敏落盘）
r7_health() {
    _r7_dir="$1"
    _r7_code="$(r7_http GET /actuator/health "" "" "${_r7_dir}/health.resp")"
    r7_redact < "${_r7_dir}/health.resp" > "${_r7_dir}/health.resp.redacted" 2>/dev/null \
        && mv -f "${_r7_dir}/health.resp.redacted" "${_r7_dir}/health.resp"
    [ "$_r7_code" = "200" ] || r7_fail "health 期望 200 实得 $_r7_code: $(cat "${_r7_dir}/health.resp")"
}

# 发布 bundle（body={content:<文件内容>}；幂等锚=canonical digest，同内容重发 replayed=true）
# 用法：r7_publish_bundle <content_json_file> <tag>；成功 echo 64hex digest
r7_publish_bundle() {
    _r7_content="$1"; _r7_tag="$2"
    _r7_dir="$(dirname "$_r7_content")"
    printf '{"content":%s}' "$(cat "$_r7_content")" > "${_r7_dir}/publish-${_r7_tag}.body"
    _r7_code="$(r7_http POST /api/config-bundles R7_RELEASE_BEARER \
        "${_r7_dir}/publish-${_r7_tag}.body" "${_r7_dir}/publish-${_r7_tag}.resp")"
    [ "$_r7_code" = "200" ] \
        || r7_fail "publish(${_r7_tag}) HTTP $_r7_code: $(cat "${_r7_dir}/publish-${_r7_tag}.resp")"
    _r7_digest="$(sed -E 's/.*"bundleDigest":"([0-9a-f]{64})".*/\1/' "${_r7_dir}/publish-${_r7_tag}.resp")"
    echo "$_r7_digest" | grep -qE '^[0-9a-f]{64}$' \
        || r7_fail "publish(${_r7_tag}) 响应无合法 digest: $(cat "${_r7_dir}/publish-${_r7_tag}.resp")"
    echo "$_r7_digest"
}

# 原子激活（EN-02 资格化 CAS：body 必带 expectedActiveRevision——客户端预期的当前
# 激活 revision，0=从未激活；服务端不替用户推算预期——P06。
# 200 moved/replayed，409=指针被并发移走即败者面零状态改写）
r7_activate() {
    _r7_digest="$1"; _r7_dir="$2"
    _r7_code="$(r7_http GET /api/config-bundles/active R7_RELEASE_BEARER "" \
        "${_r7_dir}/activate-active.resp")"
    if [ "$_r7_code" = "200" ]; then
        _r7_exp="$(sed -E 's/.*"revision":([0-9]+).*/\1/' "${_r7_dir}/activate-active.resp")"
        echo "$_r7_exp" | grep -qE '^[0-9]+$' \
            || r7_fail "active 响应无合法 revision: $(cat "${_r7_dir}/activate-active.resp")"
    elif [ "$_r7_code" = "404" ]; then
        _r7_exp=0
    else
        r7_fail "active(activate 前置) HTTP $_r7_code: $(cat "${_r7_dir}/activate-active.resp")"
    fi
    printf '{"expectedActiveRevision":%s}' "$_r7_exp" > "${_r7_dir}/activate.body"
    _r7_code="$(r7_http POST "/api/config-bundles/${_r7_digest}/activate" R7_RELEASE_BEARER \
        "${_r7_dir}/activate.body" "${_r7_dir}/activate.resp")"
    [ "$_r7_code" = "200" ] \
        || r7_fail "activate(${_r7_digest}) HTTP $_r7_code: $(cat "${_r7_dir}/activate.resp")"
}

# EN-02 资格门桥（e2e 专用）：activate 现要求未撤销 quality_verdict=PASS 证明
# （227896c 起），而 grant/revoke 的 HTTP 端点归 EN-09/10（未建）——e2e 探针 bundle
# 以 SQL 面如实授予。这是显式 DML（不走 r7_psql_ro 证据通道，不违其只读纪律），
# provenance 全量落库（runner/grader/granted_by 如实标注 e2e 自证+HTTP 面未建）。
# 用法：r7_qualify <bundle_digest> <runs_dir>；幂等：已有未撤销证明则跳过
r7_qualify() {
    _r7_digest="$1"; _r7_dir="$2"
    [ -n "$R7_PG_URL" ] || r7_fail "环境变量 R7_PG_URL 未注入（资格授予需要 DB 面）"
    _r7_have="$(r7_psql_ro R7_PG_URL \
        "select count(*) from release_qualification where candidate_digest='${_r7_digest}' and revoked_at is null" \
        '-At')"
    [ "${_r7_have:-0}" -ge 1 ] && return 0
    ${R7_PSQL_CMD:-psql} "$R7_PG_URL" -v ON_ERROR_STOP=1 -q -At \
        -c "insert into release_qualification (id, candidate_digest, baseline_digest,
            dataset_manifest_digest, runner_version, grader_version, quality_verdict,
            usage_status, granted_scope, granted_by, granted_at)
            values (gen_random_uuid(), '${_r7_digest}', null, repeat('a0',32),
            'e2e-r7-a0', 'e2e-r7-a0', 'PASS', 'UNKNOWN', 'e2e-a0-probe',
            'e2e-r7-operator(sql-grant;EN0910-pending)', now()) returning id" \
        > "${_r7_dir}/qualify.txt" \
        || r7_fail "qualify(${_r7_digest}) 授予失败: $(cat "${_r7_dir}/qualify.txt")"
    r7_log "qualification 授予 id=$(cat "${_r7_dir}/qualify.txt")（EN-02 资格门；SQL 桥）"
}

# 当前激活 digest（从未激活 404 → echo 空串）
r7_active_digest() {
    _r7_dir="$1"
    _r7_code="$(r7_http GET /api/config-bundles/active R7_RELEASE_BEARER "" "${_r7_dir}/active.resp")"
    if [ "$_r7_code" = "200" ]; then
        sed -E 's/.*"bundleDigest":"([0-9a-f]{64})".*/\1/' "${_r7_dir}/active.resp"
    elif [ "$_r7_code" = "404" ]; then
        echo ""
    else
        r7_fail "active HTTP $_r7_code: $(cat "${_r7_dir}/active.resp")"
    fi
}

# 合成告警注入（Alertmanager webhook v4 形；唯一键由 <service> 承担；
# 同键异 startsAt → 异 payloadHash → 复燃新 episode）
# 用法：r7_inject_alert <alertname> <service> <firing|resolved> <runs_dir> [summary注记]
#       <summary注记> 缺省为 E2E-R7 synthetic <service>；B 门四案经此差分场景意图
r7_inject_alert() {
    _r7_an="$1"; _r7_svc="$2"; _r7_st="$3"; _r7_dir="$4"
    _r7_sum="${5:-E2E-R7 synthetic ${_r7_svc}}"
    # 毫秒时间戳：投影乱序防御以 startsAt 与 resolvedAt 比先后（复燃判据），
    # 秒级截断会让同秒内的 refire 被误判"迟到 firing"不复活（AM6 E2E 实证）
    _r7_now="$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)"
    if [ "$_r7_st" = "resolved" ]; then
        _r7_ends="$_r7_now"
    else
        _r7_ends="0001-01-01T00:00:00Z"
    fi
    cat > "${_r7_dir}/alert-${_r7_svc}-${_r7_st}.body" <<EOF
{"version":"4","groupKey":"r7e2e:${_r7_svc}","status":"${_r7_st}","receiver":"e2e-r7","groupLabels":{"alertname":"${_r7_an}","service":"${_r7_svc}"},"commonLabels":{"alertname":"${_r7_an}","service":"${_r7_svc}"},"commonAnnotations":{"summary":"${_r7_sum}"},"alerts":[{"status":"${_r7_st}","fingerprint":"r7e2e-${_r7_svc}-${_r7_now}","labels":{"alertname":"${_r7_an}","service":"${_r7_svc}","severity":"warning"},"annotations":{"summary":"${_r7_sum}"},"startsAt":"${_r7_now}","endsAt":"${_r7_ends}"}]}
EOF
    r7_http POST /webhooks/alertmanager R7_WEBHOOK_BEARER \
        "${_r7_dir}/alert-${_r7_svc}-${_r7_st}.body" "${_r7_dir}/alert-${_r7_svc}-${_r7_st}.resp"
}

# ---------------------------------------------------------------------------
# R7 断言包专用面
# ---------------------------------------------------------------------------

# 破坏性动作总闸（docker kill/start 只服务 RX13/RD08/RX02/RD09 硬杀/重启场景；
# 未显式放行一律拒绝——防在共享窗上误杀控制面）
r7_require_destructive() {
    [ "${R7_ALLOW_DESTRUCTIVE:-0}" = "1" ] \
        || r7_fail "破坏性动作未放行：本场景对 control-app 做 docker kill/start，\
必须显式注入 R7_ALLOW_DESTRUCTIVE=1（操作员知情确认面，见 RUNBOOK）"
}

r7_container_name() { echo "${R7_CONTROL_CONTAINER:-deploy-control-app-1}"; }

# SIGKILL 硬杀（隔离进程死亡面；非 compose stop——停止 ≠ 硬终止）
r7_container_kill() {
    r7_require_destructive
    _r7_c="$(r7_container_name)"
    docker kill "$_r7_c" >> "${R7_KILL_LOG:-/dev/null}" 2>&1 \
        || r7_fail "docker kill ${_r7_c} 失败"
}

# 硬杀后原容器拉起（compose 建的容器 docker start 保留原身份/网络/挂载）
r7_container_start() {
    r7_require_destructive
    _r7_c="$(r7_container_name)"
    docker start "$_r7_c" >> "${R7_KILL_LOG:-/dev/null}" 2>&1 \
        || r7_fail "docker start ${_r7_c} 失败"
}

# 容器本次启动时刻（RFC3339；重启证据=该值在 kill 前后必须变化）
r7_container_started_at() {
    docker inspect -f '{{.State.StartedAt}}' "$(r7_container_name)" 2>/dev/null
}

# run 终态轮询：finished_at 非空即终态（QUEUED/RUNNING/REPORTING 为活跃集，V12），
# echo 终态 state 串。用法：r7_wait_run_terminal <url_var> <run_uuid> <timeout秒>
r7_wait_run_terminal() {
    _r7_uv="$1"; _r7_run="$2"; _r7_to="$3"
    _r7_t=0
    while [ "$_r7_t" -lt "$_r7_to" ]; do
        _r7_state="$(r7_psql_ro "$_r7_uv" \
            "SELECT state FROM rca_run WHERE id='${_r7_run}' AND finished_at IS NOT NULL" '-At')"
        if [ -n "$_r7_state" ]; then
            echo "$_r7_state"
            return 0
        fi
        sleep 3
        _r7_t=$((_r7_t + 3))
    done
    r7_fail "run ${_r7_run} 终态轮询超时(${_r7_to}s)——仍处活跃集（QUEUED/RUNNING/REPORTING）"
}

# 绑定快照（重启前后身份恒等比对用；行序稳定排序，含三元组+输入引用+schema 冻结件）
# 用法：r7_binding_snapshot <url_var> <run_uuid> <out_file>
r7_binding_snapshot() {
    _r7_uv="$1"; _r7_run="$2"; _r7_out="$3"
    r7_psql_ro "$_r7_uv" \
        "SELECT task_id||'|'||round_id||'|'||task_key||'|'||role_id||'|'||role_version||'|'||
                role_digest||'|'||coalesce(release_digest,'')||'|'||
                md5(input_refs::text)||'|'||md5(expected_output_schema::text)
         FROM rca_task_execution_binding WHERE run_id='${_r7_run}' ORDER BY task_id" \
        '-At' > "$_r7_out"
}

# 注入 service → rca_run 行关联（原样 incident_key 命中；见 AM6 惯例 i.incident_key=原样键）
# 用法：r7_run_id_for_incident <url_var> <incident_key_raw>；echo run uuid（无则空）
r7_run_id_for_incident() {
    _r7_uv="$1"; _r7_key="$2"
    r7_psql_ro "$_r7_uv" \
        "SELECT r.id FROM rca_run r JOIN incident i ON i.id=r.incident_id
         WHERE i.incident_key='${_r7_key}' ORDER BY r.created_at DESC LIMIT 1" '-At'
}

# scenario-results.json 行（唯一允许状态面：PASS/FAIL/BLOCKED_EXTERNAL）
r7_scenario_result() {
    # 用法：r7_scenario_result <runs_dir> <scenario_id> <name> <fidelity> <status>
    _r7_runs="$1"; _r7_sid="$2"; _r7_name="$3"; _r7_fid="$4"; _r7_status="$5"
    case "$_r7_status" in
        PASS|FAIL|BLOCKED_EXTERNAL) ;;
        *) r7_fail "非法场景状态: $_r7_status（SKIP/NOT_RUN 不入表=总体失败）" ;;
    esac
    printf '{"scenario":"%s","name":"%s","fidelity":"%s","status":"%s","recorded_at":"%s"}\n' \
        "$_r7_sid" "$_r7_name" "$_r7_fid" "$_r7_status" "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
        >> "$_r7_runs/scenario-results.json"
}

# 资源账三连（执行前/峰值/执行后各记一次）
r7_resource_snapshot() {
    _r7_runs="$1"; _r7_tag="$2"
    {
        printf '# %s %s\n' "$(date -u +%Y-%m-%dT%H:%M:%SZ)" "$_r7_tag"
        df -h / | r7_redact
        free -m 2>/dev/null || true
        docker stats --no-stream --format '{{.Name}} {{.CPUPerc}} {{.MemUsage}}' 2>/dev/null || true
    } >> "$_r7_runs/resource.log" 2>&1 || true
}

# control-app 日志取证（自 T0 起，脱敏落盘；并回显"账本 STARTED 写失败"计数供诊断）
# 用法：r7_dump_container_logs <since_utc> <out_file>
r7_dump_container_logs() {
    _r7_since="$1"; _r7_out="$2"
    docker logs "$(r7_container_name)" --since "$_r7_since" 2>&1 | r7_redact > "$_r7_out" || true
    grep -c '账本 STARTED 写失败' "$_r7_out" 2>/dev/null || true
}
