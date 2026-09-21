#!/bin/sh
Q() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "$1"; }
echo '=== incident 的 current_rca_run_id ==='
Q "select 'incident_current_run='||coalesce(current_rca_run_id::text,'NULL') from incident where id='5781021b-735a-44ca-8281-d2ccf3de6c0c'"
echo '=== 该 incident 名下全部 run ==='
Q "select left(r.id::text,8)||' | '||r.state||' | engine='||coalesce(r.engine,'-')||' | created='||r.created_at from rca_run r where r.incident_id='5781021b-735a-44ca-8281-d2ccf3de6c0c' order by r.created_at"
echo '=== 82cf4cbf 的 run 行关键列 ==='
Q "select 'trigger='||coalesce(trigger_kind,'-')||' | purpose='||coalesce(purpose,'-')||' | incident='||left(incident_id::text,8) from rca_run where id='82cf4cbf-44fd-4ea0-9184-84115a53e9f0'"
exit 0
