#!/bin/sh
set -e
cd /opt/build/pr
echo '886535c5da94065928e9da09cef0bf57  /tmp/fup04-full.tar.gz' | md5sum -c -
tar xzf /tmp/fup04-full.tar.gz
export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD SUCCESS|BUILD FAILURE|ERROR' | head -4
cd deploy
docker compose build control-app web 2>&1 | grep -cE 'DONE'
docker compose up -d control-app web 2>&1 | tail -2
sleep 35
curl -s -o /dev/null -w 'health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
echo '--- std worker 重建（新镜像含 FUP-04 锚点容差 + skew=60）---'
JUDGE_BASE=$(grep '^OPENAI_COMPAT_BASE_URL=' .env | cut -d= -f2- | tr -d '\r"')
JUDGE_KEY=$(grep '^AGENT_MODEL_API_KEY=' .env | cut -d= -f2- | tr -d '\r"')
docker inspect eval-worker-std --format '{{range .Config.Env}}{{println .}}{{end}}' > /tmp/std6.env
NEWTAG="p6$(date +%H%M%S)"
if grep -q '"run-tag":"[^"]*"' /tmp/std6.env; then
  sed -i "s|\"run-tag\":\"[^\"]*\"|\"run-tag\":\"$NEWTAG\"|" /tmp/std6.env
else
  sed -i "s|\"app\":{\"alert\":{\"eval\":{|&\"run-tag\":\"$NEWTAG\",|" /tmp/std6.env
fi
if ! grep -q '"judge":' /tmp/std6.env; then
  sed -i "s|{\"app\":{\"alert\":{\"eval\":{|&\"judge\":{\"base-url\":\"$JUDGE_BASE\",\"api-key\":\"$JUDGE_KEY\",\"model\":\"deepseek-v3\"},|" /tmp/std6.env
fi
if grep -q '"resolver-skew-seconds":' /tmp/std6.env; then
  sed -i 's|"resolver-skew-seconds":[0-9]*|"resolver-skew-seconds":60|' /tmp/std6.env
else
  sed -i 's|{"app":{"alert":{"eval":{|&"resolver-skew-seconds":60,|' /tmp/std6.env
fi
echo "run-tag -> $(grep -o '"run-tag":"[^"]*"' /tmp/std6.env)"
echo "skew -> $(grep -o '"resolver-skew-seconds":[0-9]*' /tmp/std6.env)"
docker rm -f eval-worker-std >/dev/null 2>&1 || true
docker run -d --name eval-worker-std \
  --entrypoint java \
  --network eval-mgmt --network alert-net \
  -v /opt/build/pr/deploy/alert/eval:/eval:ro \
  --env-file /tmp/std6.env \
  pr-agent/control-app:0.0.1-SNAPSHOT \
  -jar /app/app.jar --spring.profiles.active=eval \
  > /dev/null
sleep 28
docker logs eval-worker-std 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -E '评测执行注册表装载|Started ControlApplication|Application run failed' | tail -2
