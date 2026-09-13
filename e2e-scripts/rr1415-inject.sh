#!/bin/sh
# rr1415-inject.sh —— 隔离栈最小注入车：bundle 发布激活 + 告警注入 + 等 SUCCEEDED
# 输出末行=run id（driver 复用 e2e-r7-common.sh 原语）
. /opt/build/pr/rr-iso/rr-iso-openv.sh
. /opt/build/b2tree/e2e-r7-common.sh
SUITE="rriso-$(r7_suite_run_id)"
RUNS="${R7_RUNS_DIR}/$SUITE"
mkdir -p "$RUNS"
SVC=checkout
AN="E2ERR14Rotation"
r7_health "$RUNS" >/dev/null 2>&1 || { echo "health-fail"; exit 1; }
cat > "$RUNS/bundle.content" <<EOF
{"policy_version":"rr14-${SUITE}","canary":{"percent":0,"whitelist":["alertname=${AN}|service=${SVC}"],"max_native_runs":500},"native":{"proposal":{"schema_version":"am4-plan.v1","tasks":[{"key":"investigate-metrics","type":"metrics@1","inputs":[]},{"key":"investigate-logs","type":"logs@1","inputs":[]},{"key":"investigate-change","type":"change@1","inputs":[]}],"edges":[]}}}
EOF
DA="$(r7_publish_bundle "$RUNS/bundle.content" rr14)"
r7_qualify "$DA" "$RUNS" >/dev/null
r7_activate "$DA" "$RUNS" >/dev/null
r7_inject_alert "$AN" "$SVC" firing "$RUNS" "rr14 rotation vehicle [${SUITE}]" >/dev/null
WL_L="alertname=$(echo "$AN" | tr '[:upper:]' '[:lower:]')|service=${SVC}"
WLKEY="${WL_L}:${WL_L}"
r7_db_poll_ge "WHITELISTED" 120 R7_PG_URL \
  "SELECT count(*) FROM canary_route_decision WHERE stickiness_key='${WLKEY}' AND decision='WHITELISTED' AND bundle_digest='${DA}' AND run_id IS NOT NULL" 1 >/dev/null 2>&1
RID="$(r7_psql_ro R7_PG_URL "SELECT run_id FROM canary_route_decision WHERE stickiness_key='${WLKEY}' AND decision='WHITELISTED' AND bundle_digest='${DA}' ORDER BY id DESC LIMIT 1" '-At')"
STATE="$(r7_wait_run_terminal R7_PG_URL "$RID" 600)"
echo "inject-done state=$STATE"
echo "$RID"
