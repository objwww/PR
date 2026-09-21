#!/bin/sh
# P4 红队 E2E run-10：eval worker（worker 模式）——rt-v2 数据集（五案例独立 alertname）+ rounds=1
set -eu
cd /opt/build/pr

# 回放专用空注册表（执行面=纯 case_version 回放案例）
cat > deploy/alert/eval/eval-replay-empty.yml <<'YML'
registry_version: 1
schema_version: 1
lexicon_binding: "synonym-lexicon-v1.yml (lexicon_version: 1)"
scenarios: []
YML

sha() { sha256sum "$1" | cut -d' ' -f1; }
PROMPT_DIGEST=$(sha control-app/src/main/java/com/objwww/pr/control/infrastructure/nativeexec/NativeInvestigationExecutor.java)
TOOL_DIGEST=$(cat control-app/src/main/java/com/objwww/pr/control/infrastructure/tool/*.java | sha256sum | cut -d' ' -f1)
RULE_DIGEST=$(cat deploy/alert/prometheus/rules/*.yml | sha256sum | cut -d' ' -f1)
EVAL_PASS=$(grep '^EVAL_DB_PASSWORD=' deploy/.env | cut -d= -f2)
BEARER=$(grep '^ALERTMANAGER_WEBHOOK_BEARER_TOKEN=' deploy/.env | cut -d= -f2)

SPRING_JSON=$(printf '{"app":{"alert":{"eval":{"registry-path":"file:/registry-replay-empty.yml","lexicon-path":"file:/eval/synonym-lexicon-v1.yml","dataset-version":"rt-v2","model":"glm-5","prompt-version":"am3-rca-v2","prompt-digest":"%s","tool-registry-digest":"%s","provider-fingerprint":"litellm:glm-5@dashscope","alert-rule-digest":"%s","grader-version":"grader-p4-redteam-v2","webhook-url":"http://control-app:8080/webhooks/alertmanager","webhook-bearer":"%s"}},"eval":{"launch":{"enabled":true,"modes":"L"},"rounds":1}}}' "$PROMPT_DIGEST" "$TOOL_DIGEST" "$RULE_DIGEST" "$BEARER")

echo "PROMPT=$PROMPT_DIGEST"
echo "TOOL=$TOOL_DIGEST"
echo "RULE=$RULE_DIGEST"
echo "BEARER_SET=$([ -n "$BEARER" ] && echo yes || echo no)"

nohup docker run --rm --name eval-worker-p4rt \
  --entrypoint java \
  --network eval-mgmt --network alert-net \
  -v /opt/build/pr/deploy/alert/eval:/eval:ro \
  -v /opt/build/pr/deploy/alert/eval/eval-replay-empty.yml:/registry-replay-empty.yml:ro \
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/pr_agent \
  -e SPRING_DATASOURCE_USERNAME=eval_app \
  -e SPRING_DATASOURCE_PASSWORD="$EVAL_PASS" \
  -e SPRING_FLYWAY_ENABLED=false \
  -e SPRING_APPLICATION_JSON="$SPRING_JSON" \
  -e JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50" \
  pr-agent/control-app:0.0.1-SNAPSHOT \
  -jar /app/app.jar --spring.profiles.active=eval \
  > /tmp/p4-worker.log 2>&1 &
echo "worker pid=$!"
sleep 25
echo '--- worker 启动日志 ---'
grep -E 'Started ControlApplication|评测执行注册表装载|ERROR|REJECTED' /tmp/p4-worker.log | tail -8
