#!/bin/sh
code=$(curl -s -o /dev/null -w '%{http_code}' http://127.0.0.1:8080/actuator/prometheus)
echo "prom_status=$code"
echo '--- actuator links ---'
curl -s http://127.0.0.1:8080/actuator | tr ',' '\n' | grep -o 'prometheus\|metrics\|health' | sort -u
echo '--- rca_reconcile* meters (if any) ---'
curl -s http://127.0.0.1:8080/actuator/prometheus | grep -E '^rca_reconcile|^rca_late_commit|^rca_cancel_to_quiesce' | head -40
echo '--- run states ---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select state,count(*) from rca_run group by state order by 1;"
echo '--- app registry AlertMetrics beans wired? (startup log) ---'
docker logs deploy-control-app-1 --since 30m 2>&1 | grep -c 'RunReconciler' || true
exit 0
