#!/bin/sh
Q() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "$1"; }
echo '=== 两条"报告组装中" run 的真实状态 ==='
Q "select left(r.id::text,8)||' | state='||r.state||' | engine='||coalesce(r.engine,'-')||' | created='||r.created_at||' | updated='||r.updated_at||' | lease='||coalesce(r.lease_owner,'-')||'@'||coalesce(r.lease_epoch::text,'-')||' util='||coalesce(r.lease_until::text,'-') from rca_run r where r.id::text like '37b3ef3e%' or r.id::text like '2de23770%'"
echo '=== 有没有报告行 ==='
Q "select left(run_id::text,8)||' | report='||count(*) from rca_report where run_id::text like '37b3ef3e%' or run_id::text like '2de23770%' group by run_id"
echo '=== 对应 incident ==='
Q "select left(i.id::text,8)||' | key='||i.incident_key||' | '||i.status from incident i where i.id::text like '3b5e3514%' or i.id::text like '6bcbacfd%'"
echo '=== 近 24h run 状态分布 ==='
Q "select state||' = '||count(*) from rca_run where created_at > now() - interval '24 hours' group by state"
echo '=== 全部 run 状态分布 ==='
Q "select state||' = '||count(*) from rca_run group by state"
exit 0
