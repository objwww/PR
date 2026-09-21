#!/bin/sh
# B1-1 复原：摘 override 重建 control-app（INPUTCAPTURE 回默认 digest-only）+ 验证
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
echo "== env 验证（应无 INPUTCAPTURE）=="
docker inspect deploy-control-app-1 --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -i INPUTCAPTURE \
  && echo "WARN: env 仍在（.env 键被 compose 某行映射？需查）" || echo "OK: INPUTCAPTURE 已回默认 digest-only"
docker inspect deploy-control-app-1 --format "StartedAt={{.State.StartedAt}} Status={{.State.Status}}"
echo "== 195 姿态复核：flagd paymentFailure 回收 =="
docker exec flagd-admin-am3 python3 -c "import urllib.request,json;req=urllib.request.Request('http://127.0.0.1:8081/flags',data=json.dumps({'flag':'paymentFailure','variant':'off'}).encode(),headers={'Content-Type':'application/json'});print(urllib.request.urlopen(req).read().decode())"
echo "RESTORE-DONE"
