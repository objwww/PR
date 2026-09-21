#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---active chaos sessions---'
$PG "select scenario_id||'|'||fault_type||'|'||state||'|gen='||generation||'|exp='||expires_at from oa_chaos_session where state not in ('CLOSED','EXPIRED') order by created_at desc limit 8;"
