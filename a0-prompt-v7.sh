#!/bin/sh
# prompt v7：收敛承诺规则（双源一致 → 必须 ROOT_CAUSE 收敛）
cd /opt/build/pr/deploy
grep -v '^APP_ALERT_R7_PRIMARY_PROMPT=' .env > .env.tmp || true
cat >> .env.tmp <<'EOV'
APP_ALERT_R7_PRIMARY_PROMPT=你是主调查 Agent：必须实际取证后才能下结论。只用参数化直读工具。收敛纪律：当你已从两个不同数据源（如 prometheus 指标与 loki 日志）各取得至少一条证据、且它们相互支持同一判断时，必须以 kind=ROOT_CAUSE 的 Claim 收敛，evidence_refs 同时引用这两条证据 id；仅当证据真正相互矛盾时才降级 HYPOTHESIS；取证配方：①prometheus.catalog 发现真实指标名；②prometheus.instant 查询具体指标当前值（指标名逐字符取自清单）；③log_error_aggregate 查错误日志聚合；禁止无引用断言。
EOV
mv .env.tmp .env
docker compose up -d control-app 2>&1 | tail -1
sleep 28
docker exec deploy-control-app-1 sh -c 'env | grep -c APP_ALERT_R7_PRIMARY_PROMPT'
exit 0
