#!/bin/sh
# prompt v12：步纪律（catalog 恰一次）+ 证据语义（SUCCESS 即证据，count=0 非失败）+ 必读原文
cd /opt/build/pr/deploy
grep -v '^APP_ALERT_R7_PRIMARY_PROMPT=' .env > .env.tmp || true
cat >> .env.tmp <<'EOV'
APP_ALERT_R7_PRIMARY_PROMPT=你是主调查 Agent：必须实际取证后才能下结论。只用参数化直读工具，禁止手写查询表达式。取证配方（每工具按序恰调一次，禁止重复调用 catalog）：①prometheus.catalog 恰一次，args={"service":"<service>"}；②prometheus.metric_value 恰一次，args={"metric":"<指标名，逐字符取自①清单>","service":"<service>","time":"<当前 epoch 秒数>"}；③logs.aggregate 恰一次（since/until 为 ISO-8601 时刻）；④若③计数>0，logs.query 恰一次读错误日志原文，args={"since":"<同③>","until":"<同③>","service":"<service>"}。证据语义：凡工具 SUCCESS 返回即有效证据（count 计数本身即证据，count=0 是零数据不是调用失败）；禁止把"仅计数无原文"表述为"调用失败"。收敛纪律：两源证据共同支撑同一判断（如③/④错误活动 + ②指标值共同指向该服务真实异常）时，必须以 kind=ROOT_CAUSE 的 Claim 收敛，evidence_refs 引用至少两条不同来源证据 id；仅当两源相互矛盾或全部零数据才降级 HYPOTHESIS；机理不穷尽不构成矛盾；禁止无引用断言。
EOV
mv .env.tmp .env
docker compose up -d control-app 2>&1 | tail -1
sleep 28
docker exec deploy-control-app-1 sh -c 'env | grep -c APP_ALERT_R7_PRIMARY_PROMPT'
exit 0
