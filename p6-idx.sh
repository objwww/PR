#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---s3 rows---'
$PG "select scenario_id||'|'||state||'|gen='||generation from arena.oa_chaos_session where scenario_id like 'chaos-eval%s3%' order by created_at desc limit 6;"
echo '---indexes---'
$PG "select indexname, indexdef from pg_indexes where tablename='oa_chaos_session';" | cut -c1-260
