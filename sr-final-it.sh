#!/bin/sh
# SR 终局复核：对 195 现树（含热旋转修复的最终代码）重跑 PostgresRunReconcilerIT
cd /opt/build/pr && export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp -pl control-app test -Dtest=PostgresRunReconcilerIT > /opt/build/pr-logs/sr-final-it.log 2>&1
grep -E 'Tests run:|BUILD' /opt/build/pr-logs/sr-final-it.log | tail -3
# 运行后生产面健康复核（IT 用 Testcontainers 独立库，不应影响生产栈）
curl -s -o /dev/null -w 'health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
docker logs deploy-control-app-1 --since 10m 2>&1 | grep -c run_reconcile_decision
docker logs deploy-control-app-1 2>&1 | grep -c ERROR || true
exit 0
