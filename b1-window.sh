#!/bin/sh
# B1 窗口探测：对方部署/验收窗是否已静默
echo "== 最近 6 个 run =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select created_at::time(0)||' '||state from rca_run order by created_at desc limit 6;"
echo "== 活跃 run =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(*) from rca_run where state in ('QUEUED','RUNNING','REPORTING');"
echo "== control-app 启动时间 vs 现在 =="
docker inspect deploy-control-app-1 --format "StartedAt={{.State.StartedAt}}"
date -u +"now_utc=%Y-%m-%dT%H:%M:%SZ"
echo "== control 最近 3 分钟日志行数（活动度）=="
docker logs deploy-control-app-1 --since 3m 2>&1 | wc -l
echo "== 最近 2 分钟日志尾部 5 行 =="
docker logs deploy-control-app-1 --since 2m 2>&1 | tail -5 | cut -c1-160
