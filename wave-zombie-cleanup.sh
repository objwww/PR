#!/bin/sh
Q() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "$1"; }
Q "update rca_run set state='FAILED', finished_at=now(), last_error=('{\"reason\":\"清场：0908 Holmes-shadow 时代 REPORTING 僵尸（incident 已结、无报告行），前端产品化波次1 人工清场\"}')::json where (state='REPORTING' and id::text like '37b3ef3e%') or (state='REPORTING' and id::text like '2de23770%')"
echo '--- 清场后 ---'
Q "select state||' = '||count(*) from rca_run group by state"
Q "select left(id::text,8)||' | '||state||' | err='||coalesce(last_error::text,'-') from rca_run where state='FAILED' order by created_at desc limit 3"
exit 0
