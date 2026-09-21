#!/bin/sh
# B2 CL-06 复原：摘 override 回零委派姿态 + flagd off
set -u
CD=/opt/build/pr/deploy
echo "== 摘除 override 重建 =="
docker compose -p deploy -f "$CD/docker-compose.yml" up -d control-app 2>&1 | tail -3
i=0
while [ $i -lt 18 ]; do
  sleep 5
  docker logs deploy-control-app-1 --since 2m 2>&1 | grep -q "Started ControlApplication" && { echo "startup-ok"; break; }
  i=$((i+1))
done
echo "== env 验证（应回：MAX_DELEGATION=0+四工具配方+无 INPUTCAPTURE）=="
docker inspect deploy-control-app-1 --format '{{range .Config.Env}}{{println .}}{{end}}' \
  | grep -E 'INPUTCAPTURE|MAX_DELEGATION_BATCHES|PRIMARY_PROMPT' > /tmp/b2cl06/env-restored.txt || true
grep -q 'MAX_DELEGATION_BATCHES=0' /tmp/b2cl06/env-restored.txt \
  && echo "OK: 委派批回 0（零委派姿态）" || echo "WARN: 委派批未回 0"
grep -q 'INPUTCAPTURE' /tmp/b2cl06/env-restored.txt \
  && echo "WARN: INPUTCAPTURE 仍在" || echo "OK: INPUTCAPTURE 已回默认 digest-only"
grep -q '取证配方' /tmp/b2cl06/env-restored.txt \
  && echo "OK: prompt 回四工具配方" || echo "WARN: prompt 非四工具配方（人工核）"
docker inspect deploy-control-app-1 --format 'StartedAt={{.State.StartedAt}} Status={{.State.Status}}'
echo "== flagd paymentFailure 回收 =="
docker exec flagd-admin-am3 python3 -c "import urllib.request,json;req=urllib.request.Request('http://127.0.0.1:8081/flags',data=json.dumps({'flag':'paymentFailure','variant':'off'}).encode(),headers={'Content-Type':'application/json'});print(urllib.request.urlopen(req).read().decode())"
echo RESTORE-DONE
