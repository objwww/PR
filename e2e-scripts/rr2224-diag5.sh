#!/bin/sh
# v3 NORUN 五案取证：决策/inbox/run 三面时间线（v3 窗=11:00 UTC 后）
PG="docker exec rriso-postgres-1 psql -U postgres -d pr_agent -At -F| -c"
echo ===DECISIONS===
$PG "select id, stickiness_key, decision, run_id, created_at from canary_route_decision where created_at > '2026-09-13 11:00+00' order by id"
echo ===INBOX===
$PG "select id, state, created_at from alert_inbox where created_at > '2026-09-13 11:00+00' order by created_at"
echo ===RUNS===
$PG "select id, state, created_at, finished_at from rca_run where created_at > '2026-09-13 11:00+00' order by created_at"
echo ===LSLOW-TOOLS===
$PG "select tool_name, state, reason_code from rca_tool_invocation where run_id='d0feb978-14a0-4559-b340-c7216e311ba5' order by call_seq"
echo ===LSLOW-MODEL===
$PG "select state, error_code, created_at from rca_model_call where run_id='d0feb978-14a0-4559-b340-c7216e311ba5' order by created_at"
