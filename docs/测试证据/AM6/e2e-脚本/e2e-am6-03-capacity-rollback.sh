#!/bin/sh
# ============================================================================
# e2e-am6-03-capacity-rollback.sh —— E2E-AM6-03：M6-03 50% 姿态容量与回退演练
#                                     （[195] 部署段真栈；经 m6-run-scenario.sh 包裹）
#
# 必断言（AM6 落码方案 M6-03）：
#   ① 50% 姿态：percent=50 bundle 发布/激活（whitelist 直达键 + max_native_runs=50
#     + native.proposal），status 面可查 percent=50；
#   ② 容量报告真栈执行留证（deploy/policy/m6-capacity-report.sh：内存三档分类/
#     容器 RSS/引擎同窗对照/积压/绝对 SLO——真实窗口数据，禁 before/after）；
#   ③ 白名单直达 → WHITELISTED → NATIVE run：**在途即取证** config_digest=D50
#     （run 启动固定不再变），终态复验仍=D50；
#   ④ 回退演练：percent=0 rollback → 新注入键 BUCKETED_HOLMES 且 percent=0、
#     engine=HOLMES（断言 SQL 随件）→ **在途/历史 NATIVE run 的 config_digest
#     不变**（回退只影响新 Run）→ status 回 percent=0；
#   ⑤ 终态：active=percent=0 回滚靶（验证后不留放量态）。
# ============================================================================

set -e
. "$(dirname "$0")/e2e-am6-common.sh"

SUITE="$(am6_suite_run_id)"
RUNS="${AM6_RUNS_DIR:-./runs}/${SUITE}-am6-03"
mkdir -p "$RUNS"
SFX="$(echo "${SUITE}" | tr 'A-Z' 'a-z')"

am6_log "E2E-AM6-03 开始 suite=$SUITE runs=$RUNS"
am6_resource_snapshot "$RUNS" "start"

# ---------------------------------------------------------------------------
# phase0 50% 姿态发布/激活
# ---------------------------------------------------------------------------
am6_log "phase0 50% 姿态：发布并激活 percent=50 bundle"
am6_http GET /api/canary/status AM6_RELEASE_BEARER "" "$RUNS/status-pre.json" >/dev/null
grep -q '"nativeReady":true' "$RUNS/status-pre.json" \
    || am6_fail "phase0 前置不满足 nativeReady 非 true: $(cat "$RUNS/status-pre.json")"
cat > "$RUNS/bundle-canary50.content" <<EOF
{"policy_version":"am6-e2e-03-canary50-${SUITE}","canary":{"percent":50,"whitelist":["alertname=HighErrorRate|service=am6e2e03-native"],"max_native_runs":50},"native":{"proposal":{"schema_version":"am4-plan.v1","tasks":[{"key":"investigate-metrics","type":"metrics@1","inputs":[]},{"key":"investigate-logs","type":"logs@1","inputs":[]},{"key":"investigate-change","type":"change@1","inputs":[]}],"edges":[]}}}
EOF
D50="$(am6_publish_bundle "$RUNS/bundle-canary50.content" canary50)"
am6_activate "$D50" "$RUNS"
am6_http GET /api/canary/status AM6_RELEASE_BEARER "" "$RUNS/status-p50.json" >/dev/null
grep -q '"percent":50' "$RUNS/status-p50.json" \
    || am6_fail "phase0 status percent 非 50: $(cat "$RUNS/status-p50.json")"
am6_log "  percent=50 生效 active=$D50"

# ---------------------------------------------------------------------------
# phase1 容量报告真栈执行留证（真实窗口数据）
# ---------------------------------------------------------------------------
am6_log "phase1 容量报告执行（deploy/policy/m6-capacity-report.sh）"
sh /opt/build/pr/deploy/policy/m6-capacity-report.sh > "$RUNS/capacity-report.txt" 2>&1
grep -q 'classified_tier=' "$RUNS/capacity-report.txt" \
    || am6_fail "phase1 容量报告缺内存分类面: $(tail -5 "$RUNS/capacity-report.txt")"
grep -q 'engine_comparison' "$RUNS/capacity-report.txt" \
    || am6_fail "phase1 容量报告缺引擎同窗对照段"
grep -q 'SLO-1' "$RUNS/capacity-report.txt" \
    || am6_fail "phase1 容量报告缺绝对 SLO 段"
grep -E 'classified_tier|in_flight=|PASS mem_available' "$RUNS/capacity-report.txt" \
    | while read -r _l; do am6_log "  $_l"; done
am6_log "phase1 PASS（$RUNS/capacity-report.txt）"

# ---------------------------------------------------------------------------
# phase2 白名单直达 NATIVE 全链（在途即取证 digest 冻结）
# ---------------------------------------------------------------------------
am6_log "phase2 白名单直达 → NATIVE run（在途即取证 config_digest）"
WL_RAW="alertname=higherrorrate|service=am6e2e03-native"
WLKEY_DB="${WL_RAW}:${WL_RAW}"
_code="$(am6_inject_alert HighErrorRate "am6e2e03-native" firing "$RUNS")"
[ "$_code" = "202" ] || am6_fail "phase2 注入期望 202 实得 $_code: $(cat "$RUNS/alert-am6e2e03-native-firing.resp")"
am6_db_poll_ge "phase2 WHITELISTED 审计行" 120 AM6_PG_URL \
    "SELECT count(*) FROM canary_route_decision WHERE stickiness_key='${WLKEY_DB}'
     AND decision='WHITELISTED' AND bundle_digest='${D50}'" 1
NRUN="$(am6_psql_ro AM6_PG_URL "SELECT run_id FROM canary_route_decision
    WHERE stickiness_key='${WLKEY_DB}' AND decision='WHITELISTED' AND bundle_digest='${D50}'
    ORDER BY id DESC LIMIT 1" '-At')"
D_EARLY="$(am6_psql_ro AM6_PG_URL "SELECT config_digest FROM rca_run
    WHERE id='${NRUN}'" '-At')"
[ "$D_EARLY" = "$D50" ] || am6_fail "phase2 run 启动即固定 digest 期望 $D50 实得 $D_EARLY"
STATE_EARLY="$(am6_psql_ro AM6_PG_URL "SELECT state FROM rca_run WHERE id='${NRUN}'" '-At')"
am6_log "  在途取证 run=$NRUN state=${STATE_EARLY} digest=$D_EARLY（run 启动固定不再变）"
am6_db_poll_ge "phase2 NATIVE run SUCCEEDED" 300 AM6_PG_URL \
    "SELECT count(*) FROM rca_run WHERE id='${NRUN}' AND state='SUCCEEDED'
     AND engine='NATIVE' AND config_digest='${D50}'" 1
am6_log "phase2 PASS（终态复验 digest 仍=$D50）"

# ---------------------------------------------------------------------------
# phase3 回退演练：percent=0 → 新 run 全 HOLMES；在途 run digest 不变
# ---------------------------------------------------------------------------
am6_log "phase3 回退演练（断言 SQL 随件）"
printf '{"policy_version":"am6-e2e-03-rollback-%s","canary":{"percent":0}}' "$SUITE" \
    > "$RUNS/bundle-rollback.content"
DRB="$(am6_publish_bundle "$RUNS/bundle-rollback.content" rollback)"
am6_rollback "$DRB" "$RUNS"
am6_http GET /api/canary/status AM6_RELEASE_BEARER "" "$RUNS/status-rollback.json" >/dev/null
grep -q '"percent":0' "$RUNS/status-rollback.json" \
    || am6_fail "phase3 回退后 percent 非 0: $(cat "$RUNS/status-rollback.json")"
POST_RAW="alertname=higherrorrate|service=am6e2e03-post-${SFX}"
POSTKEY="${POST_RAW}:${POST_RAW}"
_code="$(am6_inject_alert HighErrorRate "am6e2e03-post-${SFX}" firing "$RUNS")"
[ "$_code" = "202" ] || am6_fail "phase3 注入期望 202 实得 $_code: $(cat "$RUNS/alert-am6e2e03-post-${SFX}-firing.resp")"
am6_db_poll_ge "phase3 回退后新 run 全 HOLMES（BUCKETED_HOLMES+percent=0+engine=HOLMES）" 120 \
    AM6_PG_URL "SELECT count(*) FROM canary_route_decision d JOIN rca_run r ON r.id=d.run_id
     WHERE d.stickiness_key='${POSTKEY}' AND d.bundle_digest='${DRB}'
       AND d.decision='BUCKETED_HOLMES' AND d.percent=0 AND r.engine='HOLMES'" 1
FROZEN="$(am6_psql_ro AM6_PG_URL "SELECT config_digest FROM rca_run
    WHERE id='${NRUN}' AND engine='NATIVE'" '-At')"
[ "$FROZEN" = "$D50" ] || am6_fail "phase3 在途 run digest 应不变（$D50）实得 $FROZEN"
am6_psql_ro AM6_PG_URL "SELECT id, engine, state, config_digest FROM rca_run
    WHERE id='${NRUN}'" > "$RUNS/rollback-digest-frozen.txt"
am6_log "phase3 PASS（新 run 全 HOLMES；NATIVE run=$NRUN digest 冻结 $D50——回退只影响新 Run）"
am6_log "phase4 终态：active=$DRB（percent=0，验证后不留放量态）"

am6_resource_snapshot "$RUNS" "end"
am6_scenario_result "$RUNS" "E2E-AM6-03" \
    "M6-03 容量与回退演练（50% 姿态/容量报告真栈留证/在途 digest 冻结/回退全绿）" \
    "real-stack-195" "PASS"
am6_log "E2E-AM6-03 PASS（suite=$SUITE，证据=$RUNS）"
