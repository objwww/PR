#!/bin/sh
# v4 全 NORUN 取证：inbox 状态机 + 决策表增量 + 路由日志
PG="docker exec rriso-postgres-1 psql -U postgres -d pr_agent -At -F| -c"
echo ===INBOX-V4===
$PG "select id, state, decision, attempt_count, processed_at, received_at from alert_inbox where received_at > '2026-09-13 12:00+00' order by received_at"
echo ===INBOX-LASTERR-V4===
$PG "select id, state, last_error from alert_inbox where received_at > '2026-09-13 12:00+00' order by received_at"
echo ===DECISIONS-AFTER-31===
$PG "select id, stickiness_key, decision, run_id, created_at from canary_route_decision where id > 31 order by id"
echo ===APPLOG-ROUTE===
docker logs rriso-control-app-1 --since 25m 2>&1 | grep -iE 'canary|route|whitelist|stickiness' | tail -20
