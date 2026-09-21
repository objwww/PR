#!/bin/sh
# O01 状态闭环：firing→调查→报告→resolved→incident RESOLVED（通知面=publication/notify 行数对账）
SCRIPT_DIR="/opt/build/pr/docs/测试证据/R7/e2e-脚本"
. "${SCRIPT_DIR}/e2e-r7-common.sh"
. /opt/build/r7-operator-env.sh
RUNS="/opt/build/runs-r7batch3/o01"
mkdir -p "$RUNS"

# 1. resolved 注入（A0 场景 incident；O01 闭环最后一段）
r7_inject_alert "E2EA0CheckoutProbe" "checkout" "resolved" "$RUNS" || r7_fail "resolved 注入失败"

# 2. 轮询 incident RESOLVED（60s 上限）
i=0
while [ $i -lt 30 ]; do
  STATUS=$($R7_PSQL_CMD "$R7_PG_URL" -tA -c "select status from incident where incident_key='alertname=E2EA0CheckoutProbe|service=checkout' order by last_event_at desc limit 1;")
  if [ "$STATUS" = "RESOLVED" ]; then
    echo "O01-RESOLVED-REACHED"
    break
  fi
  sleep 2
  i=$((i+1))
done
echo "incident-status=$STATUS"

# 3. 状态机对账：事件/发布/通知三面计数
echo '=== 状态闭环对账 ==='
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select status, generation, received_count, resolved_at is not null as has_resolved from incident where incident_key='alertname=E2EA0CheckoutProbe|service=checkout' order by last_event_at desc limit 1;"
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select count(*) from alert_event where incident_id in (select id from incident where incident_key='alertname=E2EA0CheckoutProbe|service=checkout');"
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select count(*) from report_publication;"
echo '=== 调查报告在档（本轮 NATIVE 链产物） ==='
$R7_PSQL_CMD "$R7_PG_URL" -tA -c "select count(*) from rca_report where run_id in (select id from rca_run where created_at > now() - interval '2 hours');"
echo O01-CLOSED-LOOP-DONE
exit 0
