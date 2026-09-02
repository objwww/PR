#!/usr/bin/env bash
# ============================================================================
# e2e-m3.sh —— M3 G2 硬门核心集 L6 整栈故障注入（compose 真栈，195；
#   docs/M3-技术方案.md §11 L6 表 + G2 硬门表，逐条回指 §4.1~§4.11）
#
# G2 映射：H1=E2E-41/54（隐藏重试防线）；H2=E2E-45（长 Retry-After 不占死 Worker）；
#   H3=E2E-48（HALF_OPEN 并发只放一发）；H4=E2E-49（STARTED 写失败零触网）；
#   H5=E2E-50（远端成功终态未落账 → UNKNOWN 不伪造 OK）；H6=E2E-51（fallback
#   checkpoint 崩溃后不重复烧模型）；H7=E2E-56/61（密钥/私仓内容零泄漏）；
#   H8=E2E-60（三层重试严格上界）；H9=E2E-46（同域故障不放大不切备）。
#   BT 演示别名承载：BT-M3-01/03→E2E-42；BT-M3-02→E2E-70。
#
# 用法：
#   bash e2e-m3.sh E2E-41          # 单用例
#   bash e2e-m3.sh all             # 13 条全量（配置形态不符者记 [SKIP]，见下）
# 证据：smoke-evidence/e2e-<ts>/<case>/（journal 快照 + SQL 摘录 + summary.txt）。
# 复原：trap m3_cleanup EXIT——摘全部 stub 运行时映射、兜底拉起 postgres/control、
#   杀残留表锁会话；各用例内另有就地复原。本脚本不新增任何 compose 旋钮透传。
#
# 前置：deploy/.env 已接入；stub GitHub + stub 模型双模式（否则整脚本拒跑）。
# 路由配置形态（读运行中 control 容器 env 判定，改 .env 后必须 docker compose
#   up -d control-app 重建才生效）：
#   single        不设 AGENT_MODEL_FALLBACK（当前 195 全 stub 窗口默认形态）
#   dual-inherit  仅设 AGENT_MODEL_FALLBACK=<非主模型名>（端点/密钥 C-2 继承主侧）
#   dual-distinct 再设 OPENAI_COMPAT_BASE_URL_FALLBACK=http://github-stub:8080/fallback
#                 （备路由端点独立 path 前缀，WireMock urlPath 精确区分主备；
#                  静态 model-chat-completions 映射 ".*/chat/completions" 天然覆盖
#                  /fallback 前缀，备侧成功响应零新增映射）
#   形态需求：E2E-42/51/60=dual-distinct；E2E-46=dual-inherit；E2E-45/48=single；
#   其余形态无关。.env 两档增补（含密钥值）由执行方在 195 操作；形态不符一律
#   [SKIP] 并打印所需键名，不伪造通过。
#
# 耗时提示：E2E-49/50/51 恢复段含租约回收等待（默认 max-lease 600s；演练窗
#   合法三元组 APP_WORKER_MAXLEASESECONDS=60 + APP_MODEL_GATEWAY_TOTALDEADLINEMS=30000
#   + APP_MODEL_PERCALLTIMEOUTMS=20000 可压缩，本脚本不改旋钮，DP-28 纪律）。
#
# PR 号段（与 e2e-m2.sh 20000+、smoke-test.sh 100~899 错开）：
#   41→41000+，42→42000+，45→45000+/45500+，46→46000+，48→48000+（连号 50 个），
#   49→49000+，50→50000+，51→51000+，54→54000+，56→56000+，60→60000+，
#   61→61000+/61500+，70→70000+/70500+。
#
# 诚实清单（无法纯脚本自动化/语义口径声明，执行方须知）：
#   1) E2E-50 的 SIGKILL 落点是「HTTP 完成前/后、终态更新前」等价类（45s stub
#      延迟放大在途窗，STARTED 悬挂 + Recovery UNKNOWN 语义对该类内任意落点等价，
#      同 e2e-m2.sh W4/W5 先例）；E2E-51 的 kill 落点为「checkpoint 提交后，T2 前/后」
#      等价类（两落点期望同为重启零新增模型调用）。
#   2) E2E-49 的确定性注入 = 后台 psql 持 model_call_ledger 表锁（EXCLUSIVE，阻塞
#      INSERT 的 ROW EXCLUSIVE）→ pg_stat_activity 见证 INSERT 等待 → docker stop
#      postgres：字面达成「PG 在 STARTED INSERT 处不可用」。恢复段含 control 重启
#      （连接池/工作线程干净复位）+ 租约回收等待。
#   3) E2E-50 的 Recovery 等待用「UPDATE started_at 回拨 1h（超极级权限，仅拨锚定
#      行 id）」压缩：app.model.ledger.recovery-after-seconds（默认 240s）未接线
#      compose 透传，本任务纪律不新增；真实路径（等 240s+周期扫描 ≤60s）与本手法
#      走同一条件更新 SQL，语义等价，不伪造 OK。
#   4) E2E-45 仅 single 形态成立：双路由下 A6 模型级 429 允许同域 fallback（裁定
#      C-2 唯一放行族），不会再 Defer。E2E-48 仅 single 形态：双路由是两熔断器各
#      放一探针，超出「主端点恰好一探针」断言口径。
#   5) E2E-45「PR2 先于 PR1 完成」断言依赖 stub 模式单 Run 收敛（实测 ~60s 内）
#      显著快于 Defer 窗口 120s；环境异常缓慢时该断言假性 FAIL——此时查 worker
#      健康，不得放宽断言。
#   6) E2E-48 熔断阈值 3/冷却 60s 为代码默认（compose 未透传 circuit 旋钮，本任务
#      只准加 fallback 三行透传）；「HALF_OPEN 恰好一探针」落地为：首个 OPEN_REJECT
#      事件之后的任意两个模型请求间隔 ≥55s（冷却 60s 的量级区分）且至少 1 个探针。
#   7) 密钥扫描口径（E2E-56/61）：DB=pg_dump 全库（事件/账本/全文本列一网打尽）、
#      control/publisher 全量日志、stub journal 请求体、CAS 卷（容器内用自身 env
#      比对，密钥不出容器）。stub journal 的请求头合法持有 Authorization: Bearer
#      ——stub 替身即「供应商侧」，必然见到密钥，该面由 DP-26/AFT-28 守；泄漏防线
#      断言的是「密钥不得被我方持久化面再记录」，故 journal 按体扫描不按头。
#      扫描命中计数是唯一落证据的内容；密钥本体经 grep -f - stdin 传入，不进
#      argv、不写任何证据文件。
#   8) E2E-60「理论上限」口径（附录 B + §4.4）：单次 complete()（=单 invocation）
#      ≤ max-physical-calls-per-step(6)；attempt 重跑各持新预算，三层总上界 =
#      6 × max_attempts(3) = 18；配合 journal==账本（无隐藏重试）+ Run/Step 明确
#      终态 + 零悬挂 STARTED 构成 G2-H8 完整断言集。
#   9) 全部为合成故障演练（stub 注入），不冒充真实平台验证（§11 总声明）；
#      真实百炼回归需用户逐次批准，不在本脚本范围。
# ============================================================================
set -uo pipefail
cd "$(dirname "$0")"

CASE="${1:-}"
usage() {
    echo "用法：bash e2e-m3.sh <E2E-41|E2E-42|E2E-45|E2E-46|E2E-48|E2E-49|E2E-50|E2E-51|E2E-54|E2E-56|E2E-60|E2E-61|E2E-70|all>" >&2
    exit 2
}
[ -n "$CASE" ] || usage

[ -f .env ] || { echo "缺 .env（见 README）"; exit 1; }
set -a; . ./.env; set +a

EVIDENCE="smoke-evidence/e2e-$(date +%Y%m%d-%H%M%S)"
mkdir -p "$EVIDENCE"
export M2_EVIDENCE="$EVIDENCE"
. ./m2-lib.sh
. ./m3-lib.sh

PASS=0; FAIL=0
ok()  { PASS=$((PASS + 1)); echo "  [PASS] $*" | tee -a "$SUMMARY"; }
bad() { FAIL=$((FAIL + 1)); echo "  [FAIL] $*" | tee -a "$SUMMARY"; }
note() { echo "  [NOTE] $*" | tee -a "$SUMMARY"; }
skip() { echo "  [SKIP] $*" | tee -a "$SUMMARY"; }
assert_eq() { if [ "$2" = "$3" ]; then ok "$1（=$2）"; else bad "$1：实际=[$2] 期望=[$3]"; fi }

begin_case() { # <case-id> <标题>：每用例独立证据子目录与 summary（同 e2e-m2 惯例）
    CASE_DIR="$EVIDENCE/$1"
    mkdir -p "$CASE_DIR"
    M2_EVIDENCE="$CASE_DIR"
    SUMMARY="$CASE_DIR/summary.txt"
    : > "$SUMMARY"
    echo "== $1 $2 ==" | tee -a "$SUMMARY"
}

end_case() { echo "-- $1 累计：PASS=$PASS FAIL=$FAIL" | tee -a "$SUMMARY"; }

# ---------------------------------------------------------------- 共享助手
# work_item / step join 片段（PR 圈定；同 e2e-m2.sh）
wi_where() { echo "from work_item wi join run_step rs on rs.id=wi.step_id join review_run rr on rr.id=wi.review_run_id join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.repository_full_name='$M2_REPO' and s.pr_number=$1"; }
m3_step_where() { echo "from run_step rs join review_run rr on rr.id=rs.review_run_id join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.repository_full_name='$M2_REPO' and s.pr_number=$1"; }
m3_run_where() { echo "from review_run rr join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.repository_full_name='$M2_REPO' and s.pr_number=$1"; }

# 账本 join 片段与计数（$2=追加条件，须自带 and）
m3_ledger_from() { echo "from model_call_ledger ml join review_run rr on rr.id=ml.review_run_id join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.repository_full_name='$M2_REPO' and s.pr_number=$1"; }
m3_ledger_count() { m2_psql "select count(*) $(m3_ledger_from "$1") ${2:-}" | tr -d '[:space:]'; }

# 运行中 control 容器 env 才是生效配置（.env 改动须重建容器后才真）
m3_container_env() { docker compose exec -T control-app printenv "$1" 2>/dev/null | tr -d '\r'; }

m3_route_mode() {
    local fb fburl
    fb=$(m3_container_env AGENT_MODEL_FALLBACK)
    if [ -z "$fb" ]; then echo single; return; fi
    fburl=$(m3_container_env OPENAI_COMPAT_BASE_URL_FALLBACK)
    if [ -n "$fburl" ]; then echo dual-distinct; else echo dual-inherit; fi
}

m3_need_mode() { # <case> <want>：形态不符记 [SKIP] + 打印所需 .env 键名清单
    local mode hint
    mode=$(m3_route_mode)
    [ "$mode" = "$2" ] && { ok "$1 路由形态 [$mode] 满足前置"; return 0; }
    case "$2" in
        dual-distinct) hint="需 .env 端点覆盖档并 docker compose up -d control-app：AGENT_MODEL_FALLBACK=<非主模型名>、OPENAI_COMPAT_BASE_URL_FALLBACK=http://github-stub:8080/fallback、AGENT_MODEL_API_KEY_FALLBACK=（留空=C-2 继承主侧密钥）" ;;
        dual-inherit)  hint="需 .env 继承档并重建 control：仅设 AGENT_MODEL_FALLBACK=<非主模型名>（端点/密钥 C-2 继承主侧，OPENAI_COMPAT_BASE_URL_FALLBACK 留空）" ;;
        single)        hint="需单路由形态（.env 摘除 AGENT_MODEL_FALLBACK 并重建 control；当前为双路由档）" ;;
        *)             hint="未知形态 $2" ;;
    esac
    skip "$1 需 [$2] 形态，当前 [$mode]——$hint"
    return 1
}

m3_need_dual_stub() { # <case>：dual-distinct 且 fallback 端点指向 github-stub
    m3_need_mode "$1" dual-distinct || return 1
    case "$(m3_container_env OPENAI_COMPAT_BASE_URL_FALLBACK)" in
        *github-stub*) ok "$1 fallback 端点指向 github-stub（注入/取证面可用）" ;;
        *) skip "$1 需 fallback 端点为 github-stub 独立 path 前缀（如 http://github-stub:8080/fallback）；真实端点不做合成故障注入"
           return 1 ;;
    esac
}

# <base-url> → <path>/v1/chat/completions（Spring AI completionsPath 默认含 /v1，
# 195 journal 实证请求路径为 /v1/chat/completions——漏 /v1 则精确匹配恒空）
m3_url_chat_path() {
    local p
    p=$(printf '%s' "$1" | sed -E 's#^[a-zA-Z][a-zA-Z0-9+.-]*://[^/]+##')
    p="${p%/}"
    printf '%s/v1/chat/completions' "$p"
}
m3_primary_chat_path()  { m3_url_chat_path "$(m3_container_env OPENAI_COMPAT_BASE_URL)"; }
m3_fallback_chat_path() { m3_url_chat_path "$(m3_container_env OPENAI_COMPAT_BASE_URL_FALLBACK)"; }

# ---- 密钥扫描（E2E-56/61，§4.11；纪律见文件头诚实清单 7）----
M3_SECRET=""

m3_secret_load() { # 从运行中 control 容器 env 读真实值（与容器实际使用值同源）
    M3_SECRET=$(m3_container_env AGENT_MODEL_API_KEY)
    [ -n "$M3_SECRET" ]
}

m3_secret_hits() { # <file> → stdout 命中行数；模式经 stdin（grep -f -），密钥不进 argv
    local n
    n=$(grep -cF -f - "$1" 2>/dev/null <<<"$M3_SECRET")
    echo "${n:-0}"
}

m3_secret_scan_cas() { # CAS 卷：容器内用自身 env 比对，密钥不出容器，只回计数；失败=ERROR
    local out rc
    out=$(docker compose exec -T control-app sh -c 'grep -rIF "$AGENT_MODEL_API_KEY" /var/cas 2>/dev/null | wc -l' 2>/dev/null)
    rc=$?
    if [ $rc -eq 0 ] && [ -n "$out" ]; then
        printf '%s\n' "$(printf '%s' "$out" | tr -d '[:space:]')"
    else
        echo "ERROR(cas扫描未执行)"
    fi
}

# 扫描面产出失败必须显式 ERROR（断言非 0 即 FAIL），杜绝"扫描没跑成"被零命中假象吞掉
m3_secret_scan_all() { # 输出五行 k=N（计数是唯一出口；被扫原文临时文件即扫即删）
    local t; t=$(mktemp)
    if docker compose exec -T postgres pg_dump -U postgres -d "${POSTGRES_DB:-pr_agent}" > "$t" 2>/dev/null \
            && grep -q "PostgreSQL database dump" "$t"; then
        echo "db=$(m3_secret_hits "$t")"
    else
        echo "db=ERROR(pg_dump失败)"
    fi
    docker compose logs --no-color control-app > "$t" 2>/dev/null
    [ -s "$t" ] && echo "logs-control=$(m3_secret_hits "$t")" || echo "logs-control=ERROR(日志为空)"
    docker compose logs --no-color publisher-app > "$t" 2>/dev/null
    [ -s "$t" ] && echo "logs-publisher=$(m3_secret_hits "$t")" || echo "logs-publisher=ERROR(日志为空)"
    if curl -s --max-time 15 "$(m2_stub_admin)/__admin/requests" | jq -r '.requests[].body // empty' > "$t" 2>/dev/null; then
        echo "journal-bodies=$(m3_secret_hits "$t")"
    else
        echo "journal-bodies=ERROR(journal拉取失败)"
    fi
    rm -f "$t"
    echo "cas=$(m3_secret_scan_cas)"
}

m3_assert_scans_clean() { # <阶段标签> <scan_all 输出>
    local tag="$1" line k v
    while IFS= read -r line; do
        [ -n "$line" ] || continue
        k=${line%%=*}; v=${line##*=}
        assert_eq "[$tag] 密钥扫描 $k 零命中" "$v" "0"
    done <<< "$2"
}

# ---- 栈级操作（带中断兜底标记，trap 用）----
M3_PG_DOWN=0
M3_CONTROL_DOWN=0
M3_LOCK_PID=""

m3_pg_stop()  { docker compose stop postgres > /dev/null 2>&1; M3_PG_DOWN=1; }
m3_pg_start() { docker compose start postgres > /dev/null 2>&1; M3_PG_DOWN=0; }

m3_kill_control() { local st; st=$(m2_kill_app control-app); M3_CONTROL_DOWN=1; printf '%s' "$st"; }
m3_revive_control() {
    docker compose up -d control-app > /dev/null 2>&1
    M3_CONTROL_DOWN=0
    m2_wait_for "control 复活 401" 180 m2_control_alive
}

m3_cleanup() {
    [ -n "$M3_LOCK_PID" ] && kill "$M3_LOCK_PID" 2>/dev/null
    m3_model_fault_once_off; m3_model_fault_off_route; m3_model_leak_off; m3_model_fault_off
    [ "$M3_PG_DOWN" = "1" ] && docker compose start postgres > /dev/null 2>&1
    [ "$M3_CONTROL_DOWN" = "1" ] && docker compose up -d control-app > /dev/null 2>&1
    m2_cleanup
}
trap m3_cleanup EXIT
m2_probe_sync_start   # TB-13：stub 探针联动守护（ensure 语义，常驻不随脚本退出）

# ---------------------------------------------------------------- E2E-41（G2-H1）
# 主模型首次 500、call 级重试成功：WireMock scenario 确定性「首败后成」，
# 断言真实 HTTP 恰 2 次、账本 FAILED+SUCCEEDED 同 invocation 两行、发布恰一次。
e2e_41() {
    begin_case E2E-41 "主模型首调用 500 → call 级重试成功（恰 2 次真实 HTTP）"
    m2_journal_reset
    if ! m3_model_fault_once_on server-500; then
        bad "scenario 映射注入失败——用例无效"; end_case E2E-41; return
    fi
    local PR=$((41000 + RANDOM % 400))
    local HTTP; HTTP=$(m2_send_pr_webhook "$PR" opened "$M2_HEAD_SHA" "$M2_BASE_SHA" e41)
    assert_eq "webhook 受理" "$HTTP" "202"
    m2_wait_sql "PR#$PR outbox CONFIRMED=2（重试成功收敛）" 600 "2" \
        "select count(*) $(m2_pr_sql_where "$PR") and oc.state='CONFIRMED'" || true
    assert_eq "journal chat/completions 恰 2 条（首次 500 + 重试 200）" \
        "$(m2_model_calls "$CASE_DIR/model.json")" "2"
    assert_eq "账本恰 2 行" "$(m3_ledger_count "$PR")" "2"
    assert_eq "FAILED(SERVER_ERROR/http500) 恰 1 行" \
        "$(m3_ledger_count "$PR" "and ml.state='FAILED' and ml.outcome='SERVER_ERROR' and ml.http_status=500")" "1"
    assert_eq "SUCCEEDED 恰 1 行" "$(m3_ledger_count "$PR" "and ml.state='SUCCEEDED'")" "1"
    assert_eq "同一 invocation（重试不另铸逻辑调用）" \
        "$(m2_psql "select count(distinct ml.invocation_id) $(m3_ledger_from "$PR")" | tr -d '[:space:]')" "1"
    assert_eq "call_seq 序列=1,2（两段记账不重号）" \
        "$(m2_psql "select string_agg(ml.call_seq::text, ',' order by ml.call_seq) $(m3_ledger_from "$PR")" | tr -d '[:space:]')" "1,2"
    assert_eq "finding 恰 1 条（无重复落）" "$(m2_pr_finding_count "$PR")" "1"
    m3_model_fault_once_off
    echo "  复原：scenario 故障/成功映射已摘除" | tee -a "$SUMMARY"
    m2_register_pr_resources "$PR" || true
    end_case E2E-41
}

# ---------------------------------------------------------------- E2E-42（BT-M3-01/03）
# dual-distinct：主路由 urlPath 精确 500 持续、备路由（/fallback 前缀）正常 →
# 预算公式 3+1（主 R+1=3 次失败后切备 1 次成功）；账本 lineage + checkpoint 备身份。
e2e_42() {
    begin_case E2E-42 "主路由持续 500 → 自动切备完成评审恰一次（预算公式 3+1）"
    m3_need_dual_stub E2E-42 || { end_case E2E-42; return; }
    local PCHAT FCHAT FBM
    PCHAT=$(m3_primary_chat_path); FCHAT=$(m3_fallback_chat_path)
    FBM=$(m3_container_env AGENT_MODEL_FALLBACK)
    m2_journal_reset
    if ! m3_model_fault_on_route "$PCHAT" server-500; then
        bad "主路由故障映射注入失败——用例无效"; end_case E2E-42; return
    fi
    local PR=$((42000 + RANDOM % 400))
    local HTTP; HTTP=$(m2_send_pr_webhook "$PR" opened "$M2_HEAD_SHA" "$M2_BASE_SHA" e42)
    assert_eq "webhook 受理" "$HTTP" "202"
    m2_wait_sql "PR#$PR outbox CONFIRMED=2（切备后完成评审）" 600 "2" \
        "select count(*) $(m2_pr_sql_where "$PR") and oc.state='CONFIRMED'" || true
    m2_journal_find POST "\"urlPath\":\"$PCHAT\"" "$CASE_DIR/journal-primary.json"
    m2_journal_find POST "\"urlPath\":\"$FCHAT\"" "$CASE_DIR/journal-fallback.json"
    assert_eq "主侧请求恰 3 次（R+1，预算公式）" "$(m2_journal_count "$CASE_DIR/journal-primary.json")" "3"
    assert_eq "备侧请求恰 1 次（切备即成）" "$(m2_journal_count "$CASE_DIR/journal-fallback.json")" "1"
    assert_eq "账本恰 4 行" "$(m3_ledger_count "$PR")" "4"
    assert_eq "PRIMARY FAILED 恰 3 行" \
        "$(m3_ledger_count "$PR" "and ml.route_role='PRIMARY' and ml.state='FAILED'")" "3"
    assert_eq "FALLBACK SUCCEEDED 恰 1 行" \
        "$(m3_ledger_count "$PR" "and ml.route_role='FALLBACK' and ml.state='SUCCEEDED'")" "1"
    assert_eq "fallback_from 恰 1 行且=primary（lineage 完整）" \
        "$(m2_psql "select count(*) $(m3_ledger_from "$PR") and ml.fallback_from='primary'" | tr -d '[:space:]')" "1"
    assert_eq "call_seq 跨路由连续 1,2,3,4" \
        "$(m2_psql "select string_agg(ml.call_seq::text, ',' order by ml.call_seq) $(m3_ledger_from "$PR")" | tr -d '[:space:]')" "1,2,3,4"
    assert_eq "MODEL_FALLBACK_SELECTED 事件恰 1 条" "$(m2_pr_event_count "$PR" MODEL_FALLBACK_SELECTED)" "1"
    assert_eq "checkpoint 身份=备路由（openai-compatible/$FBM/configured）" \
        "$(m2_psql "select count(*) from step_checkpoint sc join run_step rs on rs.id=sc.step_id join review_run rr on rr.id=rs.review_run_id join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.repository_full_name='$M2_REPO' and s.pr_number=$PR and sc.model_identity='openai-compatible/$FBM/configured'" | tr -d '[:space:]')" "1"
    assert_eq "finding 恰 1 条（发布恰一次）" "$(m2_pr_finding_count "$PR")" "1"
    m3_model_fault_off_route
    echo "  复原：主路由故障映射已摘除" | tee -a "$SUMMARY"
    m2_register_pr_resources "$PR" || true
    end_case E2E-42
}

# ---------------------------------------------------------------- E2E-45（G2-H2）
# single：429 + Retry-After=120s（> inline-retry-max-delay 15s）→ durable Defer：
# available_at 拨到 notBefore；Worker 不被占死（PR2 先完成）；故障复原后到期续跑。
e2e_45() {
    begin_case E2E-45 "429 + 超长 Retry-After → durable Defer 不占死 Worker"
    # 双路由下 A6 模型级 429 允许 fallback（唯一放行族），Defer 路径不成立（诚实清单 4）
    m3_need_mode E2E-45 single || { end_case E2E-45; return; }
    m2_journal_reset
    if ! m3_model_fault_on rl-429-header 120; then
        bad "429 故障映射注入失败——用例无效"; end_case E2E-45; return
    fi
    local T0; T0=$(m2_db_epoch)
    local PR1=$((45000 + RANDOM % 400))
    local HTTP; HTTP=$(m2_send_pr_webhook "$PR1" opened "$M2_HEAD_SHA" "$M2_BASE_SHA" e45a)
    assert_eq "PR1 webhook 受理" "$HTTP" "202"
    m2_wait_sql "PR#$PR1 429 已落账（RATE_LIMITED_TRANSIENT, retry_after=120s）" 180 "1" \
        "select count(*) $(m3_ledger_from "$PR1") and ml.outcome='RATE_LIMITED_TRANSIENT' and ml.retry_after_ms=120000" || true
    assert_eq "PR1 work_item RETRY_WAIT（Defer 挂回队列，不占 Worker）" \
        "$(m2_psql "select wi.state $(wi_where "$PR1")" | tr -d '[:space:]')" "RETRY_WAIT"
    local AVAIL
    AVAIL=$(m2_psql "select extract(epoch from wi.available_at)::bigint $(wi_where "$PR1")" | tr -d '[:space:]')
    if [ -n "$AVAIL" ] && [ "$AVAIL" -ge $((T0 + 100)) ]; then
        ok "Defer 拨点 available_at-T0=$((AVAIL - T0))s（≥100s=notBefore 生效，非 30s 线性退避）"
    else
        bad "Defer 拨点异常：available_at=$AVAIL T0=$T0"
    fi
    assert_eq "MODEL_RETRY_DEFERRED 事件恰 1 条" "$(m2_pr_event_count "$PR1" MODEL_RETRY_DEFERRED)" "1"
    # 复原故障映射（旋钮复原），另发 PR2：Worker 空闲必须能先跑完
    m3_model_fault_off
    local PR2=$((45500 + RANDOM % 400))
    local HTTP2; HTTP2=$(m2_send_pr_webhook "$PR2" opened "$M2_HEAD_SHA" "$M2_BASE_SHA" e45b)
    assert_eq "PR2 webhook 受理" "$HTTP2" "202"
    m2_wait_sql "PR#$PR2 outbox CONFIRMED=2（PR1 Defer 期间 Worker 照常）" 600 "2" \
        "select count(*) $(m2_pr_sql_where "$PR2") and oc.state='CONFIRMED'" || true
    local TN; TN=$(m2_db_epoch)
    if [ -n "$AVAIL" ] && [ "$TN" -lt "$AVAIL" ]; then
        ok "PR2 完成时刻早于 PR1 Defer 到期（$TN < $AVAIL）"
        assert_eq "PR2 完成时 PR1 outbox 仍零 CONFIRMED（先后序确定）" "$(m2_pr_confirmed_count "$PR1")" "0"
    else
        bad "PR2 完成过晚（$TN ≥ PR1 available_at=$AVAIL），先后序不可证——环境过慢（诚实清单 5）"
    fi
    # 诚实等满 Defer 窗口（120s）到期续跑，不拨 DB 时钟
    m2_wait_sql "PR#$PR1 Defer 到期续跑收敛 CONFIRMED=2" 300 "2" \
        "select count(*) $(m2_pr_sql_where "$PR1") and oc.state='CONFIRMED'" || true
    assert_eq "PR1 账本恰 2 行（FAILED 429 + 续跑 SUCCEEDED）" "$(m3_ledger_count "$PR1")" "2"
    assert_eq "journal 恰 3 条（PR1 两次 + PR2 一次，无重试风暴）" \
        "$(m2_model_calls "$CASE_DIR/model.json")" "3"
    echo "  复原：429 故障映射已在 PR2 前摘除" | tee -a "$SUMMARY"
    m2_register_pr_resources "$PR1" || true
    m2_register_pr_resources "$PR2" || true
    end_case E2E-45
}

# ---------------------------------------------------------------- E2E-46（G2-H9）
# dual-inherit（端点继承主侧=同故障域）：端点级 500 → ENDPOINT 域同域禁切备，
# 备侧零请求；故障复原后主侧续跑收敛。
e2e_46() {
    begin_case E2E-46 "主备同端点（C-2 继承）端点级 500 → 同故障域不放大、备侧零请求"
    m3_need_mode E2E-46 dual-inherit || { end_case E2E-46; return; }
    m2_journal_reset
    # 全局映射：主备同 path，两端点同吃 500（端点级故障的字面形态）
    if ! m3_model_fault_on server-500; then
        bad "500 故障映射注入失败——用例无效"; end_case E2E-46; return
    fi
    local PR=$((46000 + RANDOM % 400))
    local HTTP; HTTP=$(m2_send_pr_webhook "$PR" opened "$M2_HEAD_SHA" "$M2_BASE_SHA" e46)
    assert_eq "webhook 受理" "$HTTP" "202"
    # 第一 attempt：主侧 R+1=3 次失败落账（A9 同域禁切备 → Defer/RETRY_WAIT）
    m2_wait_sql "PR#$PR 主侧 3 次失败落账" 300 "3" \
        "select count(*) $(m3_ledger_from "$PR") and ml.state='FAILED'" || true
    assert_eq "备侧零请求（账本 FALLBACK 行=0，同故障域不放大）" \
        "$(m3_ledger_count "$PR" "and ml.route_role='FALLBACK'")" "0"
    assert_eq "MODEL_FALLBACK_SELECTED 事件=0" "$(m2_pr_event_count "$PR" MODEL_FALLBACK_SELECTED)" "0"
    # 复原：后续 attempt 主侧成功（熔断冷却后经探针恢复，全程不切备）
    m3_model_fault_off
    m2_wait_sql "PR#$PR outbox CONFIRMED=2（主侧恢复收敛）" 600 "2" \
        "select count(*) $(m2_pr_sql_where "$PR") and oc.state='CONFIRMED'" || true
    assert_eq "全程 FALLBACK 行=0（含恢复段）" "$(m3_ledger_count "$PR" "and ml.route_role='FALLBACK'")" "0"
    assert_eq "账本恰 4 行（3 FAILED + 1 SUCCEEDED，全 PRIMARY）" "$(m3_ledger_count "$PR")" "4"
    assert_eq "SUCCEEDED 行 route_role=PRIMARY" \
        "$(m3_ledger_count "$PR" "and ml.state='SUCCEEDED' and ml.route_role='PRIMARY'")" "1"
    assert_eq "journal==账本=4（每次触网皆有账，无隐藏请求）" \
        "$(m2_model_calls "$CASE_DIR/model.json")" "4"
    echo "  复原：500 故障映射已摘除" | tee -a "$SUMMARY"
    m2_register_pr_resources "$PR" || true
    end_case E2E-46
}

# ---------------------------------------------------------------- E2E-48（G2-H3）
# single：持续 500 + 并发 50 个 Run → CLOSED 烧 3 次开闸 → OPEN 零触网快败
# （OPEN_REJECT 事件流）→ 冷却到期 HALF_OPEN 恰好 1 探针/周期（permit 原子）。
# 熔断为进程内存态：先重启 control 归 CLOSED，隔离前案计数。旋钮未透传（诚实清单 6）。
e2e_48() {
    begin_case E2E-48 "持续 500 + 并发 50 Run → OPEN 快败 + HALF_OPEN 恰好一探针"
    m3_need_mode E2E-48 single || { end_case E2E-48; return; }
    docker compose restart control-app > /dev/null 2>&1
    m2_wait_for "control 重启后 401（熔断器归 CLOSED）" 180 m2_control_alive || true
    if ! m3_model_fault_on server-500; then
        bad "500 故障映射注入失败——用例无效"; end_case E2E-48; return
    fi
    m2_journal_reset
    local BASE=$((48000 + RANDOM % 400)) ACC=0 i
    for i in $(seq 0 49); do
        [ "$(m2_send_pr_webhook $((BASE + i)) opened "$M2_HEAD_SHA" "$M2_BASE_SHA" e48)" = "202" ] && ACC=$((ACC + 1))
    done
    assert_eq "50 个 webhook 全部 202" "$ACC" "50"
    # 全部 50 个 step 终态 FAILED（attempt 预算 3 次耗尽；约 3min，见诚实清单 6）
    m2_wait_sql "50 个 step 全部终态 FAILED（attempts 耗尽）" 600 "50" \
        "select count(*) from run_step rs join review_run rr on rr.id=rs.review_run_id join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.repository_full_name='$M2_REPO' and s.pr_number between $BASE and $((BASE + 49)) and rs.state='FAILED'" || true
    assert_eq "50 个 Run 全部 FAILED 终态（无悬挂）" \
        "$(m2_psql "select count(*) from review_run rr join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.repository_full_name='$M2_REPO' and s.pr_number between $BASE and $((BASE + 49)) and rr.state='FAILED'" | tr -d '[:space:]')" "50"
    local REJ TOPEN
    REJ=$(m2_psql "select count(*) from execution_event ee join review_run rr on rr.id=ee.review_run_id join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.repository_full_name='$M2_REPO' and s.pr_number between $BASE and $((BASE + 49)) and ee.event_type='MODEL_CIRCUIT_OPEN_REJECT'" | tr -d '[:space:]')
    TOPEN=$(m2_psql "select (extract(epoch from min(ee.occurred_at))*1000)::bigint from execution_event ee join review_run rr on rr.id=ee.review_run_id join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.repository_full_name='$M2_REPO' and s.pr_number between $BASE and $((BASE + 49)) and ee.event_type='MODEL_CIRCUIT_OPEN_REJECT'" | tr -d '[:space:]')
    if [ -n "$REJ" ] && [ "$REJ" -ge 40 ]; then
        ok "OPEN 零触网快败事件=$REJ（≥40：绝大多数 attempt 未触网）"
    else
        bad "OPEN_REJECT 事件不足（=$REJ），快败路径未被充分行使"
    fi
    m2_journal_find POST '"urlPathPattern":".*/chat/completions"' "$CASE_DIR/model.json"
    local TOTAL; TOTAL=$(m2_journal_count "$CASE_DIR/model.json")
    if [ -n "$TOPEN" ]; then
        # 探针=熔断 OPEN 之后（TOPEN 为首个 OPEN_REJECT 事件锚）的触网请求。
        # 烧闸的 3 次请求发生在 OPEN 之前，窗口必须严格从 TOPEN 起算——
        # 曾用 TOPEN-2000 把烧闸请求圈进探针集，932ms 间隔系烧闸请求互间隔，误判 FAIL。
        local PROBES MINGAP FIRSTGAP
        PROBES=$(jq --argjson t0 "$TOPEN" '[.requests[] | select(.loggedDate >= $t0)] | length' "$CASE_DIR/model.json")
        MINGAP=$(jq --argjson t0 "$TOPEN" \
            '([.requests[] | select(.loggedDate >= $t0) | .loggedDate] | sort) as $s
             | if ($s | length) < 2 then 999999
               else [range(1; $s | length) as $i | ($s[$i] - $s[$i-1])] | min end' "$CASE_DIR/model.json")
        FIRSTGAP=$(jq --argjson t0 "$TOPEN" \
            '(([.requests[] | select(.loggedDate >= $t0) | .loggedDate] | min) // $t0) - $t0' "$CASE_DIR/model.json")
        [ "$PROBES" -ge 1 ] && ok "HALF_OPEN 探针已行使（OPEN 后请求=$PROBES）" \
            || bad "OPEN 后零探针——HALF_OPEN 路径未被行使"
        if [ "$FIRSTGAP" -ge 55000 ]; then
            ok "首个探针距开闸 ${FIRSTGAP}ms≥55s（冷却 60s 期内零放行）"
        else
            bad "首个探针距开闸仅 ${FIRSTGAP}ms（<55s）——冷却期内提前放行"
        fi
        if [ "$MINGAP" -ge 55000 ]; then
            ok "任意两探针间隔≥55s（${MINGAP}ms；冷却 60s 每周期恰一发，permit 原子）"
        else
            bad "探针间隔过密（${MINGAP}ms < 55s）——同一冷却周期内多发放行"
        fi
    else
        bad "无 OPEN_REJECT 事件时间锚——用例无效"
    fi
    [ "$TOTAL" -ge 4 ] && ok "触网总数=$TOTAL（≥4：3 次开闸烧 + ≥1 探针）" \
        || bad "触网总数异常（=$TOTAL < 4）"
    m3_model_fault_off
    # 收尾（防跨案污染）：50 个演练 PR 在 stub 里仍 open 且无成功评审，
    # PrStateReconciler 会按退避重燃它们（合成 intake + 真实模型调用），
    # 漏进后续用例的 journal 计数窗口（E2E-49「恰 1 次」曾被 482xx 重燃污染）。
    # 就地 CLOSED 掐断重燃源；已生成的 REVIEW_COMPLETE/repair 不触模型，不影响后续断言。
    m2_psql "update pr_subject set state='CLOSED', updated_at=now() where repository_full_name='$M2_REPO' and pr_number between $BASE and $((BASE + 49)) and state='OPEN'" > /dev/null
    ok "收尾：50 个演练 PR 主体已 CLOSED（reconciler 重燃源掐断）"
    echo "  复原：500 故障映射已摘除；50 个 Run 为终态 FAILED；主体已 CLOSED 防重燃（无 drift 资源需登记）" | tee -a "$SUMMARY"
    end_case E2E-48
}

# ---------------------------------------------------------------- E2E-49（G2-H4）
# PG 在 STARTED INSERT 处不可用（表锁阻塞 + 见证 + docker stop，诚实清单 2）
# → 零触网；PG 恢复后正常执行。
e2e_49() {
    begin_case E2E-49 "PG 在账本 STARTED INSERT 处不可用 → 模型 stub 零请求"
    m2_journal_reset
    # 表锁持窗：EXCLUSIVE 锁阻塞 INSERT（ROW EXCLUSIVE 冲突），SELECT 不受影响
    local LOCK_LOG="$CASE_DIR/e49-lock.log"
    docker compose exec -T postgres psql -U postgres -d "${POSTGRES_DB:-pr_agent}" >"$LOCK_LOG" 2>&1 <<'SQL' &
BEGIN;
LOCK TABLE model_call_ledger IN EXCLUSIVE MODE;
SELECT 'LOCK_HELD';
SELECT pg_sleep(600);
ROLLBACK;
SQL
    M3_LOCK_PID=$!
    local i
    for i in $(seq 1 75); do grep -q LOCK_HELD "$LOCK_LOG" 2>/dev/null && break; sleep 0.2; done
    if ! grep -q LOCK_HELD "$LOCK_LOG"; then
        bad "表锁未落地（装备故障）——用例无效"
        kill "$M3_LOCK_PID" 2>/dev/null; M3_LOCK_PID=""
        end_case E2E-49; return
    fi
    ok "账本表锁已持有（INSERT 将被阻塞）"
    local PR=$((49000 + RANDOM % 400))
    local HTTP; HTTP=$(m2_send_pr_webhook "$PR" opened "$M2_HEAD_SHA" "$M2_BASE_SHA" e49)
    assert_eq "webhook 受理" "$HTTP" "202"
    # 确定性见证：worker 已领 item、Gateway 的 INSERT 正挂在锁等待上
    if ! m2_wait_sql "账本 INSERT 已被表锁阻塞（pg_stat_activity 见证锁等待）" 180 "1" \
        "select count(*) from pg_stat_activity where wait_event_type='Lock' and query ilike '%model_call_ledger%'"; then
        bad "未观察到 INSERT 阻塞——用例无效"
        kill "$M3_LOCK_PID" 2>/dev/null; M3_LOCK_PID=""
        end_case E2E-49; return
    fi
    # 注入：PG 在 STARTED INSERT 处不可用（锁会话随 PG 停止同灭）
    m3_pg_stop
    M3_LOCK_PID=""
    sleep 15   # 给 worker 撞上「INSERT 失败 → 零触网」分支的时间
    m3_pg_start
    m2_wait_for "postgres 恢复 healthy" 120 m2_pg_healthy || true
    assert_eq "故障窗内模型 stub 零请求（D5：账写不进去就不打电话）" \
        "$(m2_model_calls "$CASE_DIR/model.json")" "0"
    assert_eq "故障窗内账本零行（INSERT 全部失败，无半截账）" "$(m3_ledger_count "$PR")" "0"
    # 恢复段：control 重启干净复位（连接池/工作线程），租约回收后续跑
    docker compose restart control-app > /dev/null 2>&1
    m2_wait_for "control 复活 401" 180 m2_control_alive || true
    m2_wait_sql "PR#$PR outbox CONFIRMED=2（PG 恢复后正常执行）" 900 "2" \
        "select count(*) $(m2_pr_sql_where "$PR") and oc.state='CONFIRMED'" || true
    assert_eq "恢复后模型调用恰 1 次" "$(m2_model_calls "$CASE_DIR/model.json")" "1"
    assert_eq "恢复后账本恰 1 行 SUCCEEDED" "$(m3_ledger_count "$PR" "and ml.state='SUCCEEDED'")" "1"
    echo "  复原：postgres 已 start、control 已重启、表锁会话已随 PG 停止消亡" | tee -a "$SUMMARY"
    m2_register_pr_resources "$PR" || true
    end_case E2E-49
}

# ---------------------------------------------------------------- E2E-50（G2-H5）
# 模型成功在途（stub 45s 延迟）→ SIGKILL control → 悬挂 STARTED 由 Recovery 标
# UNKNOWN（不伪造 OK）；租约回收后新 attempt 新 invocation 续跑收敛。
e2e_50() {
    begin_case E2E-50 "模型调用在途 SIGKILL → 悬挂 STARTED 标 UNKNOWN（不伪造 OK）"
    m2_journal_reset
    m2_model_delay_on 45000   # 放大「STARTED 已落库、HTTP 在途」窗口（诚实清单 1）
    local PR=$((50000 + RANDOM % 400))
    local HTTP; HTTP=$(m2_send_pr_webhook "$PR" opened "$M2_HEAD_SHA" "$M2_BASE_SHA" e50)
    assert_eq "webhook 受理" "$HTTP" "202"
    # 0.5s 密轮询命中 STARTED 窗口并锚定行 id（拨时钟只拨该行，避开新 attempt 在途行）
    local LID="" deadline=$((SECONDS + 300))
    while [ $SECONDS -lt $deadline ]; do
        LID=$(m2_psql "select ml.id $(m3_ledger_from "$PR") and ml.state='STARTED' limit 1" 2>/dev/null | tr -d '[:space:]')
        [ -n "$LID" ] && break
        sleep 0.5
    done
    if [ -z "$LID" ]; then
        bad "未捕获 STARTED 窗口——用例无效"
        m2_model_delay_off; end_case E2E-50; return
    fi
    ok "STARTED 行落库（在途窗口开启，行 id 已锚定）"
    local ST; ST=$(m3_kill_control)
    [ "$ST" != "running" ] && ok "control 已 SIGKILL（State=$ST）" || bad "control 仍在运行（$ST）"
    m2_model_delay_off
    m3_revive_control || true
    # Recovery 等待压缩（诚实清单 3）：拨锚定行 started_at 回 1h → 超龄 →
    # 下轮周期扫描（≤60s，启动已扫）标 UNKNOWN；真实 240s 路径同一条件更新
    m2_psql "update model_call_ledger set started_at = now() - interval '3600 seconds' where id='$LID' and state='STARTED'" > /dev/null
    m2_wait_sql "超龄 STARTED 标 UNKNOWN" 120 "UNKNOWN" \
        "select state from model_call_ledger where id='$LID'" || true
    m2_wait_sql "PR#$PR outbox CONFIRMED=2（租约回收后续跑收敛）" 900 "2" \
        "select count(*) $(m2_pr_sql_where "$PR") and oc.state='CONFIRMED'" || true
    assert_eq "老行终态=UNKNOWN（永不再改写，不伪造 OK）" \
        "$(m2_psql "select state from model_call_ledger where id='$LID'" | tr -d '[:space:]')" "UNKNOWN"
    assert_eq "该 Run 账本恰 2 行（UNKNOWN + 新 invocation SUCCEEDED）" "$(m3_ledger_count "$PR")" "2"
    assert_eq "SUCCEEDED 恰 1 行（新 attempt 新 invocation，不重放原 invocation）" \
        "$(m3_ledger_count "$PR" "and ml.state='SUCCEEDED'")" "1"
    assert_eq "模型计数=2（在途悬挂 1 + 续跑 1，无第三次）" "$(m2_model_calls "$CASE_DIR/model.json")" "2"
    assert_eq "finding 恰 1 条" "$(m2_pr_finding_count "$PR")" "1"
    echo "  复原：模型延迟映射已摘除、control 已复活" | tee -a "$SUMMARY"
    m2_register_pr_resources "$PR" || true
    end_case E2E-50
}

# ---------------------------------------------------------------- E2E-51（G2-H6）
# dual-distinct：主侧 500 → 备侧成功并提交 checkpoint → SIGKILL → 重启复用
# checkpoint（§4.7 规则 3：备路由仍在配置且契约未变）→ 零新增模型调用。
e2e_51() {
    begin_case E2E-51 "fallback checkpoint 后 SIGKILL → 重启零新增模型调用"
    m3_need_dual_stub E2E-51 || { end_case E2E-51; return; }
    # 熔断器是进程内存态：前案（如 E2E-42）主侧三连烧会把主路由留在 OPEN，
    # 导致本案主侧 OPEN_REJECT 快败、零触网直切备（曾因此 JP=0 误判 FAIL）。
    # 重启归 CLOSED，隔离前案熔断状态（同 E2E-48 口径）。
    docker compose restart control-app > /dev/null 2>&1
    m2_wait_for "control 重启后 401（熔断器归 CLOSED）" 180 m2_control_alive || true
    local PCHAT FCHAT FBM
    PCHAT=$(m3_primary_chat_path); FCHAT=$(m3_fallback_chat_path)
    FBM=$(m3_container_env AGENT_MODEL_FALLBACK)
    m2_journal_reset
    if ! m3_model_fault_on_route "$PCHAT" server-500; then
        bad "主路由故障映射注入失败——用例无效"; end_case E2E-51; return
    fi
    local PR=$((51000 + RANDOM % 400))
    local HTTP; HTTP=$(m2_send_pr_webhook "$PR" opened "$M2_HEAD_SHA" "$M2_BASE_SHA" e51)
    assert_eq "webhook 受理" "$HTTP" "202"
    # checkpoint 落库 = fallback 已成功且 checkpoint 事务已提交（kill 窗口开启）
    m2_wait_sql "checkpoint 落库（fallback 成功已提交）" 300 "1" \
        "select count(*) from step_checkpoint sc join run_step rs on rs.id=sc.step_id join review_run rr on rr.id=rs.review_run_id join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.repository_full_name='$M2_REPO' and s.pr_number=$PR" || true
    m2_journal_find POST "\"urlPath\":\"$PCHAT\"" "$CASE_DIR/journal-primary-pre.json"
    m2_journal_find POST "\"urlPath\":\"$FCHAT\"" "$CASE_DIR/journal-fallback-pre.json"
    local JP JF J0
    JP=$(m2_journal_count "$CASE_DIR/journal-primary-pre.json")
    JF=$(m2_journal_count "$CASE_DIR/journal-fallback-pre.json")
    assert_eq "崩溃前主侧恰 3 次" "$JP" "3"
    assert_eq "崩溃前备侧恰 1 次" "$JF" "1"
    J0=$((JP + JF))
    assert_eq "崩溃前 FALLBACK SUCCEEDED 恰 1 行" \
        "$(m3_ledger_count "$PR" "and ml.route_role='FALLBACK' and ml.state='SUCCEEDED'")" "1"
    assert_eq "checkpoint 身份=备路由（openai-compatible/$FBM/configured）" \
        "$(m2_psql "select count(*) from step_checkpoint sc join run_step rs on rs.id=sc.step_id join review_run rr on rr.id=rs.review_run_id join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.repository_full_name='$M2_REPO' and s.pr_number=$PR and sc.model_identity='openai-compatible/$FBM/configured'" | tr -d '[:space:]')" "1"
    local ST; ST=$(m3_kill_control)
    [ "$ST" != "running" ] && ok "control 已 SIGKILL（State=$ST）" || bad "control 仍在运行（$ST）"
    # 故障映射保持在挂（stub 未动）：若恢复路径错误重调主侧，必留 500 痕迹
    m3_revive_control || true
    m2_wait_sql "PR#$PR outbox CONFIRMED=2（checkpoint 续跑收敛）" 900 "2" \
        "select count(*) $(m2_pr_sql_where "$PR") and oc.state='CONFIRMED'" || true
    m2_journal_find POST '"urlPathPattern":".*/chat/completions"' "$CASE_DIR/model-post.json"
    assert_eq "重启后模型计数不变（=$J0，checkpoint 复用零新增）" \
        "$(m2_journal_count "$CASE_DIR/model-post.json")" "$J0"
    assert_eq "账本行数不变（恰 4 行，无新 invocation）" "$(m3_ledger_count "$PR")" "4"
    assert_eq "checkpoint 恰 1 行（无重复写）" "$(m2_pr_checkpoint_count "$PR")" "1"
    assert_eq "finding 恰 1 条" "$(m2_pr_finding_count "$PR")" "1"
    m3_model_fault_off_route
    echo "  复原：主路由故障映射已摘除、control 已复活" | tee -a "$SUMMARY"
    m2_register_pr_resources "$PR" || true
    end_case E2E-51
}

# ---------------------------------------------------------------- E2E-54（G2-H1）
# 一次正常评审：journal chat/completions == 账本物理调用数 == 1（隐藏重试防线，
# Spring AI 1.0 retry.max-attempts=1 + 手工 RetryTemplate(1) 双保险的部署面复核）。
e2e_54() {
    begin_case E2E-54 "正常评审：journal == 账本物理调用数（隐藏重试防线）"
    m2_journal_reset
    local PR=$((54000 + RANDOM % 400))
    if m2_run_pr_e2e "$PR" e54 600; then ok "评审闭环 CONFIRMED=2"; else bad "评审未收敛"; fi
    local J L
    J=$(m2_model_calls "$CASE_DIR/model.json")
    L=$(m3_ledger_count "$PR")
    assert_eq "journal chat/completions 恰 1 条" "$J" "1"
    assert_eq "账本物理调用恰 1 行（SUCCEEDED）" "$L" "1"
    assert_eq "journal == 账本（无 journal 多于账本的隐藏重试）" "$J" "$L"
    end_case E2E-54
}

# ---------------------------------------------------------------- E2E-56（G2-H7a）
# 错误体嵌入真实密钥原文（Authorization: Bearer <key> 形态）→ Run 失败后面向
# 全栈扫描：DB/日志/事件（随 pg_dump）/journal 请求体/CAS 零命中（§4.11）。
e2e_56() {
    begin_case E2E-56 "错误体含密钥原文 → 账本/日志/事件/CAS 脱敏零命中"
    if ! m3_secret_load; then
        bad "control 容器读不到 AGENT_MODEL_API_KEY——用例无效"; end_case E2E-56; return
    fi
    m2_journal_reset
    if ! m3_model_leak_on "$M3_SECRET"; then
        bad "泄漏探针映射注入失败——用例无效"; end_case E2E-56; return
    fi
    local PR=$((56000 + RANDOM % 400))
    local HTTP; HTTP=$(m2_send_pr_webhook "$PR" opened "$M2_HEAD_SHA" "$M2_BASE_SHA" e56)
    assert_eq "webhook 受理" "$HTTP" "202"
    m2_wait_sql "泄漏故障已落账（FAILED + sanitized_message 非空）" 180 "t" \
        "select count(*) >= 1 $(m3_ledger_from "$PR") and ml.state='FAILED' and ml.sanitized_message is not null" || true
    local SCANS; SCANS=$(m3_secret_scan_all)
    echo "$SCANS" | tr '\n' ' ' | sed 's/^/  [扫描-失败面] /' >> "$SUMMARY"
    m3_assert_scans_clean "失败面" "$SCANS"
    # 复原并等收敛，再扫一次（覆盖恢复路径的日志/账本）
    m3_model_leak_off
    m2_wait_sql "PR#$PR 故障复原后收敛 CONFIRMED=2" 900 "2" \
        "select count(*) $(m2_pr_sql_where "$PR") and oc.state='CONFIRMED'" || true
    SCANS=$(m3_secret_scan_all)
    echo "$SCANS" | tr '\n' ' ' | sed 's/^/  [扫描-恢复后] /' >> "$SUMMARY"
    m3_assert_scans_clean "恢复后" "$SCANS"
    echo "  复原：泄漏探针映射已摘除（注入期间未做任何 mappings 快照取证，防密钥落证据）" | tee -a "$SUMMARY"
    m2_register_pr_resources "$PR" || true
    end_case E2E-56
}

# ---------------------------------------------------------------- E2E-60（G2-H8）
# dual-distinct：主备两端点持续 500 → attempt1 烧满 6 次（3 主 + 3 备，预算公式
# min(B,(R+1)×路由数)=6）→ Defer/熔断快败接管 → attempts(3) 耗尽 → Run/Step
# 明确终态 FAILED；上界口径见诚实清单 8。
e2e_60() {
    begin_case E2E-60 "双路由持续失败 → 三层重试严格上界 + 明确终态"
    m3_need_dual_stub E2E-60 || { end_case E2E-60; return; }
    # 熔断器进程内存态隔离：前案可能把任一路由留在 OPEN，改变触网计数（同 E2E-48/51 口径）
    docker compose restart control-app > /dev/null 2>&1
    m2_wait_for "control 重启后 401（熔断器归 CLOSED）" 180 m2_control_alive || true
    m2_journal_reset
    # 全局映射：".*/chat/completions" 同时覆盖主侧与 /fallback 前缀（双端点同挂）
    if ! m3_model_fault_on server-500; then
        bad "500 故障映射注入失败——用例无效"; end_case E2E-60; return
    fi
    local PR=$((60000 + RANDOM % 400))
    local HTTP; HTTP=$(m2_send_pr_webhook "$PR" opened "$M2_HEAD_SHA" "$M2_BASE_SHA" e60)
    assert_eq "webhook 受理" "$HTTP" "202"
    # §4.4：物理调用预算（6，主备共享）在 attempt1 内 3 主+3 备烧满 → 耗尽即 Fail，
    # Step 直接 FAILED（attempt 预算是进程崩溃级兜底，本场景不行使；对比 E2E-48：
    # OPEN_REJECT 快败不耗物理预算，才走满 3 个 attempt）。
    m2_wait_sql "step 终态 FAILED（物理调用预算 6 耗尽即 Fail）" 600 "FAILED" \
        "select rs.state $(m3_step_where "$PR")" || true
    assert_eq "Run 终态 FAILED" \
        "$(m2_psql "select rr.state $(m3_run_where "$PR")" | tr -d '[:space:]')" "FAILED"
    assert_eq "step_attempt 恰 1 行（预算 attempt1 内耗尽即 Fail，无第二 attempt）" \
        "$(m2_psql "select count(*) from step_attempt sa join run_step rs on rs.id=sa.step_id join review_run rr on rr.id=rs.review_run_id join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.repository_full_name='$M2_REPO' and s.pr_number=$PR" | tr -d '[:space:]')" "1"
    local JTOT LTOT MAXPERINV
    JTOT=$(m2_model_calls "$CASE_DIR/model.json")
    LTOT=$(m3_ledger_count "$PR")
    MAXPERINV=$(m2_psql "select coalesce(max(c),0) from (select count(*) as c $(m3_ledger_from "$PR") group by ml.invocation_id) t" | tr -d '[:space:]')
    assert_eq "单 invocation ≤ 6（max-physical-calls-per-step 硬上界）" \
        "$([ -n "$MAXPERINV" ] && [ "$MAXPERINV" -le 6 ] && echo yes || echo "no($MAXPERINV)")" "yes"
    assert_eq "账本总行数恰 6（3 主 + 3 备，attempt1 内烧满物理预算）" "$LTOT" "6"
    assert_eq "journal == 账本（无隐藏重试/无账外触网）" "$JTOT" "$LTOT"
    assert_eq "账本全部 FAILED（零 SUCCEEDED）" \
        "$(m3_ledger_count "$PR" "and ml.state='FAILED'")" "$LTOT"
    assert_eq "零悬挂 STARTED" "$(m3_ledger_count "$PR" "and ml.state='STARTED'")" "0"
    # 终态后零触网（无僵尸重试）
    local J1 J2
    J1=$(m2_model_calls "$CASE_DIR/model-tail.json")
    sleep 45
    J2=$(m2_model_calls "$CASE_DIR/model-tail2.json")
    assert_eq "终态后 45s 窗口 journal 零增量" "$J2" "$J1"
    m3_model_fault_off
    # 收尾（同 E2E-48 口径）：FAILED 且仍 OPEN 的主体会被 reconciler 重燃（真实模型调用），
    # 污染后续用例的 journal 计数窗口；就地 CLOSED 掐断。
    m2_psql "update pr_subject set state='CLOSED', updated_at=now() where repository_full_name='$M2_REPO' and pr_number=$PR and state='OPEN'" > /dev/null
    ok "收尾：演练 PR 主体已 CLOSED（reconciler 重燃源掐断）"
    echo "  复原：500 故障映射已摘除；Run 为终态 FAILED、主体已 CLOSED 防重燃（无 drift 资源需登记）" | tee -a "$SUMMARY"
    end_case E2E-60
}

# ---------------------------------------------------------------- E2E-61（G2-H7b）
# 正常 + 泄漏故障混合各跑一个 Run 收敛后，全栈密钥扫描兜底（EX-33 部署面）。
e2e_61() {
    begin_case E2E-61 "正常+故障混合跑后全栈密钥扫描（零命中兜底）"
    if ! m3_secret_load; then
        bad "control 容器读不到 AGENT_MODEL_API_KEY——用例无效"; end_case E2E-61; return
    fi
    m2_journal_reset
    local PRA=$((61000 + RANDOM % 400))
    if m2_run_pr_e2e "$PRA" e61a 600; then ok "正常 Run 闭环"; else bad "正常 Run 未收敛"; fi
    local PRB=$((61500 + RANDOM % 400))
    if ! m3_model_leak_on "$M3_SECRET"; then
        bad "泄漏探针映射注入失败——用例无效"; end_case E2E-61; return
    fi
    local HTTP; HTTP=$(m2_send_pr_webhook "$PRB" opened "$M2_HEAD_SHA" "$M2_BASE_SHA" e61b)
    assert_eq "故障 Run webhook 受理" "$HTTP" "202"
    m2_wait_sql "故障 Run 已落 FAILED 账（脱敏路径已行使）" 180 "t" \
        "select count(*) >= 1 $(m3_ledger_from "$PRB") and ml.state='FAILED' and ml.sanitized_message is not null" || true
    m3_model_leak_off
    m2_wait_sql "故障 Run 复原收敛 CONFIRMED=2" 900 "2" \
        "select count(*) $(m2_pr_sql_where "$PRB") and oc.state='CONFIRMED'" || true
    m2_register_pr_resources "$PRB" || true
    # 全栈扫描（口径见诚实清单 7）：DB 全库（含事件/账本全文本列）+ 双容器全量
    # 日志 + stub journal 请求体 + CAS 卷
    local SCANS; SCANS=$(m3_secret_scan_all)
    echo "$SCANS" | tr '\n' ' ' | sed 's/^/  [扫描] /' >> "$SUMMARY"
    m3_assert_scans_clean "全栈" "$SCANS"
    echo "  复原：泄漏探针映射已摘除" | tee -a "$SUMMARY"
    end_case E2E-61
}

# ---------------------------------------------------------------- E2E-70（BT-M3-02）
# 两个 Run（stub 固定 usage 100/50/150）→ 日成本聚合 SQL 与账本逐行合计精确
# 一致；单价缺省（=0）时 cost_micros 全 null、金额聚合为空（§4.8 R-M4：token
# 计数照常，不估算造数）。compose 未透传 APP_MODEL_PRICE_* → 单价恒缺省，口径确定。
e2e_70() {
    begin_case E2E-70 "日成本聚合 == 账本逐行合计（单价缺省 → cost 全 null）"
    local PRA=$((70000 + RANDOM % 300)) PRB=$((70500 + RANDOM % 300))
    if m2_run_pr_e2e "$PRA" e70a 600; then ok "Run A 闭环"; else bad "Run A 未收敛"; fi
    if m2_run_pr_e2e "$PRB" e70b 600; then ok "Run B 闭环"; else bad "Run B 未收敛"; fi
    local RUNS
    RUNS=$(m2_psql "select string_agg(quote_literal(rr.id::text), ',') from review_run rr join pr_revision rev on rev.id=rr.pr_revision_id join pr_subject s on s.id=rev.pr_subject_id where s.repository_full_name='$M2_REPO' and s.pr_number in ($PRA,$PRB)" | tr -d '[:space:]')
    local LW="from model_call_ledger where state='SUCCEEDED' and review_run_id in ($RUNS)"
    assert_eq "两 Run 各恰 1 行 SUCCEEDED（固定 usage 100/50/150）" \
        "$(m2_psql "select count(*) $LW and prompt_tokens=100 and completion_tokens=50 and total_tokens=150 and usage_missing=false" | tr -d '[:space:]')" "2"
    local DIRECT; DIRECT=$(m2_psql "select count(*)||'|'||sum(prompt_tokens)||'|'||sum(completion_tokens)||'|'||sum(total_tokens) $LW" | tr -d '[:space:]')
    assert_eq "逐行合计=2|200|100|300" "$DIRECT" "2|200|100|300"
    local TODAY DAGG
    TODAY=$(m2_psql "select current_date" | tr -d '[:space:]')
    DAGG=$(m2_psql "select string_agg(concat(d::date::text,'|',c,'|',sp,'|',sc,'|',st), ';') from (select date_trunc('day', started_at) d, count(*) c, sum(prompt_tokens) sp, sum(completion_tokens) sc, sum(total_tokens) st $LW group by 1) t" | tr -d '[:space:]')
    assert_eq "日聚合（group by day）== 逐行合计且同日" "$DAGG" "$TODAY|$DIRECT"
    assert_eq "cost_micros 全 null（单价缺省 0，不估算造数）" \
        "$(m2_psql "select count(*) from model_call_ledger where review_run_id in ($RUNS) and cost_micros is null" | tr -d '[:space:]')" "2"
    assert_eq "日金额聚合为空（sum over null = null）" \
        "$(m2_psql "select coalesce(sum(cost_micros)::text,'NULL') from model_call_ledger where review_run_id in ($RUNS)" | tr -d '[:space:]')" "NULL"
    end_case E2E-70
}

# ---------------------------------------------------------------- 调度
m2_stub_github_mode || { echo "本套需 stub GitHub 模式（GITHUB_API_BASE 指 github-stub）"; exit 1; }
m2_stub_model_mode || { echo "本套需 stub 模型模式（OPENAI_COMPAT_BASE_URL 指 github-stub；模型故障注入与 journal 计数取证面）"; exit 1; }
case "$CASE" in
    E2E-41) e2e_41 ;;
    E2E-42) e2e_42 ;;
    E2E-45) e2e_45 ;;
    E2E-46) e2e_46 ;;
    E2E-48) e2e_48 ;;
    E2E-49) e2e_49 ;;
    E2E-50) e2e_50 ;;
    E2E-51) e2e_51 ;;
    E2E-54) e2e_54 ;;
    E2E-56) e2e_56 ;;
    E2E-60) e2e_60 ;;
    E2E-61) e2e_61 ;;
    E2E-70) e2e_70 ;;
    all) e2e_41; e2e_42; e2e_45; e2e_46; e2e_48; e2e_49; e2e_50; e2e_51; e2e_54; e2e_56; e2e_60; e2e_61; e2e_70 ;;
    *) usage ;;
esac

echo "=================================================="
echo "E2E 结果：PASS=$PASS FAIL=$FAIL；证据目录 $EVIDENCE"
[ "$FAIL" -eq 0 ]
