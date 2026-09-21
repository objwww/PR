#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---table schema---'
$PG "select table_schema, table_name from information_schema.tables where table_name='oa_chaos_session';"
echo '---non-closed sessions---'
$PG "select table_schema||'.'||scenario_id||'|'||fault_type||'|'||state||'|gen='||generation||'|exp='||expires_at from oa_chaos_session, information_schema.tables t where t.table_name='oa_chaos_session' and state not in ('CLOSED','EXPIRED') order by created_at desc limit 8;" 2>&1 | head -10
