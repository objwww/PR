#!/bin/sh
# SR 终态证据：迁移/历史 Run/对账活跃
echo "=== flyway 108 ==="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select version||'|'||success from flyway_schema_history where version='108';"
echo "=== 历史 REPORTING Run（保持原样） ==="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select id||'|'||state||'|'||coalesce(purpose,'NULL')||'|'||coalesce(completion_kind,'NULL') from rca_run where state='REPORTING';"
echo "=== purpose 分布 ==="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select coalesce(purpose,'NULL')||':'||count(*) from rca_run group by purpose order by 1;"
echo "=== 近 10 分钟对账决策事件数 ==="
docker logs deploy-control-app-1 --since 10m 2>&1 | grep -c run_reconcile_decision
echo "=== 对账最近一条决策（ALERT_ONLY 零写证据） ==="
docker logs deploy-control-app-1 --since 10m 2>&1 | grep run_reconcile_decision | tail -1
echo "=== ERROR count（启动至今） ==="
docker logs deploy-control-app-1 2>&1 | grep -c ERROR
exit 0
