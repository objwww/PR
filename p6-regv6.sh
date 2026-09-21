#!/bin/sh
set -e
# 注册表 v6 同步 + std worker 换新 run-tag 重建
cd /opt/build/pr
tar xzf /tmp/reg-v6.tar.gz deploy/alert/eval/eval-scenarios.yml
grep -c 'panel: true' deploy/alert/eval/eval-scenarios.yml
cd deploy
docker inspect eval-worker-std --format '{{range .Config.Env}}{{println .}}{{end}}' > /tmp/std7.env
NEWTAG="p6$(date +%H%M%S)"
sed -i "s|\"run-tag\":\"[^\"]*\"|\"run-tag\":\"$NEWTAG\"|" /tmp/std7.env
echo "run-tag -> $(grep -o '\"run-tag\":\"[^\"]*\"' /tmp/std7.env)"
docker rm -f eval-worker-std >/dev/null 2>&1 || true
docker run -d --name eval-worker-std \
  --entrypoint java \
  --network eval-mgmt --network alert-net \
  -v /opt/build/pr/deploy/alert/eval:/eval:ro \
  --env-file /tmp/std7.env \
  pr-agent/control-app:0.0.1-SNAPSHOT \
  -jar /app/app.jar --spring.profiles.active=eval \
  > /dev/null
sleep 28
docker logs eval-worker-std 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -E '评测执行注册表装载|Started ControlApplication|Application run failed' | tail -2
echo '---全量残留 firing 检查---'
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -At -c "select incident_key||' '||status from incident where incident_key like 'alertname=Arena%' and status='FIRING';"
