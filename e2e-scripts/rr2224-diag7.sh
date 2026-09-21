#!/bin/sh
# v3 吞告警第三刀 v2：last_error 是 json 列，用 json 全文输出
PG="docker exec rriso-postgres-1 psql -U postgres -d pr_agent -At -F| -c"
echo ===INBOX-V3===
$PG "select id, state, decision, attempt_count, max_attempts, next_retry_at, processed_at, received_at from alert_inbox where received_at > '2026-09-13 10:45+00' order by received_at"
echo ===INBOX-LASTERR===
$PG "select id, state, last_error from alert_inbox where received_at > '2026-09-13 10:45+00' order by received_at"
