#!/bin/sh
. /opt/build/r7-operator-env.sh
echo '=== 最近路由决策（全列自适应） ==='
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select created_at::time(0), decision, incident_key from canary_route_decision order by created_at desc limit 5;"
echo '=== 最近 runs ==='
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select r.created_at::time(0), r.state, t.state, t.task_key from rca_run r join rca_task t on t.run_id=r.id where r.created_at > now() - interval '15 minutes' order by r.created_at desc limit 4;"
echo '=== 注入 202 后的 runner/worker 日志 ==='
docker logs deploy-control-app-1 --since 12m 2>&1 | grep -viE 'UserDetails|HikariPool|tomcat|Start completed|Starting' | tail -14
exit 0
