#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
RUN=7cd1356a
echo '---state---'
$PG "select state from eval_run where id::text like '$RUN%';"
echo '---phases tail---'
$PG "select phase, detail::text, created_at from eval_phase_event where eval_run_id::text like '$RUN%' order by created_at desc limit 4;"
echo '---ArenaOrderStuck incident---'
$PG "select incident_key||' '||status||' gen='||generation||' last='||last_event_at from incident where incident_key like 'alertname=ArenaOrderStuck%' and last_event_at > '2026-09-19T07:00:00Z' order by last_event_at desc limit 3;"
echo '---chaos sessions this tag---'
$PG "select scenario_id||'|'||state from arena.oa_chaos_session where scenario_id like 'chaos-eval-p6150753%' order by created_at;"
