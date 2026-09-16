#!/bin/sh
# E2E 步骤3：跟踪 inbox → incident → run 状态
FP=$(cat /tmp/e2e-last-fp.txt)
echo "fp=$FP INBOX=a8c8dd00-b271-4284-ad9d-a6b38f3a21f1"
echo '=== inbox 行 ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select id || ' | ' || state || ' | attempts=' || attempts || ' | incident=' || coalesce(incident_id::text,'-') || ' | err=' || coalesce(last_error,'-') from alert_inbox where id='a8c8dd00-b271-4284-ad9d-a6b38f3a21f1'"
echo '=== 最近 3 incident ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select id || ' | key=' || incident_key || ' | ' || status || ' | run=' || coalesce(current_rca_run_id::text,'-') || ' | ' || created_at from incident order by created_at desc limit 3"
echo '=== 该 incident 的 run ==='
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tA -c "select r.id || ' | ' || r.state || ' | ' || r.created_at from rca_run r join incident i on r.incident_id=i.id join alert_inbox a on a.incident_id=i.id where a.id='a8c8dd00-b271-4284-ad9d-a6b38f3a21f1' order by r.created_at"
exit 0
