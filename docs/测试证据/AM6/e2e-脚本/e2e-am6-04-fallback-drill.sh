#!/bin/sh
# ============================================================================
# e2e-am6-04-fallback-drill.sh —— E2E-AM6-04：M6-04 Native Primary fallback 链
#                                   （[195] 部署段真栈；经 m6-run-scenario.sh 包裹）
#
# 必断言（AM6 落码方案 M6-04 演练段；20 路并发/重启重放恰一 fallback 归
#          PostgresRunFallbackIT 真 PG 实证，本场景证生产面全链）：
#   ① DRILL 姿态：percent=100 bundle（**无 native.proposal**）发布/激活；
#   ② NATIVE run 因 PROPOSAL_MISSING 终态失败（封闭错误类）→ 自动铸恰一个
#     HOLMES fallback：run_fallback 恰 1 行（depth=1, error_class 留痕）+
#     fallback_of 审计事件恰 1 条 + fallback run engine=HOLMES/trigger_kind=RERUN/
#     同 incident 同 generation + incident.current_rca_run_id 上移；
#   ③ fallback run 真 LLM 收尾 SUCCEEDED → rca_report 恰 1 →
#     report_generation_winner 恰 1（winner_* 指向报告/run）→ report_publication
#     恰 1 → notify_outbox 每渠道恰 1（uq_notify_outbox_delivery 防重）；
#     NATIVE 源 run 零报告零发布（fail-closed 不产半成品）；
#   ④ depth=1 结构封死：fallback run 自身不再有第二级占位（run_fallback 零行）；
#   ⑤ 一键切回计时：rollback 回预演 active（percent=0）→ status 面观测计时；
#   ⑥ 终态：active=预演回滚靶（验证后不留放量态）。
# ============================================================================

set -e
. "$(dirname "$0")/e2e-am6-common.sh"

SUITE="$(am6_suite_run_id)"
RUNS="${AM6_RUNS_DIR:-./runs}/${SUITE}-am6-04"
mkdir -p "$RUNS"
SVC="am6e2e04-fb"
RAWKEY="alertname=higherrorrate|service=${SVC}"
DBKEY="${RAWKEY}:${RAWKEY}"

am6_log "E2E-AM6-04 开始 suite=$SUITE runs=$RUNS"
am6_resource_snapshot "$RUNS" "start"

# ---------------------------------------------------------------------------
# phase0 DRILL 姿态：记录预演 active → 发布/激活 percent=100（无 proposal）
# ---------------------------------------------------------------------------
am6_log "phase0 DRILL 姿态：percent=100 且无 native.proposal（NATIVE 必失败姿态）"
am6_http GET /api/canary/status AM6_RELEASE_BEARER "" "$RUNS/status-pre.json" >/dev/null
grep -q '"nativeReady":true' "$RUNS/status-pre.json" \
    || am6_fail "phase0 前置不满足 nativeReady 非 true: $(cat "$RUNS/status-pre.json")"
PRE="$(am6_active_digest "$RUNS")"
[ -n "$PRE" ] || am6_fail "phase0 预演 active digest 为空（需先有安全态锚）"
am6_log "  预演 active=$PRE（回滚靶）"
cat > "$RUNS/bundle-drill100.content" <<EOF
{"policy_version":"am6-e2e-04-drill100-${SUITE}","canary":{"percent":100}}
EOF
DDRILL="$(am6_publish_bundle "$RUNS/bundle-drill100.content" drill100)"
am6_activate "$DDRILL" "$RUNS"
am6_http GET /api/canary/status AM6_RELEASE_BEARER "" "$RUNS/status-p100.json" >/dev/null
grep -q '"percent":100' "$RUNS/status-p100.json" \
    || am6_fail "phase0 status percent 非 100: $(cat "$RUNS/status-p100.json")"
am6_log "  percent=100 生效 active=$DDRILL（无 proposal 段）"

# ---------------------------------------------------------------------------
# phase1 NATIVE 失败 → 恰一 fallback（占位/事件/指针全链真栈）
# ---------------------------------------------------------------------------
am6_log "phase1 注入告警 → BUCKETED_NATIVE → PROPOSAL_MISSING → 自动 fallback"
_code="$(am6_inject_alert HighErrorRate "$SVC" firing "$RUNS")"
[ "$_code" = "202" ] || am6_fail "phase1 注入期望 202 实得 $_code: $(cat "$RUNS/alert-${SVC}-firing.resp")"
am6_db_poll_ge "phase1 BUCKETED_NATIVE 审计行" 120 AM6_PG_URL \
    "SELECT count(*) FROM canary_route_decision WHERE stickiness_key='${DBKEY}'
     AND decision='BUCKETED_NATIVE' AND bundle_digest='${DDRILL}'" 1
SRUN="$(am6_psql_ro AM6_PG_URL "SELECT run_id FROM canary_route_decision
    WHERE stickiness_key='${DBKEY}' AND decision='BUCKETED_NATIVE'
    AND bundle_digest='${DDRILL}' ORDER BY id DESC LIMIT 1" '-At')"
INC="$(am6_psql_ro AM6_PG_URL "SELECT incident_id FROM rca_run WHERE id='${SRUN}'" '-At')"
GEN="$(am6_psql_ro AM6_PG_URL "SELECT generation FROM rca_run WHERE id='${SRUN}'" '-At')"
am6_log "  NATIVE run=$SRUN incident=$INC generation=$GEN"
am6_db_poll_ge "phase1 NATIVE run FAILED(PROPOSAL_MISSING)" 180 AM6_PG_URL \
    "SELECT count(*) FROM rca_run WHERE id='${SRUN}' AND state='FAILED'
     AND engine='NATIVE' AND last_error::text LIKE '%PROPOSAL_MISSING%'" 1
am6_db_poll_ge "phase1 run_fallback 占位行" 60 AM6_PG_URL \
    "SELECT count(*) FROM run_fallback WHERE source_native_run_id='${SRUN}'
     AND depth=1 AND error_class='PROPOSAL_MISSING'" 1
FRUN="$(am6_psql_ro AM6_PG_URL "SELECT fallback_run_id FROM run_fallback
    WHERE source_native_run_id='${SRUN}'" '-At')"
am6_db_poll_ge "phase1 fallback_of 审计事件恰一条" 60 AM6_PG_URL \
    "SELECT count(*) FROM rca_event WHERE event_type='fallback_of'
     AND run_id='${FRUN}'" 1
_HOLMES_OK="$(am6_psql_ro AM6_PG_URL "SELECT count(*) FROM rca_run
    WHERE id='${FRUN}' AND engine='HOLMES' AND trigger_kind='RERUN'
    AND incident_id='${INC}' AND generation='${GEN}'" '-At')"
[ "$_HOLMES_OK" = "1" ] || am6_fail "phase1 fallback run 形态不符 (engine/RERUN/同代): $_HOLMES_OK"
_PTR_OK="$(am6_psql_ro AM6_PG_URL "SELECT count(*) FROM incident
    WHERE id='${INC}' AND current_rca_run_id='${FRUN}'" '-At')"
[ "$_PTR_OK" = "1" ] || am6_fail "phase1 incident 指针未上移到 fallback run"
{
    echo "source_native_run_id=$SRUN engine=NATIVE state=FAILED last_error~PROPOSAL_MISSING"
    echo "run_fallback: depth=1 error_class=PROPOSAL_MISSING fallback_run_id=$FRUN"
    echo "fallback_run_id=$FRUN engine=HOLMES trigger_kind=RERUN generation=$GEN"
    echo "incident.current_rca_run_id=$FRUN"
    am6_psql_ro AM6_PG_URL "SELECT source_native_run_id, fallback_run_id, depth,
        error_class, created_at FROM run_fallback WHERE source_native_run_id='${SRUN}'"
} > "$RUNS/fallback-chain.txt" 2>&1
am6_log "phase1 PASS（恰一 HOLMES fallback，全链留痕 $RUNS/fallback-chain.txt）"

# ---------------------------------------------------------------------------
# phase2 fallback run 真 LLM 收尾 → 唯一赢家报告/发布/每渠道恰一通知
# ---------------------------------------------------------------------------
am6_log "phase2 fallback run 真 LLM 驱动至 SUCCEEDED（holmes 面结论源）"
am6_db_poll_ge "phase2 fallback run SUCCEEDED" 420 AM6_PG_URL \
    "SELECT count(*) FROM rca_run WHERE id='${FRUN}' AND state='SUCCEEDED'
     AND engine='HOLMES'" 1
REP="$(am6_psql_ro AM6_PG_URL "SELECT id FROM rca_report WHERE run_id='${FRUN}'" '-At')"
[ -n "$REP" ] || am6_fail "phase2 fallback run 无报告"
_WIN_OK="$(am6_psql_ro AM6_PG_URL "SELECT count(*) FROM report_generation_winner
    WHERE incident_id='${INC}' AND generation='${GEN}'
    AND winner_report_id='${REP}' AND winner_run_id='${FRUN}'" '-At')"
[ "$_WIN_OK" = "1" ] || am6_fail "phase2 赢家行形态不符: $_WIN_OK"
_PUB_OK="$(am6_psql_ro AM6_PG_URL "SELECT count(*) FROM report_publication
    WHERE report_id='${REP}'" '-At')"
[ "$_PUB_OK" = "1" ] || am6_fail "phase2 publication 非 1: $_PUB_OK"
_OBX_N="$(am6_psql_ro AM6_PG_URL "SELECT count(*) FROM notify_outbox
    WHERE report_id='${REP}'" '-At')"
_OBX_C="$(am6_psql_ro AM6_PG_URL "SELECT count(DISTINCT channel) FROM notify_outbox
    WHERE report_id='${REP}'" '-At')"
[ -n "$_OBX_N" ] && [ "$_OBX_N" -ge 1 ] && [ "$_OBX_N" = "$_OBX_C" ] \
    || am6_fail "phase2 outbox 每渠道恰一不满足 (rows=$_OBX_N channels=$_OBX_C)"
_NAT_REP="$(am6_psql_ro AM6_PG_URL "SELECT count(*) FROM rca_report
    WHERE run_id='${SRUN}'" '-At')"
[ "$_NAT_REP" = "0" ] || am6_fail "phase2 NATIVE 源 run 不应有报告: $_NAT_REP"
{
    echo "fallback_run_id=$FRUN state=SUCCEEDED"
    echo "rca_report=$REP"
    echo "report_generation_winner: incident=$INC generation=$GEN winner_report=$REP winner_run=$FRUN"
    echo "report_publication: report=$REP count=1"
    echo "notify_outbox: report=$REP rows=$_OBX_N distinct_channels=$_OBX_C（uq_notify_outbox_delivery）"
    echo "native_source_report_count=$_NAT_REP（fail-closed 零报告）"
    am6_psql_ro AM6_PG_URL "SELECT id, engine, state, trigger_kind, generation
        FROM rca_run WHERE id IN ('${SRUN}','${FRUN}') ORDER BY created_at"
    am6_psql_ro AM6_PG_URL "SELECT incident_id, generation, winner_report_id,
        winner_run_id, decided_at FROM report_generation_winner
        WHERE incident_id='${INC}'"
} > "$RUNS/winner-publication.txt" 2>&1
am6_log "phase2 PASS（赢家唯一：报告/发布/每渠道恰一；留痕 $RUNS/winner-publication.txt）"

# ---------------------------------------------------------------------------
# phase3 depth=1 封死 + 一键切回计时
# ---------------------------------------------------------------------------
am6_log "phase3 depth=1 封死断言 + 一键切回计时"
_D2="$(am6_psql_ro AM6_PG_URL "SELECT count(*) FROM run_fallback
    WHERE source_native_run_id='${FRUN}'" '-At')"
[ "$_D2" = "0" ] || am6_fail "phase3 fallback run 自身不得有第二级占位: $_D2"
_T0="$(date +%s)"
am6_rollback "$PRE" "$RUNS"
am6_poll_until "phase3 status 回 percent=0" 60 \
    "am6_http GET /api/canary/status AM6_RELEASE_BEARER '' $RUNS/status-rollback.json >/dev/null && grep -q '\\\"percent\\\":0' $RUNS/status-rollback.json"
_T1="$(date +%s)"
echo "rollback_to=$PRE start=$_T0 observed=$_T1 seconds=$((_T1 - _T0))" \
    > "$RUNS/drill-timing.txt"
am6_log "phase3 PASS（depth=1 零二跳；一键切回 $((_T1 - _T0))s，留痕 drill-timing.txt）"

# ---------------------------------------------------------------------------
# phase4 终态
# ---------------------------------------------------------------------------
am6_log "phase4 终态：active=$PRE（percent=0，验证后不留放量态）"
am6_active_digest "$RUNS" > "$RUNS/active-final.txt"
grep -q "$PRE" "$RUNS/active-final.txt" \
    || am6_fail "phase4 active 未回预演靶: $(cat "$RUNS/active-final.txt")"

am6_resource_snapshot "$RUNS" "end"
am6_scenario_result "$RUNS" "E2E-AM6-04" \
    "M6-04 Native fallback 全链演练（PROPOSAL_MISSING 触发/恰一 HOLMES fallback/赢家唯一发布/depth=1/一键切回计时）" \
    "real-stack-195" "PASS"
am6_log "E2E-AM6-04 PASS（suite=$SUITE，证据=$RUNS）"
