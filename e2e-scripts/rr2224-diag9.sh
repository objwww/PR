#!/bin/sh
# v5 终止后取证：v5 窗的决策行 + 激活面状态
PG="docker exec rriso-postgres-1 psql -U postgres -d pr_agent -At -F| -c"
echo ===DECISIONS-AFTER-V4===
$PG "select id, stickiness_key, decision, run_id, created_at from canary_route_decision where id > 121 order by id limit 30"
echo ===R7-COMMON-BUNDLE-FUNCS===
grep -n 'r7_publish_bundle\|r7_qualify\|r7_activate' /opt/build/b2tree/e2e-r7-common.sh | head
echo ===FUNCS-BODY===
sed -n "$(grep -n 'r7_publish_bundle()' /opt/build/b2tree/e2e-r7-common.sh | cut -d: -f1),+60p" /opt/build/b2tree/e2e-r7-common.sh
