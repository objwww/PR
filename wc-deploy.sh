#!/bin/sh
# WC 批 195 部署 + 验证（覆盖式 OVERLAY，绝不 rsync --delete）
set -e
cd /opt/build/pr && export JAVA_HOME=/opt/jdk-21.0.12.1+1
echo "== build jar =="
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep BUILD | tail -1
cd deploy
echo "== compose build =="
docker compose build control-app 2>&1 | tail -1
echo "== migrate =="
docker compose up migrate 2>&1 | tail -2
echo "== up control-app =="
docker compose up -d control-app 2>&1 | tail -1
sleep 40
for i in 1 2 3 4 5 6; do
    code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/health || echo 000)
    echo "health attempt$i -> $code"
    [ "$code" = "200" ] && break
    sleep 15
done
echo "== boot/ERROR 检查 =="
docker logs deploy-control-app-1 2>&1 | grep -c 'APPLICATION FAILED' || echo "boot_failures=0"
echo "ERROR_count=$(docker logs deploy-control-app-1 --since 10m 2>&1 | grep -c ERROR || true)"
echo "== flyway 109 =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select version||'|'||success from flyway_schema_history where version in ('98','109');" || true
echo "== WC-5 指标面（actuator prometheus） =="
curl -s http://127.0.0.1:8080/actuator/prometheus 2>/dev/null | grep -E 'rca_reconcile_(last_success_epoch_ms|oldest_unseen_age_ms|terminal_run_open_tasks|scan_duration|decision_total|unknown_action_total)|rca_late_commit_rejected|rca_cancel_to_quiesce' | head -30 || echo "prometheus_not_exposed"
exit 0
