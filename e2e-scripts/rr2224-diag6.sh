#!/bin/sh
# v3 吞告警取证第二刀：inbox 真列名 + 决策全序 + 窗口表
PG="docker exec rriso-postgres-1 psql -U postgres -d pr_agent -At -F| -c"
echo ===INBOX-COLS===
$PG "select column_name from information_schema.columns where table_name='alert_inbox' order by ordinal_position"
echo ===DECISIONS-ALL===
$PG "select id, stickiness_key, decision, run_id, created_at from canary_route_decision order by id"
echo ===WINDOW-COLS===
$PG "select column_name from information_schema.columns where table_name='canary_window_verdict' order by ordinal_position"
echo ===WINDOWS===
$PG "select * from canary_window_verdict order by 1 desc limit 15"
