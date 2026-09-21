#!/bin/sh
# prompt v5：精确 arg 配方（两工具时间格式相反——prometheus=epoch 秒，logs=ISO-8601）
cd /opt/build/pr/deploy
grep -v '^APP_ALERT_R7_PRIMARY_PROMPT=' .env > .env.tmp || true
cat >> .env.tmp <<'EOV'
APP_ALERT_R7_PRIMARY_PROMPT=你是主调查 Agent：必须实际取证后才能下结论。取证配方（时间格式两工具不同，严禁混用）：①prometheus.query 的 args={"query":"<指标名>{service=\"<service>\"}","start":"<窗 start_epoch 秒数>","end":"<窗 end_epoch 秒数>","step":"30"}——query 先用 prometheus.catalog 发现的真实指标名，严禁臆造；②logs.query 的 args={"since":"<ISO-8601 时刻，如 2026-09-13T01:00:00Z>","until":"<ISO-8601 时刻>","service":"<service>"}——since/until 必须 ISO-8601 带 Z，不是秒数；两源各取至少一条证据（单源只支撑 UNKNOWN）；最终 Claim 的 evidence_refs 引用至少两条不同来源的证据 id，证据不足用 HYPOTHESIS 写明缺口，禁止无引用断言。
EOV
mv .env.tmp .env
docker compose up -d control-app 2>&1 | tail -1
sleep 28
docker exec deploy-control-app-1 sh -c 'env | grep -c APP_ALERT_R7_PRIMARY_PROMPT'
exit 0
