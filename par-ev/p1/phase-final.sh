#!/bin/sh
# Phase D：恢复终态配置（删除探针）→ 重启 → 验证只含 3 endpoint → 收尾 down
EV=/tmp/gatus-evidence
cd /opt/gatus-contract-test || exit 1

echo "=== write final config (probe removed, in-place) ==="
cat config-final.yml > config.yml
echo "=== restart gatus ==="
docker compose -f compose.yaml restart gatus 2>&1
echo "=== wait 25s ==="
sleep 25
{
  echo "=== DATE ==="; date -u
  echo "=== gatus logs (since restart) ==="
  docker compose -f compose.yaml logs --since 1m gatus
  echo "=== statuses API ==="
  curl -s http://127.0.0.1:8081/api/v1/endpoints/statuses > $EV/statuses-final.json
  echo "(saved)"
  echo "=== sqlite file in volume ==="
  docker exec gatus-contract-test-gatus-1 ls -la /data
  echo "=== full hook log size ==="
  wc -l /tmp/gatus-hook.log
} > $EV/phase-final.log 2>&1

echo "=== cleanup: down containers (keep image), remove test volume, kill hook listener ==="
docker compose -f compose.yaml down -v 2>&1
docker ps -a | grep -i gatus || echo "(no gatus container left)"
docker images --digests | grep -i gatus
pkill -f "python3 /opt/gatus-contract-test/hook.py" && echo "hook listener stopped" || echo "(hook already stopped)"
cp /tmp/gatus-hook.log $EV/gatus-hook.log 2>/dev/null
echo "=== /opt/gatus-contract-test final listing ==="
ls -la /opt/gatus-contract-test
echo DONE_PHASE_FINAL
