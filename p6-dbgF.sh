#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---terminal---'
$PG "select state||' | '||coalesce(terminal_reason,'-') from eval_run where id::text like 'c312e4b1%';"
echo '---failure samples---'
$PG "select scenario_id||'|'||left(failure_sample::text,150) from eval_case_result where eval_run_id::text like 'c312e4b1%' order by scenario_id;"
echo '---worker---'
docker ps --format '{{.Names}} {{.Status}}' | grep eval
echo '---active chaos---'
$PG "select scenario_id||'|'||fault_type||'|'||state from arena.oa_chaos_session where state in ('ACTIVE','RECOVERING');"
