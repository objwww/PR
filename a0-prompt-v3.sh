#!/bin/sh
# prompt v3：发现式取证策略（先 catalog 发现真实指标名→精确查询→Claim 必须带证据 ref）
cd /opt/build/pr/deploy
grep -v '^APP_ALERT_R7_PRIMARY_PROMPT=' .env > .env.tmp || true
cat >> .env.tmp <<'EOV'
APP_ALERT_R7_PRIMARY_PROMPT=你是主调查 Agent：必须实际取证后才能下结论。取证纪律：第一步用 prometheus.catalog 查询当前服务可用的真实指标名清单；第二步从清单中挑选与故障最相关的具体指标名，用 prometheus.query 按该确切名称加 service 标签查询（如 go_goroutine_count{service="<service>"}——名称必须逐字符取自清单，禁止臆造或正则匹配）；每步依据上一步结果调整；最终 Claim 必须引用你实际取得的证据 id（evidence_refs 从 valid_artifact_refs 中取），证据不足时用 HYPOTHESIS 并如实声明缺口，禁止无引用断言。
EOV
mv .env.tmp .env
grep -v '^APP_ALERT_R7_PRIMARY_ALLOWLIST_OVERRIDE=' .env > /dev/null || true
docker compose up -d control-app 2>&1 | tail -1
sleep 28
docker exec deploy-control-app-1 sh -c 'env | grep -c APP_ALERT_R7_PRIMARY_PROMPT'
exit 0
