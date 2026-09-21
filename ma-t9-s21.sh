#!/bin/bash
source /tmp/ma-drill-lib.sh
TS=$(date +%H%M%S); SCEN="ma-t9-s21r-$TS"
echo "======== S21 重跑：下单事件丢失（F14，履约缺口 counter 差值）========"
echo "[on] $(chaos_on F14 "$SCEN" 600 ArenaFulfillmentGap F14 ticket ma-t9-s21)"
sleep 10
echo "[create 25 单：履约行被吞（created+25, fulfillments+0）]"
for i in $(seq 1 25); do create_order "chaos-t9s21r-$TS-$i" >/dev/null & done; wait
sleep 5
echo "[SQL: 断点核验，期望 with_fulfillment=0]"
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -t -c "select count(*) from arena.oa_trade_order t where t.correlation_id like 'chaos-t9s21r-$TS-%' and exists (select 1 from arena.oa_fulfillment_order f where f.trade_order_id=t.id)"
echo "[等 firing（差值>10，轮询 4 分钟）]"
FIRED=0
for n in $(seq 1 16); do
  sleep 15
  A=$(curl -s "$PROM/api/v1/query?query=ALERTS%7Balertname%3D%22ArenaFulfillmentGap%22%7D" | grep -c firing)
  echo "poll[$n] firing_hits=$A gap=$(gauge 'increase(oa_orders_total[10m]) - increase(oa_fulfillments_started_total[10m])')"
  [ "$A" -ge 1 ] && FIRED=1 && break
done
echo "FIRED=$FIRED"
echo "[off]（恢复=计数窗口自然稀释 ~10min；会话 TTL 600s 到期由 reaper 收口）"
chaos_off F14 "$SCEN"
echo "[轮询 resolved（最长 13 分钟）]"
for n in $(seq 1 26); do
  sleep 30
  A=$(curl -s "$PROM/api/v1/query?query=ALERTS%7Balertname%3D%22ArenaFulfillmentGap%22%7D" | grep -c firing)
  echo "resolve[$n] firing_hits=$A"
  [ "$A" -eq 0 ] && break
done
docker exec deploy-postgres-1 psql -U postgres -d pr_agent -t -c "select 's21_session(到期收口前可为RECOVERING)='||state from arena.oa_chaos_session where scenario_id='$SCEN'"
