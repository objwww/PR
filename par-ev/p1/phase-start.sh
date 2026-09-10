#!/bin/sh
# Phase B：起捕获监听 + compose up，等待 firing，落证据
EV=/tmp/gatus-evidence
cd /opt/gatus-contract-test || exit 1

echo "=== start hook listener ==="
nohup python3 /opt/gatus-contract-test/hook.py > $EV/hook.stderr 2>&1 &
sleep 1
curl -s -o /dev/null -w "hook self-test GET /healthz -> %{http_code}\n" http://127.0.0.1:18080/healthz

echo "=== docker compose up ==="
docker compose -f compose.yaml up -d 2>&1
echo "=== wait 50s for probe firing (2 fails x 10s) ==="
sleep 50
{
  echo "=== DATE ==="; date -u
  echo "=== docker compose ps ==="
  docker compose -f compose.yaml ps
  echo "=== gatus logs ==="
  docker compose -f compose.yaml logs gatus
  echo "=== statuses API ==="
  curl -s http://127.0.0.1:8081/api/v1/endpoints/statuses > $EV/statuses-firing.json
  cat $EV/statuses-firing.json
  echo ""
  echo "=== hook log ==="
  cat /tmp/gatus-hook.log
} > $EV/phase-firing.log 2>&1
echo DONE_PHASE_FIRING
