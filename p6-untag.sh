#!/bin/sh
PG="docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c"
echo '---untagged s3 session exists?---'
$PG "select scenario_id||'|'||state||'|gen='||generation from arena.oa_chaos_session where scenario_id in ('chaos-eval-s3-r1','chaos-eval-s4-r1','chaos-eval-s5-r1','chaos-eval-s16-r1','chaos-eval-s17-r1');"
echo '---ACTIVE/RECOVERING now---'
$PG "select scenario_id||'|'||fault_type||'|'||state from arena.oa_chaos_session where state in ('ACTIVE','RECOVERING');"
