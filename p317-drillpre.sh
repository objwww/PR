#!/bin/sh
set -e
P() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "$1"; }
echo '--- 既有演练参数（照抄用）:'
P "select scenario_id, target_env, duration_seconds, traffic_scale, state from drill_job order by created_at desc limit 3;"
echo '--- 当前 UTC 时间与窗口核对:'
P "select now() at time zone 'utc';"
