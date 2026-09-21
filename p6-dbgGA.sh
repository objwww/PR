#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
RUN=098a2a8a
echo '---terminal---'
$PG "select state||' | '||coalesce(terminal_reason,'-') from eval_run where id::text like '$RUN%';"
echo '---ALL judge rows (no filter)---'
$PG "select scenario_id||'|r'||round_no||'|'||verdict||'|'||coalesce(passed::text,'-')||'/'||coalesce(total::text,'-')||'|'||coalesce(left(error,100),'ok') from eval_case_judge where eval_run_id::text like '$RUN%' order by scenario_id, round_no;"
echo '---phases tail---'
$PG "select phase, detail::text, created_at from eval_phase_event where eval_run_id::text like '$RUN%' order by created_at desc limit 5;"
echo '---worker log judge errors---'
docker logs eval-worker-std 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -iE 'judge|批件完成' | tail -8 | cut -c1-200
