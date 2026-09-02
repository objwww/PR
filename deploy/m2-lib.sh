#!/usr/bin/env bash
# ============================================================================
# m2-lib.sh —— M2 部署门 / E2E / BT 共享函数库（docs/M2-技术方案.md §11）
# 被 smoke-test.sh（DP-15~19）、e2e-m2.sh（E2E-26~34）、bt-m2.sh（BT-M2-01~03）
# source；本库只做"机制"，不断言（ok/bad/assert_* 由各脚本持有）。
#
# 关键设计（写断言前必读，均回指波次1主代码事实）：
#   * CHECK_RUN 探针 = GET /repos/{repo}/commits/{sha}/check-runs（LIST_CHECKS_FOR_SHA，
#     按 external_id=operation_id 匹配）；静态 stub 恒回 {"check_runs": []}，即默认态
#     等价于"远端对象已删"。探针可见性由 probe-sync 机制维护（状态文件 + 守护联动 +
#     原子换装，见下方"探针可见性"区块；TB-13：INC-44 随机 id 后无联动必起
#     drift-repair 风暴；TB-18：换装必须原子，否则空档伪 NOT_FOUND 铸游离修复单）。
#   * REVIEW 探针 = GET /repos/{repo}/pulls/{n}/reviews，按 body 内 marker
#     <!-- ai-review:{operation_id} --> 匹配并现算 sha256(body) 比对（episode 制）。
#   * 修复重建需要新远端 id（uq_pub_resource(resource_type, remote_id)，旧行保留原
#     remote_id 不覆盖，I26）：注入 POST /check-runs 覆盖映射返回新 id，否则
#     ON CONFLICT DO NOTHING 会导致"新 PRESENT 行"断言失败。
#   * 远端 id 号段纪律（TB-12：固定 id 撞唯一索引被静默吸收是结构性复发，非一次性脏数据）：
#     7000001/8000001 = 历史遗留固定 id（已退役，一次性清理后不再产生）；
#     7100000-7149999 = DP-18 修复重建显式 id；7150000-7199999 = 延迟/探针预注册场景 id；
#     7200000-7499999 = 基线创建静态映射随机 id（response-template，compose 已开
#     --local-response-templating）；7500000-7999999 = E2E-28/30B/30C/33 显式重建 id；
#     8100000-8999999 = create-review 随机 id。任何新用例取号先入此段表。
#   * bash 兼容性：195 为 CentOS 7 自带 bash 4.2，空数组展开必须写
#     ${arr[@]+"${arr[@]}"} 形式（set -u 下裸 "${arr[@]}" 在 bash<4.4 报错）。
#
# 使用前置：调用脚本已 cd deploy/、加载 .env（set -a; . ./.env）、并设
#   M2_EVIDENCE=<证据目录>（库函数会把 journal/mapping 快照落进去）。
# ============================================================================
[ -n "${M2_LIB_LOADED:-}" ] && return 0
M2_LIB_LOADED=1

# 库文件所在目录（= deploy/）：probe-sync 稳定状态目录与常驻守护脚本锚点（TB-21）
_M2_LIB_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# stub 固定 PR 元数据（wiremock/mappings/stub.json get-pr-metadata 映射）锚定的
# head/base SHA——webhook 负载必须与其一致（M1 权威读），与 smoke-test.sh DP-05 同源。
M2_REPO="stuborg/stubrepo"
M2_HEAD_SHA="deadbeefcccccccccccccccccccccccccccccccc"
M2_BASE_SHA="cafe0000bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"

# ---------------------------------------------------------------- 基础原语
m2_psql() { timeout 20 docker compose exec -T postgres psql -U postgres -d "${POSTGRES_DB:-pr_agent}" -tAc "$1"; }
m2_psql_db() { timeout 20 docker compose exec -T postgres psql -U postgres -d "$1" -tAc "$2"; }

m2_stub_admin() { echo "http://127.0.0.1:${STUB_ADMIN_PORT:-19090}"; }

m2_stub_github_mode() { case "${GITHUB_API_BASE:-}" in *github-stub*) return 0;; *) return 1;; esac }
m2_stub_model_mode()  { case "${OPENAI_COMPAT_BASE_URL:-}" in *github-stub*) return 0;; *) return 1;; esac }

m2_control_http_code() { curl -s -o /dev/null -w '%{http_code}' -X POST --data-binary '{}' -H 'Content-Type: application/json' -H 'X-GitHub-Event: pull_request' -H 'X-GitHub-Delivery: m2-alive-probe' "http://127.0.0.1:${CONTROL_PORT:-8080}/webhooks/github"; }
m2_control_alive() { [ "$(m2_control_http_code)" = 401 ]; }
m2_publisher_boots() { docker compose logs --no-color publisher-app 2>/dev/null | grep -c "Started PublisherApplication"; }
m2_pg_healthy() { [ "$(docker inspect "$(docker compose ps -q postgres)" -f '{{.State.Health.Status}}' 2>/dev/null)" = "healthy" ]; }

# m2_wait_for <描述> <超时秒> <命令...>：与 smoke-test.sh wait_for 同语义
m2_wait_for() {
    local desc="$1" timeout="$2"; shift 2
    local deadline=$((SECONDS + timeout))
    while [ $SECONDS -lt $deadline ]; do
        if "$@" >/dev/null 2>&1; then echo "  [就绪] $desc"; return 0; fi
        sleep 3
    done
    echo "  [超时] $desc（>${timeout}s）"; return 1
}

# m2_wait_sql <描述> <超时秒> <期望值> <SQL>：轮询至 SQL 的 -tA 输出 == 期望值
m2_wait_sql() {
    local desc="$1" timeout="$2" want="$3" sql="$4" got=""
    local deadline=$((SECONDS + timeout))
    while [ $SECONDS -lt $deadline ]; do
        got=$(m2_psql "$sql" 2>/dev/null | tr -d '[:space:]')
        if [ "$got" = "$want" ]; then echo "  [就绪] $desc"; return 0; fi
        sleep 3
    done
    echo "  [超时] $desc（>${timeout}s，最后=[$got]）"; return 1
}

# ---------------------------------------------------------------- wiremock journal
m2_journal_reset() { curl -s -X DELETE "$(m2_stub_admin)/__admin/requests" > /dev/null; }

# m2_journal_find <method> <匹配子句json片段> <out-file>
#   例：m2_journal_find POST '"url":"/repos/x/check-runs"' f.json
#       m2_journal_find POST '"urlPathPattern":".*/chat/completions"' f.json
m2_journal_find() {
    curl -s -X POST "$(m2_stub_admin)/__admin/requests/find" -H 'Content-Type: application/json' \
        -d "{\"method\":\"$1\",$2}" > "$3"
}

m2_journal_count() { jq '.requests | length' "$1" 2>/dev/null || echo 0; }

# m2_model_calls <out-file>：stub journal 中模型调用（chat/completions）计数
m2_model_calls() {
    m2_journal_find POST '"urlPathPattern":".*/chat/completions"' "$1"
    m2_journal_count "$1"
}

# m2_wait_journal <描述> <超时秒> <method> <urlPathPattern> <期望次数下限>
m2_wait_journal() {
    local desc="$1" timeout="$2" method="$3" pat="$4" want="$5" n=0
    local f="${M2_EVIDENCE:?}/.wait-journal.json"
    local deadline=$((SECONDS + timeout))
    while [ $SECONDS -lt $deadline ]; do
        m2_journal_find "$method" "\"urlPathPattern\":\"$pat\"" "$f"
        n=$(m2_journal_count "$f")
        if [ "$n" -ge "$want" ]; then echo "  [就绪] $desc（=$n）"; return 0; fi
        sleep 2
    done
    echo "  [超时] $desc（>${timeout}s，最后=$n）"; return 1
}

# m2_wait_journal_body <描述> <超时秒> <method> <urlPathPattern> <body包含子串> <期望次数下限>
# 用于"某次写操作的请求体含特定 operation_id"类断言（如 repair 命令的 external_id）
m2_wait_journal_body() {
    local desc="$1" timeout="$2" method="$3" pat="$4" sub="$5" want="$6" n=0
    local f="${M2_EVIDENCE:?}/.wait-journal-body.json"
    local deadline=$((SECONDS + timeout))
    while [ $SECONDS -lt $deadline ]; do
        m2_journal_find "$method" "\"urlPathPattern\":\"$pat\"" "$f"
        n=$(jq --arg s "$sub" '[.requests[].body | select(contains($s))] | length' "$f" 2>/dev/null || echo 0)
        if [ "$n" -ge "$want" ]; then echo "  [就绪] $desc（=$n）"; return 0; fi
        sleep 2
    done
    echo "  [超时] $desc（>${timeout}s，最后=$n）"; return 1
}

# ---------------------------------------------------------------- wiremock 运行时映射注入
# 全部经 admin API 注入（不碰 wiremock/mappings/ 静态文件），id 入 M2_MAP_IDS，
# 由 m2_cleanup 统一 DELETE 复原。
M2_MAP_IDS=()

m2_map_add() { # <mapping-json> → stdout: mapping id（失败为空）
    local id
    id=$(curl -s --max-time 10 -X POST "$(m2_stub_admin)/__admin/mappings" -H 'Content-Type: application/json' -d "$1" | jq -r '.id // empty')
    if [ -n "$id" ]; then M2_MAP_IDS+=("$id"); fi
    echo "$id"
}

m2_map_del() { # <mapping id>
    [ -n "$1" ] && curl -s --max-time 10 -X DELETE "$(m2_stub_admin)/__admin/mappings/$1" > /dev/null
    return 0
}

# ---- CHECK_RUN / REVIEW 探针可见性：probe-sync 常驻守护 + 稳定状态目录（TB-13/TB-18/TB-21/TB-22）----
# 设计（取代旧的 M2_CK_ENTRIES 纯脚本态方案）：
#   * stub 本是"无状态替身"，INC-44 随机 id 后脚本无法预注册探针可见映射 →
#     每个新建/重建对象下轮巡检必判 MISSING → drift-repair 风暴（TB-13）。
#     probe-sync 守护轮询 stub journal 的 POST /check-runs、POST /pulls/{n}/reviews，
#     按 external_id/marker 回查 DB 取 remote_id，即时登记探针可见映射——
#     stub 由此具备"重建即可见"的最小状态性。
#   * TB-21/TB-22 教训（守护必须常驻且带超时）：
#     - 守护曾是脚本生命周期的子进程：trap 退出即停 + m2_cleanup 摘除全部探针映射
#       → 脚本外任何栈运行窗口（尤其重启）= 无防线，历史资源一次性消化风暴（TB-21）。
#     - 案内冻结（TB-22）：curl/psql 全无超时，风暴负载尖峰上一轮扫描卡死即永久
#       失能。现全链路 --max-time/timeout 兜底，单轮有界，下轮自愈。
#     现制：守护由 deploy/probe-sync-daemon.sh 以 nohup 常驻（生命周期独立于任何
#     测试脚本，栈重启不死）；脚本的 m2_probe_sync_start 改为 ensure（心跳检测，
#     死了就地拉起）；脚本退出不再停守护、不再摘探针映射（映射即 stub 世界现状，
#     与 DB 资源共存亡）。
#   * 状态存稳定目录（守护与脚本共享，flock 互斥；不再随证据目录一轮一清）：
#       ${M2_PROBE_SYNC_DIR:-deploy/probe-sync-state}/
#       ck_<sha>.json / rv_<pr>.json = 可见对象数组；*.mapid = 当前映射 id；
#       seen.txt / pending-*.txt = 守护私有 journal 游标（脚本永不写）；
#       heartbeat = 守护心跳（每轮写 epoch 秒，ensure 据此判活）。
#   * 守护只登记"新 POST"，不复活被脚本摘除的对象——m2_check_present_remove
#     模拟"远端删除"的语义保持成立。
#   * 换装一律 PUT /__admin/mappings/{id} 原地更新（TB-18：del/add 空档会让探针
#     撞静态空响应 → 伪 NOT_FOUND → 伪 MISSING + 游离修复单）；映射因栈重启失效
#     时自动回退 POST 重建；守护每轮探测已知 mapid 存活，stub 重启即全量重发布。
#   * compose down/up 后 stub 运行时映射全失 → 用例亦可调 m2_probe_sync_republish_all
#     按状态文件即时恢复（E2E-30 三形态已挂接；守护下一轮也会自动补）。
M2_PS_DIR=""

m2_ps_init() {
    [ -n "$M2_PS_DIR" ] && return 0
    # TB-21：状态目录稳定化（默认 deploy/probe-sync-state，env 可覆盖），
    # 不再随 M2_EVIDENCE 一轮一清；seen.txt 游标归守护私有，此处绝不截断
    M2_PS_DIR="${M2_PROBE_SYNC_DIR:-${_M2_LIB_DIR}/probe-sync-state}"
    mkdir -p "$M2_PS_DIR"
}

# m2_ps_publish <state-file> <mapid-file> <urlPath> <wrap-key或空串>：按状态文件重写映射
m2_ps_publish() {
    local f="$1" mf="$2" url="$3" wrap="$4" mid="" body json rc
    [ -f "$mf" ] && mid=$(cat "$mf")
    body=$(cat "$f" 2>/dev/null || echo '[]')
    if [ -z "$body" ] || [ "$body" = "[]" ]; then
        [ -n "$mid" ] && m2_map_del "$mid"
        rm -f "$mf"
        return 0
    fi
    if [ -n "$wrap" ]; then
        json=$(jq -n --arg url "$url" --arg wrap "$wrap" --argjson arr "$body" \
            '{priority:1, request:{method:"GET", urlPath:$url},
              response:{status:200, jsonBody:{($wrap):$arr}}, metadata:{m2ProbeSync:true}}')
    else
        json=$(jq -n --arg url "$url" --argjson arr "$body" \
            '{priority:1, request:{method:"GET", urlPath:$url},
              response:{status:200, jsonBody:$arr}, metadata:{m2ProbeSync:true}}')
    fi
    if [ -n "$mid" ]; then
        rc=$(curl -s --max-time 10 -o /dev/null -w '%{http_code}' -X PUT "$(m2_stub_admin)/__admin/mappings/$mid" \
            -H 'Content-Type: application/json' -d "$json")
        [ "$rc" = "200" ] && return 0   # 原子换装成功
    fi
    mid=$(m2_map_add "$json")           # 无映射或已失效 → 新建
    if [ -n "$mid" ]; then echo "$mid" > "$mf"; else rm -f "$mf"; fi
}

# m2_ps_ck_upsert <sha> <external_id> <remote_id>：登记/覆盖一个可见 check-run（幂等）
m2_ps_ck_upsert() {
    m2_ps_init
    local sha="$1" eid="$2" rid="$3"
    (
        flock -w 15 9 || exit 1
        local f="${M2_PS_DIR}/ck_${sha}.json" tmp="${M2_PS_DIR}/.ck_${sha}.tmp"
        [ -f "$f" ] || echo '[]' > "$f"
        jq --arg e "$eid" '[.[] | select(.external_id != $e)]' "$f" > "$tmp" && mv "$tmp" "$f"
        jq --arg e "$eid" --arg id "$rid" \
            '(. + [{id:($id|tonumber), external_id:$e, html_url:("http://stub.local/check-runs/"+$id)}]) | .[-200:]' \
            "$f" > "$tmp" && mv "$tmp" "$f"
        m2_ps_publish "$f" "${M2_PS_DIR}/ck_${sha}.mapid" \
            "/repos/${M2_REPO}/commits/${sha}/check-runs" check_runs
    ) 9>"${M2_PS_DIR}/.lock"
}

# m2_ps_ck_remove <sha> <external_id>：摘除（= stub 侧删除该对象；守护不会复活它）
m2_ps_ck_remove() {
    m2_ps_init
    local sha="$1" eid="$2"
    (
        flock -w 15 9 || exit 1
        local f="${M2_PS_DIR}/ck_${sha}.json" tmp="${M2_PS_DIR}/.ck_${sha}.tmp"
        if [ -f "$f" ]; then
            jq --arg e "$eid" '[.[] | select(.external_id != $e)]' "$f" > "$tmp" && mv "$tmp" "$f"
        fi
        m2_ps_publish "$f" "${M2_PS_DIR}/ck_${sha}.mapid" \
            "/repos/${M2_REPO}/commits/${sha}/check-runs" check_runs
    ) 9>"${M2_PS_DIR}/.lock"
}

# m2_ps_rv_upsert <pr> <remote_id> <body>：登记/覆盖一条可见 review（按 marker 幂等）
m2_ps_rv_upsert() {
    m2_ps_init
    local pr="$1" rid="$2" body="$3" opid
    opid=$(printf '%s' "$body" | grep -oE '<!-- ai-review:[a-f0-9-]+ -->' | head -1 \
        | sed 's/<!-- ai-review://; s/ -->//')
    (
        flock -w 15 9 || exit 1
        local f="${M2_PS_DIR}/rv_${pr}.json" tmp="${M2_PS_DIR}/.rv_${pr}.tmp"
        [ -f "$f" ] || echo '[]' > "$f"
        if [ -n "$opid" ]; then
            jq --arg m "$opid" '[.[] | select((.body | contains($m)) | not)]' "$f" > "$tmp" && mv "$tmp" "$f"
        fi
        jq --arg id "$rid" --arg b "$body" \
            '. + [{id:($id|tonumber), html_url:("http://stub.local/reviews/"+$id), body:$b}]' \
            "$f" > "$tmp" && mv "$tmp" "$f"
        m2_ps_publish "$f" "${M2_PS_DIR}/rv_${pr}.mapid" \
            "/repos/${M2_REPO}/pulls/${pr}/reviews" ""
    ) 9>"${M2_PS_DIR}/.lock"
}

# m2_ps_rv_clear <pr>：该 PR 全部 review 不可见（回落静态 []）
m2_ps_rv_clear() {
    m2_ps_init
    local pr="$1"
    (
        flock -w 15 9 || exit 1
        echo '[]' > "${M2_PS_DIR}/rv_${pr}.json"
        m2_ps_publish "${M2_PS_DIR}/rv_${pr}.json" "${M2_PS_DIR}/rv_${pr}.mapid" \
            "/repos/${M2_REPO}/pulls/${pr}/reviews" ""
    ) 9>"${M2_PS_DIR}/.lock"
}

# ---- 兼容包装（签名与旧 M2_CK_*/M2_RV_* 方案一致，调用点零改动）----
m2_check_present_add()          { m2_ps_ck_upsert "$M2_HEAD_SHA" "$1" "$2"; }
m2_check_present_remove()       { m2_ps_ck_remove "$M2_HEAD_SHA" "$1"; }
m2_check_present_remove_quiet() { m2_ps_ck_remove "$M2_HEAD_SHA" "$1"; }
m2_check_present_sync() { # 兼容旧内部助手：按状态文件重发布
    m2_ps_init
    (   flock -w 15 9 || exit 1
        m2_ps_publish "${M2_PS_DIR}/ck_${M2_HEAD_SHA}.json" "${M2_PS_DIR}/ck_${M2_HEAD_SHA}.mapid" \
            "/repos/${M2_REPO}/commits/${M2_HEAD_SHA}/check-runs" check_runs
    ) 9>"${M2_PS_DIR}/.lock"
}
m2_review_present_set()   { m2_ps_rv_upsert "$1" "$2" "$3"; }
m2_review_present_clear() { m2_ps_rv_clear "$1"; }

# ---- probe-sync 常驻守护（TB-21/TB-22）----
# 守护生命周期独立于测试脚本：由 probe-sync-daemon.sh 以 nohup 常驻，
# 脚本侧只做 ensure（心跳新鲜+pid 存活则复用，否则就地拉起）。
m2_probe_sync_start() { # 仅 stub GitHub 模式有意义
    m2_stub_github_mode || return 0
    m2_ps_init
    bash "${_M2_LIB_DIR}/probe-sync-daemon.sh" ensure
}

m2_probe_sync_stop() {
    # TB-21：守护常驻化后脚本退出不再停守护（守护即 stub 世界的"状态性"，随栈共存亡）。
    # 保留本函数仅为兼容既有调用点；显式停守护用 probe-sync-daemon.sh stop。
    return 0
}

# 守护主循环的单轮（由 probe-sync-daemon.sh run 调用；脚本不直接调）
m2_ps_daemon_tick() {
    m2_ps_scan_once || true
    # stub 重启检测：已知 mapid 失效（404）且存在非空状态 → 全量重发布（幂等）
    local mf mid rc f
    mf=$(ls "${M2_PS_DIR}"/*.mapid 2>/dev/null | head -1)
    if [ -n "$mf" ]; then
        mid=$(cat "$mf" 2>/dev/null)
        if [ -n "$mid" ]; then
            rc=$(curl -s --max-time 10 -o /dev/null -w '%{http_code}' "$(m2_stub_admin)/__admin/mappings/$mid")
            [ "$rc" = "404" ] && m2_probe_sync_republish_all
        fi
    else
        for f in "${M2_PS_DIR}"/ck_*.json "${M2_PS_DIR}"/rv_*.json; do
            [ -f "$f" ] || continue
            if [ "$(cat "$f" 2>/dev/null)" != "[]" ]; then m2_probe_sync_republish_all; break; fi
        done
    fi
    date +%s > "${M2_PS_DIR}/.heartbeat.tmp" && mv "${M2_PS_DIR}/.heartbeat.tmp" "${M2_PS_DIR}/heartbeat"
}

m2_ps_scan_once() {
    local admin jf ts eid sha
    admin=$(m2_stub_admin)
    # ---- check-runs：新 POST → pending ----
    jf="${M2_PS_DIR}/.scan-ck.json"
    curl -s --max-time 15 -X POST "$admin/__admin/requests/find" -H 'Content-Type: application/json' \
        -d '{"method":"POST","urlPathPattern":"/repos/[^/]+/[^/]+/check-runs"}' > "$jf" 2>/dev/null
    jq -r '.requests[]? | [(.loggedDate|tostring),
             (.body | fromjson? | .external_id // ""),
             (.body | fromjson? | .head_sha // "")] | @tsv' "$jf" 2>/dev/null \
    | while IFS=$'\t' read -r ts eid sha; do
        [ -n "$eid" ] && [ -n "$sha" ] || continue
        grep -qsF "${ts}:${eid}" "${M2_PS_DIR}/seen.txt" && continue
        echo "${ts}:${eid}" >> "${M2_PS_DIR}/seen.txt"
        printf '%s\t%s\t0\n' "$eid" "$sha" >> "${M2_PS_DIR}/pending-ck.txt"
    done
    # ---- reviews：新 POST → pending（body 大且含换行，落文件）----
    local jf2="${M2_PS_DIR}/.scan-rv.json"
    curl -s --max-time 15 -X POST "$admin/__admin/requests/find" -H 'Content-Type: application/json' \
        -d '{"method":"POST","urlPathPattern":"/repos/[^/]+/[^/]+/pulls/[0-9]+/reviews"}' > "$jf2" 2>/dev/null
    jq -c '.requests[]?' "$jf2" 2>/dev/null | while read -r req; do
        local rts pr opid
        rts=$(printf '%s' "$req" | jq -r '.loggedDate')
        pr=$(printf '%s' "$req" | jq -r '.url' | sed -n 's#.*pulls/\([0-9][0-9]*\)/reviews.*#\1#p')
        opid=$(printf '%s' "$req" | jq -r '(.body | fromjson? | .body // "")' 2>/dev/null \
            | grep -oE '<!-- ai-review:[a-f0-9-]+ -->' | head -1 | sed 's/<!-- ai-review://; s/ -->//')
        [ -n "$pr" ] && [ -n "$opid" ] || continue
        grep -qsF "${rts}:rv:${opid}" "${M2_PS_DIR}/seen.txt" && continue
        echo "${rts}:rv:${opid}" >> "${M2_PS_DIR}/seen.txt"
        printf '%s' "$req" | jq -r '(.body | fromjson? | .body // "")' \
            > "${M2_PS_DIR}/.rv-body-${opid}.txt"
        printf '%s\t%s\t0\n' "$pr" "$opid" >> "${M2_PS_DIR}/pending-rv.txt"
    done
    m2_ps_resolve_ck
    m2_ps_resolve_rv
}

# pending 解析：回查 DB 取 remote_id（行未落库则下轮再试，300 轮≈5min 后丢弃——
# 修复重建退避超窗会有新一轮 POST 重新入队，自愈合）
m2_ps_resolve_ck() {
    local pf="${M2_PS_DIR}/pending-ck.txt"
    [ -f "$pf" ] || return 0
    local keep="${M2_PS_DIR}/.pending-ck.keep" eid sha tries rid
    : > "$keep"
    while IFS=$'\t' read -r eid sha tries; do
        [ -n "$eid" ] || continue
        rid=$(m2_psql "select remote_id from publication_resource where created_by_operation_id='$eid' order by created_at desc limit 1" 2>/dev/null | head -1 | tr -d '[:space:]')
        if [ -n "$rid" ]; then
            m2_ps_ck_upsert "$sha" "$eid" "$rid"
        elif [ "${tries:-0}" -lt 300 ]; then
            printf '%s\t%s\t%s\n' "$eid" "$sha" "$((tries + 1))" >> "$keep"
        fi
    done < "$pf"
    mv "$keep" "$pf"
}

m2_ps_resolve_rv() {
    local pf="${M2_PS_DIR}/pending-rv.txt"
    [ -f "$pf" ] || return 0
    local keep="${M2_PS_DIR}/.pending-rv.keep" pr opid tries rid
    : > "$keep"
    while IFS=$'\t' read -r pr opid tries; do
        [ -n "$opid" ] || continue
        rid=$(m2_psql "select remote_id from publication_resource where created_by_operation_id='$opid' order by created_at desc limit 1" 2>/dev/null | head -1 | tr -d '[:space:]')
        if [ -n "$rid" ] && [ -f "${M2_PS_DIR}/.rv-body-${opid}.txt" ]; then
            m2_ps_rv_upsert "$pr" "$rid" "$(cat "${M2_PS_DIR}/.rv-body-${opid}.txt")"
            rm -f "${M2_PS_DIR}/.rv-body-${opid}.txt"
        elif [ "${tries:-0}" -lt 300 ]; then
            printf '%s\t%s\t%s\n' "$pr" "$opid" "$((tries + 1))" >> "$keep"
        else
            rm -f "${M2_PS_DIR}/.rv-body-${opid}.txt"
        fi
    done < "$pf"
    mv "$keep" "$pf"
}

# compose down/up 后按状态文件恢复全部探针映射（stub 容器重建丢运行时映射）
m2_probe_sync_republish_all() {
    m2_stub_github_mode || return 0
    m2_ps_init
    (
        flock -w 15 9 || exit 1
        local f sha pr
        rm -f "${M2_PS_DIR}"/*.mapid
        for f in "${M2_PS_DIR}"/ck_*.json; do
            [ -f "$f" ] || continue
            sha=$(basename "$f" .json); sha=${sha#ck_}
            m2_ps_publish "$f" "${M2_PS_DIR}/ck_${sha}.mapid" \
                "/repos/${M2_REPO}/commits/${sha}/check-runs" check_runs
        done
        for f in "${M2_PS_DIR}"/rv_*.json; do
            [ -f "$f" ] || continue
            pr=$(basename "$f" .json); pr=${pr#rv_}
            m2_ps_publish "$f" "${M2_PS_DIR}/rv_${pr}.mapid" \
                "/repos/${M2_REPO}/pulls/${pr}/reviews" ""
        done
    ) 9>"${M2_PS_DIR}/.lock"
    echo "  [probe-sync] 栈重启后按状态文件重发布完成"
}

# ---- POST /check-runs 响应覆盖（新远端 id / 固定延迟，二选一互斥）----
M2_POST_CHECK_MAP_ID=""

m2_post_check_override_on() { # <new_remote_id>：修复重建返回新 id（避让 uq_pub_resource）
    m2_post_check_override_off
    M2_POST_CHECK_MAP_ID=$(m2_map_add "$(jq -n --arg id "$1" \
        '{priority:1, request:{method:"POST", urlPathPattern:"/repos/[^/]+/[^/]+/check-runs"},
          response:{status:201, jsonBody:{id:($id|tonumber), html_url:("http://stub.local/check-runs/"+$id), status:"completed"}}}')")
}

m2_post_check_delay_on() { # <毫秒> [返回id=缺省 7150000+RANDOM%50000]：写响应延迟（放大"写已达
    # stub、CONFIRM 前"崩溃窗口，E2E-29/30C）；repair 场景必须给新 id 避让 uq_pub_resource；
    # 缺省 id 每次调用随机（TB-12：固定 7000001 跨轮复发撞库）
    m2_post_check_override_off
    local id="${2:-$((7150000 + RANDOM % 50000))}"
    M2_POST_CHECK_MAP_ID=$(m2_map_add "$(jq -n --argjson ms "$1" --arg id "$id" \
        '{priority:1, request:{method:"POST", urlPathPattern:"/repos/[^/]+/[^/]+/check-runs"},
          response:{status:201, fixedDelayMilliseconds:$ms,
                    jsonBody:{id:($id|tonumber), html_url:("http://stub.local/check-runs/"+$id), status:"completed"}}}')")
}

m2_post_check_override_off() { m2_map_del "$M2_POST_CHECK_MAP_ID"; M2_POST_CHECK_MAP_ID=""; }

# ---- 模型延迟（放大 checkpoint 崩溃窗口，DP-17/E2E-26/BT-01）----
M2_MODEL_DELAY_MAP_ID=""

m2_model_delay_on() { # <毫秒>；响应体与静态 model-chat-completions 映射一致（固定 1 条 finding）
    m2_model_delay_off
    M2_MODEL_DELAY_MAP_ID=$(m2_map_add "$(jq -n --argjson ms "$1" \
        '{priority:1, request:{method:"POST", urlPathPattern:".*/chat/completions"},
          response:{status:200, fixedDelayMilliseconds:$ms,
            headers:{"Content-Type":"application/json"},
            jsonBody:{id:"chatcmpl-stub", object:"chat.completion", created:0, model:"stub",
              choices:[{index:0, finish_reason:"stop",
                message:{role:"assistant",
                  content:"[{\"file\":\"src/Main.java\",\"line\":1,\"existing_code\":\"System.out.println(\\\"hello stub\\\");\",\"rule\":\"smoke-stub\",\"severity\":\"INFO\",\"message\":\"stub 固定发现（DP-05 冒烟）\"}]"}}],
              usage:{prompt_tokens:100, completion_tokens:50, total_tokens:150}}}}')")
}

m2_model_delay_off() { m2_map_del "$M2_MODEL_DELAY_MAP_ID"; M2_MODEL_DELAY_MAP_ID=""; }

# ---- 故障注入（429+Retry-After / 404，priority 0 压过一切探针映射）----
# 名称 → 端点：
#   post-check   写路径（FencedPublicationExecutor，E2E-32A）
#   list-check   check 探针（OutboxRecoveryScanner E2E-32B / DriftReconciler E2E-32C/34）
#   repo         sanity 读（E2E-34 权限撤销模拟）
#   list-reviews review 探针
declare -A M2_FAULT_MAP_IDS=()

m2_fault_on() { # <name> <status> [retry-after秒]
    local name="$1" status="$2" ra="${3:-}" method url json
    case "$name" in
        post-check)   method=POST; url='/repos/[^/]+/[^/]+/check-runs' ;;
        list-check)   method=GET;  url='/repos/[^/]+/[^/]+/commits/[^/]+/check-runs' ;;
        repo)         method=GET;  url='/repos/[^/]+/[^/]+' ;;
        list-reviews) method=GET;  url='/repos/[^/]+/[^/]+/pulls/[0-9]+/reviews' ;;
        *) echo "m2_fault_on: 未知故障点 $name" >&2; return 1 ;;
    esac
    if [ -n "$ra" ]; then
        json=$(jq -n --arg m "$method" --arg u "$url" --arg ra "$ra" --argjson st "$status" \
            '{priority:0, request:{method:$m, urlPathPattern:$u},
              response:{status:$st, headers:{"Retry-After":$ra}, jsonBody:{message:"m2 fault injection"}}}')
    else
        json=$(jq -n --arg m "$method" --arg u "$url" --argjson st "$status" \
            '{priority:0, request:{method:$m, urlPathPattern:$u},
              response:{status:$st, jsonBody:{message:"m2 fault injection"}}}')
    fi
    m2_fault_off "$name"
    M2_FAULT_MAP_IDS[$name]=$(m2_map_add "$json")
}

m2_fault_off() { m2_map_del "${M2_FAULT_MAP_IDS[$1]:-}"; unset "M2_FAULT_MAP_IDS[$1]" 2>/dev/null || true; }

# ---- PR 元数据覆盖（E2E-31：模拟 push 新 commit——权威读的 head sha 换届）----
M2_PR_META_MAP_ID=""

m2_pr_meta_override_on() { # <pr_number> <new_head_sha>
    m2_pr_meta_override_off
    M2_PR_META_MAP_ID=$(m2_map_add "$(jq -n --arg url "/repos/${M2_REPO}/pulls/$1" --arg head "$2" --arg base "$M2_BASE_SHA" \
        --argjson pr "$1" \
        '{priority:1, request:{method:"GET", urlPath:$url},
          response:{status:200, jsonBody:{number:$pr, state:"open", draft:false, merged:false,
            head:{sha:$head, ref:"feature-stub"}, base:{ref:"main", sha:$base},
            updated_at:"2026-09-01T00:00:00Z"}}}')")
}

m2_pr_meta_override_off() { m2_map_del "$M2_PR_META_MAP_ID"; M2_PR_META_MAP_ID=""; }

# ---------------------------------------------------------------- 业务编排原语
# m2_send_pr_webhook <pr> <action> <head_sha> <base_sha> <tag> → stdout http code
m2_send_pr_webhook() {
    local pr="$1" action="$2" head="$3" base="$4" tag="$5" payload sig
    payload="${M2_EVIDENCE:?}/webhook-${tag}.json"
    cat > "$payload" <<JSON
{"action":"$action","number":$pr,
 "installation":{"id":${GITHUB_INSTALLATION_ID:-555000}},
 "repository":{"id":9001,"full_name":"$M2_REPO"},
 "pull_request":{"state":"open","draft":false,"merged":false,
   "head":{"sha":"$head"},"base":{"ref":"main","sha":"$base"}}}
JSON
    sig=$(openssl dgst -sha256 -hmac "${GITHUB_WEBHOOK_SECRET:?}" "$payload" | sed 's/^.* //')
    curl -s -o "${M2_EVIDENCE}/webhook-${tag}-response.json" -w '%{http_code}' \
        -X POST "http://127.0.0.1:${CONTROL_PORT:-8080}/webhooks/github" \
        -H "X-Hub-Signature-256: sha256=$sig" \
        -H "X-GitHub-Event: pull_request" \
        -H "X-GitHub-Delivery: ${tag}-$(date +%s)-${pr}" \
        -H 'Content-Type: application/json' \
        --data-binary @"$payload"
}

# ---- PR 域 SQL（全部按 pr_number 圈定，杜绝跨用例串扰）----
m2_pr_sql_where() { # <pr> → 复用 join 片段（outbox_command 别名 oc）
    echo "from outbox_command oc join review_run rr on rr.id=oc.review_run_id join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.repository_full_name='$M2_REPO' and s.pr_number=$1"
}

m2_pr_confirmed_count() { m2_psql "select count(*) $(m2_pr_sql_where "$1") and oc.state='CONFIRMED'" | tr -d '[:space:]'; }

m2_pr_op() { # <pr> <command_type> [state] → 最新一条 operation_id（空=无）
    local cond=""
    [ -n "${3:-}" ] && cond="and oc.state='$3'"
    m2_psql "select oc.operation_id $(m2_pr_sql_where "$1") and oc.command_type='$2' $cond order by oc.created_at desc limit 1" | tr -d '[:space:]'
}

m2_pr_checkpoint_count() {
    m2_psql "select count(*) from step_checkpoint sc join run_step rs on rs.id=sc.step_id join review_run rr on rr.id=rs.review_run_id join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.repository_full_name='$M2_REPO' and s.pr_number=$1" | tr -d '[:space:]'
}

m2_pr_finding_count() {
    m2_psql "select count(*) from review_finding f join review_run rr on rr.id=f.review_run_id join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.repository_full_name='$M2_REPO' and s.pr_number=$1" | tr -d '[:space:]'
}

m2_pr_run_count() {
    m2_psql "select count(*) from review_run rr join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.repository_full_name='$M2_REPO' and s.pr_number=$1" | tr -d '[:space:]'
}

m2_pr_event_count() { # <pr> <event_type>
    m2_psql "select count(*) from execution_event ee join review_run rr on rr.id=ee.review_run_id join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.repository_full_name='$M2_REPO' and s.pr_number=$1 and ee.event_type='$2'" | tr -d '[:space:]'
}

m2_resource_of_op() { m2_psql "select id from publication_resource where created_by_operation_id='$1'" | tr -d '[:space:]'; }
m2_resource_field() { m2_psql "select coalesce($2::text,'') from publication_resource where id='$1'" | tr -d '[:space:]'; }
m2_request_of_resource() { m2_psql "select id from repair_request where publication_resource_id='$1' order by created_at desc limit 1" | tr -d '[:space:]'; }
m2_request_field() { m2_psql "select coalesce($2::text,'') from repair_request where id='$1'" | tr -d '[:space:]'; }
m2_db_epoch() { m2_psql "select extract(epoch from now())::bigint" | tr -d '[:space:]'; }

m2_force_drift_due() { # <resource_id>：把 next_check_at 拉到 now()（巡检调度加速，不改语义）
    m2_psql "update publication_resource set next_check_at=now() where id='$1'" > /dev/null
}

# m2_register_pr_resources <pr>：为已 CONFIRMED 的 check/review 资源注入"探针可见"
# 映射，防止 DriftReconciler 首轮巡检把静态 stub 的空探针当 MISSING 铸 repair 单
# （见文件头"关键设计"）。仅 stub GitHub 模式下有意义；非 stub 模式 no-op。
m2_register_pr_resources() {
    m2_stub_github_mode || return 0
    local pr="$1" ck_op ck_rid rv_op rv_rid jf body
    ck_op=$(m2_pr_op "$pr" CREATE_CHECK CONFIRMED)
    if [ -n "$ck_op" ]; then
        ck_rid=$(m2_psql "select remote_id from publication_resource where created_by_operation_id='$ck_op'" | tr -d '[:space:]')
        [ -n "$ck_rid" ] && m2_check_present_add "$ck_op" "$ck_rid"
    fi
    rv_op=$(m2_pr_op "$pr" PUBLISH_REVIEW CONFIRMED)
    if [ -n "$rv_op" ]; then
        rv_rid=$(m2_psql "select remote_id from publication_resource where created_by_operation_id='$rv_op'" | tr -d '[:space:]')
        jf="${M2_EVIDENCE:?}/m2-register-${pr}-reviews.json"
        m2_journal_find POST "\"url\":\"/repos/${M2_REPO}/pulls/$pr/reviews\"" "$jf"
        body=$(jq -r '.requests[-1].body | fromjson | .body' "$jf" 2>/dev/null)
        if [ -n "$body" ] && [ "$body" != "null" ] && [ -n "$rv_rid" ]; then
            m2_review_present_set "$pr" "$rv_rid" "$body"
        fi
    fi
}

# m2_run_pr_e2e <pr> <tag> [CONFIRMED超时秒]：webhook → 等 outbox 两条 CONFIRMED
# → 注册探针映射。返回：0=收敛；1=超时/未收敛（http code 落 $M2_EVIDENCE/webhook-<tag>-response.json 旁）
m2_run_pr_e2e() {
    local pr="$1" tag="$2" timeout="${3:-600}" http
    http=$(m2_send_pr_webhook "$pr" opened "$M2_HEAD_SHA" "$M2_BASE_SHA" "$tag")
    echo "  webhook $tag → HTTP $http（PR#$pr）"
    [ "$http" = "202" ] || return 1
    m2_wait_sql "PR#$pr outbox 两条 CONFIRMED" "$timeout" "2" \
        "select count(*) $(m2_pr_sql_where "$pr") and oc.state='CONFIRMED'" || return 1
    m2_register_pr_resources "$pr"
    return 0
}

# ---------------------------------------------------------------- 栈级操作
m2_kill_app() { # <service>：SIGKILL 并确认非 running
    docker compose kill -s SIGKILL "$1" > /dev/null 2>&1
    sleep 2
    docker inspect "$(docker compose ps -q "$1")" -f '{{.State.Status}}' 2>/dev/null || echo missing
}

m2_start_app() { # <service>：拉起并等存活探针（control=401 / publisher=Started 计数+1）
    local svc="$1" before=0
    if [ "$svc" = "publisher-app" ]; then before=$(m2_publisher_boots); fi
    docker compose up -d "$svc" > /dev/null 2>&1
    if [ "$svc" = "control-app" ]; then
        m2_wait_for "control 复活后 401" 180 m2_control_alive
    else
        local target=$((before + 1))
        m2_wait_for "publisher 复活（Started 计数≥$target）" 180 bash -c "[ \$(docker compose logs --no-color publisher-app 2>/dev/null | grep -c 'Started PublisherApplication') -ge $target ]"
    fi
}

m2_stack_ready() { # compose down/up 后的整栈就绪
    m2_wait_for "postgres healthy" 120 m2_pg_healthy || return 1
    m2_wait_for "control 401" 240 m2_control_alive || return 1
    m2_wait_for "publisher Started" 240 bash -c "[ \$(docker compose logs --no-color publisher-app 2>/dev/null | grep -c 'Started PublisherApplication') -ge 1 ]"
}

# ---------------------------------------------------------------- 复原（trap EXIT）
m2_cleanup() {
    local id
    m2_probe_sync_stop   # TB-21：no-op，守护常驻不随脚本退出
    for id in ${M2_MAP_IDS[@]+"${M2_MAP_IDS[@]}"}; do m2_map_del "$id"; done
    M2_MAP_IDS=()
    # TB-21：probe-sync 探针映射（*.mapid 登记的）不再随脚本退出摘除——
    # 映射即 stub 世界现状，须与 DB 资源行同寿命，否则脚本间窗口必起 MISSING 风暴。
    docker unpause "$(docker compose ps -q control-app 2>/dev/null)" > /dev/null 2>&1
    docker unpause "$(docker compose ps -q publisher-app 2>/dev/null)" > /dev/null 2>&1
    docker rm -f e2e27-control2 > /dev/null 2>&1
    return 0
}
