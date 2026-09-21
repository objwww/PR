#!/bin/sh
. /opt/build/r7-operator-env.sh
RUN_ID=30f8a30f-4a6d-422f-944d-1e5105598e3c
echo '=== task 终态 ==='
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select task_key, state, attempt_count from rca_task where run_id='$RUN_ID';"
echo '=== run 状态 ==='
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select state, finished_at is not null from rca_run where id='$RUN_ID';"
echo '=== 本 run 日志 WARN/ERROR ==='
docker logs deploy-control-app-1 --since 6m 2>&1 | grep -E "WARN|ERROR" | grep -v UserDetails | tail -10
echo '=== 姿态复核（容器内 env 布尔） ==='
docker exec deploy-control-app-1 sh -c 'env | grep -E "APP_ALERT_R7_PRIMARY_(ENABLED|RELEASE)" | sed "s/RELEASE_DIGEST=.*/RELEASE_DIGEST=<present>/"'
exit 0
