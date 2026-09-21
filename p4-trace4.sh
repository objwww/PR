#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---ArenaOrderStuck incidents---'
$PG "select incident_key, status, episode_started_at, resolved_at, last_event_at, current_rca_run_id from incident where incident_key like '%ArenaOrderStuck%' or incident_key like '%f95e79c2%' order by episode_started_at desc limit 5;"
echo '---all open incidents---'
$PG "select incident_key, status, episode_started_at from incident where status not in ('RESOLVED','CLOSED') order by episode_started_at desc limit 8;"
