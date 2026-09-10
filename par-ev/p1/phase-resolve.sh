#!/bin/sh
# Phase C：探针翻转到存活目标（捕获监听）→ 触发恢复，落证据
EV=/tmp/gatus-evidence
cd /opt/gatus-contract-test || exit 1

echo "=== flip probe url to live target (in-place write, keep inode) ==="
cat config-ok.yml > config.yml
grep -n "host.docker.internal:18080/healthz" config.yml
echo "=== restart gatus ==="
docker compose -f compose.yaml restart gatus 2>&1
echo "=== wait 45s for resolve (2 successes x 10s + resolved send) ==="
sleep 45
{
  echo "=== DATE ==="; date -u
  echo "=== gatus logs (since restart) ==="
  docker compose -f compose.yaml logs --since 2m gatus
  echo "=== hook log (tail) ==="
  tail -40 /tmp/gatus-hook.log
  echo "=== statuses API ==="
  curl -s http://127.0.0.1:8081/api/v1/endpoints/statuses > $EV/statuses-resolved.json
  echo "(saved)"
} > $EV/phase-resolved.log 2>&1
echo DONE_PHASE_RESOLVED
