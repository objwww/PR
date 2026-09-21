#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---latest sessions all states---'
$PG "select scenario_id||'|'||fault_type||'|'||state||'|gen='||generation||'|exp='||expires_at from arena.oa_chaos_session order by created_at desc limit 6;"
echo '---states distribution---'
$PG "select state, count(*) from arena.oa_chaos_session group by state;"
