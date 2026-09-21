#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---ACTIVE chaos sessions---'
$PG "select scenario_id||'|'||fault_type||'|'||state||'|gen='||generation||'|exp='||expires_at from arena.oa_chaos_session where state in ('ACTIVE','RECOVERING') order by created_at desc limit 6;"
echo '---RUNNING eval runs---'
$PG "select left(id::text,8)||' '||coalesce(display_name,'?')||' '||state||' '||created_at from eval_run where state='RUNNING' order by created_at desc limit 4;"
echo '---recent chaos sessions all---'
$PG "select scenario_id||'|'||state||'|created='||created_at from arena.oa_chaos_session order by created_at desc limit 4;"
