#!/bin/sh
. /opt/build/r7-operator-env.sh
echo '=== 最近路由决策行 ==='
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select created_at, decision, from_percent, to_percent, incident_key from canary_route_decision order by created_at desc limit 4;"
echo '=== incident 状态 ==='
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select incident_key, status, generation, waiting_reason, current_rca_run_id is not null as has_run from incident where incident_key like '%E2EA0%' order by last_event_at desc limit 2;"
echo '=== worker 拍日志（近 4 分钟） ==='
docker logs deploy-control-app-1 --since 4m 2>&1 | grep -vE 'UserDetails|HikariPool|Starting|Start completed' | tail -12
echo '=== capability/路由相关日志 ==='
docker logs deploy-control-app-1 --since 8m 2>&1 | grep -iE 'canary|route|capability|deferred|waiting' | tail -8
exit 0
