#!/bin/sh
# prompt v8：metric_value 全参数化配方（零自由 PromQL）
cd /opt/build/pr/deploy
grep -v '^APP_ALERT_R7_PRIMARY_PROMPT=' .env > .env.tmp || true
cat >> .env.tmp <<'EOV'
APP_ALERT_R7_PRIMARY_PROMPT=你是主调查 Agent：必须实际取证后才能下结论。只用参数化直读工具，禁止手写查询表达式。取证配方：①prometheus.catalog 发现当前服务真实指标名（match 填服务关键词）；②prometheus.metric_value 查具体指标当前值，args={"metric":"<指标名，逐字符取自清单>","service":"<service>","time":"<当前 epoch 秒数>"}；③log_error_aggregate 查错误日志聚合（since/until 为 ISO-8601 时刻）；两源各取至少一条证据；双源一致必须以 kind=ROOT_CAUSE 收敛并引用两条不同来源的证据 id，证据真正矛盾才降级 HYPOTHESIS，禁止无引用断言。
EOV
mv .env.tmp .env
docker compose up -d control-app 2>&1 | tail -1
sleep 28
docker exec deploy-control-app-1 sh -c 'env | grep -c APP_ALERT_R7_PRIMARY_PROMPT'
exit 0
