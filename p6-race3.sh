#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---ACTIVE chaos sessions---'
$PG "select scenario_id||'|'||fault_type||'|'||state||'|gen='||generation||'|exp='||expires_at||'|created='||created_at from arena.oa_chaos_session where state in ('ACTIVE','RECOVERING') order by created_at desc limit 6;"
echo '---latest eval runs---'
$PG "select left(id::text,8)||' '||state||' '||coalesce(display_name,'?')||' '||created_at from eval_run order by created_at desc limit 4;"
echo '---latest chaos sessions---'
$PG "select scenario_id||'|'||state||'|created='||created_at from arena.oa_chaos_session order by created_at desc limit 5;"
