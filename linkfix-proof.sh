#!/bin/sh
Q() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "$1"; }
echo '=== 兜底查询：5781021b 的最近 run（详情接口现在取的就是它） ==='
Q "select 'run='||left(r.id::text,8)||' state='||r.state||' started='||coalesce(r.started_at::text,'-') from rca_run r where r.incident_id='5781021b-735a-44ca-8281-d2ccf3de6c0c' order by r.created_at desc limit 1"
echo '=== 受益面：指针为空但已有 run 的活跃事件 ==='
Q "select count(*) from incident i where i.current_rca_run_id is null and exists (select 1 from rca_run r where r.incident_id=i.id)"
exit 0
