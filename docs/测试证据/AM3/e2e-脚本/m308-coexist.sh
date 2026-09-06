#!/bin/sh
# E2E-M3-08 共存取证（M3-30；评测批期间/结束后各跑一次，纯观察零干扰）
# 断言面：HOST1 全栈 RSS 合账（§6.8 预算）/ 关键容器零 OOM 重启 / live 流量存活 /
#         live-订单零污染（chaos- 前缀隔离，INV-AM2-1）
# 用法: sh cc-m330-m308-coexist.sh <phase-tag>
set -eu
PHASE="${1:?usage: cc-m330-m308-coexist.sh <phase>}"
echo "E2E|INFO|m308-phase|$PHASE"

echo "== RSS 合账（docker stats --no-stream，MiB） =="
docker stats --no-stream --format '{{.Name}} {{.MemUsage}}' | sort
echo "-- RSS 总计（MiB，每容器 usage 前数值求和；GiB 行换算） --"
docker stats --no-stream --format '{{.MemUsage}}' | awk '
{
  split($1, u, "MiB"); if (u[2] == "" && u[1] != $1) s += u[1];
  else { split($1, g, "GiB"); if (g[2] == "" && g[1] != $1) s += g[1] * 1024 }
}
END { printf "TOTAL=%.1f MiB\n", s }'

echo "== 关键容器重启计数（OOM/崩溃信号；应全 0） =="
for c in deploy-control-app-1 deploy-notify-app-1 order-arena alert-order-arena-1 alert-arena-chaos-admin-1 litellm-am3 holmesgpt-am1 prometheus-am0 alertmanager-am0 flagd-admin-am3 deploy-postgres-1 $(docker ps --filter name=eval-runner --format '{{.Names}}'); do
  n=$(docker inspect "$c" --format '{{.RestartCount}}' 2>/dev/null || echo absent)
  oom=$(docker inspect "$c" --format '{{.State.OOMKilled}}' 2>/dev/null || echo absent)
  echo "$c restarts=$n oom=$oom"
done

echo "== live 流量存活（otel demo frontend） =="
code=$(docker run --rm --network opentelemetry-demo curlimages/curl:8.8.0 -s -o /dev/null -w "%{http_code}" http://frontend:8080/ || echo FAIL)
echo "frontend_http=$code"

echo "== 订单隔离（arena：chaos- 前缀 = 评测流量；live- 前缀 = 正常流量） =="
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tAc \
  "select 'chaos_intents='||count(distinct intent_id) from arena.oa_trade_order where correlation_id like 'chaos-%'"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tAc \
  "select 'live_intents='||count(distinct intent_id) from arena.oa_trade_order where correlation_id like 'live-%'"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -tAc \
  "select 'cross_prefix_violations='||count(*) from arena.oa_trade_order where correlation_id not like 'chaos-%' and correlation_id not like 'live-%'"

echo "== gauges（故障残留信号，批间应归零） =="
for g in oa_duplicate_orders_current oa_state_violations_current oa_stuck_orders_current; do
  v=$(curl -s "http://127.0.0.1:9090/api/v1/query?query=$g" | grep -o '"value":\[[^]]*\]' | tail -1)
  echo "$g $v"
done
exit 0
