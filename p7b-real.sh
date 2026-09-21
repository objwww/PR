#!/bin/sh
# P6 真端到端修复：①补发缺失类修构建 ②worker 换完整注册表 v4+chaos+judge+litellm ③发起 SMOKE panel（S3/S4/S5 真故障注入）
set -e
cd /opt/build/pr
echo 'f4a1e72e67372e4ea76f49302d839e33  /tmp/p7b-guards.tar.gz' | md5sum -c -
tar xzf /tmp/p7b-guards.tar.gz
export JAVA_HOME=/opt/jdk-21.0.12.1+1
mvn -B -ntp package -pl control-app -am -DskipTests 2>&1 | grep -E 'BUILD SUCCESS|BUILD FAILURE|ERROR' | head -5

# ---------- 重建 worker 启动脚本（真端到端形态） ----------
cd deploy
CHAOS_TOKEN=$(grep '^CHAOS_ADMIN_TOKEN=' .env | cut -d= -f2- | tr -d '\r"')
JUDGE_BASE=$(grep '^OPENAI_COMPAT_BASE_URL=' .env | cut -d= -f2- | tr -d '\r"')
JUDGE_KEY=$(grep '^AGENT_MODEL_API_KEY=' .env | cut -d= -f2- | tr -d '\r"')
LITELLM_MASTER=$(docker inspect litellm-am3 --format '{{range .Config.Env}}{{println .}}{{end}}' | grep -E '^LITELLM_MASTER_KEY=' | cut -d= -f2- | tr -d '\r')

cat > /tmp/p6w.sh <<SCRIPT
#!/bin/sh
# P6 真端到端 eval worker：完整注册表 v4（注入场景真故障）+ chaos/flagd 注入 + judge + litellm 用量对账
set -eu
cd /opt/build/pr
sha() { sha256sum "\$1" | cut -d' ' -f1; }
PROMPT_DIGEST=\$(sha control-app/src/main/java/com/objwww/pr/control/infrastructure/nativeexec/NativeInvestigationExecutor.java)
TOOL_DIGEST=\$(cat control-app/src/main/java/com/objwww/pr/control/infrastructure/tool/*.java | sha256sum | cut -d' ' -f1)
RULE_DIGEST=\$(cat deploy/alert/prometheus/rules/*.yml | sha256sum | cut -d' ' -f1)
EVAL_PASS=\$(grep '^EVAL_DB_PASSWORD=' deploy/.env | cut -d= -f2)
BEARER=\$(grep '^ALERTMANAGER_WEBHOOK_BEARER_TOKEN=' deploy/.env | cut -d= -f2)
SPRING_JSON=\$(printf '{"app":{"alert":{"eval":{"registry-path":"file:/eval/eval-scenarios.yml","lexicon-path":"file:/eval/synonym-lexicon-v1.yml","dataset-version":"eval-ds-1","model":"glm-5","prompt-version":"am3-rca-v2","prompt-digest":"%s","tool-registry-digest":"%s","provider-fingerprint":"litellm:glm-5@dashscope","alert-rule-digest":"%s","grader-version":"grader-p6-real-e2e-v1","webhook-url":"http://control-app:8080/webhooks/alertmanager","webhook-bearer":"%s","judge":{"base-url":"%s","api-key":"%s","model":"qwen3-max"},"litellm":{"base-url":"http://litellm-am3:4000","master-key":"%s"}},"eval":{"launch":{"enabled":true,"modes":"L"},"rounds":2}}}}' "\$PROMPT_DIGEST" "\$TOOL_DIGEST" "\$RULE_DIGEST" "\$BEARER" "\$JUDGE_BASE" "\$JUDGE_KEY" "\$LITELLM_MASTER")
echo "registry=v4-full judge=\$([ -n "\$JUDGE_KEY" ] && echo on) litellm=\$([ -n "\$LITELLM_MASTER" ] && echo on) chaos=\$([ -n "\$CHAOS_TOKEN" ] && echo on)"
nohup docker run --rm --name eval-worker-p6real \\
  --entrypoint java \\
  --network eval-mgmt --network alert-net \\
  -v /opt/build/pr/deploy/alert/eval:/eval:ro \\
  -e SPRING_DATASOURCE_URL=jdbc:postgresql://postgres:5432/pr_agent \\
  -e SPRING_DATASOURCE_USERNAME=eval_app \\
  -e SPRING_DATASOURCE_PASSWORD="\$EVAL_PASS" \\
  -e SPRING_FLYWAY_ENABLED=false \\
  -e CHAOS_ADMIN_TOKEN="$CHAOS_TOKEN" \\
  -e SPRING_APPLICATION_JSON="\$SPRING_JSON" \\
  -e JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=50" \\
  pr-agent/control-app:0.0.1-SNAPSHOT \\
  -jar /app/app.jar --spring.profiles.active=eval \\
  > /tmp/p6-worker.log 2>&1 &
echo "worker pid=\$!"
sleep 25
grep -E 'Started ControlApplication|评测执行注册表装载|ERROR|REJECTED' /tmp/p6-worker.log | tail -6
SCRIPT
echo '--- worker 脚本重建完成，重启 worker ---'
docker rm -f eval-worker-p4rt eval-worker-p6real >/dev/null 2>&1 || true
sh /tmp/p6w.sh
