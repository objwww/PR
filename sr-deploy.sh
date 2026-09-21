#!/bin/sh
# SR 批（影子收口 + RunReconciler）195 部署与生效验证
set -x
cd /opt/build/pr
tar -xzf /opt/build/pr-sr.tar.gz -C /opt/build/pr
export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep BUILD | tail -1
cd deploy
docker compose build control-app web 2>&1 | tail -2
docker compose up migrate 2>&1 | tail -3
docker compose up -d control-app web 2>&1 | tail -2
sleep 35
echo '=== flyway 108 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select version||'|'||success from flyway_schema_history where version='108';"
echo '=== V108 columns ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select string_agg(column_name, ',') from information_schema.columns where table_name='rca_run' and column_name in ('purpose','purpose_source','completion_kind','reconcile_deadline_at','reporting_started_at','recovery_attempts');"
echo '=== health ==='
curl -s -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8080/actuator/health
echo '=== ERROR count ==='
docker logs deploy-control-app-1 2>&1 | grep -c ERROR
echo '=== reconciler 线程启动 ==='
docker logs deploy-control-app-1 --since 3m 2>&1 | grep -iE 'run-reconciler|reconcile' | head -5
echo '=== 历史 REPORTING Run 保持原样（不批量过期） ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select count(*) from rca_run where state='REPORTING';"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select id||'|'||state||'|'||coalesce(purpose,'NULL')||'|'||coalesce(completion_kind,'NULL') from rca_run where state in ('REPORTING','RUNNING','QUEUED');"
echo '=== 全表 run 状态分布 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select state||':'||count(*) from rca_run group by state order by 1;"
exit 0
