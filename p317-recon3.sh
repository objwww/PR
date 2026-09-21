#!/bin/sh
set -e
P() { docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "$1"; }
INC=f4cf44b2-a5fd-48fe-875c-69ba3f45d78a
echo "--- 对账1：alert_event 事件数（合并轴 ALERT 组基线）:"
P "select count(*), count(*) filter (where status='firing') from alert_event where incident_id='$INC' and starts_at is not null;"
echo "--- 对账2：同键其他 episode 数:"
P "select count(*) from incident where incident_key = (select incident_key from incident where id='$INC') and id <> '$INC';"
echo "--- 对账3：同服务24h并发数（排除自身）:"
P "select count(*) from incident where id <> '$INC' and coalesce(substring(incident_key from 'service=([^|]+)'),'')='order-arena' and first_seen_at >= now() - interval '24 hours';"
echo "--- 对账4：窗口内 control-app 之外变更数（order-arena 服务）:"
P "select count(*) from change_event where service='order-arena';"
echo "--- 对账5：drill_job 总数与最近:"
P "select count(*) from drill_job;"
echo "--- 端点实测:"
J=/tmp/p317.cookie
T=$(grep XSRF-TOKEN $J | tail -1 | awk '{print $NF}')
curl -s -b $J "http://127.0.0.1:8080/api/v1/incidents/$INC/timeline-merged" | head -c 1000
echo
curl -s -o /dev/null -w 'related=%{http_code}\n' -b $J "http://127.0.0.1:8080/api/v1/incidents/$INC/related"
