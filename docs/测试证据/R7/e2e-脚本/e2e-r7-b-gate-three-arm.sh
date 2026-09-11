#!/bin/sh
# ============================================================================
# e2e-r7-b-gate-three-arm.sh —— B 门三臂同预算盲评（执行卡 §执行顺序 #10）
#
# 用法：e2e-r7-b-gate-three-arm.sh armB|armC|joint
#   armB   固定三角色臂（legacy 部署形态，primary.enabled=false；确定性 handler 面）
#   armC   主 Agent 动态委派臂（主模式部署形态；真模型）
#   joint  汇总两臂指标 + 盲评封存 + 臂A BLOCKED_EXTERNAL 登记入账
#
# 三臂登记（诚实面）：
#   臂A（单主 Agent 零委派）= BLOCKED_EXTERNAL：零委派需 max_delegation_batches=0
#       旋钮，当前为 DeterministicSupervisor.MAX_DELEGATION_BATCHES=2 编译常量、
#       无运行时旋钮——旋钮化登记为 B 门执行前置债，禁止以臂C 冒充臂A。
#   臂B 固定三角色 = legacy handler 驱动（零模型调用、零 token 消耗）——它对比的
#       是"确定性工程基线"，不是 LLM 三角色；语义偏差在 RUNBOOK/联合报告如实登记。
#   同预算=部署旋钮同一性的操作员证词（R7_ARM_BUDGET_ATTEST），脚本不度量配置本体。
#
# 四案（§七四场景执行面）：同一真实服务四个独立 episode（bgatec1..c4），场景意图
#   经 annotations.summary 差异注入（零委派/必要委派/双专家/有界收敛）；语义打分
#   归独立评分者，脚本只做执行面取证与封存。
# 盲评封存：joint 产出 blind-cases.json（仅报告 sha256+run id，无臂标签，交评分者）
#   与 arm-map.json（臂↔案例映射，chmod 600，操作员保管至评分完毕）。
#
# 前置：两臂各自部署形态见 RUNBOOK（切姿态=操作员改 env 重启容器）；脚本按行为面
#   （task_key 形态）核姿态，不信任部署证词。会话一致性：三步共用 R7_BGATE_SESSION
#   （默认当日 UTC 日期）。本脚本无破坏性动作（姿态切换的重启归操作员步骤）。
# ============================================================================

set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
. "${SCRIPT_DIR}/e2e-r7-common.sh"

ARM="${1:-}"
case "$ARM" in
    armB|armC|joint) : ;;
    *) echo "usage: $0 armB|armC|joint" >&2; exit 1 ;;
esac

SESSION="${R7_BGATE_SESSION:-$(date -u +%Y%m%d)}"
RUNS="${R7_RUNS_DIR:-./runs}/r7bgate-${SESSION}"
mkdir -p "$RUNS"
SVC="${R7_BGATE_SERVICE:-checkout}"
SFX="$(date -u +%H%M%S)"
r7_log "arm=$ARM session=$SESSION runs=$RUNS service=$SVC"

CASE_SUM_C1="single metric breach, isolated symptom"
CASE_SUM_C2="error rate spike correlated with a recent deployment"
CASE_SUM_C3="multi-signal: latency + errors + suspected configuration change"
CASE_SUM_C4="intermittent symptom, evidence may be incomplete"

# 四案 alertname（大写注入面；stickiness 用小写形）
AN_C1="BGateC1Isolated"; AN_C2="BGateC2DeployCorr"
AN_C3="BGateC3MultiSignal"; AN_C4="BGateC4Intermittent"

case_alertname() { case "$1" in
    1) echo "$AN_C1" ;; 2) echo "$AN_C2" ;; 3) echo "$AN_C3" ;; 4) echo "$AN_C4" ;;
esac; }
case_summary() { case "$1" in
    1) echo "$CASE_SUM_C1" ;; 2) echo "$CASE_SUM_C2" ;; 3) echo "$CASE_SUM_C3" ;; 4) echo "$CASE_SUM_C4" ;;
esac; }

r7_resource_snapshot "$RUNS" "pre-$ARM"

# ---------------------------------------------------------------------------
# armB / armC 公共执行面：bundle（白名单四案）→ 逐案注入（summary 差分场景意图）
# → 逐案 run 终态
# ---------------------------------------------------------------------------
run_arm() {
    _tag="$1"
    _wl=""
    _i=1
    while [ "$_i" -le 4 ]; do
        _an="$(case_alertname "$_i")"
        # 白名单匹配面对 incident_key 原样大小写敏感（195 真窗差分实证：
        # 原样 E2EA0CheckoutProbe→WHITELISTED；全小写→BUCKETED_HOLMES 不铸 run）
        _wl="${_wl}\"alertname=${_an}|service=${SVC}\","
        _i=$((_i + 1))
    done
    _wl="$(echo "$_wl" | sed -E 's/,$//')"
    cat > "$RUNS/bundle-${_tag}.content" <<EOF
{"policy_version":"r7-bgate-${_tag}-${SESSION}","canary":{"percent":0,"whitelist":[${_wl}],"max_native_runs":500},"native":{"proposal":{"schema_version":"am4-plan.v1","tasks":[{"key":"investigate-metrics","type":"metrics@1","inputs":[]},{"key":"investigate-logs","type":"logs@1","inputs":[]},{"key":"investigate-change","type":"change@1","inputs":[]}],"edges":[]}}}
EOF
    _digest="$(r7_publish_bundle "$RUNS/bundle-${_tag}.content" "$_tag")"
    r7_activate "$_digest" "$RUNS"
    r7_log "[$_tag] digest=$_digest"

    _i=1
    while [ "$_i" -le 4 ]; do
        _an="$(case_alertname "$_i")"
        _sum="$(case_summary "$_i")"
        r7_log "[$_tag] case c$_i 注入（$_an@$SVC）"
        # summary 携带套件唯一后缀（BA-111 同律：同身份同摘要会被产品正确去重，
        # 跨套件重跑必须换 payloadHash 才能开新 episode）
        _code="$(r7_inject_alert "$_an" "$SVC" firing "$RUNS" "${_sum} [r7bgate-${SFX}]")"
        [ "$_code" = "202" ] || r7_fail "[$_tag] c$_i 注入期望 202 实得 $_code: $(cat "$RUNS/alert-${SVC}-firing.resp")"
        _wl_l="alertname=$(echo "$_an" | tr '[:upper:]' '[:lower:]')|service=${SVC}"
        _key="${_wl_l}:${_wl_l}"
        r7_db_poll_ge "[$_tag] c$_i WHITELISTED 原子对" 120 R7_PG_URL \
            "SELECT count(*) FROM canary_route_decision WHERE stickiness_key='${_key}'
             AND decision='WHITELISTED' AND bundle_digest='${_digest}' AND run_id IS NOT NULL" 1
        _runid="$(r7_psql_ro R7_PG_URL "SELECT run_id FROM canary_route_decision
            WHERE stickiness_key='${_key}' AND decision='WHITELISTED'
            AND bundle_digest='${_digest}' ORDER BY id DESC LIMIT 1" '-At')"
        _state="$(r7_wait_run_terminal R7_PG_URL "$_runid" 900)"
        printf '%s|%s|%s|%s|%s\n' "c$_i" "$_an" "$_runid" "$_state" "$_digest" \
            >> "$RUNS/${_tag}-runs.txt"
        r7_log "[$_tag] c$_i run=$_runid state=$_state"
        _i=$((_i + 1))
    done
}

if [ "$ARM" = "armB" ] || [ "$ARM" = "armC" ]; then
    rm -f "$RUNS/${ARM}-runs.txt"
    run_arm "$ARM"

    # 姿态行为面核验（不信任部署证词）
    _runlist="$(cut -d'|' -f3 "$RUNS/${ARM}-runs.txt" | tr '\n' ',' | sed -E 's/,$//')"
    if [ "$ARM" = "armB" ]; then
        _primary="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_task
            WHERE run_id IN ($_runlist) AND task_key='PRIMARY_INVESTIGATE'" '-At')"
        [ "$_primary" = "0" ] || r7_fail "[armB] 姿态非 legacy：发现 $_primary 个主模式任务"
        r7_db_poll_ge "[armB] NATIVE_INVESTIGATE 驱动任务在场" 30 R7_PG_URL \
            "SELECT count(*) FROM rca_task WHERE run_id IN ($_runlist)
             AND task_key='NATIVE_INVESTIGATE' AND state='DONE'" 4
        r7_log "[armB] 姿态行为面 PASS（legacy 三角色：零主模式任务 + 4 驱动任务 DONE）"
    else
        _primary="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_task
            WHERE run_id IN ($_runlist) AND task_key='PRIMARY_INVESTIGATE' AND state='DONE'" '-At')"
        [ "${_primary:-0}" -ge 1 ] || r7_fail "[armC] 姿态非主模式：零 PRIMARY_INVESTIGATE DONE"
        _dele="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_task
            WHERE run_id IN ($_runlist) AND task_key LIKE 'DELEGATE-%'" '-At')"
        [ "${_dele:-0}" -ge 1 ] \
            || r7_fail "[armC] 动态委派臂零委派（$_dele）——臂C 退化为零委派形态，取证后调案/prompt 再跑"
        r7_log "[armC] 姿态行为面 PASS（主模式 ${_primary} 主任务 + 委派 ${_dele} 子任务）"
    fi
    r7_resource_snapshot "$RUNS" "post-$ARM"
    r7_scenario_result "$RUNS" "R7-BGATE-$ARM" \
        "$ARM 四案执行面取证（$SVC 真实指标四 episode）" \
        "$([ "$ARM" = armB ] && echo deterministic-handler@195 || echo real-provider@195)" "PASS"
    r7_log "[$ARM] 完成（证据=$RUNS/${ARM}-runs.txt）"
    exit 0
fi

# ---------------------------------------------------------------------------
# joint：两臂指标汇总 + 盲评封存 + 臂A BLOCKED_EXTERNAL 入账
# ---------------------------------------------------------------------------
r7_log "joint 汇总（armB+armC → joint-report / blind-cases / arm-map）"
[ -f "$RUNS/armB-runs.txt" ] || r7_fail "joint 缺 armB-runs.txt（先跑 armB）"
[ -f "$RUNS/armC-runs.txt" ] || r7_fail "joint 缺 armC-runs.txt（先跑 armC）"

arm_metrics() {
    _tag="$1"; _outfile="$2"
    printf '{\n  "arm": "%s",\n  "cases": [\n' "$_tag" > "$_outfile"
    _first=true
    while IFS='|' read -r _case _an _runid _state _digest; do
        _claims="$(r7_psql_ro R7_PG_URL "SELECT
              count(*) FILTER (WHERE status='TRUE')||','||
              count(*) FILTER (WHERE status='FALSE')||','||
              count(*) FILTER (WHERE status='UNKNOWN')
            FROM rca_claim WHERE run_id='${_runid}' AND lifecycle='ACTIVE'" '-At')"
        _tokens="$(r7_psql_ro R7_PG_URL "SELECT COALESCE(sum((usage->>'total_tokens')::bigint),0)
            FROM rca_model_call WHERE run_id='${_runid}' AND state='SUCCESS'" '-At')"
        _latency="$(r7_psql_ro R7_PG_URL "SELECT COALESCE(extract(epoch from
              (rp.created_at - r.created_at))::bigint, -1)
            FROM rca_run r JOIN rca_report rp ON rp.run_id=r.id
            WHERE r.id='${_runid}' LIMIT 1" '-At')"
        _dele="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_task
            WHERE run_id='${_runid}' AND task_key LIKE 'DELEGATE-%'" '-At')"
        _rdigest="$(r7_psql_ro R7_PG_URL "SELECT COALESCE(encode(sha256(package::text::bytea),'hex'),'')
            FROM rca_report WHERE run_id='${_runid}' LIMIT 1" '-At')"
        [ "$_first" = true ] || printf ',\n' >> "$_outfile"
        _first=false
        printf '    {"case":"%s","run":"%s","state":"%s","claims_true_false_unknown":"%s",\n     "tokens":%s,"latency_s":%s,"delegate_tasks":%s}\n' \
            "$_case" "$_runid" "$_state" "$_claims" "${_tokens:-0}" "${_latency:--1}" "$_dele" >> "$_outfile"
        # 盲评面（arm-map 独立保管；blind-cases 只见摘要）
        printf '{"case_digest_report":"%s","run_id":"%s"}\n' "$_rdigest" "$_runid" \
            >> "${_outfile}.blind.tmp"
        printf '%s|%s|%s\n' "$_tag" "$_case" "$_runid" >> "${_outfile}.armmap.tmp"
    done < "$RUNS/${_tag}-runs.txt"
    printf '  ]\n}\n' >> "$_outfile"
}

arm_metrics armB "$RUNS/joint-armB.json"
arm_metrics armC "$RUNS/joint-armC.json"

{
    echo '{'
    echo '  "session": "'"$SESSION"'",'
    echo '  "service": "'"$SVC"'",'
    echo '  "budget_attestation": "'"${R7_ARM_BUDGET_ATTEST:-MISSING-操作员证词未注入}"'",'
    echo '  "armA": {"status": "BLOCKED_EXTERNAL",'
    echo '           "reason": "max_delegation_batches=2 为编译常量无运行时旋钮（旋钮化=B 门前置债）"},'
    echo '  "armB_semantics_note": "legacy 固定三角色=确定性 handler 面（零模型调用零 token），非 LLM 三角色",'
    echo '  "joint_armB": '
    cat "$RUNS/joint-armB.json"
    echo '  ,'
    echo '  "joint_armC": '
    cat "$RUNS/joint-armC.json"
    echo '}'
} > "$RUNS/joint-report.json"

cat "$RUNS/joint-armB.json.blind.tmp" "$RUNS/joint-armC.json.blind.tmp" \
    > "$RUNS/blind-cases.json"
cat "$RUNS/joint-armB.json.armmap.tmp" "$RUNS/joint-armC.json.armmap.tmp" \
    > "$RUNS/arm-map.json"
chmod 600 "$RUNS/arm-map.json"
rm -f "$RUNS"/joint-arm*.json.blind.tmp "$RUNS"/joint-arm*.json.armmap.tmp

r7_scenario_result "$RUNS" "R7-BGATE-joint" \
    "三臂汇总+盲评封存（臂A BLOCKED_EXTERNAL 旋钮缺件如实入账）" \
    "mixed@195" "PASS"
r7_resource_snapshot "$RUNS" "post-joint"
r7_log "joint PASS（报告=$RUNS/joint-report.json；盲评面=$RUNS/blind-cases.json；arm-map 600 权限操作员保管）"
r7_log "提醒：盲评打分归独立评分者（用户/评测线），本包只封存执行面——不以脚本口径出质量结论"
