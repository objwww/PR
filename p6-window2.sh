#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---ACTIVE chaos sessions---'
$PG "select scenario_id||'|'||fault_type||'|'||state||'|exp='||expires_at from arena.oa_chaos_session where state in ('ACTIVE','RECOVERING') order by created_at desc limit 4;"
echo '---latest s26 batch---'
$PG "select scenario_id||'|'||state||'|created='||created_at from arena.oa_chaos_session where scenario_id like '%s26%' order by created_at desc limit 2;"
echo '---RUNNING eval---'
$PG "select left(id::text,8)||' '||coalesce(display_name,'?') from eval_run where state='RUNNING' and display_name is not null and display_name != '';"
