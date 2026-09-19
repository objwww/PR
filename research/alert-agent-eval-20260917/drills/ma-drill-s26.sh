#!/bin/bash
# M-d T8 演练 S26（全工具复合：F9 掉单+变更归因诱导+审批收口）
# A 段=chaos 全流程（当前可跑，沿 ma-drill-s16 家法）；B 段=调查/工具链/审批断言
# （待充值门：DeepSeek 欠费期间新调查零根因属诚实降级，B 段 SQL 模板在充值后的
#  eval run 落档后执行；approval 表列名以 V114/V119 迁移为准，跑前核对）
# 在 195 上执行；token 从 .env 读取不回显
set -u
TS=$(date +%H%M%S)
SCEN="ma-d-t8-s26-$TS"
TOKEN=$(grep '^CHAOS_ADMIN_TOKEN=' /opt/build/pr/deploy/alert/.env | cut -d= -f2- | tr -d '\r')
DIGEST=$(printf '%s' "$SCEN" | sha256sum | cut -d' ' -f1)
CFG=$(printf 'ma-d-t8-drill' | sha256sum | cut -d' ' -f1)
RULE=$(printf 'arena-business.yml' | sha256sum | cut -d' ' -f1)
ARENA=127.0.0.1:8082
PROM=127.0.0.1:9090

call_chaos() {  # $1=faultType $2=action(on/off) $3=json-body
  echo "$3" | docker exec -i flagd-admin-am3 python3 -c '
import sys, urllib.request
token = sys.argv[1]
path = sys.argv[2]
body = sys.stdin.buffer.read()
req = urllib.request.Request("http://arena-chaos-admin:8080/chaos/" + path,
    data=body, headers={"Content-Type": "application/json", "X-Admin-Token": token})
try:
    r = urllib.request.urlopen(req, timeout=10)
    print(r.status, r.read().decode())
except urllib.error.HTTPError as e:
    print(e.code, e.read().decode())
' "$TOKEN" "$1/$2" 2>&1
}

echo "== S26-A STEP1 激活 F9 (scenario=$SCEN；详设 S26-全工具复合场景详设.md §1) =="
call_chaos "F9" "on" "{\"scenarioId\":\"$SCEN\",\"target\":\"chaos-\",\"ttlSeconds\":900,\"operator\":\"ma-d-t8-drill\",\"configDigest\":\"$CFG\",\"groundTruth\":{\"schemaVersion\":1,\"datasetVersion\":\"ma-d-t8-s26\",\"payloadDigest\":\"$DIGEST\",\"applicableScope\":\"order-arena\"},\"alertLabels\":{\"alertname\":\"ArenaPaymentOrderMismatch\",\"service\":\"order-arena\",\"fault_type\":\"F9\",\"severity\":\"page\"},\"ruleDigest\":\"$RULE\"}"

echo "== S26-A STEP1b 等开关缓存刷新（10s）=="
sleep 10

echo "== S26-A STEP2 chaos 流量：3 单创+付（掉单咬住）=="
for i in 1 2 3; do
  INTENT="chaos-mds26-$TS-$i"
  RESP=$(curl -s -X POST "$ARENA/orders" -H 'Content-Type: application/json' \
    -d "{\"intentId\":\"$INTENT\",\"correlationId\":\"$INTENT\",\"buyerId\":\"buyer-drill\",\"sku\":\"sku-std\",\"quantity\":1,\"amount\":10.00}")
  echo "create[$i]: $RESP"
  OID=$(echo "$RESP" | sed -n 's/.*"orderId":"\([^"]*\)".*/\1/p')
  if [ -n "$OID" ]; then
    echo "pay[$i]: $(curl -s -X POST "$ARENA/orders/$OID/pay" -H 'Content-Type: application/json' -d "{\"correlationId\":\"$INTENT\"}")"
  fi
done

echo "== S26-A STEP3 等探测+抓取（50s）后查 gauge 与告警 =="
sleep 50
echo "gauge: $(curl -s "$PROM/api/v1/query?query=oa_payment_order_mismatch_current" | sed 's/.*"value":\[[^,]*,//;s/\].*//')"
echo "ALERTS: $(curl -s "$PROM/api/v1/query?query=ALERTS%7Balertname%3D%22ArenaPaymentOrderMismatch%22%7D" | head -c 320)"

echo "== S26-A STEP4 变更佐证面（归因诱导项：窗内真实变更清单）=="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -t -c \
  "select 'change_events=' || count(*) from control.change_event where created_at > now() - interval '2 days';"

echo "== S26-A STEP5 关闭注入 =="
call_chaos "F9" "off" "{\"scenarioId\":\"$SCEN\",\"expectedGeneration\":0}"

echo "== S26-A STEP6 轮询恢复（最长 150s）=="
for n in $(seq 1 10); do
  sleep 15
  ST=$(docker exec deploy-postgres-1 psql -U postgres -d pr_agent -t -A -c "select state from arena.oa_chaos_session where scenario_id='$SCEN'")
  G=$(curl -s "$PROM/api/v1/query?query=oa_payment_order_mismatch_current" | sed 's/.*"value":\[[^,]*,//;s/\].*//')
  echo "poll[$n] state=$ST gauge=$G"
  if [ "$ST" = "CLOSED" ]; then break; fi
done

echo "== S26-A STEP7 终态 SQL 对账 =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -c "
select 'residual_mismatch' as chk, count(*) from arena.oa_payment_record p
  join arena.oa_trade_order t on t.id = p.order_id
where p.kind='CAPTURE' and p.result='SUCCEEDED'
  and t.booking_status='ENABLED' and t.pay_status='NOT_PAY'
  and t.correlation_id like 'chaos-mds26-$TS-%'
union all
select 'session_state_rows', count(*) from arena.oa_chaos_session where scenario_id='$SCEN';"

echo "== S26-B 充值门后的断言面（DeepSeek 充值后随 eval run 执行；现在只打印清单）=="
cat <<'NOTE'
S26-B 前置：①DeepSeek 充值；②发起覆盖 S26 的 eval run（registry v6；LAUNCH 面选
  scenario S26 或 md_suite 扩展）；③run 终态后按序断言（<RUN> 替换 runId）：

  1) 审批五表全链：GET /api/eval/runs/<RUN>/approval-chain
     ——intents/requests/decisions/grants/authorizations 逐级 ≥1（零值=链断，如实报缺）；
     （备用手写 SQL 模板见 ma-drill-s16 同目录 V114/V119 迁移列名）
  2) 工具链覆盖≥6 类 SUCCESS：GET /api/eval/runs/<RUN>/process-metrics
     ——toolCallsTotal/toolCallsUnique + rca_tool_invocation 按 tool_name 去重计数；
  3) 六要素：GET /api/eval/runs/<RUN>/six-parts —— complete/rate 与把握分布；
  4) judge rubric v2 四题：GET /api/eval/runs/<RUN>/judge —— Q4 六要素完整度通过率；
  5) 恢复面：S26-A 段 STEP7 终态对账（本脚本已跑）。
NOTE
echo "== S26 演练脚本结束（A 段以上即为可跑全量；B 段待充值）=="
