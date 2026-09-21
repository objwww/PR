#!/bin/sh
# 主 prompt 策略覆写（compose 允许面：只描述调查策略，不含场景答案）+ 重启留证
cd /opt/build/pr/deploy
cp .env /opt/build/.env.bak-a0prompt-20260912
# 幂等：先删旧行再追加
grep -v '^APP_ALERT_R7_PRIMARY_PROMPT=' .env > .env.tmp || true
cat >> .env.tmp <<'EOV'
APP_ALERT_R7_PRIMARY_PROMPT=你是主调查 Agent：必须实际取证后才能下结论。取证策略：第一步先用 prometheus.query 查询 up{service="<service>"} 确认抓取目标；若为空，则改用 {service="<service>"} 配合 go_ 或 app_ 前缀指标名查询真实指标序列；每一步都要带真实查询，最终以带证据引用的 Claim 收敛；证据不足时如实声明缺口，不得无引用断言。
EOV
mv .env.tmp .env
docker compose up -d control-app 2>&1 | tail -1
sleep 28
docker ps --format '{{.Names}} {{.Status}}' | grep control-app
docker exec deploy-control-app-1 sh -c 'echo prompt-len=$(env | grep -c APP_ALERT_R7_PRIMARY_PROMPT)'
exit 0
