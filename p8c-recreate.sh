#!/bin/sh
# p8c 收尾：换日志版镜像重建 worker + 新 tag + 发干净验证批
set -e
cd /opt/build/pr/deploy
JUDGE_BASE=$(grep '^OPENAI_COMPAT_BASE_URL=' .env | cut -d= -f2- | tr -d '\r"')
JUDGE_KEY=$(grep '^AGENT_MODEL_API_KEY=' .env | cut -d= -f2- | tr -d '\r"')
LITELLM_MASTER=$(docker inspect litellm-am3 --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -E '^LITELLM_MASTER_KEY=' | cut -d= -f2- | tr -d '\r')
EVAL_PASS=$(grep '^EVAL_DB_PASSWORD=' .env | cut -d= -f2- | tr -d '\r')
BEARER=$(grep '^ALERTMANAGER_WEBHOOK_BEARER_TOKEN=' .env | cut -d= -f2- | tr -d '\r')
CHAOS_TOKEN=$(grep '^CHAOS_ADMIN_TOKEN=' .env | cut -d= -f2- | tr -d '\r')
PROMPT_DIGEST=$(sha256sum /opt/build/pr/control-app/src/main/java/com/objwww/pr/control/infrastructure/nativeexec/NativeInvestigationExecutor.java | cut -d' ' -f1)
TOOL_DIGEST=$(cat /opt/build/pr/control-app/src/main/java/com/objwww/pr/control/infrastructure/tool/*.java | sha256sum | cut -d' ' -f1)
RULE_DIGEST=$(cat /opt/build/pr/deploy/alert/prometheus/rules/*.yml | sha256sum | cut -d' ' -f1)
NEWTAG="p8c$(date +%H%M%S)"
echo "NEWTAG=$NEWTAG"
docker rm -f eval-worker-std >/dev/null 2>&1 || true
docker run -d --name eval-worker-std \
  --entrypoint java \
  --network eval-mgmt --network alert-net \
  -v /opt/build/pr/deploy/alert/eval:/eval:ro \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/pr_agent \
  -e SPRING_DATASOURCE_USERNAME=eval_app \
  -e SPRING_DATASOURCE_PASSWORD="$EVAL_PASS" \
  -e SPRING_FLYWAY_ENABLED=false \
  -e CHAOS_ADMIN_TOKEN="$CHAOS_TOKEN" \
  -e JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75" \
  --memory 1g \
  pr-agent/control-app:0.0.1-SNAPSHOT \
  -jar /app/app.jar --spring.profiles.active=eval \
  --app.alert.eval.registry-path=file:/eval/eval-scenarios.yml \
  --app.alert.eval.lexicon-path=file:/eval/synonym-lexicon-v1.yml \
  --app.alert.eval.dataset-version=eval-ds-1 \
  --app.alert.eval.model=deepseek-v3 \
  --app.alert.eval.prompt-version=am4-native-v5 \
  --app.alert.eval.prompt-digest="$PROMPT_DIGEST" \
  --app.alert.eval.tool-registry-digest="$TOOL_DIGEST" \
  --app.alert.eval.provider-fingerprint=litellm:deepseek-v3@dashscope \
  --app.alert.eval.alert-rule-digest="$RULE_DIGEST" \
  --app.alert.eval.grader-version=grader-am3-v1 \
  --app.alert.eval.webhook-url=http://control-app:8080/webhooks/alertmanager \
  --app.alert.eval.webhook-bearer="$BEARER" \
  --app.alert.eval.judge.base-url="$JUDGE_BASE" \
  --app.alert.eval.judge.api-key="$JUDGE_KEY" \
  --app.alert.eval.judge.model=deepseek-v3 \
  --app.alert.eval.litellm.base-url=http://litellm-am3:4000 \
  --app.alert.eval.litellm.master-key="$LITELLM_MASTER" \
  --app.alert.eval.resolver-skew-seconds=60 \
  --app.alert.eval.run-tag="$NEWTAG" \
  --app.eval.launch.enabled=true \
  --app.eval.rounds=1 \
  > /var/log/eval-worker-std.log 2>&1
sleep 28
docker logs eval-worker-std 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -E '评测执行注册表装载|Started ControlApplication|Application run failed' | tail -3

# 登录并发批（p6-launch23 舞步）
NEWHASH=$(/opt/jdk-21.0.12.1+1/bin/java -cp /tmp/ssc.jar /tmp/Gen.java 'Tmp#p8c-clean-20260920')
BK=/tmp/env-backup-p8c-$(date +%Y%m%dT%H%M%S)
cp .env "$BK"
export ESC_HASH=$(echo "$NEWHASH" | sed 's/\$/\$\$/g')
sed -i "s|^AUTH_OPERATOR_PASSWORD_BCRYPT=.*|AUTH_OPERATOR_PASSWORD_BCRYPT=$ESC_HASH|" .env
docker compose up -d control-app >/dev/null
sleep 35
U=$(grep '^AUTH_OPERATOR_USERNAME=' .env | cut -d= -f2- | tr -d '\r"')
J=/tmp/probe-p8c.jar; rm -f $J
curl -s -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T=$(awk '$6=="XSRF-TOKEN"{print $7}' $J)
curl -s -b $J -c $J -H "X-XSRF-TOKEN: $T" \
  --data-urlencode "username=$U" --data-urlencode "password=Tmp#p8c-clean-20260920" \
  -o /dev/null -w 'login=%{http_code}\n' http://127.0.0.1:8080/api/auth/login
curl -s -b $J -c $J http://127.0.0.1:8080/api/auth/csrf -o /dev/null
T2=$(awk '$6=="XSRF-TOKEN"{print $7}' $J | tail -1)
curl -s -b $J -H "X-XSRF-TOKEN: $T2" -H 'Content-Type: application/json' \
  -X POST http://127.0.0.1:8080/api/eval/runs \
  -d '{"displayName":"P8c-干净验证批(tool_invocation回退全量)","mode":"L","datasetVersion":"eval-ds-1","panel":"SMOKE","roundsPerScenario":1,"idempotencyKey":"p8c-toolinv-clean-20260920"}' \
  -w '\nhttp=%{http_code}\n'
cp "$BK" .env
echo 'env-restored-file-only'
sleep 8
docker logs eval-worker-std 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -E '领取 LAUNCH' | tail -1
echo P8C_LAUNCHED
