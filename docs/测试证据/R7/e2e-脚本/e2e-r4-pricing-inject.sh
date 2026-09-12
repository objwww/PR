#!/bin/sh
# ============================================================================
# e2e-r4-pricing-inject.sh —— R4 遗留销账：SPRING_APPLICATION_JSON 价目表注入真值
#
# 场景：操作员经 .env SPRING_APPLICATION_JSON 注入 app.model.price.<model>.*
#       单价表 → 真实 checkout 现场合成告警 → 主 Agent 真模型收敛 →
#       rca_model_call 行按注入价目落账（pricing_version/currency/cost_micros）。
# 断言面（只读 SQL 全经 r7_psql_ro）：
#   ① run SUCCEEDED（真模型收敛，复用 A0 同款骨架）
#   ② SUCCESS 台账行 pricing_version 全 = 注入值（≠ 默认 unpriced）
#   ③ cost_micros 全非空且 > 0；currency 全 = 注入值
# 本脚本对 195 无破坏性动作（注入面由 runner 外的操作员临时姿态提供并回收）。
# ============================================================================
set -e
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
. "${SCRIPT_DIR}/e2e-r7-common.sh"

SUITE="r4p-$(r7_suite_run_id)"
RUNS="${R7_RUNS_DIR:-./runs}/${SUITE}"
mkdir -p "$RUNS"
SVC="${R7_A0_SERVICE:-checkout}"
AN="E2ER4PricingProbe"
PV="${R4_EXPECT_PV:-op-inject-20260912}"
CUR="${R4_EXPECT_CURRENCY:-CNY}"
r7_log "suite=$SUITE runs=$RUNS scene=service:$SVC alertname:$AN expect_pv=$PV expect_cur=$CUR"

r7_resource_snapshot "$RUNS" "pre"

# ---------------------------------------------------------------------------
# phase1 姿态 + 路由 bundle（percent=0 + 白名单本场景 service）
# ---------------------------------------------------------------------------
r7_log "phase1 姿态：health 200 + bundle 发布激活（白名单 $SVC）"
r7_health "$RUNS"
cat > "$RUNS/bundle-r4p.content" <<EOF
{"policy_version":"r7-e2e-r4p-${SUITE}","canary":{"percent":0,"whitelist":["alertname=${AN}|service=${SVC}"],"max_native_runs":500},"native":{"proposal":{"schema_version":"am4-plan.v1","tasks":[{"key":"investigate-metrics","type":"metrics@1","inputs":[]},{"key":"investigate-logs","type":"logs@1","inputs":[]},{"key":"investigate-change","type":"change@1","inputs":[]}],"edges":[]}}}
EOF
DA="$(r7_publish_bundle "$RUNS/bundle-r4p.content" r4p)"
r7_qualify "$DA" "$RUNS"
r7_activate "$DA" "$RUNS"
r7_log "phase1 PASS（digest=$DA）"

# ---------------------------------------------------------------------------
# phase2 注入 + WHITELISTED 原子对
# ---------------------------------------------------------------------------
r7_log "phase2 注入 checkout 合成告警（${AN}@$SVC）"
_code="$(r7_inject_alert "$AN" "$SVC" firing "$RUNS" "E2E-R7 R4 pricing ${SUITE} checkout 现场")"
[ "$_code" = "202" ] || r7_fail "phase2 注入期望 202 实得 $_code: $(cat "$RUNS/alert-${SVC}-firing.resp")"
WL_L="alertname=$(echo "$AN" | tr '[:upper:]' '[:lower:]')|service=${SVC}"
WLKEY="${WL_L}:${WL_L}"
r7_db_poll_ge "phase2 WHITELISTED 审计行（run_id 非空=原子对）" 120 R7_PG_URL \
    "SELECT count(*) FROM canary_route_decision WHERE stickiness_key='${WLKEY}'
     AND decision='WHITELISTED' AND bundle_digest='${DA}' AND run_id IS NOT NULL" 1
RUNID="$(r7_psql_ro R7_PG_URL "SELECT run_id FROM canary_route_decision
    WHERE stickiness_key='${WLKEY}' AND decision='WHITELISTED' AND bundle_digest='${DA}'
    ORDER BY id DESC LIMIT 1" '-At')"
r7_log "phase2 PASS（原子对成立；run=$RUNID）"

# ---------------------------------------------------------------------------
# phase3 真模型收敛（900s 上限）
# ---------------------------------------------------------------------------
r7_log "phase3 等 run 终态（真模型 900s 上限）"
STATE="$(r7_wait_run_terminal R7_PG_URL "$RUNID" 900)"
[ "$STATE" = "SUCCEEDED" ] || r7_fail "phase3 run 终态=$STATE（要求 SUCCEEDED）"
r7_log "phase3 PASS（run SUCCEEDED）"

# ---------------------------------------------------------------------------
# phase4 价目断言：台账按注入价目落账
# ---------------------------------------------------------------------------
r7_log "phase4 价目断言：pricing_version=${PV} / currency=${CUR} / cost 全非零"
_NALL="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_model_call
    WHERE run_id='${RUNID}' AND state='SUCCESS'" '-At')"
[ "$_NALL" -ge 1 ] || r7_fail "phase4 SUCCESS 台账应 ≥1 实得 $_NALL"
_NPV="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_model_call
    WHERE run_id='${RUNID}' AND state='SUCCESS' AND pricing_version='${PV}'" '-At')"
[ "$_NPV" = "$_NALL" ] || r7_fail "phase4 pricing_version 应全=${PV}（$_NPV/$_NALL）"
_NBAD="$(r7_psql_ro R7_PG_URL "SELECT count(*) FROM rca_model_call
    WHERE run_id='${RUNID}' AND state='SUCCESS'
      AND (cost_micros IS NULL OR cost_micros<=0 OR currency IS DISTINCT FROM '${CUR}')" '-At')"
[ "$_NBAD" = "0" ] || r7_fail "phase4 cost/currency 违例 $_NBAD 行（期望 0）"
r7_psql_ro R7_PG_URL "SELECT requested_model||'|'||pricing_version||'|'||currency||'|'||\
cost_micros||'|'||coalesce(usage->>'prompt_tokens','?')||'+'||\
coalesce(usage->>'completion_tokens','?') FROM rca_model_call
    WHERE run_id='${RUNID}' ORDER BY action_seq" '-At' \
    > "$RUNS/phase4-pricing-rows.txt"
r7_log "phase4 落账样例（model|pv|cur|cost_micros|tokens）:"
cat "$RUNS/phase4-pricing-rows.txt"
r7_log "SUITE PASS（pv=${PV} rows=$_NALL run=$RUNID evidence=$RUNS）"
