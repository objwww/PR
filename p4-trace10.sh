#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---inbox since 03:42---'
$PG "select state, decision, received_at, processed_at, last_error from alert_inbox where received_at > '2026-09-17T03:42:00Z' order by received_at;"
echo '---payload head of first---'
$PG "select convert_from(substring(payload_raw from 1 for 400),'UTF8') from alert_inbox where received_at > '2026-09-17T03:42:00Z' order by received_at limit 1;"
echo '---new incidents---'
$PG "select incident_key, status, category from incident where last_event_at > '2026-09-17T03:42:00Z' order by last_event_at desc limit 5;"
