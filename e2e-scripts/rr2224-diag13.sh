#!/bin/sh
# v6 六案 NORUN 取证：决策值分布 + inbox 状态 + incident 更新时间
PG="docker exec rriso-postgres-1 psql -U postgres -d pr_agent -At -F| -c"
echo ===V6-DECISION-BY-KEY===
$PG "select stickiness_key, decision, count(*), min(created_at), max(created_at) from canary_route_decision where created_at > '2026-09-13 12:05+00' group by stickiness_key, decision order by min(created_at)"
echo ===V6-INBOX===
$PG "select id, state, decision, attempt_count, processed_at, received_at from alert_inbox where received_at > '2026-09-13 12:05+00' order by received_at"
echo ===V6-INCIDENT-UPDATES===
$PG "select incident_key, status, generation, updated_at from incident where incident_key ilike '%e2rr%' order by updated_at desc limit 12"
echo ===DECISION-VALUES-ALLTIME===
$PG "select decision, count(*) from canary_route_decision group by decision"
