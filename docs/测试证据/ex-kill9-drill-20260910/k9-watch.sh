#!/usr/bin/env bash
set -u
RID=05b2d85e-3946-4bc2-bad1-1c094d8344c1
END=$(date -u -d '2026-09-10 10:44:00' +%s 2>/dev/null || date -d '2026-09-10T10:44:00Z' +%s)
while [ "$(date -u +%s)" -lt "$END" ]; do
  NOW=$(date -u +%FT%T.%3NZ)
  LINE=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tAc "SELECT r.state, t.state, t.lease_owner, t.lease_epoch, t.attempt_count, (SELECT count(*) FROM rca_attempt a WHERE a.task_id=t.id), (SELECT string_agg(a.status, ',' ORDER BY a.attempt_no) FROM rca_attempt a WHERE a.task_id=t.id) FROM rca_run r JOIN rca_task t ON t.run_id=r.id AND t.task_key='NATIVE_INVESTIGATE' WHERE r.id='$RID'")
  DONE=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tAc "SELECT count(*) FROM rca_run r JOIN incident i ON i.id=r.incident_id WHERE i.incident_key LIKE 'alertname=Kill9DrillProbe%' AND r.state IN ('SUCCEEDED','FAILED','CANCELLED')")
  RUNS=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tAc "SELECT string_agg(r.state, ',' ORDER BY i.incident_key) FROM rca_run r JOIN incident i ON i.id=r.incident_id WHERE i.incident_key LIKE 'alertname=Kill9DrillProbe%'")
  echo "$NOW probe03(run,task,owner,epoch,attempts,statuses)=$LINE terminal=$DONE/11 states=[$RUNS]"
  sleep 20
done
echo "=== reclaim/recovery log lines since kill ==="
docker logs deploy-control-app-1 --since 2026-09-10T10:30:09 2>&1 | grep -E '租约过期回收|悬挂|UNKNOWN|阶段|恢复|native 全链完成|ERROR|Exception' | tail -40
