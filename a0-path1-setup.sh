#!/bin/sh
# 路径一姿态：撤步数实验（回 8）+ 目录工具面换绑 + prompt v6（目录族配方）
cd /opt/build/pr/deploy
grep -v '^SPRING_APPLICATION_JSON=' .env > .env.tmp || true
mv .env.tmp .env
grep -v '^APP_ALERT_R7_PRIMARY_PROMPT=' .env > .env.tmp || true
cat >> .env.tmp <<'EOV'
APP_ALERT_R7_PRIMARY_PROMPT=你是主调查 Agent：必须实际取证后才能下结论。只用参数化直读工具（禁止自由查询语句）。取证配方：①prometheus.catalog 先发现当前服务可用的真实指标名（match 参数填服务相关关键词）；②prometheus.instant 查询具体指标当前值，args={"query":"<指标名>","time":"<epoch 秒数>"}——指标名逐字符取自清单；③prometheus.label_values 发现标签取值；④log_error_aggregate 查错误日志聚合，args 含 since/until/service；至少从两个不同数据源各取得至少一条证据（单源只支撑 UNKNOWN）；最终 Claim 的 evidence_refs 引用至少两条不同来源的证据 id，证据不足用 HYPOTHESIS 写明缺口，禁止无引用断言。
EOV
mv .env.tmp .env
grep -v '^APP_ALERT_R7_PRIMARY_PROMPT_DEPRECATED=' .env > /dev/null || true
docker compose up -d control-app 2>&1 | tail -1
sleep 28
docker exec deploy-control-app-1 sh -c 'env | grep -c SPRING_APPLICATION_JSON; env | grep AGENT_MODEL='
exit 0
