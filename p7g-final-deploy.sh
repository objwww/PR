#!/bin/sh
set -e
cd /opt/build/pr
echo 'd47bc4e0009b4af7b5a277e48a4b323c  /tmp/fup05-full.tar.gz' | md5sum -c -
tar xzf /tmp/fup05-full.tar.gz
export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD SUCCESS|BUILD FAILURE|ERROR' | head -4
cd deploy
docker compose build control-app web 2>&1 | grep -cE 'DONE'
docker compose up -d control-app web 2>&1 | tail -2
sleep 35
curl -s -o /dev/null -w 'health=%{http_code}\n' http://127.0.0.1:8080/actuator/health
echo '--- worker 换新 run-tag 重建（新镜像含 UI 修复 + FUP-04 锚点容差 + judge 重试）---'
NEWTAG="p6g$(date +%H%M%S)"
docker inspect eval-worker-std --format '{{range .Config.Env}}{{println .}}{{end}}' > /tmp/stdF.env 2>/dev/null || true
if [ ! -s /tmp/stdF.env ]; then
  echo 'FALLBACK: constructing env from scratch'
  EVAL_PASS=$(grep '^EVAL_DB_PASSWORD=' .env | cut -d= -f2- | tr -d '\r')
  CHAOS_TOKEN=$(grep '^CHAOS_ADMIN_TOKEN=' .env | cut -d= -f2- | tr -d '\r')
  JUDGE_BASE=$(grep '^OPENAI_COMPAT_BASE_URL=' .env | cut -d= -f2- | tr -d '\r')
  JUDGE_KEY=$(grep '^AGENT_MODEL_API_KEY=' .env | cut -d= -f2- | tr -d '\r')
  LITELLM_MASTER=$(docker inspect litellm-am3 --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -E '^LITELLM_MASTER_KEY=' | cut -d= -f2- | tr -d '\r')
  BEARER=$(grep '^ALERTMANAGER_WEBHOOK_BEARER_TOKEN=' .env | cut -d= -f2- | tr -d '\r')
  cat > /tmp/stdF.env <<EOF
SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/pr_agent
SPRING_DATASOURCE_USERNAME=eval_app
SPRING_DATASOURCE_PASSWORD=$EVAL_PASS
SPRING_FLYWAY_ENABLED=false
CHAOS_ADMIN_TOKEN=$CHAOS_TOKEN
SPRING_APPLICATION_JSON={"app":{"alert":{"eval":{"registry-path":"file:/eval/eval-scenarios.yml","lexicon-path":"file:/eval/synonym-lexicon-v1.yml","dataset-version":"eval-ds-1","model":"deepseek-v3","prompt-version":"am4-native-v5","provider-fingerprint":"litellm:deepseek-v3@dashscope","grader-version":"grader-am3-v1","webhook-url":"http://control-app:8080/webhooks/alertmanager","webhook-bearer":"$BEARER","judge":{"base-url":"$JUDGE_BASE","api-key":"$JUDGE_KEY","model":"deepseek-v3"},"litellm":{"base-url":"http://litellm-am3:4000","master-key":"$LITELLM_MASTER"},"resolver-skew-seconds":60,"run-tag":"$NEWTAG"}},"eval":{"launch":{"enabled":true,"modes":"L"},"rounds":1}}}
EOF
fi
docker rm -f eval-worker-std >/dev/null 2>&1 || true
docker run -d --name eval-worker-std \
  --entrypoint java \
  --network eval-mgmt --network alert-net \
  -v /opt/build/pr/deploy/alert/eval:/eval:ro \
  --env-file /tmp/stdF.env \
  -e JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75" \
  --memory 1g \
  pr-agent/control-app:0.0.1-SNAPSHOT \
  -jar /app/app.jar --spring.profiles.active=eval \
  > /dev/null
sleep 28
docker logs eval-worker-std 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -E '评测执行注册表装载|Started ControlApplication|Application run failed' | tail -3
