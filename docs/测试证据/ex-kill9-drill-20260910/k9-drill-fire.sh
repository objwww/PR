#!/usr/bin/env bash
# kill -9 crash-recovery drill: fire 10 firing alerts, snipe control-app PID1 mid-run
set -u
LOG() { echo "$(date -u +%FT%T.%3NZ) $*"; }
TOKEN=$(grep -E '^ALERTMANAGER_WEBHOOK_BEARER_TOKEN=' /opt/build/pr/deploy/.env | cut -d= -f2-)

LOG "PRE_KILL_CONTAINER $(docker inspect deploy-control-app-1 --format 'running={{.State.Running}} pid={{.State.Pid}} startedAt={{.State.StartedAt}} restartCount={{.RestartCount}}')"

SINCE=$(date -u +%FT%TZ)
LOG "sniper_armed since=$SINCE pattern='native 全链完成'"
(
  timeout 180 docker logs -f --since "$SINCE" deploy-control-app-1 2>&1 | while IFS= read -r line; do
    case "$line" in
      *"native 全链完成"*)
        LOG "TRIGGER first native-completion line observed"
        sleep 0.12
        LOG "KILL_SEND begin (docker kill -s KILL deploy-control-app-1)"
        docker kill -s KILL deploy-control-app-1 >/dev/null 2>&1; rc=$?
        LOG "KILL_SEND done rc=$rc"
        exit 0
        ;;
    esac
  done
  LOG "SNIPER_EXIT_WITHOUT_KILL"
) &
SPID=$!
sleep 2

PIDS=""
for n in 01 02 03 04 05 06 07 08 09 10; do
  NOW=$(date -u +%FT%T.%3NZ)
  FP="kill9drill${n}-$(date +%s%N)"
  curl -sS -o /tmp/k9-fire-$n.resp -w "fire_$n http=%{http_code} req_at=$NOW resp_at=$(date -u +%FT%T.%3NZ)\n" \
    -X POST http://127.0.0.1:8080/webhooks/alertmanager \
    -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
    -d "{\"version\":\"4\",\"status\":\"firing\",\"receiver\":\"control-app\",\"groupKey\":\"kill9-drill-$n\",\"groupLabels\":{\"alertname\":\"Kill9DrillProbe$n\"},\"commonLabels\":{\"alertname\":\"Kill9DrillProbe$n\",\"service\":\"order-arena\",\"job\":\"order-arena\",\"severity\":\"page\"},\"commonAnnotations\":{\"summary\":\"kill9 crash recovery drill $n\"},\"externalURL\":\"http://alertmanager-am0:9093\",\"alerts\":[{\"status\":\"firing\",\"labels\":{\"alertname\":\"Kill9DrillProbe$n\",\"service\":\"order-arena\",\"job\":\"order-arena\",\"instance\":\"order-arena:8080\",\"severity\":\"page\",\"fault_type\":\"DRILL\"},\"annotations\":{\"summary\":\"kill9 crash recovery drill $n\"},\"startsAt\":\"$NOW\",\"endsAt\":\"0001-01-01T00:00:00Z\",\"generatorURL\":\"http://prometheus-am0:9090/graph\",\"fingerprint\":\"$FP\"}]}" &
  PIDS="$PIDS $!"
done
wait $PIDS
LOG "all_10_fired"

wait $SPID 2>/dev/null
LOG "sniper_joined"

sleep 2
LOG "POST_KILL_CONTAINER $(docker inspect deploy-control-app-1 --format 'running={{.State.Running}} pid={{.State.Pid}} exitCode={{.State.ExitCode}} oomKilled={{.State.OOMKilled}} finishedAt={{.State.FinishedAt}} startedAt={{.State.StartedAt}} restartCount={{.RestartCount}}' 2>&1)"

LOG "POST_KILL_DB_SNAPSHOT_BEGIN"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent <<'SQL'
\x off
SELECT r.id AS run_id, substring(i.incident_key for 26) AS inc, r.state, r.engine,
       r.started_at, r.finished_at, r.last_event_seq
  FROM rca_run r JOIN incident i ON i.id = r.incident_id
 WHERE i.incident_key LIKE 'alertname=Kill9DrillProbe%'
 ORDER BY i.incident_key;
SELECT t.run_id, t.task_key, t.state, t.lease_owner, t.lease_until, t.lease_epoch, t.attempt_count
  FROM rca_task t JOIN rca_run r ON r.id = t.run_id JOIN incident i ON i.id = r.incident_id
 WHERE i.incident_key LIKE 'alertname=Kill9DrillProbe%' AND t.task_key = 'NATIVE_INVESTIGATE'
 ORDER BY t.created_at;
SELECT a.task_id, a.attempt_no, a.status, a.worker_id, a.started_at, a.finished_at
  FROM rca_attempt a JOIN rca_task t ON t.id = a.task_id JOIN rca_run r ON r.id = t.run_id
  JOIN incident i ON i.id = r.incident_id
 WHERE i.incident_key LIKE 'alertname=Kill9DrillProbe%'
 ORDER BY a.started_at;
SELECT v.run_id, v.call_seq, v.tool_name, v.state, v.result_ref, v.started_at, v.settled_at
  FROM rca_tool_invocation v JOIN rca_run r ON r.id = v.run_id JOIN incident i ON i.id = r.incident_id
 WHERE i.incident_key LIKE 'alertname=Kill9DrillProbe%'
 ORDER BY v.started_at;
SQL
LOG "POST_KILL_DB_SNAPSHOT_END"
