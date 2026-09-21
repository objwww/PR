#!/bin/sh
# p8 部署：rca_tool_invocation 回退（SingleCaseScorer 三段降级最后一段）+ 构造链接通
# 前置：两文件已由本机 scp 到 /tmp/p8-files/；195 树已在 b2f44a9e（precheck 已验）
set -e
PR=/opt/build/pr
cd "$PR"

echo '== [0/5] 在飞批次检查 =='
PG=deploy-postgres-1
docker exec -i "$PG" psql -U postgres -d pr_agent -t -A -c \
  "select 'eval_run_active='||id||' '||state from eval_run where state in ('RUNNING','SCORING','PENDING')"
docker exec -i "$PG" psql -U postgres -d pr_agent -t -A -c \
  "select 'chaos_active='||fault_type||'@'||target||' '||state from arena.oa_chaos_session where state in ('ACTIVE','RECOVERING')"
echo '(空=无在飞，安全)'

echo '== [1/5] 落源码 =='
mkdir -p /tmp/p8-files
tar xzf /tmp/p8-batch.tar.gz -C /tmp/p8-files
cp -f /tmp/p8-files/control-app/src/main/java/com/objwww/pr/control/eval/application/SingleCaseScorer.java \
      "$PR/control-app/src/main/java/com/objwww/pr/control/eval/application/SingleCaseScorer.java"
cp -f /tmp/p8-files/control-app/src/main/java/com/objwww/pr/control/eval/application/EvalRunnerConfig.java \
      "$PR/control-app/src/main/java/com/objwww/pr/control/eval/application/EvalRunnerConfig.java"
grep -c 'rca_tool_invocation WHERE run_id' \
  "$PR/control-app/src/main/java/com/objwww/pr/control/eval/application/SingleCaseScorer.java"

echo '== [2/5] mvn package =='
export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD SUCCESS|BUILD FAILURE|ERROR' | head -4

echo '== [3/5] docker build control-app =='
cd "$PR/deploy"
docker compose build control-app 2>&1 | tail -2

echo '== [4/5] 重建 worker（新 run-tag）=='
JUDGE_BASE=$(grep '^OPENAI_COMPAT_BASE_URL=' .env | cut -d= -f2- | tr -d '\r"')
JUDGE_KEY=$(grep '^AGENT_MODEL_API_KEY=' .env | cut -d= -f2- | tr -d '\r"')
LITELLM_MASTER=$(docker inspect litellm-am3 --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -E '^LITELLM_MASTER_KEY=' | cut -d= -f2- | tr -d '\r')
EVAL_PASS=$(grep '^EVAL_DB_PASSWORD=' .env | cut -d= -f2- | tr -d '\r')
BEARER=$(grep '^ALERTMANAGER_WEBHOOK_BEARER_TOKEN=' .env | cut -d= -f2- | tr -d '\r')
CHAOS_TOKEN=$(grep '^CHAOS_ADMIN_TOKEN=' .env | cut -d= -f2- | tr -d '\r')
PROMPT_DIGEST=$(sha256sum /opt/build/pr/control-app/src/main/java/com/objwww/pr/control/infrastructure/nativeexec/NativeInvestigationExecutor.java | cut -d' ' -f1)
TOOL_DIGEST=$(cat /opt/build/pr/control-app/src/main/java/com/objwww/pr/control/infrastructure/tool/*.java | sha256sum | cut -d' ' -f1)
RULE_DIGEST=$(cat deploy/alert/prometheus/rules/*.yml | sha256sum | cut -d' ' -f1)
NEWTAG="p8$(date +%H%M%S)"
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

echo '== [5/5] worker 启动验证 =='
sleep 28
docker logs eval-worker-std 2>&1 | sed 's/\x1b\[[0-9;]*m//g' | grep -E '评测执行注册表装载|Started ControlApplication|Application run failed' | tail -3
echo '--- disk ---'
df -h / | tail -1
echo "P8_DEPLOY_OK tag=$NEWTAG"
