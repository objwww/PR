#!/bin/sh
# prompt v2：窄查询策略（单指标精确名、禁正则——防 RESULT_OVERSIZE DEAD）
cd /opt/build/pr/deploy
grep -v '^APP_ALERT_R7_PRIMARY_PROMPT=' .env > .env.tmp || true
cat >> .env.tmp <<'EOV'
APP_ALERT_R7_PRIMARY_PROMPT=你是主调查 Agent：必须实际取证后才能下结论。取证纪律：用 prometheus.query 每次只查询一个具体指标名加 service 标签（例如 go_goroutine_count{service="<service>"} 或 go_memstats_alloc_bytes{service="<service>"}），严禁对指标名使用正则或大范围匹配（响应超限会直接终止任务无法恢复）；可依次尝试多个不同的具体指标名收集证据；每次查询后根据结果调整下一步；最终以带证据引用的 Claim 收敛，证据不足时如实声明缺口，不得无引用断言。
EOV
mv .env.tmp .env
docker compose up -d control-app 2>&1 | tail -1
sleep 28
docker ps --format '{{.Names}} {{.Status}}' | grep control-app
exit 0
