#!/bin/sh
PG="docker exec rriso-postgres-1 psql -U postgres -d pr_agent -At -F| -c"
echo ===V8-LDEAD-TOOLS===
$PG "select tool_name, state, coalesce(reason_code,'-') from rca_tool_invocation where run_id='ab92148b-3b1e-487f-a55f-196a24066684' order by call_seq"
echo ===V8-LDEAD-MODEL===
$PG "select state, coalesce(error_code,'-'), created_at from rca_model_call where run_id='ab92148b-3b1e-487f-a55f-196a24066684' order by created_at"
echo ===V8-LDEAD-RUNSTATE===
$PG "select state from rca_run where id='ab92148b-3b1e-487f-a55f-196a24066684'"
echo ===BUDGET-ENV===
grep BUDGET /opt/build/pr/rr-iso/rr-iso.env
echo ===COMPOSE-ENV-REACH===
docker exec rriso-control-app-1 printenv APP_ALERT_AM4_BUDGET_TOOL_CALLS APP_ALERT_AM4_PROMETHEUS_BASE_URL
