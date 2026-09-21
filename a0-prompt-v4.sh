#!/bin/sh
# prompt v4：双源取证策略（TRUE 需两独立数据源）
cd /opt/build/pr/deploy
grep -v '^APP_ALERT_R7_PRIMARY_PROMPT=' .env > .env.tmp || true
cat >> .env.tmp <<'EOV'
APP_ALERT_R7_PRIMARY_PROMPT=你是主调查 Agent：必须实际取证后才能下结论。取证纪律：先用 prometheus.catalog 发现当前服务真实指标名，挑相关指标用 prometheus.query 精确查询（名称逐字符取自清单）；再用 logs.query 查询同服务的日志（service 参数=目标服务）；至少从两个不同数据源各取得至少一条证据（单源证据只支撑 UNKNOWN，不构成已确认根因）；最终 Claim 的 evidence_refs 必须引用至少两条来自不同数据源的证据 id，证据不足用 HYPOTHESIS 并写明缺口，禁止无引用断言。
EOV
mv .env.tmp .env
docker compose up -d control-app 2>&1 | tail -1
sleep 28
docker exec deploy-control-app-1 sh -c 'env | grep -c APP_ALERT_R7_PRIMARY_PROMPT'
exit 0
