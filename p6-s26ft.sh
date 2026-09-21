#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---s26 drill fault types---'
$PG "select scenario_id||'|'||fault_type||'|'||state from arena.oa_chaos_session where scenario_id like '%s26%' order by created_at desc limit 5;"
