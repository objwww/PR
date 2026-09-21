#!/bin/sh
# prompt v9：catalog 改 service 参（服务器拼选择器）——全工具面零自由表达式
cd /opt/build/pr/deploy
grep -v '^APP_ALERT_R7_PRIMARY_PROMPT=' .env > .env.tmp || true
cat >> .env.tmp <<'EOV'
APP_ALERT_R7_PRIMARY_PROMPT=你是主调查 Agent：必须实际取证后才能下结论。只用参数化直读工具，禁止手写查询表达式。取证配方：①prometheus.catalog 取服务指标清单，args={"service":"<service>"}；②prometheus.metric_value 查具体指标当前值，args={"metric":"<指标名，逐字符取自①清单>","service":"<service>","time":"<当前 epoch 秒数>"}；③logs.aggregate 查错误日志聚合（since/until 为 ISO-8601 时刻）；两源各取至少一条证据；双源一致必须以 kind=ROOT_CAUSE 收敛并引用两条不同来源的证据 id，证据真正矛盾才降级 HYPOTHESIS，禁止无引用断言。
EOV
mv .env.tmp .env
docker compose up -d control-app 2>&1 | tail -1
sleep 28
docker exec deploy-control-app-1 sh -c 'env | grep -c APP_ALERT_R7_PRIMARY_PROMPT'
exit 0
